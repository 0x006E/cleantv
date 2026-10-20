/**
 * Structured Logging Service for CleanTV Companion Mobile App
 */

export interface LogEntry {
    timestamp: number;
    level: 'INFO' | 'WARN' | 'ERROR' | 'TX';
    tag: string;
    message: string;
}

class AppLogger {
    private logs: LogEntry[] = [];
    private maxLogs = 50;
    private listeners: ((entry: LogEntry) => void)[] = [];

    log(level: 'INFO' | 'WARN' | 'ERROR' | 'TX', tag: string, message: string) {
        const entry: LogEntry = {
            timestamp: Date.now(),
            level,
            tag,
            message,
        };
        this.logs.unshift(entry);
        if (this.logs.length > this.maxLogs) {
            this.logs.pop();
        }
        for (const listener of this.listeners) {
            try {
                listener(entry);
            } catch {}
        }
    }

    info(tag: string, message: string) {
        this.log('INFO', tag, message);
    }

    warn(tag: string, message: string) {
        this.log('WARN', tag, message);
    }

    error(tag: string, message: string) {
        this.log('ERROR', tag, message);
    }

    tx(keyName: string, hexCode: string, target: string) {
        this.log('TX', 'Transmitter', `[${keyName}] ${hexCode} -> ${target}`);
    }

    getRecentLogs(): LogEntry[] {
        return [...this.logs];
    }

    getLatestMessage(): string {
        if (this.logs.length === 0) return 'Ready. Connect to CleanTV.';
        const latest = this.logs[0];
        return `[${latest.level}] ${latest.message}`;
    }

    subscribe(listener: (entry: LogEntry) => void): () => void {
        this.listeners.push(listener);
        return () => {
            this.listeners = this.listeners.filter(l => l !== listener);
        };
    }
}

export const Logger = new AppLogger();

