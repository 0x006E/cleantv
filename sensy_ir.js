#!/usr/bin/env node

/**
 * Xiaomi / Sensara Sensy USB Consumer IR Dongle Driver & CLI
 * VID: 0x2717, PID: 0x003d
 * 
 * Reverse-engineered from co.sensara.appsense
 */

const { usb } = require('usb');

const VENDOR_ID = 0x2717;
const PRODUCT_ID = 0x003d;

const OPCODES = {
    GET_CONFIG: 1,        // 0x01
    SET_CONFIG: 2,        // 0x02
    START_BUFFERING: 3,   // 0x03
    END_BUFFERING: 4,     // 0x04
    RESET: 120,           // 0x78
    GET_VERSION: 127      // 0x7F
};

const STATES = {
    0: 'Idle',
    1: 'Configured',
    2: 'Buffering',
    3: 'BufferingEmitting',
    4: 'Emitting'
};

const ENDPOINTS = {
    CMD: 2,      // Interrupt OUT
    RESP: 1,     // Interrupt IN
    DATA: 4      // Bulk OUT
};

class SensyIrDongle {
    constructor() {
        this.device = null;
        this.interface = null;
        this.isConnected = false;
    }

    /**
     * Find and connect to the USB IR dongle
     */
    async connect() {
        if (this.isConnected) return;

        const devices = await usb.getDevices();
        this.device = devices.find(d => d.vendorId === VENDOR_ID && d.productId === PRODUCT_ID);

        if (!this.device) {
            throw new Error(
                `Sensy IR Dongle not found (Expected VID: 0x${VENDOR_ID.toString(16)}, PID: 0x${PRODUCT_ID.toString(16)}). ` +
                `Ensure dongle is plugged in and you have read/write permissions to /dev/bus/usb.`
            );
        }

        await this.device.open();
        await this.device.selectConfiguration(1);
        await this.device.claimInterface(0);
        this.isConnected = true;
    }

    /**
     * Disconnect and release the device
     */
    async disconnect() {
        if (!this.isConnected || !this.device) return;
        try {
            await this.device.releaseInterface(0);
        } catch (_) {}
        try {
            await this.device.close();
        } catch (_) {}
        this.isConnected = false;
        this.device = null;
    }

    /**
     * Send command opcode and read response from response endpoint
     */
    async _sendCommand(cmdBuf) {
        if (typeof cmdBuf === 'number') {
            cmdBuf = Buffer.from([cmdBuf]);
        }
        await this.device.transferOut(ENDPOINTS.CMD, cmdBuf);
        const res = await this.device.transferIn(ENDPOINTS.RESP, 64);
        return Buffer.from(res.data.buffer);
    }

    /**
     * Get firmware API version (OP 127)
     */
    async getVersion() {
        await this.connect();
        const res = await this._sendCommand(OPCODES.GET_VERSION);
        if (res.length < 3 || res[0] !== OPCODES.GET_VERSION || res[1] !== 0) {
            return 0; // Prototype / fallback
        }
        return res[2];
    }

    /**
     * Reset dongle to Idle state (OP 120)
     */
    async reset() {
        await this.connect();
        const res = await this._sendCommand(OPCODES.RESET);
        if (res[0] !== OPCODES.RESET || res[1] !== 0) {
            throw new Error(`Reset failed: status code ${res[1]}`);
        }
        await this.waitForState(0, 1000);
        return true;
    }

    /**
     * Read current device state (OP 1)
     */
    async getState() {
        await this.connect();
        const res = await this._sendCommand(OPCODES.GET_CONFIG);
        if (res[0] !== OPCODES.GET_CONFIG || res[1] !== 0) {
            throw new Error('Failed to read device state');
        }
        const stateCode = res[3];
        const stateName = STATES[stateCode] || `Unknown(${stateCode})`;
        const result = {
            stateCode,
            stateName,
            status: res[1],
            valid: res[2]
        };

        if (stateCode > 0 && stateCode <= 4 && res.length >= 13) {
            result.substate = res[4];
            result.frequency = res.readUInt16LE(5);
            result.bufferTargetSize = res.readUInt16LE(7);
            result.bufferCurrentSize = res.readUInt16LE(9);
            result.currentPosition = res.readUInt16LE(11);
        }
        return result;
    }

