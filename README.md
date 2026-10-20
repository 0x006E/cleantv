# CleanTV & CleanTV Companion

[![Android TV](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-3DDC84.svg?logo=android)](tv-launcher/)
[![Mobile Companion](https://img.shields.io/badge/Companion-React%20Native%20(Android)-61DAFB.svg?logo=react)](mobile/)
[![TypeScript](https://img.shields.io/badge/Driver-TypeScript%20%7C%20Node.js-3178C6.svg?logo=typescript)](src/)
[![Security](https://img.shields.io/badge/Security-AES--256--GCM%20%2F%20PBKDF2-blueviolet.svg)](docs/PROTOCOL.md)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

An ultra-fast, zero-bloat Android TV launcher and mobile companion system engineered for TVs with the **Xiaomi / Sensara USB Consumer IR Blaster Dongle (`2717:003d`)**. 

CleanTV turns legacy cable/DTH Set-Top Boxes into first-class smart TV experiences with live channel rails, instant infrared tuning, an on-screen translucent remote sidebar, and encrypted LAN beaming from mobile devices.

---

## Screenshots

| TV Rails & Live Channels | Interactive STB Setup Wizard |
|:---:|:---:|
| ![TV Home Rails](docs/screenshots/tv_home_rails.png) | ![STB Setup Wizard](docs/screenshots/tv_stb_wizard.png) |

| Live TV Remote Sidebar | Mobile Companion Remote | Mobile IR Studio & Scanner |
|:---:|:---:|:---:|
| ![TV Remote Sidebar](docs/screenshots/tv_remote_sidebar.png) | ![Mobile Remote](docs/screenshots/mobile_remote.png) | ![Mobile Scanner](docs/screenshots/mobile_scanner.png) |

---

## Key Features

- **Ultra-Fast Leanback Launcher (`dev.ntsv.cleantv`)**:
  - Replaces bloated, ad-heavy stock launchers (PatchWall, Google TV) with zero background overhead.
  - Native Android TV Rails UI displaying installed apps, favorite channels, and live inputs.
  - Custom glass highlight animations, time-of-day ambient background, and speed-compiled ART profile.
- **USB IR Blaster Engine (`2717:003d`)**:
  - Direct hardware driver for Xiaomi/Sensara USB IR dongles on Android TV and mobile USB OTG.
  - Supports 3,700+ providers and Set-Top Box models (Kerala Vision, Tata Play, Airtel DTH, Sun Direct, Dish TV, etc.).
  - Automatic STB configuration wizard on first launch when an IR dongle is detected.
- **CleanTV Companion (`dev.ntsv.cleantvcompanion`)**:
  - React Native mobile app with haptic touch controls, D-Pad navigation, channel selector, and volume/power keys.
  - IR Protocol Scanner & Studio for discovering unknown remotes and capturing timing pulses.
  - Real-time transmission logger with millisecond precision.
- **End-to-End Encrypted LAN Beaming**:
  - Zero-cloud local Wi-Fi control over port `8090`.
  - Authenticated **AES-256-GCM** encryption with **PBKDF2-SHA256** key derivation from an on-screen TV pairing PIN.
  - HMAC integrity verification prevents tampering and replay attacks.
- **Thoughtful Remote UX**:
  - **Live TV Back Key Mapping**: Single press sends STB `BACK` via IR; double-press within 2 seconds cleanly exits back to the Launcher.
  - **Translucent Remote Sidebar**: Access full STB controls on TV without interrupting live video playback.
  - **Settings Backup & Restore**: Export and import complete provider, channel mapping, and custom key configs across devices.

---

## Architecture

```
                       +---------------------------------------+
                       |    Sensara STB Cloud / Local DB       |
                       |      (3,700+ DTH/Cable Providers)     |
                       +-------------------+-------------------+
                                           |
                   +-----------------------+-----------------------+
                   |                                               |
                   v                                               v
   +-------------------------------+               +-------------------------------+
   |      CleanTV Companion        |  AES-256-GCM  |        CleanTV Launcher       |
   | (dev.ntsv.cleantvcompanion)   | <-----------> |       (dev.ntsv.cleantv)      |
   | - React Native UI             |  Port 8090    | - Android TV Rails Home       |
   | - IR Code Studio & Scanner    |   LAN Wi-Fi   | - Live TV Player Activity     |
   | - Dynamic Remote Sync         |               | - Embedded HTTP Command Server|
   +---------------+---------------+               +---------------+---------------+
                   |                                               |
                   | USB OTG                                       | USB Host
                   v                                               v
       +---------------------------------------------------------------+
       |       Xiaomi / Sensara USB IR Blaster Dongle (VID 0x2717)     |
       +-------------------------------+-------------------------------+
                                       |
                                       | 38kHz NEC Modulated IR
                                       v
                       +-------------------------------+
                       |       Cable / DTH STB         |
                       |    (Kerala Vision, etc.)      |
                       +-------------------------------+
```

---

## Repository Layout

```text
├── tv-launcher/                # Android TV Leanback Launcher (dev.ntsv.cleantv)
│   ├── app/src/main/java/      # Native Java launcher, STB engine, and HTTP server
│   ├── app/src/main/res/       # TV Rails layouts, animations, and design tokens
│   └── build-and-install.sh    # Fast build and zero-downtime ADB deployment
├── mobile/                     # React Native Companion App (dev.ntsv.cleantvcompanion)
│   ├── src/                    # UI Components, Screens, Encryption, and Decoupled Services
│   └── android/                # Native Android wrapper & Sensy USB bridge module
├── research/                   # Hardware reverse-engineering, driver & CLI suite
│   ├── src/                    # TypeScript WebUSB driver & SQLite query engine
│   ├── tests/                  # Hardware TX, NEC encoding, and database test suites
│   ├── sensy_ir.js             # Standalone reverse-engineered prototype script
│   └── lcndb.db                # Reference STB SQLite database
├── tools/                      # Mi TV deployment, ADB batching, and debloating scripts
│   ├── install-mitv4a.sh       # Automated installation and debloat for Mi TV 4A
│   └── restore-mitv4a.sh       # System restore and stock launcher re-enable
└── docs/                       # Technical specs and developer guides
    ├── PROTOCOL.md             # LAN Beaming encryption and REST API documentation
    ├── HARDWARE_IR.md          # Reverse-engineered USB protocol & opcodes
    └── screenshots/            # UI screenshots and visual assets
```

---

## Quick Start

### 1. TV Launcher Installation

Ensure ADB debugging is enabled on your Android TV:

```bash
# Set TV host IP address
export TV_HOST="100.97.35.51:5555"

# Build and install release APK, grant permissions, and set as default Home
cd tv-launcher
./build-and-install.sh
```

Or build manually via Gradle:
```bash
cd tv-launcher
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity --user 0 dev.ntsv.cleantv/.MainActivity
```

### 2. Mobile Companion App Installation

```bash
cd mobile

# Install dependencies
npm install

# Bundle React Native JavaScript assets
npx react-native bundle --platform android --dev false \
  --entry-file index.js \
  --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res/

# Build and install release APK
cd android
./gradlew assembleRelease
adb -s <DEVICE_ID> install -r app/build/outputs/apk/release/app-release.apk
```

### 3. Standalone Research Driver & CLI

You can also explore the hardware protocol and query the STB database directly from your workstation:

```bash
cd research

# Install dependencies & build
npm install
npm run build

# Check dongle and database status
npm run stb -- --status

# Interactive pairing wizard
npm run stb -- --pair-kerala

# Send specific IR key commands
npm run stb -- --send power
npm run stb -- --send vol_up
npm run stb -- --send 1 0
```
See the [Research & Hardware Engineering Guide](research/README.md) for reverse-engineering details and test suite usage.

---

## Security & Privacy

- **100% Local**: No internet connection, telemetry, or cloud accounts required.
- **Encrypted Local Beaming**: All LAN commands use AES-256-GCM authenticated with a dynamically generated pairing PIN.
- **Sandboxed Preferences**: Backups contain only STB channel customizations and pairing keys; private user data is never accessed.

---

## Acknowledgements & Upstream Credits

- **[CleanYourTV](https://github.com/Gathin23/cleanyourtv)** by **[Gathin23](https://github.com/Gathin23)**:
  - The TV launcher foundation (`tv-launcher/`), sleek Leanback Rails architecture, glass navigation, ambient backdrop system, and ADB debloating scripts are derived from the fantastic open-source [CleanYourTV](https://github.com/Gathin23/cleanyourtv) project (licensed under MIT).
- **Sensara / Sensy**:
  - The USB Consumer IR dongle hardware reference (`2717:003d`) and LCN database schema.
- **The Outfit Project**:
  - The Outfit typeface is licensed under the SIL Open Font License 1.1.
- **AI Assistance**:
  - This project was researched, architected, and built with AI pair-programming assistance from **Google DeepMind's Antigravity**.

---

## License

This project is licensed under the Apache 2.0 License. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for details.
