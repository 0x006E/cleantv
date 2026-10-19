/**
 * Unit & Integration Tests for SensyDatabase
 */

import { SensyDatabase } from '../src/sensy_db.js';

async function runDbTests(): Promise<void> {
    console.log('--- Running SensyDatabase Tests ---');
    const db = new SensyDatabase();

    // Test 1: Metadata
    db.open();
    const meta = db.getMetadata();
    console.log(`[PASS] Metadata retrieved: v${meta.dataVersion} (DataModel: v${meta.dataModelVersion})`);
    if (meta.dataVersion < 380) {
        throw new Error(`Expected dataVersion >= 380, got ${meta.dataVersion}`);
    }

    // Test 2: Search Providers
    const keralaProviders = db.searchProviders('Kerala Vision');
    console.log(`[PASS] Found ${keralaProviders.length} providers matching "Kerala Vision"`);
    if (keralaProviders.length === 0) {
        throw new Error('Expected at least one Kerala Vision provider');
    }

    // Test 3: Kerala Vision Remotes
    const remotes = db.getKeralaVisionRemotes();
    console.log(`[PASS] Found ${remotes.length} candidate remotes for Kerala Vision`);
    const expectedIds = [43, 74, 85, 114, 116, 184, 248];
    const actualIds = remotes.map(r => r.remote.id).sort((a, b) => a - b);
    console.log(`Candidate remote IDs: [${actualIds.join(', ')}]`);
    for (const exp of expectedIds) {
        if (!actualIds.includes(exp)) {
            throw new Error(`Missing expected remote ID ${exp} in Kerala Vision candidates`);
        }
    }

    // Test 4: Verify Remote 43 Key Codes
    const r43 = remotes.find(r => r.remote.id === 43);
    if (!r43) throw new Error('Remote 43 not found');
    console.log(`[PASS] Remote 43 protocol: ${r43.remote.protocolName} (${r43.remote.length} bits, gap: ${r43.remote.gap}ms)`);
    
    const pwr = r43.keyMap['POWER'];
    if (!pwr || pwr.rawCode.toLowerCase() !== '0x14eb18e7') {
        throw new Error(`Unexpected Remote 43 Power code: ${pwr?.rawCode}`);
    }
    console.log(`[PASS] Remote 43 POWER key: ${pwr.rawCode}`);

    const volUp = r43.keyMap['VOL UP'];
    if (!volUp || volUp.rawCode.toLowerCase() !== '0x14ebaa55') {
        throw new Error(`Unexpected Remote 43 VOL UP code: ${volUp?.rawCode}`);
    }
    console.log(`[PASS] Remote 43 VOL UP key: ${volUp.rawCode}`);

    // Test 5: Verify Remote 74 Key Codes
    const r74 = remotes.find(r => r.remote.id === 74);
    if (!r74) throw new Error('Remote 74 not found');
    const pwr74 = r74.keyMap['POWER'];
    if (!pwr74 || pwr74.rawCode.toLowerCase() !== '0x00ff38c7') {
        throw new Error(`Unexpected Remote 74 Power code: ${pwr74?.rawCode}`);
    }
    console.log(`[PASS] Remote 74 POWER key: ${pwr74.rawCode}`);

    // Test 6: Online Version Check
    console.log('Testing online CDN version check...');
    const check = await db.checkOnlineLatestVersion();
    console.log(`[PASS] Online version check: local v${check.currentVersion}, CDN v${check.latestVersion}`);
    console.log(`       CDN URL: ${check.downloadUrl}`);
    if (check.latestVersion < check.currentVersion) {
        throw new Error(`CDN version (${check.latestVersion}) should be >= local (${check.currentVersion})`);
    }

    db.close();
    console.log('All SensyDatabase tests passed successfully!\n');
}

runDbTests().catch(err => {
    console.error('[FAIL] DB test failed:', err);
    process.exit(1);
});
