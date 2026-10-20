/**
 * Wi-Fi LAN Beaming Client for Mi TV 4A Launcher
 * Connects to TvCommandServer running on port 8090
 */

export interface TvStatus {
    status: string;
    dongleConnected: boolean;
    providerTitle: string;
    providerId: number;
    remoteId: number;
    bitLength: number;
    hasCustomRemote?: boolean;
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

    /**
     * Compute full 32-bit NEC hex code from 8-bit Address and 8-bit Command
     * Example: address=0x14, command=0x8a -> "0x14eb8a75"
     */
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
     * Pair companion app with Mi TV using 6-digit code shown on TV screen
     */
    async pair(tvIp: string, pin: string, clientName: string = 'Pixel 8 Companion'): Promise<{ success: boolean; token: string; message: string }> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/pair`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 8000);
        try {
            const res = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pin, clientName, clientId: 'pixel8_companion' }),
                signal: controller.signal,
            });
            clearTimeout(timeout);
            const data = await res.json();
            if (!res.ok || !data.success) {
                throw new Error(data.error || 'Pairing failed. Check 6-digit PIN on TV.');
            }
            return data;
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
            const res = await fetch(url, {
                method: 'POST',
                headers,
                body: JSON.stringify({ token }),
            });
            const data = await res.json();
            return !!data.success;
        } catch {
            return false;
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
     * Beam an IR hex code to the Mi TV to blast via USB dongle
     */
    async blast(tvIp: string, hexCode: string, bitLength: number = 32, token?: string | null): Promise<boolean> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/blast`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 8000);
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        try {
            const res = await fetch(url, {
                method: 'POST',
                headers,
                body: JSON.stringify({ hex: hexCode, bits: bitLength }),
                signal: controller.signal,
            });
            clearTimeout(timeout);
            if (!res.ok) {
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.error || `Blast failed: HTTP ${res.status}`);
            }
            const data = await res.json();
            return !!data.success;
        } catch (err: any) {
            clearTimeout(timeout);
            throw err;
        }
    },

    /**
     * Beam and save a custom remote keymap to the Mi TV launcher
     */
    async saveCustomRemote(tvIp: string, config: CustomRemoteConfig, token?: string | null): Promise<boolean> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/custom-remote`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 8000);
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        try {
            const res = await fetch(url, {
                method: 'POST',
                headers,
                body: JSON.stringify(config),
                signal: controller.signal,
            });
            clearTimeout(timeout);
            if (!res.ok) throw new Error(`Save failed: HTTP ${res.status}`);
            const data = await res.json();
            return !!data.success;
        } catch (err: any) {
            clearTimeout(timeout);
            throw err;
        }
    },

    /**
     * Fetch the saved custom remote configuration from the Mi TV
     */
    async getCustomRemote(tvIp: string, token?: string | null): Promise<CustomRemoteConfig | null> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/custom-remote`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 8000);
        const headers: Record<string, string> = {};
        if (token) headers['Authorization'] = `Bearer ${token}`;

        try {
            const res = await fetch(url, { headers, signal: controller.signal });
            clearTimeout(timeout);
            if (!res.ok) return null;
            const data = await res.json();
            if (data && data.keys) return data as CustomRemoteConfig;
            return null;
        } catch {
            clearTimeout(timeout);
            return null;
        }
    },

    /**
     * Tune a channel on the Mi TV (IR blast + switch AV1)
     */
    async tuneChannel(tvIp: string, lcn: string, token?: string | null): Promise<boolean> {
        const url = `http://${tvIp.trim()}:${this.DEFAULT_PORT}/api/tune`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 8000);
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        try {
            const res = await fetch(url, {
                method: 'POST',
                headers,
                body: JSON.stringify({ lcn }),
                signal: controller.signal,
            });
            clearTimeout(timeout);
            if (!res.ok) {
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.error || `Tune failed: HTTP ${res.status}`);
            }
            const data = await res.json();
            return !!data.success;
        } catch (err: any) {
            clearTimeout(timeout);
            throw err;
        }
    },
};