    /**
     * Wait for device to reach a target state code
     */
    async waitForState(targetState, timeoutMs = 5000) {
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const st = await this.getState();
            if (st.stateCode === targetState) {
                return true;
            }
            await new Promise(r => setTimeout(r, 20));
        }
        throw new Error(`Timeout waiting for state ${STATES[targetState] || targetState} (${timeoutMs}ms)`);
    }

    /**
     * Transmit raw IR timings
     * @param {number} frequency - Carrier frequency in Hz (e.g. 38000)
     * @param {number[]} pattern - Alternating pulse/space durations in microseconds [pulse, space, pulse, ...]
     */
    async transmit(frequency, pattern) {
        if (!pattern || pattern.length === 0) {
            throw new Error('Pattern cannot be empty');
        }

        const byteLength = pattern.length * 4;
        if (byteLength > 65535) {
            throw new Error(`Pattern buffer too large (${byteLength} bytes > 65535 limit)`);
        }

        await this.connect();

        // 1. Reset device before configuration
        await this.reset();

        // 2. Configure frequency and total buffer size (OP 2)
        const cfg = Buffer.alloc(5);
        cfg[0] = OPCODES.SET_CONFIG;
        cfg.writeUInt16LE(frequency, 1);
        cfg.writeUInt16LE(byteLength, 3);
        const cfgRes = await this._sendCommand(cfg);
        if (cfgRes[0] !== OPCODES.SET_CONFIG || cfgRes[1] !== 0) {
            await this.reset();
            throw new Error(`Configure device failed: status code ${cfgRes[1]}`);
        }
        await this.waitForState(1, 1000); // Wait for STATE_CONFIGURED (1)

        // 3. Serialize pattern to 32-bit little-endian microsecond integers
        const dataBuf = Buffer.alloc(byteLength);
        for (let i = 0; i < pattern.length; i++) {
            dataBuf.writeUInt32LE(pattern[i], i * 4);
        }

        // 4. Start buffering (OP 3)
        const bufRes = await this._sendCommand(OPCODES.START_BUFFERING);
        if (bufRes[0] !== OPCODES.START_BUFFERING || bufRes[1] !== 0) {
            await this.reset();
            throw new Error(`Start buffering failed: status code ${bufRes[1]}`);
        }
        await this.waitForState(2, 1000); // Wait for STATE_BUFFERING (2)

        // 5. Stream data in 64-byte chunks via Bulk Endpoint (Ep 4)
        const CHUNK_SIZE = 64;
        for (let offset = 0; offset < dataBuf.length; offset += CHUNK_SIZE) {
            const chunk = dataBuf.subarray(offset, Math.min(offset + CHUNK_SIZE, dataBuf.length));
            await this.device.transferOut(ENDPOINTS.DATA, chunk);
        }

        // 6. Finish buffering / Trigger emission (OP 4)
        const endRes = await this._sendCommand(OPCODES.END_BUFFERING);
        if (endRes[0] !== OPCODES.END_BUFFERING || endRes[1] !== 0) {
            await this.reset();
            throw new Error(`End buffering failed: status code ${endRes[1]}`);
        }

        // 7. Wait for emission to complete and device to return to Idle (0)
        await this.waitForState(0, 10000);
        return true;
    }

    /**
     * Send command using standard NEC protocol (38kHz)
     * @param {number} address - 8-bit or 16-bit address (e.g. 0x04)
     * @param {number} command - 8-bit command (e.g. 0x08)
     */
    async sendNec(address, command) {
        const pattern = [];

        // NEC Leader code: 9000µs mark, 4500µs space
        pattern.push(9000, 4500);

        // Helper to encode a byte (LSB first)
        const encodeByte = (byte) => {
            for (let b = 0; b < 8; b++) {
                pattern.push(560); // 560µs pulse
                if ((byte & (1 << b)) !== 0) {
                    pattern.push(1690); // Logical 1 space (~1690µs)
                } else {
                    pattern.push(560);  // Logical 0 space (~560µs)
                }
            }
        };

        if (address > 0xFF) {
            // Extended NEC (16-bit address)
            encodeByte(address & 0xFF);
            encodeByte((address >> 8) & 0xFF);
        } else {
            // Standard NEC (8-bit address + inverted 8-bit address)
            encodeByte(address & 0xFF);
            encodeByte((~address) & 0xFF);
        }

        // 8-bit command + inverted command
        encodeByte(command & 0xFF);
        encodeByte((~command) & 0xFF);

        // Stop bit
        pattern.push(560, 40000);

        return this.transmit(38000, pattern);
    }

    /**
     * Send Pronto HEX encoded IR code
     * @param {string} prontoHexStr - Space-delimited Pronto hex string (e.g. "0000 006D 0022 ...")
     */
    async sendPronto(prontoHexStr) {
        const tokens = prontoHexStr.trim().split(/\s+/).map(h => parseInt(h, 16));
        if (tokens.length < 4) {
            throw new Error('Invalid Pronto HEX format: too few tokens');
        }

        const rawType = tokens[0];
        if (rawType !== 0x0000) {
            throw new Error(`Unsupported Pronto type 0x${rawType.toString(16)} (only 0x0000 raw is supported)`);
        }

        const freqFactor = tokens[1];
        const carrierHz = Math.round(1000000 / (freqFactor * 0.241246));
        const timebaseUs = (1000000 / carrierHz);

        const seq1Count = tokens[2] * 2;
        const seq2Count = tokens[3] * 2;
        const dataTokens = tokens.slice(4, 4 + seq1Count + seq2Count);

        const pattern = dataTokens.map(cycles => Math.round(cycles * timebaseUs));
        return this.transmit(carrierHz, pattern);
    }
}

