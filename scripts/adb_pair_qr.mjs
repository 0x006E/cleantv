#!/usr/bin/env node

/**
 * Android ADB Wireless Debugging QR Code Generator & Listener
 * Standards compliant: WIFI:T:ADB;S:<name>;P:<code>;;
 */

import { execSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import QRCode from 'qrcode';
import qrcodeTerminal from 'qrcode-terminal';
import { Bonjour } from 'bonjour-service';

const ARTIFACT_DIR = '/home/nithin/.gemini/antigravity/brain/b72da994-bfbd-42bc-8efd-41ecb2c76f67';
const SVG_OUTPUT_PATH = path.join(ARTIFACT_DIR, 'adb_pairing_qr.svg');
const PNG_OUTPUT_PATH = path.join(ARTIFACT_DIR, 'adb_pairing_qr.png');

// Standard AOSP values
const randHex = Math.random().toString(16).substring(2, 10);
const SERVICE_NAME = `adbqr-${randHex}`;
const PAIRING_CODE = Math.floor(100000 + Math.random() * 900000).toString();
const PAYLOAD = `WIFI:T:ADB;S:${SERVICE_NAME};P:${PAIRING_CODE};;`;

console.log('===============================================================');
console.log('   Android Wireless Debugging QR Code Generator & Auto-Pair   ');
console.log('===============================================================');
console.log(`Standard Payload : ${PAYLOAD}`);
console.log(`mDNS Service     : ${SERVICE_NAME}`);
console.log(`Pairing Password : ${PAIRING_CODE}`);
console.log('===============================================================\n');

// Generate SVG & PNG images for artifact display
try {
    const svgString = await QRCode.toString(PAYLOAD, { type: 'svg', margin: 2, width: 340 });
    fs.writeFileSync(SVG_OUTPUT_PATH, svgString, 'utf8');
    await QRCode.toFile(PNG_OUTPUT_PATH, PAYLOAD, { width: 340, margin: 2 });
    console.log(`Generated QR Images:\n  SVG: ${SVG_OUTPUT_PATH}\n  PNG: ${PNG_OUTPUT_PATH}\n`);
} catch (err) {
    console.warn('Image generation warning:', err.message);
}

// Display in terminal
console.log('--- SCAN WITH PHONE IN WIRELESS DEBUGGING ---');
console.log('(Developer Options -> Wireless Debugging -> Pair device with QR code)\n');
qrcodeTerminal.generate(PAYLOAD, { small: true });

console.log(`\nWaiting for phone discovery via local mDNS...`);
console.log(`(If your Wi-Fi router blocks mDNS/multicast, select "Pair with pairing code" on phone`);
console.log(` and run: adb pair <ip>:<port> ${PAIRING_CODE})\n`);

// Initialize Bonjour to listen for pairing service broadcasted by phone
const bonjour = new Bonjour();

// 1. Announce our pairing service name
try {
    bonjour.publish({
        name: SERVICE_NAME,
        type: 'adb-tls-pairing',
        port: 5555
    });
} catch (_) {}

// 2. Discover phone
let paired = false;
bonjour.find({ type: 'adb-tls-pairing' }, async (service) => {
    if (paired) return;
    const ip = service.addresses?.[0] || service.host;
    const port = service.port;
    if (ip && port) {
        paired = true;
        console.log(`\n🎉 Discovered Android device pairing service: ${ip}:${port} (${service.name})`);
        console.log(`Pairing with: adb pair ${ip}:${port} ${PAIRING_CODE}...`);
        try {
            const out = execSync(`adb pair ${ip}:${port} ${PAIRING_CODE}`, { encoding: 'utf8' });
            console.log(out.trim());
        } catch (e) {
            console.error('Pairing command error:', e.message);
        }

        // Check for adb-tls-connect service or poll adb devices
        await new Promise(r => setTimeout(r, 2000));
        const dev = execSync('adb devices', { encoding: 'utf8' });
        console.log('\nADB Devices:\n' + dev.trim());
        bonjour.destroy();
        process.exit(0);
    }
});

// Timeout after 90 seconds
setTimeout(() => {
    bonjour.destroy();
    console.log('\nListener timed out. You can re-run with: node scripts/adb_pair_qr.mjs');
    process.exit(0);
}, 90000);
