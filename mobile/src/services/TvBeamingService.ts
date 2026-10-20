/**
 * Wi-Fi LAN Beaming Client for Mi TV 4A Launcher
 * Connects to TvCommandServer running on port 8090
 */

import { SensyIr } from '../SensyIrNative';

export interface TvStatus {
    status: string;
    tvName?: string;
    dongleConnected: boolean;
    providerTitle: string;
    providerId: number;
    remoteId: number;
    bitLength: number;
    hasCustomRemote?: boolean;
    pairingRequired?: boolean;
    isPaired?: boolean;
    hasSessionKey?: boolean;
    pairedDevicesCount?: number;
}

export interface CustomRemoteConfig {
    name: string;
    baseRemoteId: number;
    addressHex: string; // e.g. "0x14eb"
    keys: Record<string, string>; // e.g. { "RECALL": "0x14eb8a75", "AUDIO": "0x14eb4ab5" }
}

export interface CandidateKey {
    name: string;
    hex: string;
    commandByte: number;
    source: string;
    description: string;
}

export const KNOWN_KERALA_CANDIDATE_KEYS: Record<string, CandidateKey[]> = {
    RECALL: [
        { name: 'Recall / Last Ch', hex: '0x14eb8a75', commandByte: 0x8a, source: 'Sai Vision Remote 225', description: 'Most common Kerala/Den recall key' },
        { name: 'Prev Channel', hex: '0x14eb32cd', commandByte: 0x32, source: 'Remote 148 / 175', description: 'Alternate last channel toggle' },
        { name: 'Previous Key', hex: '0x14eb827d', commandByte: 0x82, source: 'Remote 43 (Prev)', description: 'Previous track/media toggle' },
        { name: 'Back Alt', hex: '0x14eb6a95', commandByte: 0x6a, source: 'Remote 225 (Back)', description: 'Secondary UI return code' },
    ],
    AUDIO: [
        { name: 'Audio Track 1', hex: '0x14eb4ab5', commandByte: 0x4a, source: 'Remote 83', description: 'Toggles Malayalam / English / Hindi audio' },
        { name: 'Audio Track 2', hex: '0x14ebca35', commandByte: 0xca, source: 'Remote 148', description: 'Secondary language track toggle' },
        { name: 'Audio Track 3', hex: '0x14eb52ad', commandByte: 0x52, source: 'Remote 175', description: 'Sound/Stereo track selector' },
    ],
    SUBTITLE: [
        { name: 'Subtitles 1', hex: '0x14eb12ed', commandByte: 0x12, source: 'Remote 58', description: 'On-screen subtitle selector' },
        { name: 'Subtitles 2', hex: '0x14eb728d', commandByte: 0x72, source: 'Remote 148', description: 'Alternate subtitle toggle' },
    ],
    FAV: [
        { name: 'Favorites 1', hex: '0x14eb3ac5', commandByte: 0x3a, source: 'Remote 225', description: 'Opens favorite channel list' },
        { name: 'Favorites 2', hex: '0x14eb0af5', commandByte: 0x0a, source: 'Remote 175', description: 'Favorite channels toggle' },
    ],
    GUIDE: [
        { name: 'EPG Guide 1', hex: '0x14eb20df', commandByte: 0x20, source: 'Remote 43', description: 'Program guide overlay' },
        { name: 'EPG Guide 2', hex: '0x14ebf20d', commandByte: 0xf2, source: 'Remote 83', description: 'Alternate TV guide key' },
    ],
};

