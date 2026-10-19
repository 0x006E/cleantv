#!/usr/bin/env node

/**
 * STB Remote Pairing & Keycode Transmitter CLI
 * Integrates SensyDatabase (SQLite + Cloud CDN) and SensyIrDongle (USB Blaster)
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import * as readline from 'node:readline';
import { SensyDatabase } from './sensy_db.js';
import { SensyIrDongle } from './sensy_ir.js';
import {
    RemoteProfile,
    PairedStbConfig,
    KeyCodeInfo
} from './types.js';

const CONFIG_FILE = path.resolve(process.cwd(), 'paired_stb.json');

/**
 * Normalize human key names to match database names
 */
function normalizeKeyName(input: string): string {
    const s = input.trim().toUpperCase();
    const aliases: Record<string, string> = {
        'PWR': 'POWER',
        'POWER': 'POWER',
        'VOL+': 'VOL UP',
        'VOLUP': 'VOL UP',
        'VOL_UP': 'VOL UP',
        'VOLUME_UP': 'VOL UP',
        'VOL-': 'VOL DOWN',
        'VOLDOWN': 'VOL DOWN',
        'VOL_DOWN': 'VOL DOWN',
        'VOLUME_DOWN': 'VOL DOWN',
        'MUTE': 'VOL MUTE',
        'VOL_MUTE': 'VOL MUTE',
        'CH+': 'CH UP',
        'CHUP': 'CH UP',
        'CH_UP': 'CH UP',
        'CHANNEL_UP': 'CH UP',
        'CH-': 'CH DOWN',
        'CHDOWN': 'CH DOWN',
        'CH_DOWN': 'CH DOWN',
        'CHANNEL_DOWN': 'CH DOWN',
        'OK': 'OK',
        'ENTER': 'OK',
        'MENU': 'MENU',
        'BACK': 'BACK',
        'EXIT': 'EXIT',
        'INFO': 'INFO',
        'UP': 'UP',
        'DOWN': 'DOWN',
        'LEFT': 'LEFT',
        'RIGHT': 'RIGHT',
        'RED': 'RED',
        'GREEN': 'GREEN',
        'YELLOW': 'YELLOW',
        'BLUE': 'BLUE'
    };

    if (aliases[s]) {
        return aliases[s];
    }
    // Digits 0-9
    if (/^[0-9]$/.test(s)) {
        return s;
    }
    return s;
}

/**
 * Ask question in terminal
 */
function askQuestion(rl: readline.Interface, query: string): Promise<string> {
    return new Promise(resolve => rl.question(query, resolve));
}

/**
 * Save paired configuration to disk
 */
function saveConfig(config: PairedStbConfig): void {
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2), 'utf8');
}

/**
 * Load paired configuration from disk
 */
function loadConfig(): PairedStbConfig | null {
    if (!fs.existsSync(CONFIG_FILE)) {
        return null;
    }
    try {
        const raw = fs.readFileSync(CONFIG_FILE, 'utf8');
        return JSON.parse(raw) as PairedStbConfig;
    } catch (_) {
        return null;
    }
}

