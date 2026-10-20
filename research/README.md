# CleanTV Research & Hardware Engineering

This directory contains the original reverse-engineering artifacts, standalone TypeScript WebUSB driver, STB database query engine, and interactive CLI utilities for the **Xiaomi / Sensara USB Consumer IR Blaster Dongle (`2717:003d`)**.

These tools serve as an educational and technical reference for developers exploring USB HID/bulk reverse engineering, infrared protocol modulation, and embedded Android hardware integration without needing an Android runtime.

---

## 📂 Directory Contents

| Path | Description |
|---|---|
| `src/sensy_ir.ts` | Complete TypeScript driver for Xiaomi USB IR Dongle (`2717:003d`) using WebUSB. |
| `src/sensy_db.ts` | High-performance offline SQLite query engine (`node:sqlite`) for 3,700+ STB models. |
| `src/pair_and_send.ts` | Interactive CLI tool for STB candidate cycling, signal transmission, and OTA sync. |
| `src/types.ts` | Strictly typed interfaces for hardware states, opcodes, and DB schemas. |
| `sensy_ir.js` | The original single-file reverse-engineered prototype script. |
| `tests/` | Unit and hardware verification test suites (`test_db.ts`, `test_ir.ts`, `test_pair_send.ts`). |
| `scripts/adb_pair_qr.mjs` | Terminal QR code generator for Android 11+ Wi-Fi ADB pairing. |
| `lcndb.db` | Reference offline STB SQLite database. |
| `paired_stb.json` | Sample export of paired remote configuration. |

---

## 🔍 Reverse-Engineering Methodology

The driver and database specifications in this repository were reverse-engineered directly from the vendor APK (`co.sensara.appsense`):

### 1. Hardware USB Interface
- **VID**: `0x2717` (Xiaomi Communications Co., Ltd.)
- **PID**: `0x003d` (Sensy IR Dongle)
- **Endpoints**: `0x01` (OUT) and `0x81` (IN) for bulk transmission.

### 2. Protocol Command Opcodes
The hardware microcontroller operates as a state machine accepting binary command packets:
- `0x01` (`OP_GET_CONFIG`): Queries dongle parameters (baud rate, modulation frequency).
- `0x02` (`OP_SET_CONFIG`): Sets modulation carrier frequency (default 38,000 Hz) and pulse duty cycle.
- `0x03` (`OP_START_BUFFER`): Prepares the microcontroller FIFO to receive raw timing intervals or Pronto HEX.
- `0x04` (`OP_END_BUFFER`): Commits the buffered pulse sequence and triggers the infrared transmitter LED.
- `0x78` (`OP_RESET`): Clears the FIFO and resets the internal state machine.
- `0x7F` (`OP_GET_VERSION`): Retrieves hardware firmware revision.

### 3. Infrared Signal Modulation (NEC 32-bit)
The dongle converts 32-bit hex values into precise microsecond pulse/space sequences:
- **Leader Pulse**: 9,000 µs pulse + 4,500 µs space.
- **Bit 0**: 562.5 µs pulse + 562.5 µs space.
- **Bit 1**: 562.5 µs pulse + 1,687.5 µs space.
- **Stop Bit**: 562.5 µs pulse.

---

## 🚀 Running the Standalone CLI

You can interact directly with the USB IR dongle on Linux or macOS from this directory:

### Prerequisites
- Node.js 20+ (with native SQLite support)
- `libusb` headers (if compiling native bindings on your system)

### Setup & Build
```bash
cd research
npm install
npm run build
```

### Running Test Suites
```bash
npm run test:db    # Tests SQLite queries and Kerala Vision candidate remote models
npm run test:ir    # Validates NEC pulse generation and hardware communication
npm run test:e2e   # Verifies pairing configuration persistence and keycode dispatch
```

### Interactive STB Pairing CLI
```bash
# Check dongle connection and database status
npm run stb -- --status

# Interactive Kerala Vision pairing wizard
npm run stb -- --pair-kerala

# Send specific remote key commands
npm run stb -- --send power
npm run stb -- --send vol_up
npm run stb -- --send 1 0

# Check Sensara CDN for updated STB database versions
npm run stb -- --check-cdn
```
