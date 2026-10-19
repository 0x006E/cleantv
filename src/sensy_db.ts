/**
 * Sensy STB & IR Database Library (Strict TypeScript)
 * 
 * Manages offline SQLite data querying (lcndb.db) via built-in `node:sqlite`
 * and online OTA database synchronization from Sensara's static CDN.
 */

import { DatabaseSync } from 'node:sqlite';
import * as fs from 'node:fs';
import * as path from 'node:path';
import {
    DatabaseMetadata,
    ProviderInfo,
    ProviderBrand,
    ProviderGroup,
    RemoteInfo,
    KeyCodeInfo,
    RemoteProfile,
    OnlineVersionCheck,
    KEY_NAMES,
    PROTOCOLS
} from './types.js';

const SENSARA_CDN_BASE = 'http://sensara-static-files.sensara.tv/lcndata';

export class SensyDatabase {
    private db: DatabaseSync | null = null;
    private readonly dbPath: string;

    /**
     * @param dbPath - Absolute or relative path to lcndb.db file (defaults to ./lcndb.db)
     */
    constructor(dbPath: string = path.resolve(process.cwd(), 'lcndb.db')) {
        this.dbPath = dbPath;
    }

    /**
     * Open the SQLite database connection
     */
    public open(): void {
        if (this.db) return;
        if (!fs.existsSync(this.dbPath)) {
            throw new Error(`Database file not found at: ${this.dbPath}. Please place lcndb.db in root or run update.`);
        }
        this.db = new DatabaseSync(this.dbPath);
    }

    /**
     * Close the SQLite connection
     */
    public close(): void {
        if (!this.db) return;
        this.db.close();
        this.db = null;
    }

    /**
     * Ensure database is open before querying
     */
    private ensureDb(): DatabaseSync {
        if (!this.db) {
            this.open();
        }
        return this.db!;
    }

    /**
     * Get database schema metadata (data_version, data_model_version)
     */
    public getMetadata(): DatabaseMetadata {
        const db = this.ensureDb();
        const row = db.prepare('SELECT data_version, data_model_version FROM metadata LIMIT 1').get() as {
            data_version: number;
            data_model_version: number;
        } | undefined;

        if (!row) {
            throw new Error('Invalid database: metadata table is empty');
        }
        return {
            dataVersion: row.data_version,
            dataModelVersion: row.data_model_version
        };
    }

    /**
     * Search TV / Cable / DTH providers by title or keyword
     */
    public searchProviders(query: string, limit: number = 25): ProviderInfo[] {
        const db = this.ensureDb();
        const stmt = db.prepare(`
            SELECT 
                p.id, p.title, p.slug, p.type, p.group_id, p.brand_id,
                b.title as brand_title,
                g.title as group_title
            FROM providers p
            LEFT JOIN provider_brands b ON p.brand_id = b.id
            LEFT JOIN provider_groups g ON p.group_id = g.id
            WHERE p.title LIKE ? OR p.slug LIKE ? OR b.title LIKE ? OR g.title LIKE ?
            ORDER BY p.title ASC
            LIMIT ?
        `);
        const searchPattern = `%${query}%`;
        const rows = stmt.all(searchPattern, searchPattern, searchPattern, searchPattern, limit) as Array<{
            id: number;
            title: string;
            slug: string;
            type: string;
            group_id: number;
            brand_id: number;
            brand_title: string | null;
            group_title: string | null;
        }>;

        return rows.map(r => ({
            id: r.id,
            title: r.title,
            slug: r.slug,
            type: r.type,
            groupId: r.group_id,
            brandId: r.brand_id,
            brandTitle: r.brand_title ?? undefined,
            groupTitle: r.group_title ?? undefined
        }));
    }

