# Xiaomi Sensara Sensy USB IR Blaster Dongle (`2717:003d`)

Hardware reference, reverse-engineered USB protocol, and timing specifications for the Sensy / Xiaomi USB Consumer IR Dongle.

---

## 1. Hardware Specifications

- **Vendor ID (VID)**: `0x2717` (Xiaomi Communications Co., Ltd.)
- **Product ID (PID)**: `0x003d` (Sensy IR Blaster)
- **USB Interface**: USB HID / Custom Bulk Transfer Endpoint (`0x01` OUT / `0x81` IN)
- **Carrier Modulation Frequency**: 36 kHz – 56 kHz (default 38 kHz)
- **Max Buffer Size**: 512 bytes per command transmission chunk

---

## 2. Command Opcodes

| Opcode | Identifier | Description |
|---|---|---|
| `0x01` | `OP_GET_CONFIG` | Queries dongle configuration and firmware parameters. |
| `0x02` | `OP_SET_CONFIG` | Sets modulation frequency, pulse duty cycle, and repeat flags. |
| `0x03` | `OP_START_BUFFER` | Begins a multi-packet raw timing or Pronto HEX payload buffer. |
| `0x04` | `OP_END_BUFFER` | Commits the buffered pulses and triggers the IR LED emitter. |
| `0x78` (`120`) | `OP_RESET` | Re-initializes dongle state machine and clears transfer FIFO. |
| `0x7F` (`127`) | `OP_GET_VERSION`| Reads hardware and firmware version strings. |

---

## 3. Protocol Encoding (NEC 32-bit)

CleanTV drives Set-Top Boxes using the standard NEC infrared modulation protocol:
- **Leader Pulse**: 9,000 µs high mark followed by 4,500 µs space.
- **Bit 0**: 562.5 µs mark followed by 562.5 µs space.
- **Bit 1**: 562.5 µs mark followed by 1,687.5 µs space.
- **Stop Bit**: 562.5 µs trailing mark.

### Example Key Codes (Kerala Vision Remote #184):
- `POWER`: `0x00ffa857`
- `VOL UP`: `0x00ff827d`
- `VOL DOWN`: `0x00ffc23d`
- `MUTE`: `0x00ff02fd`
- `BACK`: `0x00ff6897`
- `DIGIT 0`: `0x00ff42bd`
- `DIGIT 1`: `0x00ff807f`
- `DIGIT 7`: `0x00ff50af`

