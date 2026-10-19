/**
 * Xiaomi / Sensara Sensy USB Consumer IR Dongle Driver (Strict TypeScript)
 * VID: 0x2717, PID: 0x003d
 * 
 * Accurately implements device firmware protocol and NEC infrared encoding
 * reverse-engineered from co.sensara.appsense.
 */

import { webusb } from 'usb';
import {
    DongleState,
    DongleInfo
} from './types.js';

export const VENDOR_ID = 0x2717;
export const PRODUCT_ID = 0x003d;

export const OPCODES = {
    GET_CONFIG: 1,        // 0x01
    SET_CONFIG: 2,        // 0x02
    START_BUFFERING: 3,   // 0x03
    END_BUFFERING: 4,     // 0x04
    RESET: 120,           // 0x78
    GET_VERSION: 127      // 0x7F
} as const;

export const STATES: Readonly<Record<number, string>> = {
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
} as const;

type WebUSBDevice = Awaited<ReturnType<typeof webusb.requestDevice>>;

export class SensyIrDongle {
    private device: WebUSBDevice | null = null;
    private isConnected: boolean = false;

    /**
     * Find and connect to the USB IR dongle
     */
    public async connect(): Promise<void> {
        if (this.isConnected && this.device) return;

        try {
            this.device = await webusb.requestDevice({
                filters: [{ vendorId: VENDOR_ID, productId: PRODUCT_ID }]
            });
        } catch (_) {
            this.device = null;
        }

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
    public async disconnect(): Promise<void> {
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
    private async _sendCommand(cmdBuf: Buffer | number): Promise<Buffer> {
        if (!this.device) {
            throw new Error('Device not connected');
        }
        const buf = typeof cmdBuf === 'number' ? Buffer.from([cmdBuf]) : cmdBuf;
        await this.device.transferOut(ENDPOINTS.CMD, buf);
        const res = await this.device.transferIn(ENDPOINTS.RESP, 64);
        if (!res.data) {
            throw new Error('No data received from response endpoint');
        }
        return Buffer.from(res.data.buffer);
    }

    /**
     * Get firmware API version (OP 127)
     */
    public async getVersion(): Promise<number> {
        await this.connect();
        const res = await this._sendCommand(OPCODES.GET_VERSION);
        if (res.length < 3 || res[0] !== OPCODES.GET_VERSION || res[1] !== 0) {
            return 0; // Fallback / prototype
        }
        return res[2];
    }

    /**
     * Reset dongle to Idle state (OP 120)
     */
    public async reset(): Promise<boolean> {
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
    public async getState(): Promise<DongleState> {
        await this.connect();
        const res = await this._sendCommand(OPCODES.GET_CONFIG);
        if (res[0] !== OPCODES.GET_CONFIG || res[1] !== 0) {
            throw new Error('Failed to read device state');
        }
        const stateCode = res[3];
        const stateName = STATES[stateCode] ?? `Unknown(${stateCode})`;
        const result: DongleState = {
            stateCode,
            stateName,
            status: res[1],
            valid: res[2]
        };

        if (stateCode > 0 && stateCode <= 4 && res.length >= 13) {
            return {
                ...result,
                substate: res[4],
                frequency: res.readUInt16LE(5),
                bufferTargetSize: res.readUInt16LE(7),
                bufferCurrentSize: res.readUInt16LE(9),
                currentPosition: res.readUInt16LE(11)
            };
        }
        return result;
    }

    /**
     * Get complete information about connected dongle
     */
    public async getDongleInfo(): Promise<DongleInfo> {
        await this.connect();
        const firmwareVersion = await this.getVersion();
        const state = await this.getState();
        return {
            vendorId: VENDOR_ID,
            productId: PRODUCT_ID,
            firmwareVersion,
            state
        };
    }

    /**
     * Wait for device to reach a target state code
     */
    public async waitForState(targetState: number, timeoutMs: number = 5000): Promise<boolean> {
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const st = await this.getState();
            if (st.stateCode === targetState) {
                return true;
            }
            await new Promise(r => setTimeout(r, 20));
        }
        throw new Error(`Timeout waiting for state ${STATES[targetState] ?? targetState} (${timeoutMs}ms)`);
    }

    /**
     * Transmit raw IR timings in microseconds [mark, space, mark, space, ...]
     * @param frequency - Carrier frequency in Hz (e.g. 38000)
     * @param pattern - Alternating pulse/space durations in microseconds
     */
    public async transmit(frequency: number, pattern: readonly number[]): Promise<boolean> {
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
        if (!this.device) throw new Error('Device lost during buffering');
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
     * Encode a numeric or hex IR code to microsecond pulse timings matching Sensy APK's NEC.encode()
     * @param code - 32-bit integer or hex string (e.g. 0x14eb18e7 or "0x14eb18e7")
     * @param bitLength - Length in bits (defaults to 32)
     * @param gapUs - Trailing lead-out space in microseconds (defaults to 40,000µs)
     */
    public static encodeNec(code: number | bigint | string, bitLength: number = 32, gapUs: number = 40000): number[] {
        let numericVal: bigint;
        if (typeof code === 'string') {
            numericVal = BigInt(code.trim());
        } else if (typeof code === 'number') {
            numericVal = BigInt(code >>> 0);
        } else {
            numericVal = code;
        }

        const pattern: number[] = [];
        // NEC Leader code: 9000µs mark, 4500µs space
        pattern.push(9000, 4500);

        // Decompiled logic: shifts from bit (bitLength - 1) down to 0 (MSB first)
        for (let bit = bitLength - 1; bit >= 0; bit--) {
            pattern.push(560); // 560µs mark
            const isSet = (numericVal & (1n << BigInt(bit))) !== 0n;
            pattern.push(isSet ? 1690 : 560);
        }

        // Stop bit + lead-out gap
        pattern.push(560, gapUs);

        return pattern;
    }

    /**
     * Send an arbitrary 32-bit NEC code (e.g. "0x14eb18e7" from database)
     * @param hexCode - Hex code string or integer
     * @param bitLength - Bit length (default 32)
     * @param carrierHz - Carrier frequency (default 38000 Hz)
     */
    public async sendNecHex(hexCode: string | number | bigint, bitLength: number = 32, carrierHz: number = 38000): Promise<boolean> {
        const pattern = SensyIrDongle.encodeNec(hexCode, bitLength);
        return this.transmit(carrierHz, pattern);
    }

    /**
     * Send command using standard NEC address and command bytes (LSB byte format)
     * @param address - 8-bit or 16-bit address
     * @param command - 8-bit command
     */
    public async sendNec(address: number, command: number): Promise<boolean> {
        const pattern: number[] = [];
        pattern.push(9000, 4500);

        const encodeByte = (byte: number): void => {
            for (let b = 0; b < 8; b++) {
                pattern.push(560);
                if ((byte & (1 << b)) !== 0) {
                    pattern.push(1690);
                } else {
                    pattern.push(560);
                }
            }
        };

        if (address > 0xFF) {
            encodeByte(address & 0xFF);
            encodeByte((address >> 8) & 0xFF);
        } else {
            encodeByte(address & 0xFF);
            encodeByte((~address) & 0xFF);
        }

        encodeByte(command & 0xFF);
        encodeByte((~command) & 0xFF);

        pattern.push(560, 40000);
        return this.transmit(38000, pattern);
    }

    /**
     * Send Pronto HEX encoded IR code
     * @param prontoHexStr - Space-delimited Pronto hex string (e.g. "0000 006D 0022 ...")
     */
    public async sendPronto(prontoHexStr: string): Promise<boolean> {
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
