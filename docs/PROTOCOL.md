# CleanTV LAN Beaming & Encryption Protocol Specification

CleanTV uses a lightweight, zero-dependency embedded HTTP server listening on TCP port `8090` of the Android TV. Communication between the Mobile Companion (`dev.ntsv.cleantvcompanion`) and the TV Launcher (`dev.ntsv.cleantv`) is protected by **AES-256-GCM** authenticated encryption with **PBKDF2-SHA256** key derivation and rolling session tokens.

---

## 1. Security Architecture

### Key Derivation
- **Pairing PIN**: A 6-digit random PIN is generated on the TV and displayed in the Settings screen (`dev.ntsv.cleantv.LauncherSettingsActivity`).
- **PBKDF2 Parameters**:
  - Algorithm: `PBKDF2WithHmacSHA256`
  - Salt: Constant domain separation salt `cleantv-companion-pin-salt-v1`
  - Iterations: `10,000`
  - Key Length: 256 bits (32 bytes)
  
### Authenticated Encryption
- Cipher: `AES/GCM/NoPadding`
- Key: 256-bit AES key (derived from PIN during pairing, or session key post-pairing)
- Nonce / IV: 12 bytes randomly generated per message via `SecureRandom`
- Auth Tag: 128 bits (16 bytes) appended to ciphertext
- Encoding: Base64 for both `iv` and `data` (ciphertext + tag)

### Request Envelope
Encrypted requests to `/api/*` endpoints wrap their parameters inside a JSON payload:
```json
{
  "iv": "<base64-encoded-12-byte-iv>",
  "data": "<base64-encoded-ciphertext-and-tag>"
}
```

---

## 2. Endpoints

### 2.1 Status & Health Check
- **Endpoint**: `GET /api/status`
- **Auth**: None required (public status probe)
- **Response**:
```json
{
  "status": "online",
  "tvName": "CleanTV (Mi TV 4A)",
  "dongleConnected": true,
  "providerTitle": "Kerala Vision",
  "providerId": 43,
  "remoteId": 184,
  "bitLength": 32,
  "hasCustomRemote": false,
  "pairingRequired": true,
  "isConfigured": true,
  "isPaired": true,
  "hasSessionKey": true,
  "pairedDevicesCount": 1
}
```

### 2.2 Device Pairing Handshake
- **Endpoint**: `POST /api/pair`
- **Encryption**: Encrypted with the PIN-derived PBKDF2 key.
- **Decrypted Payload**:
```json
{
  "clientName": "Pixel 8 Pro",
  "timestamp": 1729440000000
}
```
- **Response**:
```json
{
  "status": "paired",
  "token": "<generated-device-bearer-token>",
  "sessionKey": "<base64-encoded-new-session-key>",
  "providerTitle": "Kerala Vision",
  "providerId": 43,
  "remoteId": 184,
  "bitLength": 32
}
```

### 2.3 IR Blasting (`/api/blast`)
- **Endpoint**: `POST /api/blast`
- **Auth**: Bearer token or AES-GCM encrypted payload
- **Payload**:
```json
{
  "keyCode": 15,
  "hexCode": "0x00ffa857",
  "frequency": 38000,
  "bitLength": 32
}
```
- **Response**:
```json
{
  "status": "blasted",
  "keyCode": 15,
  "hex": "0x00ffa857"
}
```

### 2.4 Channel Tuning (`/api/tune`)
- **Endpoint**: `POST /api/tune`
- **Payload**:
```json
{
  "lcn": 10,
  "channelName": "Flowers TV"
}
```
- Blasts the constituent digits sequentially (e.g. `1` then `0`) with inter-digit delays.

### 2.5 STB Remote Selection (`/api/select-remote`)
- **Endpoint**: `POST /api/select-remote`
- **Payload**:
```json
{
  "providerId": 43,
  "providerTitle": "Kerala Vision",
  "remoteId": 184,
  "bitLength": 32
}
```
- Updates active remote and notifies Live Channel rail on the TV screen.

### 2.6 Settings Backup & Restore
- **Backup**: `GET /api/settings/backup`
  - Returns encrypted or plaintext export containing active STB provider, remote ID, custom key overrides, and favorite channels.
- **Restore**: `POST /api/settings/restore`
  - Atomically writes the configuration to TV preferences and reloads UI state.

