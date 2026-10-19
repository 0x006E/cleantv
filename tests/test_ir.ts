/**
 * Unit & Integration Tests for SensyIrDongle and NEC Encoding
 */

import { SensyIrDongle } from '../src/sensy_ir.js';

async function runIrTests(): Promise<void> {
    console.log('--- Running Sensy IR & Protocol Tests ---');

    // Test 1: NEC 32-bit Encoding Verification
    // Remote 43 Power: 0x14eb18e7
    // 0x14 = 00010100, 0xeb = 11101011, 0x18 = 00011000, 0xe7 = 11100111
    const hex = '0x14eb18e7';
    const pattern = SensyIrDongle.encodeNec(hex, 32);

    // Header (2) + 32 bits * 2 (64) + Stop bit (2) = 68 intervals
    if (pattern.length !== 68) {
        throw new Error(`Expected pattern length 68, got ${pattern.length}`);
    }
    console.log(`[PASS] NEC Pattern length: ${pattern.length} intervals`);

    // Verify Header
    if (pattern[0] !== 9000 || pattern[1] !== 4500) {
        throw new Error(`Invalid header: expected [9000, 4500], got [${pattern[0]}, ${pattern[1]}]`);
    }
    console.log(`[PASS] NEC Leader: mark ${pattern[0]}µs, space ${pattern[1]}µs`);

    // Verify first bit (Bit 31, MSB):
    // 0x14eb18e7 >> 31 = 0
    // Pulse = 560, Space = 560
    if (pattern[2] !== 560 || pattern[3] !== 560) {
        throw new Error(`Invalid bit 31: expected [560, 560], got [${pattern[2]}, ${pattern[3]}]`);
    }
    console.log(`[PASS] Bit 31 (0) encoded correctly as [${pattern[2]}, ${pattern[3]}]µs`);

    // Verify bit 28 (Bit 28 is 1 in 0x14eb18e7):
    // 31 is idx 2,3; 30 is 4,5; 29 is 6,7; 28 is 8,9
    if (pattern[8] !== 560 || pattern[9] !== 1690) {
        throw new Error(`Invalid bit 28: expected [560, 1690], got [${pattern[8]}, ${pattern[9]}]`);
    }
    console.log(`[PASS] Bit 28 (1) encoded correctly as [${pattern[8]}, ${pattern[9]}]µs`);

    // Verify Stop bit & gap
    const lastMark = pattern[pattern.length - 2];
    const lastGap = pattern[pattern.length - 1];
    if (lastMark !== 560 || lastGap !== 40000) {
        throw new Error(`Invalid trailer: expected [560, 40000], got [${lastMark}, ${lastGap}]`);
    }
    console.log(`[PASS] Stop bit and gap: [${lastMark}, ${lastGap}]µs`);

    // Test 2: Hardware Communication & Transmission Test
    console.log('\nTesting Real USB Hardware Communication...');
    const dongle = new SensyIrDongle();
    try {
        await dongle.connect();
        const info = await dongle.getDongleInfo();
        console.log(`[PASS] USB Connected: VID 0x${info.vendorId.toString(16)}, PID 0x${info.productId.toString(16)}`);
        console.log(`[PASS] Firmware API Version: v${info.firmwareVersion}`);
        console.log(`[PASS] Initial Dongle State: ${info.state.stateName} (Code: ${info.state.stateCode})`);

        // Test Reset
        await dongle.reset();
        const stAfterReset = await dongle.getState();
        console.log(`[PASS] State after reset: ${stAfterReset.stateName}`);
        if (stAfterReset.stateCode !== 0) {
            throw new Error(`Expected Idle state (0) after reset, got ${stAfterReset.stateCode}`);
        }

        // Test Real Hardware Transmission with Kerala Vision Remote 43 Power code
        console.log(`Transmitting Kerala Vision Power code (0x14eb18e7)...`);
        const txSuccess = await dongle.sendNecHex('0x14eb18e7', 32);
        console.log(`[PASS] Transmission result: ${txSuccess}`);

        const stAfterTx = await dongle.getState();
        console.log(`[PASS] Device state after transmission: ${stAfterTx.stateName} (Code: ${stAfterTx.stateCode})`);
        if (stAfterTx.stateCode !== 0) {
            throw new Error(`Expected return to Idle state (0), got ${stAfterTx.stateCode}`);
        }

        await dongle.disconnect();
        console.log('All SensyIrDongle tests passed successfully!\n');
    } catch (err: any) {
        await dongle.disconnect();
        throw err;
    }
}

runIrTests().catch(err => {
    console.error('[FAIL] IR test failed:', err);
    process.exit(1);
});

