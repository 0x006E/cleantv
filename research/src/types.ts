/**
 * TypeScript Type Definitions for Sensy IR & STB Configuration System
 * Strictly typed definitions for hardware control, SQLite database models, and cloud updates.
 */

export interface DongleState {
    readonly stateCode: number;
    readonly stateName: string;
    readonly status: number;
    readonly valid: number;
    readonly substate?: number;
    readonly frequency?: number;
    readonly bufferTargetSize?: number;
    readonly bufferCurrentSize?: number;
    readonly currentPosition?: number;
}

export interface DongleInfo {
    readonly vendorId: number;
    readonly productId: number;
    readonly firmwareVersion: number;
    readonly state: DongleState;
}

export interface IrTransmitOptions {
    readonly frequencyHz?: number;
    readonly repeatCount?: number;
    readonly gapUs?: number;
}

export interface DatabaseMetadata {
    readonly dataVersion: number;
    readonly dataModelVersion: number;
}

export interface ProviderBrand {
    readonly id: number;
    readonly title: string;
    readonly isDth: boolean;
    readonly groupId: number;
}

export interface ProviderGroup {
    readonly id: number;
    readonly title: string;
}

export interface ProviderInfo {
    readonly id: number;
    readonly title: string;
    readonly slug: string;
    readonly type: 'CABLE' | 'DTH' | string;
    readonly groupId: number;
    readonly brandId: number;
    readonly brandTitle?: string;
    readonly groupTitle?: string;
}

export interface RemoteInfo {
    readonly id: number;
    readonly protocol: number;
    readonly protocolName: string;
    readonly length: number;
    readonly gap: number;
    readonly toggleBit: boolean;
    readonly toggleMask: string;
}

export interface KeyCodeInfo {
    readonly id: number;
    readonly remoteId: number;
    readonly keyId: number;
    readonly keyName: string;
    readonly rawCode: string;
    readonly numericCode: number;
}

export interface RemoteProfile {
    readonly remote: RemoteInfo;
    readonly keyCodes: readonly KeyCodeInfo[];
    readonly keyMap: Readonly<Record<string, KeyCodeInfo>>;
}

export interface PairedStbConfig {
    readonly providerTitle: string;
    readonly providerSlug?: string;
    readonly groupId: number;
    readonly remoteId: number;
    readonly protocol: number;
    readonly protocolName: string;
    readonly bitLength: number;
    readonly carrierHz: number;
    readonly pairedAt: string;
    readonly keys: Record<string, string>; // keyName -> hexCode
}

export interface OnlineVersionCheck {
    readonly currentVersion: number;
    readonly latestVersion: number;
    readonly updateAvailable: boolean;
    readonly downloadUrl: string;
}

export interface DownloadProgress {
    readonly bytesDownloaded: number;
    readonly totalBytes: number;
    readonly percent: number;
}

/**
 * Standard Sensy Remote Key IDs mapped from decompiled co.sensara.sensy.infrared.KeyMap
 */
export const KEY_NAMES: Readonly<Record<number, string>> = {
    0: '0',
    1: '1',
    2: '2',
    3: '3',
    4: '4',
    5: '5',
    6: '6',
    7: '7',
    8: '8',
    9: '9',
    10: 'VOL UP',
    11: 'VOL DOWN',
    12: 'VOL MUTE',
    13: 'CH UP',
    14: 'CH DOWN',
    15: 'POWER',
    16: 'SELECT',
    17: 'BACK',
    18: 'EXIT',
    19: 'MENU',
    20: 'INFO',
    21: 'UP',
    22: 'DOWN',
    23: 'LEFT',
    24: 'RIGHT',
    25: 'OK',
    26: 'RED',
    27: 'GREEN',
    28: 'YELLOW',
    29: 'BLUE',
    30: 'PLAY',
    31: 'REWIND',
    32: 'FAST FORWARD',
    33: 'RECORD',
    34: 'PAUSE',
    35: 'STOP',
    36: 'SOURCE',
    37: 'OPTIONS',
    38: 'LANGUAGE',
    39: 'GUIDE',
    40: 'PREVIOUS',
    41: 'NEXT'
};

/**
 * Supported Infrared Modulation Protocols in Sensy Database
 */
export const PROTOCOLS: Readonly<Record<number, string>> = {
    0: 'NEC',
    1: 'RC5',
    2: 'RC6',
    3: 'SONY',
    4: 'PANASONIC',
    5: 'JVC',
    6: 'SAMSUNG'
};

