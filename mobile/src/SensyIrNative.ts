/**
 * TypeScript interface to native Android USB Sensy IR module
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
    }
};