    /**
     * Get provider by unique slug (e.g. 'kerala-vision-digital-network-kochi')
     */
    public getProviderBySlug(slug: string): ProviderInfo | null {
        const db = this.ensureDb();
        const stmt = db.prepare(`
            SELECT 
                p.id, p.title, p.slug, p.type, p.group_id, p.brand_id,
                b.title as brand_title,
                g.title as group_title
            FROM providers p
            LEFT JOIN provider_brands b ON p.brand_id = b.id
            LEFT JOIN provider_groups g ON p.group_id = g.id
            WHERE p.slug = ?
            LIMIT 1
        `);
        const r = stmt.get(slug) as {
            id: number;
            title: string;
            slug: string;
            type: string;
            group_id: number;
            brand_id: number;
            brand_title: string | null;
            group_title: string | null;
        } | undefined;

        if (!r) return null;
        return {
            id: r.id,
            title: r.title,
            slug: r.slug,
            type: r.type,
            groupId: r.group_id,
            brandId: r.brand_id,
            brandTitle: r.brand_title ?? undefined,
            groupTitle: r.group_title ?? undefined
        };
    }

    /**
     * Get all Kerala Vision providers across Kerala
     */
    public getKeralaVisionProviders(): ProviderInfo[] {
        const db = this.ensureDb();
        const stmt = db.prepare(`
            SELECT 
                p.id, p.title, p.slug, p.type, p.group_id, p.brand_id,
                b.title as brand_title,
                g.title as group_title
            FROM providers p
            LEFT JOIN provider_brands b ON p.brand_id = b.id
            LEFT JOIN provider_groups g ON p.group_id = g.id
            WHERE p.group_id = 43 OR p.brand_id = 69
            ORDER BY p.title ASC
        `);
        const rows = stmt.all() as Array<{
            id: number;
            title: string;
            slug: string;
            type: string;
            group_id: number;
            brand_id: number;
            brand_title: string | null;
            group_title: string | null;
        }>;

        return rows.map(r => ({
            id: r.id,
            title: r.title,
            slug: r.slug,
            type: r.type,
            groupId: r.group_id,
            brandId: r.brand_id,
            brandTitle: r.brand_title ?? undefined,
            groupTitle: r.group_title ?? undefined
        }));
    }

    /**
     * Get all candidate STB remotes for a provider group (e.g. group_id = 43 for Kerala Vision)
     */
    public getRemotesForGroup(groupId: number): RemoteProfile[] {
        const db = this.ensureDb();
        const stmt = db.prepare(`
            SELECT 
                r.id, r.protocol, r.length, r.gap, r.toggle_bit, r.toggle_mask
            FROM provider_group_remotes pgr
            JOIN remotes r ON pgr.remote_id = r.id
            WHERE pgr.group_id = ?
            ORDER BY r.id ASC
        `);
        const rows = stmt.all(groupId) as Array<{
            id: number;
            protocol: number;
            length: number;
            gap: number;
            toggle_bit: number;
            toggle_mask: string | null;
        }>;

        return rows.map(r => this.buildRemoteProfile({
            id: r.id,
            protocol: r.protocol,
            protocolName: PROTOCOLS[r.protocol] ?? `Protocol(${r.protocol})`,
            length: r.length,
            gap: r.gap,
            toggleBit: r.toggle_bit === 1,
            toggleMask: r.toggle_mask ?? ''
        }));
    }

    /**
     * Get candidate remotes specifically for Kerala Vision (Group ID: 43)
     */
    public getKeralaVisionRemotes(): RemoteProfile[] {
        return this.getRemotesForGroup(43);
    }

    /**
     * Get a specific remote profile by remote ID
     */
    public getRemote(remoteId: number): RemoteProfile | null {
        const db = this.ensureDb();
        const stmt = db.prepare(`
            SELECT id, protocol, length, gap, toggle_bit, toggle_mask
            FROM remotes
            WHERE id = ?
        `);
        const r = stmt.get(remoteId) as {
            id: number;
            protocol: number;
            length: number;
            gap: number;
            toggle_bit: number;
            toggle_mask: string | null;
        } | undefined;

        if (!r) return null;
        return this.buildRemoteProfile({
            id: r.id,
            protocol: r.protocol,
            protocolName: PROTOCOLS[r.protocol] ?? `Protocol(${r.protocol})`,
            length: r.length,
            gap: r.gap,
            toggleBit: r.toggle_bit === 1,
            toggleMask: r.toggle_mask ?? ''
        });
    }

