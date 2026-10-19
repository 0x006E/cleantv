# Xiaomi / Sensy USB IR Blaster Dongle (`2717:003d`) & STB Remote System

A TypeScript driver, STB SQLite database library, and pairing/transmission CLI for the Xiaomi / Sensara Sensy USB Consumer IR Dongle. Reverse-engineered directly from `co-sensara-appsense-188-62097078-e53d5b3b45d2fd3224df1ea62a890347.apk`.

---

## Architecture Overview

```
                                      +-------------------------------+
                                      |    Sensara CDN (Online)       |
                                      |  lcndata/lcndb-v<ver>.db      |
                                      +---------------+---------------+
                                                      |  OTA Sync
                                                      v
+--------------------------+          +---------------+---------------+
|    SensyIrDongle (USB)   | <------> |     SensyDatabase (SQLite)    |
|   - State Machine        |          |  - Offline database (v380)    |
|   - NEC 32-bit encoder   |          |  - Kerala Vision (Group 43)   |
|   - Bulk stream buffer   |          |  - 7 Candidate Remote models  |
+------------+-------------+          +---------------+---------------+
             |                                        |
             +-------------------+--------------------+
                                 |
                                 v
                     +-----------------------+
                     |  pair_and_send CLI    |
                     |  - Interactive Pairing|
                     |  - Keycode Dispatch   |
                     |  - CDN Update & Sync  |
                     +-----------------------+
```

---

## Features

- **Strict TypeScript Types**: Fully typed interfaces for all hardware states, SQLite database entities, protocol models, and paired profiles (`types.ts`).
- **Offline & Online Database (`SensyDatabase`)**:
  - Uses Node's built-in `node:sqlite` for zero-native-dependency SQLite access.
  - Queries 3,700+ providers, STB models, key mappings, and protocol parameters.
  - Built-in online CDN checker and OTA updater supporting versions up to **v435**.
- **USB IR Blaster Driver (`SensyIrDongle`)**:
  - WebUSB-compliant device driver for Xiaomi `2717:003d`.
  - Encodes 32-bit NEC codes (MSB-first), standard NEC address/command bytes, Pronto HEX, and arbitrary raw pulse/space intervals.
- **Pairing & Transmission CLI (`pair_and_send.ts`)**:
  - Interactive pairing wizard cycling through candidate remotes (e.g. Kerala Vision 43, 74, 85, 114, 116, 184, 248).
  - One-line key transmission: `power`, `vol_up`, `vol_down`, `ch_up`, `ch_down`, `ok`, digits `0-9`, etc.
  - Rapid testing of all candidate profiles.

---

## Build & Test

```bash
# Build TypeScript
npm run build

# Run All Tests (Database, Protocol/Hardware, and E2E)
npm test

# Or run individual test suites:
npm run test:db    # SQLite queries, Kerala Vision key resolution, and CDN version probe
npm run test:ir    # NEC timing generation, state machine & real hardware TX
npm run test:e2e   # Pairing, saving config, and end-to-end keycode dispatch
```

---

## CLI Usage (`pair_and_send`)

### 1. Check Dongle & Database Status
```bash
npm run stb -- --status
```
Output:
```text
=== Sensy IR Dongle Status ===
USB Hardware : Connected (VID: 0x2717, PID: 0x3d)
Firmware API : v1
Device State : Idle (Code: 0)

=== Database Metadata ===
Local DB Version : v380 (Model v1)

=== Paired STB Configuration ===
Provider   : Kerala Vision
Remote ID  : 43
Protocol   : NEC (38000 Hz, 32 bits)
Total Keys : 38 configured
```

### 2. Kerala Vision STB Pairing

#### A. Interactive Pairing Wizard
Cycles through candidate remotes one by one, prompting you to verify if the STB turns ON/OFF or adjusts volume:
```bash
npm run stb -- --pair-kerala
```

#### B. Rapid Test All Kerala Vision Profiles
Sends the `POWER` command for each of the 7 candidate remote models every 2.5 seconds so you can watch which one toggles your STB:
```bash
npm run stb -- --test-all-kerala
```

#### C. Directly Pair a Known Remote ID
```bash
npm run stb -- --pair-remote 43 --provider "Kerala Vision"
```

### 3. Send Keycodes to Paired STB
Once paired, commands are resolved from `paired_stb.json` and transmitted immediately:
```bash
npm run stb -- --send power
npm run stb -- --send vol_up
npm run stb -- --send vol_down
npm run stb -- --send ch_up
npm run stb -- --send ch_down
npm run stb -- --send ok
npm run stb -- --send mute
npm run stb -- --send 1
```

### 4. Direct Sending (Without Pairing)
```bash
npm run stb -- --send-direct 43 power
npm run stb -- --send-direct 74 vol_up
```

### 5. Online Database Updates
Check for updates or download the latest database from Sensara CDN:
```bash
# Check latest CDN revision
npm run stb -- --check-update

# Download and upgrade local database
npm run stb -- --update-db
```

---

## Programmatic Library Usage

```typescript
import { SensyDatabase, SensyIrDongle } from './src/index.js';

async function main() {
    // 1. Initialize SQLite Database & look up Kerala Vision
    const db = new SensyDatabase();
    db.open();
    const remotes = db.getKeralaVisionRemotes();
    console.log(`Found ${remotes.length} candidate remotes for Kerala Vision.`);

    const profile = remotes[0]; // Remote 43
    const pwrCode = profile.keyMap['POWER'].rawCode; // '0x14eb18e7'
    db.close();

    // 2. Transmit via USB Dongle
    const dongle = new SensyIrDongle();
    await dongle.connect();
    console.log(`Transmitting POWER (${pwrCode}) to Set-Top Box...`);
    await dongle.sendNecHex(pwrCode, profile.remote.length, 38000);
    await dongle.disconnect();
}

main().catch(console.error);
```