// --------------------------------------------------------------------------
// CLI Interface
// --------------------------------------------------------------------------

async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0 || args.includes('--help') || args.includes('-h')) {
        console.log(`
Xiaomi / Sensara USB Consumer IR Dongle Tool
============================================
Usage:
  node sensy_ir.js --info
      Show dongle connection, firmware API version, and state.

  node sensy_ir.js --reset
      Reset dongle to Idle state.

  node sensy_ir.js --nec <address> <command>
      Send an NEC IR command (supports hex '0x04' or dec).
      Example: node sensy_ir.js --nec 0x04 0x08

  node sensy_ir.js --pronto "<pronto_hex_string>"
      Send a Pronto format IR code.
      Example: node sensy_ir.js --pronto "0000 006D 0004 0000 0015 0015 0015 0041"

  node sensy_ir.js --raw <frequency_hz> <duration1,duration2,...>
      Send raw pulse/space durations in microseconds.
      Example: node sensy_ir.js --raw 38000 9000,4500,560,560,560,1690,560

  node sensy_ir.js --test
      Run diagnostic self-test transmitting a sample NEC frame.
`);
        return;
    }

    const dongle = new SensyIrDongle();

    try {
        if (args.includes('--info')) {
            console.log('Connecting to Sensy IR dongle...');
            await dongle.connect();
            const ver = await dongle.getVersion();
            const st = await dongle.getState();
            console.log('\n--- Dongle Information ---');
            console.log(`USB Vendor ID  : 0x${VENDOR_ID.toString(16)} (Xiaomi Inc.)`);
            console.log(`USB Product ID : 0x${PRODUCT_ID.toString(16)} (Sensy Consumer IR)`);
            console.log(`Firmware API   : v${ver}`);
            console.log(`Device State   : ${st.stateName} (Code: ${st.stateCode})`);
            console.log('--------------------------\n');
        } else if (args.includes('--reset')) {
            console.log('Resetting dongle...');
            await dongle.reset();
            console.log('Dongle successfully reset to Idle.');
        } else if (args.includes('--nec')) {
            const idx = args.indexOf('--nec');
            const addrStr = args[idx + 1];
            const cmdStr = args[idx + 2];
            if (!addrStr || !cmdStr) {
                throw new Error('Missing arguments for --nec. Usage: --nec <address> <command>');
            }
            const addr = parseInt(addrStr, addrStr.startsWith('0x') ? 16 : 10);
            const cmd = parseInt(cmdStr, cmdStr.startsWith('0x') ? 16 : 10);
            console.log(`Sending NEC IR code: Address=0x${addr.toString(16)}, Command=0x${cmd.toString(16)}...`);
            await dongle.sendNec(addr, cmd);
            console.log('NEC transmission complete!');
        } else if (args.includes('--pronto')) {
            const idx = args.indexOf('--pronto');
            const prontoStr = args[idx + 1];
            if (!prontoStr) {
                throw new Error('Missing Pronto hex string for --pronto');
            }
            console.log('Sending Pronto IR code...');
            await dongle.sendPronto(prontoStr);
            console.log('Pronto transmission complete!');
        } else if (args.includes('--raw')) {
            const idx = args.indexOf('--raw');
            const freqStr = args[idx + 1];
            const patternStr = args[idx + 2];
            if (!freqStr || !patternStr) {
                throw new Error('Missing arguments for --raw. Usage: --raw <freq_hz> <d1,d2,d3...>');
            }
            const freq = parseInt(freqStr, 10);
            const pattern = patternStr.split(',').map(s => parseInt(s.trim(), 10));
            console.log(`Sending raw IR code (${freq} Hz, ${pattern.length} intervals)...`);
            await dongle.transmit(freq, pattern);
            console.log('Raw transmission complete!');
        } else if (args.includes('--test')) {
            console.log('Running diagnostic self-test with sample NEC power code...');
            await dongle.connect();
            const ver = await dongle.getVersion();
            console.log(`Found dongle API version: v${ver}`);
            console.log('Transmitting NEC code (Addr: 0x04, Cmd: 0x08)...');
            await dongle.sendNec(0x04, 0x08);
            const st = await dongle.getState();
            console.log(`Transmission successful! Dongle state: ${st.stateName}`);
        } else {
            console.error(`Unknown argument: ${args[0]}. Run with --help for usage.`);
            process.exit(1);
        }
    } catch (err) {
        console.error('\nError:', err.message);
        process.exit(1);
    } finally {
        await dongle.disconnect();
    }
}

if (require.main === module) {
    main();
}

module.exports = { SensyIrDongle, VENDOR_ID, PRODUCT_ID, OPCODES, STATES };