export const TvBeamingService = {
    DEFAULT_TV_IP: '100.97.35.51',
    DEFAULT_PORT: 8090,

    buildNecHex(address: number, command: number): string {
        const addr = address & 0xff;
        const invAddr = (~address) & 0xff;
        const cmd = command & 0xff;
        const invCmd = (~command) & 0xff;

        const p1 = addr.toString(16).padStart(2, '0');
        const p2 = invAddr.toString(16).padStart(2, '0');
        const p3 = cmd.toString(16).padStart(2, '0');
        const p4 = invCmd.toString(16).padStart(2, '0');
        return `0x${p1}${p2}${p3}${p4}`;
    },

    /**
     * Pair companion app with Mi TV using AES-256-GCM authenticated key agreement derived from 6-digit PIN.
     */
    async pair(tvIp: string, pin: string, clientName: string = 'Pixel 8 Companion'): Promise<{ success: boolean; token: string; message: string }> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/pair`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 15000);

        try {
            const cleanPin = pin.replace(/\s+/g, '').trim();
            const pinKey = await SensyIr.derivePinKey(cleanPin);

            // Encrypt handshake payload with PBKDF2 PIN key
            const payload = JSON.stringify({
                pin: cleanPin,
                clientName,
                clientId: 'pixel8_companion',
                timestamp: Date.now(),
            });
            const enc = await SensyIr.encryptPayload(pinKey, payload);

            const res = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    pin: cleanPin,
                    clientName,
                    clientId: 'pixel8_companion',
                    iv: enc.iv,
                    data: enc.data,
                }),
                signal: controller.signal,
            });
            clearTimeout(timeout);

            const rawJson = await res.json();
            if (!res.ok) {
                throw new Error(rawJson.error || 'Pairing failed. Check 6-digit PIN on TV.');
            }

            let decData: any = rawJson;
            if (rawJson.iv && rawJson.data) {
                const decStr = await SensyIr.decryptPayload(pinKey, rawJson.iv, rawJson.data);
                decData = JSON.parse(decStr);
            }

            if (!decData.success || !decData.token) {
                throw new Error(decData.error || 'Pairing rejected by TV');
            }

            // Persist token and AES-256 session key
            await SensyIr.saveAuthToken(decData.token);
            if (decData.session_key) {
                await SensyIr.saveSessionKey(decData.token, decData.session_key);
            }

            return {
                success: true,
                token: decData.token,
                message: decData.message || 'Paired securely with AES-256-GCM encryption!',
            };
        } catch (err: any) {
            clearTimeout(timeout);
            throw err;
        }
    },

    /**
     * Unpair companion app from Mi TV
     */
    async unpair(tvIp: string, token?: string | null): Promise<boolean> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/unpair`;
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;
        try {
            await fetch(url, {
                method: 'POST',
                headers,
                body: JSON.stringify({ token }),
            });
            await SensyIr.clearAuthToken();
            return true;
        } catch {
            await SensyIr.clearAuthToken();
            return false;
        }
    },

    /**
     * Helper to perform authenticated and AES-256-GCM encrypted POST request to TV
     */
    async postEncrypted(tvIp: string, path: string, dataObj: Record<string, any>, token?: string | null): Promise<any> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}${path}`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 8000);

        const sessionKey = await SensyIr.getSessionKey(token ?? undefined);
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        let bodyStr = '';
        if (sessionKey) {
            const payload = JSON.stringify({
                ...dataObj,
                timestamp: Date.now(),
            });
            const enc = await SensyIr.encryptPayload(sessionKey, payload);
            bodyStr = JSON.stringify({
                token: token ?? undefined,
                iv: enc.iv,
                data: enc.data,
            });
        } else {
            bodyStr = JSON.stringify(dataObj);
        }

        try {
            const res = await fetch(url, {
                method: 'POST',
                headers,
                body: bodyStr,
                signal: controller.signal,
            });
            clearTimeout(timeout);

            const rawJson = await res.json();
            if (!res.ok) {
                throw new Error(rawJson.error || `HTTP ${res.status}`);
            }

            if (sessionKey && rawJson.iv && rawJson.data) {
                const decStr = await SensyIr.decryptPayload(sessionKey, rawJson.iv, rawJson.data);
                return JSON.parse(decStr);
            }
            return rawJson;
        } catch (err: any) {
            clearTimeout(timeout);
            throw err;
        }
    },

    /**
     * Check if the Mi TV launcher is online on the local Wi-Fi / Tailscale
     */
    async checkStatus(tvIp: string, token?: string | null): Promise<TvStatus> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/status`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 8000);
        const headers: Record<string, string> = {};
        if (token) headers['Authorization'] = `Bearer ${token}`;

        try {
            const res = await fetch(url, { headers, signal: controller.signal });
            clearTimeout(timeout);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return await res.json();
        } catch (err: any) {
            clearTimeout(timeout);
            throw new Error(`Cannot reach TV at ${tvIp}: ${err.message}`);
        }
    },

    /**
     * Beam an IR hex code to the Mi TV to blast via USB dongle with AES-256-GCM encryption
     */
    async blast(tvIp: string, hexCode: string, bitLength: number = 32, token?: string | null): Promise<boolean> {
        const data = await this.postEncrypted(tvIp, '/api/blast', { hex: hexCode, bits: bitLength }, token);
        return !!data.success;
    },

    /**
     * Tune a channel on the Mi TV with AES-256-GCM encryption
     */
    async tuneChannel(tvIp: string, lcn: string, token?: string | null): Promise<boolean> {
        const data = await this.postEncrypted(tvIp, '/api/tune', { lcn }, token);
        return !!data.success;
    },

    /**
     * Beam and save a custom remote keymap to the Mi TV launcher
     */
    async saveCustomRemote(tvIp: string, config: CustomRemoteConfig, token?: string | null): Promise<boolean> {
        const data = await this.postEncrypted(tvIp, '/api/custom-remote', { keys: config }, token);
        return !!data.success;
    },

    /**
     * Fetch the saved custom remote configuration from the Mi TV
     */
    async getCustomRemote(tvIp: string, token?: string | null): Promise<CustomRemoteConfig | null> {
        try {
            const data = await this.postEncrypted(tvIp, '/api/custom-remote', { action: 'get' }, token);
            if (data && data.keys) return data as CustomRemoteConfig;
            return null;
        } catch {
            return null;
        }
    },

    /**
     * Pull complete settings backup from the TV
     */
    async pullSettingsFromTv(tvIp: string, token?: string | null): Promise<any> {
        const data = await this.postEncrypted(tvIp, '/api/settings/backup', {}, token);
        if (data && data.settings) return data.settings;
        return data;
    },

    /**
     * Push settings backup to restore on the TV
     */
    async pushSettingsToTv(tvIp: string, settingsObj: any, token?: string | null): Promise<boolean> {
        const data = await this.postEncrypted(tvIp, '/api/settings/restore', { settings: settingsObj }, token);
        return !!data.success;
    },
};

