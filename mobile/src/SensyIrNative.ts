/**
 * TypeScript interface to native Android USB Sensy IR module and SQLite Database
 */

import { NativeModules, Platform } from 'react-native';

export interface DeviceStatus {
    connected: boolean;
    hasPermission: boolean;
    deviceInfo?: {
        vendorId: number;
        productId: number;
        deviceName: string;
    };
    message: string;
}

export interface ProviderItem {
    id: number;
    title: string;
    slug: string;
    type: string;
    groupId: number;
    brandId: number;
    brandTitle: string;
    groupTitle: string;
}

export interface RemoteCandidate {
    id: number;
    protocol: number;
    bitLength: number;
    gap: number;
    powerCode: string;
}

const { SensyIrModule } = NativeModules;

export const SensyIr = {
    /**
     * Check if Xiaomi / Sensara USB IR dongle is connected via OTG
     */
    async getStatus(): Promise<DeviceStatus> {
        if (Platform.OS !== 'android' || !SensyIrModule) {
            return {
                connected: false,
                hasPermission: false,
                message: 'Sensy IR module is only available on Android'
            };
        }
        return SensyIrModule.getStatus();
    },

    /**
     * Request Android USB permission dialog for the dongle
     */
    async requestPermission(): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule) {
            return false;
        }
        return SensyIrModule.requestPermission();
    },

    /**
     * Search any TV / DTH / Cable provider in SQLite lcndb.db
     */
    async searchProviders(query: string, limit: number = 25): Promise<ProviderItem[]> {
        if (Platform.OS !== 'android' || !SensyIrModule) {
            return [];
        }
        return SensyIrModule.searchProviders(query, limit);
    },

    /**
     * Get candidate remotes for a selected provider
     */
    async getRemotesForProvider(providerId: number): Promise<RemoteCandidate[]> {
        if (Platform.OS !== 'android' || !SensyIrModule) {
            return [];
        }
        return SensyIrModule.getRemotesForProvider(providerId);
    },

    /**
     * Get all keycodes (keyName -> hexCode) for a remote ID from SQLite lcndb.db
     */
    async getKeyCodes(remoteId: number): Promise<Record<string, string>> {
        if (Platform.OS !== 'android' || !SensyIrModule) {
            return {};
        }
        return SensyIrModule.getKeyCodes(remoteId);
    },

    /**
     * Transmit a 32-bit NEC IR code (e.g. "0x14eb18e7")
     */
    async transmitNec(hexCode: string, bitLength: number = 32): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule) {
            throw new Error('Native USB IR transmitter not available on this platform');
        }
        return SensyIrModule.transmitNec(hexCode, bitLength);
    },

    /**
     * Reset dongle to Idle state
     */
    async resetDevice(): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule) {
            return false;
        }
        return SensyIrModule.resetDevice();
    },

    /**
     * Get saved TV IP address
     */
    async getSavedTvIp(): Promise<string> {
        if (Platform.OS !== 'android' || !SensyIrModule?.getSavedTvIp) {
            return '100.97.35.51';
        }
        return SensyIrModule.getSavedTvIp();
    },

    /**
     * Save TV IP address to preferences
     */
    async saveTvIp(ip: string): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule?.saveTvIp) {
            return false;
        }
        return SensyIrModule.saveTvIp(ip);
    },

    /**
     * Get persisted authentication token for TV Beaming
     */
    async getAuthToken(): Promise<string | null> {
        if (Platform.OS !== 'android' || !SensyIrModule?.getAuthToken) {
            return null;
        }
        return SensyIrModule.getAuthToken();
    },

    /**
     * Save persisted authentication token for TV Beaming
     */
    async saveAuthToken(token: string): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule?.saveAuthToken) {
            return false;
        }
        return SensyIrModule.saveAuthToken(token);
    },

    /**
     * Clear persisted authentication token (Unpair)
     */
    async clearAuthToken(): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule?.clearAuthToken) {
            return false;
        }
        return SensyIrModule.clearAuthToken();
    },

    /**
     * Get active SQLite LCN database version
     */
    async getDatabaseVersion(): Promise<number> {
        if (Platform.OS !== 'android' || !SensyIrModule?.getDatabaseVersion) {
            return 435;
        }
        return SensyIrModule.getDatabaseVersion();
    },

    /**
     * Sync and update LCN database from internet
     */
    async syncDatabase(customUrl?: string): Promise<{ success: boolean; version: number; message: string }> {
        if (Platform.OS !== 'android' || !SensyIrModule?.syncDatabase) {
            return { success: false, version: 0, message: 'Not supported on this platform' };
        }
        return SensyIrModule.syncDatabase(customUrl ?? null);
    },

    /**
     * Derive 256-bit AES key from 6-digit PIN using PBKDF2WithHmacSHA256
     */
    async derivePinKey(pin: string): Promise<string> {
        if (Platform.OS !== 'android' || !SensyIrModule?.derivePinKey) {
            throw new Error('Native crypto not available on this platform');
        }
        return SensyIrModule.derivePinKey(pin);
    },

    /**
     * Encrypt JSON payload with AES-256-GCM
     */
    async encryptPayload(keyB64: string, plaintextJson: string): Promise<{ iv: string; data: string }> {
        if (Platform.OS !== 'android' || !SensyIrModule?.encryptPayload) {
            throw new Error('Native crypto not available on this platform');
        }
        return SensyIrModule.encryptPayload(keyB64, plaintextJson);
    },

    /**
     * Decrypt AES-256-GCM payload
     */
    async decryptPayload(keyB64: string, ivB64: string, cipherB64: string): Promise<string> {
        if (Platform.OS !== 'android' || !SensyIrModule?.decryptPayload) {
            throw new Error('Native crypto not available on this platform');
        }
        return SensyIrModule.decryptPayload(keyB64, ivB64, cipherB64);
    },

    /**
     * Save active session key for secure communication
     */
    async saveSessionKey(token: string, keyB64: string): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule?.saveSessionKey) {
            return false;
        }
        return SensyIrModule.saveSessionKey(token, keyB64);
    },

    /**
     * Get active session key
     */
    async getSessionKey(token?: string): Promise<string | null> {
        if (Platform.OS !== 'android' || !SensyIrModule?.getSessionKey) {
            return null;
        }
        return SensyIrModule.getSessionKey(token ?? null);
    },

    /**
     * Export all app settings to JSON
     */
    async exportSettings(): Promise<string> {
        if (Platform.OS !== 'android' || !SensyIrModule?.exportSettings) {
            return '{}';
        }
        return SensyIrModule.exportSettings();
    },

    /**
     * Import app settings from JSON
     */
    async importSettings(jsonStr: string): Promise<boolean> {
        if (Platform.OS !== 'android' || !SensyIrModule?.importSettings) {
            return false;
        }
        return SensyIrModule.importSettings(jsonStr);
    }
};