async function main(): Promise<void> {
    const args = process.argv.slice(2);
    const db = new SensyDatabase();
    const dongle = new SensyIrDongle();

    if (args.length === 0 || args.includes('--help') || args.includes('-h')) {
        console.log(`
STB Remote Pairing & Keycode Transmitter Tool
=============================================
Usage:
  # Pairing & Discovery
  node pair_and_send.ts --pair-kerala
      Interactive pairing wizard for Kerala Vision Set-Top Box.
      Cycles through all candidate remote models and transmits test signals.

  node pair_and_send.ts --test-all-kerala
      Transmits POWER signal for each of the 7 Kerala Vision remotes with 2s interval.

  node pair_and_send.ts --pair-remote <remote_id> [--provider <name>]
      Directly set and save a specific remote profile.

  # Transmitting Keys
  node pair_and_send.ts --send <key_name>
      Send a keycode using the currently paired STB remote.
      Examples:
        node pair_and_send.ts --send power
        node pair_and_send.ts --send vol_up
        node pair_and_send.ts --send vol_down
        node pair_and_send.ts --send ch_up
        node pair_and_send.ts --send mute
        node pair_and_send.ts --send 1

  node pair_and_send.ts --send-direct <remote_id> <key_name>
      Send a keycode directly for any remote ID without saving.

  # Database & Status
  node pair_and_send.ts --search <keyword>
      Search providers in the SQLite database (e.g. "Kerala Vision", "Tata Play").

  node pair_and_send.ts --list-kerala
      List all candidate remote profiles and keycodes for Kerala Vision.

  node pair_and_send.ts --status
      Show USB dongle status and current paired STB config.

  node pair_and_send.ts --check-update
      Check Sensara CDN for newer versions of lcndb.db.

  node pair_and_send.ts --update-db
      Download latest database version from Sensara CDN.
`);
        return;
    }

    try {
        if (args.includes('--status')) {
            console.log('=== Sensy IR Dongle Status ===');
            try {
                await dongle.connect();
                const info = await dongle.getDongleInfo();
                console.log(`USB Hardware : Connected (VID: 0x${info.vendorId.toString(16)}, PID: 0x${info.productId.toString(16)})`);
                console.log(`Firmware API : v${info.firmwareVersion}`);
                console.log(`Device State : ${info.state.stateName} (Code: ${info.state.stateCode})`);
            } catch (err: any) {
                console.log(`USB Hardware : Not connected or accessible (${err.message})`);
            }

            console.log('\n=== Database Metadata ===');
            db.open();
            const meta = db.getMetadata();
            console.log(`Local DB Version : v${meta.dataVersion} (Model v${meta.dataModelVersion})`);

            console.log('\n=== Paired STB Configuration ===');
            const cfg = loadConfig();
            if (cfg) {
                console.log(`Provider   : ${cfg.providerTitle}`);
                console.log(`Remote ID  : ${cfg.remoteId}`);
                console.log(`Protocol   : ${cfg.protocolName} (${cfg.carrierHz} Hz, ${cfg.bitLength} bits)`);
                console.log(`Paired At  : ${cfg.pairedAt}`);
                console.log(`Total Keys : ${Object.keys(cfg.keys).length} configured`);
            } else {
                console.log('No STB currently paired. Run with --pair-kerala to pair.');
            }
        } else if (args.includes('--list-kerala')) {
            db.open();
            const remotes = db.getKeralaVisionRemotes();
            console.log(`Found ${remotes.length} candidate remote profiles for Kerala Vision (Group ID: 43):\n`);
            for (const r of remotes) {
                const pwr = r.keyMap['POWER']?.rawCode ?? 'N/A';
                const vUp = r.keyMap['VOL UP']?.rawCode ?? 'N/A';
                const vDn = r.keyMap['VOL DOWN']?.rawCode ?? 'N/A';
                const chUp = r.keyMap['CH UP']?.rawCode ?? 'N/A';
                const ok = r.keyMap['OK']?.rawCode ?? 'N/A';
                console.log(`Remote ID ${r.remote.id.toString().padEnd(4)} | Protocol: ${r.remote.protocolName.padEnd(5)} | Power: ${pwr.padEnd(12)} | Vol+: ${vUp.padEnd(12)} | Vol-: ${vDn.padEnd(12)} | OK: ${ok}`);
            }
        } else if (args.includes('--search')) {
            const idx = args.indexOf('--search');
            const query = args[idx + 1];
            if (!query) throw new Error('Missing search query');
            db.open();
            const results = db.searchProviders(query);
            console.log(`Found ${results.length} providers matching "${query}":\n`);
            for (const p of results) {
                console.log(`[${p.id}] ${p.title} (${p.type}) - Group: ${p.groupTitle ?? p.groupId}, Brand: ${p.brandTitle ?? p.brandId}`);
            }
        } else if (args.includes('--test-all-kerala')) {
            db.open();
            const remotes = db.getKeralaVisionRemotes();
            console.log('Connecting to Sensy IR Dongle...');
            await dongle.connect();
            console.log(`Starting rapid test across all ${remotes.length} Kerala Vision candidate remotes.`);
            console.log('Watch your Set-Top Box to see which profile turns it OFF/ON:\n');

            for (let i = 0; i < remotes.length; i++) {
                const r = remotes[i];
                const pwr = r.keyMap['POWER'];
                if (!pwr) continue;
                console.log(`[${i + 1}/${remotes.length}] Transmitting Remote ID ${r.remote.id} POWER (${pwr.rawCode})...`);
                await dongle.sendNecHex(pwr.rawCode, r.remote.length);
                console.log(`  -> Sent. Waiting 2.5s for STB reaction...`);
                await new Promise(res => setTimeout(res, 2500));
            }
            console.log('\nTest completed. If your STB reacted to one of the remotes, pair it using:');
            console.log('  node pair_and_send.ts --pair-remote <remote_id>');
        } else if (args.includes('--pair-kerala')) {
            db.open();
            const remotes = db.getKeralaVisionRemotes();
            console.log('Connecting to Sensy IR Dongle...');
            await dongle.connect();

            console.log(`\n======================================================`);
            console.log(`Kerala Vision Set-Top Box Pairing Wizard`);
            console.log(`There are ${remotes.length} candidate remote models in the database.`);
            console.log(`We will test them one by one. Point the blaster at your STB.`);
            console.log(`======================================================\n`);

            const rl = readline.createInterface({
                input: process.stdin,
                output: process.stdout
            });

            let matchedRemote: RemoteProfile | null = null;

            for (let i = 0; i < remotes.length; i++) {
                const r = remotes[i];
                const pwr = r.keyMap['POWER'];
                const vUp = r.keyMap['VOL UP'];
                console.log(`\n[Profile ${i + 1}/${remotes.length}] Testing Remote ID: ${r.remote.id}`);
                console.log(`Transmitting POWER (${pwr?.rawCode ?? 'N/A'})...`);
                if (pwr) {
                    await dongle.sendNecHex(pwr.rawCode, r.remote.length);
                }

                const ans = await askQuestion(rl, 'Did the Set-Top Box respond (turn on/off)? [y/n/test-vol]: ');
                if (ans.trim().toLowerCase() === 'y' || ans.trim().toLowerCase() === 'yes') {
                    matchedRemote = r;
                    break;
                } else if (ans.trim().toLowerCase().startsWith('t') && vUp) {
                    console.log(`Transmitting VOLUME UP (${vUp.rawCode})...`);
                    await dongle.sendNecHex(vUp.rawCode, r.remote.length);
                    const ansVol = await askQuestion(rl, 'Did Volume change on screen? [y/n]: ');
                    if (ansVol.trim().toLowerCase() === 'y' || ansVol.trim().toLowerCase() === 'yes') {
                        matchedRemote = r;
                        break;
                    }
                }
            }

            rl.close();

            if (matchedRemote) {
                const keyMapObj: Record<string, string> = {};
                for (const k of matchedRemote.keyCodes) {
                    keyMapObj[k.keyName] = k.rawCode;
                }

                const config: PairedStbConfig = {
                    providerTitle: 'Kerala Vision',
                    groupId: 43,
                    remoteId: matchedRemote.remote.id,
                    protocol: matchedRemote.remote.protocol,
                    protocolName: matchedRemote.remote.protocolName,
                    bitLength: matchedRemote.remote.length,
                    carrierHz: 38000,
                    pairedAt: new Date().toISOString(),
                    keys: keyMapObj
                };

                saveConfig(config);
                console.log(`\n🎉 Success! Paired with Kerala Vision Remote ID ${matchedRemote.remote.id}.`);
                console.log(`Configuration saved to ${CONFIG_FILE}.`);
                console.log(`You can now send commands using:`);
                console.log(`  node pair_and_send.ts --send power`);
                console.log(`  node pair_and_send.ts --send vol_up`);
            } else {
                console.log('\nNo remote profile was confirmed. You can test individual profiles using:');
                console.log('  node pair_and_send.ts --send-direct <remote_id> power');
            }
        } else if (args.includes('--pair-remote')) {
            const idx = args.indexOf('--pair-remote');
            const remoteIdStr = args[idx + 1];
            if (!remoteIdStr) throw new Error('Missing remote ID');
            const remoteId = parseInt(remoteIdStr, 10);
            db.open();
            const profile = db.getRemote(remoteId);
            if (!profile) throw new Error(`Remote ID ${remoteId} not found in database`);

            const keyMapObj: Record<string, string> = {};
            for (const k of profile.keyCodes) {
                keyMapObj[k.keyName] = k.rawCode;
            }

            const pIdx = args.indexOf('--provider');
            const providerTitle = pIdx !== -1 && args[pIdx + 1] ? args[pIdx + 1] : 'Custom STB';

            const config: PairedStbConfig = {
                providerTitle,
                groupId: 0,
                remoteId: profile.remote.id,
                protocol: profile.remote.protocol,
                protocolName: profile.remote.protocolName,
                bitLength: profile.remote.length,
                carrierHz: 38000,
                pairedAt: new Date().toISOString(),
                keys: keyMapObj
            };

            saveConfig(config);
            console.log(`Configured and saved Remote ID ${remoteId} (${providerTitle}) to ${CONFIG_FILE}.`);
        } else if (args.includes('--send')) {
            const idx = args.indexOf('--send');
            const keyInput = args[idx + 1];
            if (!keyInput) throw new Error('Missing key name. Usage: --send <key>');

            const config = loadConfig();
            if (!config) {
                throw new Error('No STB remote paired yet. Run with --pair-kerala or --pair-remote first.');
            }

            const normKey = normalizeKeyName(keyInput);
            const hexCode = config.keys[normKey];
            if (!hexCode) {
                throw new Error(`Key "${keyInput}" (normalized: "${normKey}") not found in paired remote profile. Available keys: ${Object.keys(config.keys).join(', ')}`);
            }

            console.log(`Connecting to Sensy IR Dongle...`);
            await dongle.connect();
            console.log(`Transmitting ${config.providerTitle} [${normKey}] -> ${hexCode} (Remote ID: ${config.remoteId})...`);
            await dongle.sendNecHex(hexCode, config.bitLength, config.carrierHz);
            console.log(`Transmission successful!`);
        } else if (args.includes('--send-direct')) {
            const idx = args.indexOf('--send-direct');
            const rIdStr = args[idx + 1];
            const keyInput = args[idx + 2];
            if (!rIdStr || !keyInput) throw new Error('Usage: --send-direct <remote_id> <key>');

            const remoteId = parseInt(rIdStr, 10);
            db.open();
            const profile = db.getRemote(remoteId);
            if (!profile) throw new Error(`Remote ID ${remoteId} not found`);

            const normKey = normalizeKeyName(keyInput);
            const keyInfo = profile.keyMap[normKey];
            if (!keyInfo) {
                throw new Error(`Key "${keyInput}" not found in remote ${remoteId}. Available: ${Object.keys(profile.keyMap).join(', ')}`);
            }

            console.log(`Connecting to Sensy IR Dongle...`);
            await dongle.connect();
            console.log(`Transmitting Remote ${remoteId} [${normKey}] -> ${keyInfo.rawCode}...`);
            await dongle.sendNecHex(keyInfo.rawCode, profile.remote.length);
            console.log(`Transmission successful!`);
        } else if (args.includes('--check-update')) {
            db.open();
            console.log('Checking Sensara CDN for database updates...');
            const check = await db.checkOnlineLatestVersion();
            console.log(`Current Local DB : v${check.currentVersion}`);
            console.log(`Latest Cloud DB  : v${check.latestVersion}`);
            console.log(`Update Available : ${check.updateAvailable ? 'YES' : 'NO'}`);
            console.log(`CDN URL          : ${check.downloadUrl}`);
        } else if (args.includes('--update-db')) {
            db.open();
            console.log('Downloading latest database from Sensara CDN...');
            const res = await db.downloadLatestDatabase(undefined, prog => {
                process.stdout.write(`\rProgress: ${prog.percent}% (${(prog.bytesDownloaded / 1024 / 1024).toFixed(2)} MB / ${(prog.totalBytes / 1024 / 1024).toFixed(2)} MB)`);
            });
            console.log(`\nDatabase updated to v${res.version} (${res.sizeBytes} bytes) at ${res.path}!`);
        } else {
            console.error(`Unknown option: ${args[0]}. Run with --help.`);
            process.exit(1);
        }
    } catch (err: any) {
        console.error('\nError:', err.message);
        process.exit(1);
    } finally {
        db.close();
        await dongle.disconnect();
    }
}

main();