    /**
     * Fetch key codes for a given remote ID
     */
    public getKeyCodes(remoteId: number): KeyCodeInfo[] {
        const db = this.ensureDb();
        const stmt = db.prepare(`
            SELECT id, remote_id, "key", code
            FROM key_codes
            WHERE remote_id = ?
            ORDER BY "key" ASC
        `);
        const rows = stmt.all(remoteId) as Array<{
            id: number;
            remote_id: number;
            key: number;
            code: string;
        }>;

        return rows.map(row => {
            const num = parseInt(row.code, 16);
            return {
                id: row.id,
                remoteId: row.remote_id,
                keyId: row.key,
                keyName: KEY_NAMES[row.key] ?? `KEY_${row.key}`,
                rawCode: row.code,
                numericCode: isNaN(num) ? 0 : num
            };
        });
    }

    /**
     * Construct full RemoteProfile helper
     */
    private buildRemoteProfile(remote: RemoteInfo): RemoteProfile {
        const keyCodes = this.getKeyCodes(remote.id);
        const keyMap: Record<string, KeyCodeInfo> = {};
        for (const k of keyCodes) {
            keyMap[k.keyName] = k;
            keyMap[k.keyName.toLowerCase().replace(/\s+/g, '_')] = k;
        }
        return {
            remote,
            keyCodes,
            keyMap
        };
    }

    /**
     * Check online Sensara CDN for newer versions of lcndb.db
     */
    public async checkOnlineLatestVersion(): Promise<OnlineVersionCheck> {
        const meta = this.getMetadata();
        let current = meta.dataVersion;

        // Perform fast probe / binary search starting from current version
        let low = current;
        let high = current + 100;
        let best = current;

        while (low <= high) {
            const mid = Math.floor((low + high) / 2);
            const url = `${SENSARA_CDN_BASE}/lcndb-v${mid}.db`;
            try {
                const res = await fetch(url, { method: 'HEAD' });
                if (res.status === 200) {
                    best = mid;
                    low = mid + 1; // probe higher
                } else {
                    high = mid - 1;
                }
            } catch (_) {
                high = mid - 1;
            }
        }

        return {
            currentVersion: meta.dataVersion,
            latestVersion: best,
            updateAvailable: best > meta.dataVersion,
            downloadUrl: `${SENSARA_CDN_BASE}/lcndb-v${best}.db`
        };
    }

    /**
     * Download the latest database from CDN to a designated path
     */
    public async downloadLatestDatabase(
        targetPath: string = this.dbPath,
        onProgress?: (progress: { bytesDownloaded: number; totalBytes: number; percent: number }) => void
    ): Promise<{ version: number; sizeBytes: number; path: string }> {
        const check = await this.checkOnlineLatestVersion();
        const url = check.downloadUrl;

        const res = await fetch(url);
        if (!res.ok || !res.body) {
            throw new Error(`Failed to download database from ${url}: HTTP ${res.status}`);
        }

        const totalBytes = parseInt(res.headers.get('content-length') ?? '0', 10);
        const tempPath = `${targetPath}.download`;

        const fileStream = fs.createWriteStream(tempPath);
        const reader = res.body.getReader();
        let bytesDownloaded = 0;

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            if (value) {
                fileStream.write(Buffer.from(value));
                bytesDownloaded += value.length;
                if (onProgress && totalBytes > 0) {
                    onProgress({
                        bytesDownloaded,
                        totalBytes,
                        percent: Math.round((bytesDownloaded / totalBytes) * 100)
                    });
                }
            }
        }

        await new Promise<void>((resolve, reject) => {
            fileStream.end((err?: Error | null) => {
                if (err) reject(err);
                else resolve();
            });
        });

        // Close current connection before swapping file
        this.close();

        // Atomically replace file
        if (fs.existsSync(targetPath)) {
            const backupPath = `${targetPath}.bak`;
            fs.copyFileSync(targetPath, backupPath);
        }
        fs.renameSync(tempPath, targetPath);

        // Verify newly downloaded file
        this.open();
        const newMeta = this.getMetadata();

        return {
            version: newMeta.dataVersion,
            sizeBytes: bytesDownloaded,
            path: targetPath
        };
    }
}

