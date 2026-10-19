# Xiaomi / Sensy USB IR Blaster Dongle (`2717:003d`)

A standalone Node.js driver and CLI utility for the Xiaomi / Sensara Sensy USB Consumer IR Dongle, reverse-engineered directly from `co-sensara-appsense-188-62097078-e53d5b3b45d2fd3224df1ea62a890347.apk`.

---

## Hardware Specifications
* **Vendor ID (VID):** `0x2717` (Xiaomi Inc.)
* **Product ID (PID):** `0x003d` (Sensy Consumer IR Dongle)
* **USB Interface:** `0` (Vendor-specific `0xFF/0xFF/0xFF`)
* **Endpoint 2 (OUT, Interrupt):** Command Dispatch (OPCODES)
* **Endpoint 1 (IN, Interrupt):** Response & State Telemetry
* **Endpoint 4 (OUT, Bulk):** Pulse Timing Buffer Stream (64-byte chunks, uint32_LE microseconds)

---

## Prerequisites
Node.js (v18+) with `usb` package installed:
```bash
npm install usb
```

Ensure your user has read/write permissions to the USB device in `/dev/bus/usb/`.

---

## CLI Usage

### 1. View Dongle Info & Status
```bash
node sensy_ir.js --info
```

### 2. Run Self-Test
Sends a test NEC power frame and observes state transitions:
```bash
node sensy_ir.js --test
```

### 3. Send NEC IR Command
Transmit an address and command (hex or decimal):
```bash
node sensy_ir.js --nec 0x04 0x08
```

### 4. Send Pronto HEX Code
Transmit standard Pronto hex formatted codes:
```bash
node sensy_ir.js --pronto "0000 006D 0022 0002 0155 00AA 0015 0015 0015 0040 ..."
```

### 5. Send Raw Pulse Timings
Transmit custom carrier frequency and pulse/space durations (in microseconds):
```bash
node sensy_ir.js --raw 38000 "9000,4500,560,560,560,1690,560"
```

### 6. Reset Dongle
```bash
node sensy_ir.js --reset
```

---

## Programmatic Library Usage

You can import `SensyIrDongle` directly in your Node.js projects:

```javascript
const { SensyIrDongle } = require('./sensy_ir.js');

async function run() {
    const dongle = new SensyIrDongle();

    try {
        await dongle.connect();

        // 1. Send NEC code (e.g. Address 0x04, Command 0x08)
        await dongle.sendNec(0x04, 0x08);

        // 2. Or send raw pulse/space timings (in microseconds) at 38kHz
        const timings = [9000, 4500, 560, 560, 560, 1690, 560];
        await dongle.transmit(38000, timings);

        // 3. Or send Pronto HEX format
        await dongle.sendPronto("0000 006D 0004 0000 0015 0015 0015 0041");

    } finally {
        await dongle.disconnect();
    }
}

run().catch(console.error);
```

