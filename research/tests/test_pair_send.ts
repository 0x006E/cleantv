/**
 * End-to-End Test for Pairing & Keycode Transmission
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import { SensyDatabase } from '../src/sensy_db.js';
import { SensyIrDongle } from '../src/sensy_ir.js';
import { PairedStbConfig } from '../src/types.js';

const TEST_CONFIG_PATH = path.resolve(process.cwd(), 'paired_stb.json');

async function runPairAndSendTests(): Promise<void> {
    console.log('--- Running End-to-End Pairing & Transmission Tests ---');

    const db = new SensyDatabase();
    const dongle = new SensyIrDongle();

    db.open();

    // 1. Simulate pairing Kerala Vision Remote 43
    console.log('Step 1: Selecting Kerala Vision Remote 43...');
    const remoteProfile = db.getRemote(43);
    if (!remoteProfile) {
        throw new Error('Remote profile 43 not found');
    }

    const keyMapObj: Record<string, string> = {};
    for (const k of remoteProfile.keyCodes) {
        keyMapObj[k.keyName] = k.rawCode;
    }

    const pairedConfig: PairedStbConfig = {
        providerTitle: 'Kerala Vision',
        groupId: 43,
        remoteId: 43,
        protocol: remoteProfile.remote.protocol,
        protocolName: remoteProfile.remote.protocolName,
        bitLength: remoteProfile.remote.length,
        carrierHz: 38000,
        pairedAt: new Date().toISOString(),
        keys: keyMapObj
    };

    // Save test paired config
    fs.writeFileSync(TEST_CONFIG_PATH, JSON.stringify(pairedConfig, null, 2), 'utf8');
    console.log(`[PASS] Paired config saved to ${TEST_CONFIG_PATH}`);

    // 2. Read back config and verify keys
    console.log('Step 2: Verifying saved configuration...');
    const saved = JSON.parse(fs.readFileSync(TEST_CONFIG_PATH, 'utf8')) as PairedStbConfig;
    if (saved.remoteId !== 43 || saved.providerTitle !== 'Kerala Vision') {
        throw new Error('Config verification failed');
    }
    console.log(`[PASS] Verified saved provider: ${saved.providerTitle}, remote: ${saved.remoteId}`);

    // Verify key mappings
    const testKeys = ['POWER', 'VOL UP', 'VOL DOWN', 'CH UP', 'CH DOWN', 'OK', '1', 'MENU'];
    for (const key of testKeys) {
        const code = saved.keys[key];
        if (!code) {
            throw new Error(`Key ${key} missing in paired config`);
        }
        console.log(`  Key [${key.padEnd(8)}] -> ${code}`);
    }

    // 3. Connect to Dongle and Transmit Keys
    console.log('\nStep 3: Transmitting paired keycodes via USB Blaster...');
    await dongle.connect();

    // Send POWER
    console.log(`Sending POWER (${saved.keys['POWER']})...`);
    await dongle.sendNecHex(saved.keys['POWER'], saved.bitLength, saved.carrierHz);
    console.log(`[PASS] POWER sent successfully!`);

    // Small delay between commands
    await new Promise(r => setTimeout(r, 200));

    // Send VOL UP
    console.log(`Sending VOL UP (${saved.keys['VOL UP']})...`);
    await dongle.sendNecHex(saved.keys['VOL UP'], saved.bitLength, saved.carrierHz);
    console.log(`[PASS] VOL UP sent successfully!`);

    await new Promise(r => setTimeout(r, 200));

    // Send Channel 1
    console.log(`Sending DIGIT 1 (${saved.keys['1']})...`);
    await dongle.sendNecHex(saved.keys['1'], saved.bitLength, saved.carrierHz);
    console.log(`[PASS] DIGIT 1 sent successfully!`);

    await dongle.disconnect();
    db.close();

    console.log('\nAll End-to-End Pairing & Transmission tests passed successfully!\n');
}

runPairAndSendTests().catch(err => {
    console.error('[FAIL] E2E test failed:', err);
    process.exit(1);
});

