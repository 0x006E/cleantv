/**
 * Decoupled TV Connection Manager
 * Manages Wi-Fi LAN / Tailscale connection, authentication, and silent health polling
 */

import { SensyIr } from '../SensyIrNative';
import { TvBeamingService, TvStatus } from './TvBeamingService';
import { Logger } from './Logger';

export type ConnectionState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED_UNPAIRED' | 'CONNECTED_PAIRED';

export interface TvConnectionListener {
    onStateChange: (state: ConnectionState, status?: TvStatus | null) => void;
    onKeyMapLoaded?: (keyMap: Record<string, string>, providerTitle?: string) => void;
}

class ConnectionManager {
    private currentIp: string = '100.97.35.51';
    private authToken: string | null = null;
    private state: ConnectionState = 'DISCONNECTED';
    private status: TvStatus | null = null;
    private pollTimer: any = null;
    private listeners: TvConnectionListener[] = [];
    private isManualConnecting: boolean = false;

    async initialize() {
        try {
            const savedIp = await SensyIr.getSavedTvIp();
            if (savedIp) this.currentIp = savedIp;

            const savedToken = await SensyIr.getAuthToken();
            if (savedToken) this.authToken = savedToken;

            // Perform initial silent check
            await this.silentCheck();
            this.startSilentPolling();
        } catch (e: any) {
            Logger.error('ConnectionManager', `Init failed: ${e.message}`);
        }
    }

    addListener(listener: TvConnectionListener): () => void {
        this.listeners.push(listener);
        listener.onStateChange(this.state, this.status);
        return () => {
            this.listeners = this.listeners.filter(l => l !== listener);
        };
    }

    private notify() {
        for (const l of this.listeners) {
            try {
                l.onStateChange(this.state, this.status);
            } catch {}
        }
    }

    getIp(): string {
        return this.currentIp;
    }

    setIp(ip: string) {
        this.currentIp = ip;
    }

    getAuthToken(): string | null {
        return this.authToken;
    }

    getState(): ConnectionState {
        return this.state;
    }

    getStatus(): TvStatus | null {
        return this.status;
    }

    isPaired(): boolean {
        return this.state === 'CONNECTED_PAIRED';
    }

    isConnected(): boolean {
        return this.state === 'CONNECTED_PAIRED' || this.state === 'CONNECTED_UNPAIRED';
    }

    /**
     * Manual user-initiated connect (sets CONNECTING state)
     */
    async connect(targetIp?: string): Promise<boolean> {
        if (targetIp) this.currentIp = targetIp.trim();
        this.isManualConnecting = true;
        this.state = 'CONNECTING';
        this.notify();

        try {
            Logger.info('ConnectionManager', `Connecting to ${this.currentIp}...`);
            const st = await TvBeamingService.checkStatus(this.currentIp, this.authToken);
            this.status = st;
            await SensyIr.saveTvIp(this.currentIp);

            if (st.isPaired && this.authToken) {
                this.state = 'CONNECTED_PAIRED';
            } else {
                this.state = 'CONNECTED_UNPAIRED';
            }

            Logger.info('ConnectionManager', `Connected: ${st.tvName || 'CleanTV'} (${st.providerTitle}) [paired=${this.isPaired()}]`);
            this.notify();

            // Load remote keycodes from native SQLite database for the TV's paired remote
            if (st.remoteId > 0) {
                this.loadRemoteKeys(st.remoteId, st.providerTitle);
            }

            return true;
        } catch (err: any) {
            this.state = 'DISCONNECTED';
            this.status = null;
            Logger.warn('ConnectionManager', `Connection failed: ${err.message}`);
            this.notify();
            throw err;
        } finally {
            this.isManualConnecting = false;
        }
    }

    /**
     * Silent background check (does not interrupt UI or clear inputs)
     */
    async silentCheck() {
        if (this.isManualConnecting) return;
        try {
            const st = await TvBeamingService.checkStatus(this.currentIp, this.authToken);
            this.status = st;
            const newState: ConnectionState = (st.isPaired && this.authToken) ? 'CONNECTED_PAIRED' : 'CONNECTED_UNPAIRED';
            if (newState !== this.state) {
                this.state = newState;
                this.notify();
            }
            if (st.remoteId > 0) {
                this.loadRemoteKeys(st.remoteId, st.providerTitle);
            }
        } catch {
            if (this.state !== 'DISCONNECTED') {
                this.state = 'DISCONNECTED';
                this.status = null;
                this.notify();
            }
        }
    }

    private startSilentPolling() {
        if (this.pollTimer) clearInterval(this.pollTimer);
        this.pollTimer = setInterval(() => this.silentCheck(), 10000);
    }

    stopPolling() {
        if (this.pollTimer) {
            clearInterval(this.pollTimer);
            this.pollTimer = null;
        }
    }

    private async loadRemoteKeys(remoteId: number, providerTitle?: string) {
        try {
            const keys = await SensyIr.getKeyCodes(remoteId);
            if (keys && Object.keys(keys).length > 0) {
                Logger.info('ConnectionManager', `Loaded ${Object.keys(keys).length} keys for remote #${remoteId}`);
                for (const l of this.listeners) {
                    l.onKeyMapLoaded?.(keys, providerTitle);
                }
            }
        } catch (e: any) {
            Logger.warn('ConnectionManager', `Could not load keys for remote #${remoteId}: ${e.message}`);
        }
    }

    /**
     * Disconnect and clear state
     */
    disconnect() {
        this.stopPolling();
        this.state = 'DISCONNECTED';
        this.status = null;
        Logger.info('ConnectionManager', 'Disconnected from TV');
        this.notify();
    }

    /**
     * Pair with CleanTV using 6-digit PIN
     */
    async pair(pin: string): Promise<boolean> {
        try {
            Logger.info('ConnectionManager', `Pairing with CleanTV at ${this.currentIp}...`);
            const res = await TvBeamingService.pair(this.currentIp, pin);
            if (res.success && res.token) {
                this.authToken = res.token;
                this.state = 'CONNECTED_PAIRED';
                Logger.info('ConnectionManager', 'Pairing successful with AES-256-GCM session!');
                this.notify();
                await this.silentCheck();
                return true;
            }
            return false;
        } catch (err: any) {
            Logger.error('ConnectionManager', `Pairing failed: ${err.message}`);
            throw err;
        }
    }

    /**
     * Unpair from CleanTV
     */
    async unpair(): Promise<boolean> {
        try {
            await TvBeamingService.unpair(this.currentIp, this.authToken);
        } catch {}
        this.authToken = null;
        this.state = 'CONNECTED_UNPAIRED';
        Logger.info('ConnectionManager', 'Unpaired device from CleanTV');
        this.notify();
        return true;
    }
}

export const TvConnectionManager = new ConnectionManager();

