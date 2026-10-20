import React, { useState, useEffect } from 'react';
import {
    SafeAreaView,
    ScrollView,
    StatusBar,
    StyleSheet,
    Text,
    TextInput,
    TouchableOpacity,
    View,
    ActivityIndicator,
    Alert,
    FlatList,
    Platform,
} from 'react-native';
import { SensyIr, DeviceStatus, ProviderItem, RemoteCandidate } from './src/SensyIrNative';
import { theme } from './src/lib/theme';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './src/components/ui/card';
import { Button } from './src/components/ui/button';
import { Badge } from './src/components/ui/badge';
import { Input } from './src/components/ui/input';
import { Separator } from './src/components/ui/separator';
import { TabsList, TabsTrigger } from './src/components/ui/tabs';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogClose } from './src/components/ui/dialog';
import { KeyScannerView } from './src/components/KeyScannerView';
import { CustomRemoteView } from './src/components/CustomRemoteView';
import { TvBeamingService, CustomRemoteConfig } from './src/services/TvBeamingService';
import { Logger, LogEntry } from './src/services/Logger';
import { TvConnectionManager } from './src/services/TvConnectionManager';

export default function App() {
    const [status, setStatus] = useState<DeviceStatus>({
        connected: false,
        hasPermission: false,
        message: 'Checking USB status...',
    });

    // Wi-Fi Beaming to Mi TV
    const [tvIp, setTvIp] = useState<string>('100.97.35.51');
    const [isTvConnected, setIsTvConnected] = useState<boolean>(false);
    const [isCheckingTv, setIsCheckingTv] = useState<boolean>(false);
    const [showTvConfig, setShowTvConfig] = useState<boolean>(false);

    // Secure TV Pairing & Auth Token
    const [authToken, setAuthToken] = useState<string | null>(null);
    const [isPaired, setIsPaired] = useState<boolean>(false);
    const [pairingPin, setPairingPin] = useState<string>('');
    const [isPairing, setIsPairing] = useState<boolean>(false);

    // Database Sync
    const [dbVersion, setDbVersion] = useState<number>(435);
    const [customDbUrl, setCustomDbUrl] = useState<string>('');
    const [isSyncingDb, setIsSyncingDb] = useState<boolean>(false);
    const [isBackupDialogOpen, setIsBackupDialogOpen] = useState<boolean>(false);
    const [backupDataText, setBackupDataText] = useState<string>('');
    const [isOperatingBackup, setIsOperatingBackup] = useState<boolean>(false);

    // Active screen navigation
    const [activeTab, setActiveTab] = useState<'remote' | 'scanner' | 'custom'>('remote');

    // Provider search state
    const [searchQuery, setSearchQuery] = useState<string>('');
    const [searchResults, setSearchResults] = useState<ProviderItem[]>([]);
    const [selectedProvider, setSelectedProvider] = useState<ProviderItem | null>(null);
    const [isSearching, setIsSearching] = useState<boolean>(false);
    const [isSearchOpen, setIsSearchOpen] = useState<boolean>(false);

    // Candidate remotes and active keymap
    const [candidateRemotes, setCandidateRemotes] = useState<RemoteCandidate[]>([]);
    const [selectedRemoteIndex, setSelectedRemoteIndex] = useState<number>(0);
    const [keyMap, setKeyMap] = useState<Record<string, string>>({});
    const [isLoadingKeys, setIsLoadingKeys] = useState<boolean>(false);

    // Custom Remote Configuration
    const [customRemote, setCustomRemote] = useState<CustomRemoteConfig>({
        name: 'Kerala Vision (Custom)',
        baseRemoteId: 43,
        addressHex: '0x14eb',
        keys: {
            RECALL: '0x14eb8a75',
            AUDIO: '0x14eb4ab5',
            SUBTITLE: '0x14eb12ed',
        },
    });

    const [isTransmitting, setIsTransmitting] = useState<boolean>(false);
    const [lastLog, setLastLog] = useState<string>('Ready. Connect to Mi TV or plug USB blaster.');
    const [logEntries, setLogEntries] = useState<LogEntry[]>(() => Logger.getRecentLogs());
    const [showKeypad, setShowKeypad] = useState<boolean>(true);

    // Check USB state periodically
    const checkUsb = async () => {
        try {
            const st = await SensyIr.getStatus();
            setStatus(st);
        } catch (e: any) {
            setLastLog(`USB Error: ${e.message}`);
        }
    };

    const [isConnecting, setIsConnecting] = useState<boolean>(false);

    useEffect(() => {
        const unsubLogger = Logger.subscribe((entry) => {
            setLogEntries([...Logger.getRecentLogs()]);
            setLastLog(entry.message);
        });

        TvConnectionManager.initialize();
        setTvIp(TvConnectionManager.getIp());

        const unsubConn = TvConnectionManager.addListener({
            onStateChange: (state, st) => {
                const connected = state === 'CONNECTED_PAIRED' || state === 'CONNECTED_UNPAIRED';
                const paired = state === 'CONNECTED_PAIRED';
                setIsTvConnected(connected);
                setIsPaired(paired);
                if (st?.providerTitle) {
                    setLastLog(`CleanTV connected (${st.providerTitle})`);
                }
            },
            onKeyMapLoaded: (keys, title) => {
                setKeyMap(keys);
                if (title) setLastLog(`Loaded remote keys for ${title}`);
            }
        });

        SensyIr.getDatabaseVersion().then((ver) => {
            if (ver > 0) setDbVersion(ver);
        }).catch(() => {});

        checkUsb();
        const usbTimer = setInterval(checkUsb, 3000);

        return () => {
            unsubLogger();
            unsubConn();
            clearInterval(usbTimer);
            TvConnectionManager.stopPolling();
        };
    }, []);

    const handleManualConnect = async () => {
        if (!tvIp.trim()) {
            Alert.alert('Missing IP', 'Please enter your CleanTV IP address.');
            return;
        }
        setIsConnecting(true);
        try {
            await TvConnectionManager.connect(tvIp.trim());
        } catch (e: any) {
            Alert.alert('Connection Failed', `Could not reach CleanTV at ${tvIp}:8090. Make sure the TV is turned on and connected.`);
        } finally {
            setIsConnecting(false);
        }
    };

    // Initial search on launch
    useEffect(() => {
        handleSearch('Kerala Vision', true);
    }, []);

    const handleSearch = async (text: string, autoSelectFirst = false) => {
        setSearchQuery(text);
        if (!text || text.trim().length === 0) {
            setSearchResults([]);
            return;
        }

        setIsSearching(true);
        try {
            const results = await SensyIr.searchProviders(text.trim(), 20);
            setSearchResults(results);
            if (autoSelectFirst && results.length > 0 && !selectedProvider) {
                selectProvider(results[0]);
            }
        } catch (e: any) {
            setLastLog(`Search Error: ${e.message}`);
        } finally {
            setIsSearching(false);
        }
    };

    const selectProvider = async (provider: ProviderItem) => {
        setSelectedProvider(provider);
        setIsSearchOpen(false);
        setLastLog(`Selected: ${provider.title}`);

        try {
            const remotes = await SensyIr.getRemotesForProvider(provider.id);
            setCandidateRemotes(remotes);
            setSelectedRemoteIndex(0);
            if (remotes.length > 0) {
                await loadRemoteKeys(remotes[0].id);
            } else {
                setKeyMap({});
                setLastLog(`No remotes in database for ${provider.title}`);
            }
        } catch (e: any) {
            setLastLog(`Remote Error: ${e.message}`);
        }
    };

    const loadRemoteKeys = async (remoteId: number) => {
        setIsLoadingKeys(true);
        try {
            const keys = await SensyIr.getKeyCodes(remoteId);
            setKeyMap(keys);
            setLastLog(`Loaded Remote ID ${remoteId} (${Object.keys(keys).length} keys mapped)`);
        } catch (e: any) {
            setLastLog(`Keys Error: ${e.message}`);
        } finally {
            setIsLoadingKeys(false);
        }
    };

    const handleSelectRemoteIndex = async (index: number) => {
        setSelectedRemoteIndex(index);
        const r = candidateRemotes[index];
        if (r) {
            await loadRemoteKeys(r.id);
        }
    };

    const handleRequestPermission = async () => {
        try {
            await SensyIr.requestPermission();
            await checkUsb();
        } catch (e: any) {
            Alert.alert('Permission Error', e.message);
        }
    };

    /**
     * Unified transmitter: sends via Wi-Fi to Mi TV or directly via phone USB
     */
    const blastHexCode = async (hex: string, bitLength = 32, keyName?: string): Promise<boolean> => {
        setIsTransmitting(true);
        try {
            if (isTvConnected) {
                if (!isPaired) {
                    Alert.alert(
                        'Pairing Required',
                        'Please pair with your CleanTV using the 6-digit code in TV Settings -> Companion App.'
                    );
                    throw new Error('Device not paired with TV');
                }
                const token = TvConnectionManager.getAuthToken();
                await TvBeamingService.blast(tvIp, hex, bitLength, token);
                Logger.tx(keyName || hex, hex, 'CleanTV');
                return true;
            } else if (status.connected && status.hasPermission) {
                await SensyIr.transmitNec(hex, bitLength);
                Logger.tx(keyName || hex, hex, 'USB Blaster');
                return true;
            } else {
                throw new Error('TV not connected');
            }
        } catch (err: any) {
            Logger.error('Transmitter', `TX error: ${err.message}`);
            throw err;
        } finally {
            setIsTransmitting(false);
        }
    };

    const handlePair = async () => {
        if (!pairingPin.trim() || pairingPin.trim().length !== 6) {
            Alert.alert('Invalid Code', 'Please enter the 6-digit pairing PIN shown on your TV screen.');
            return;
        }
        setIsPairing(true);
        try {
            const ok = await TvConnectionManager.pair(pairingPin.trim());
            if (ok) {
                Alert.alert('Paired Successfully', 'Your companion mobile app is securely paired with CleanTV!');
                setPairingPin('');
            }
        } catch (e: any) {
            Alert.alert('Pairing Failed', e.message || 'Could not pair with TV.');
        } finally {
            setIsPairing(false);
        }
    };

    const handleUnpair = async () => {
        await TvConnectionManager.unpair();
        Alert.alert('Unpaired', 'Device has been unpaired from TV.');
    };

    const handleSyncDb = async () => {
        setIsSyncingDb(true);
        try {
            const res = await SensyIr.syncDatabase(customDbUrl.trim() || undefined);
            if (res.success) {
                setDbVersion(res.version);
                Alert.alert('Database Updated', `Successfully updated LCN database to v${res.version}!`);
                setLastLog(`Database updated to v${res.version}`);
            } else {
                Alert.alert('Update Info', res.message);
            }
        } catch (e: any) {
            Alert.alert('Sync Error', e.message);
        } finally {
            setIsSyncingDb(false);
        }
    };

    const handleOpenExport = async () => {
        try {
            const data = await SensyIr.exportSettings();
            setBackupDataText(data);
            setIsBackupDialogOpen(true);
        } catch (e: any) {
            Alert.alert('Export Error', e.message);
        }
    };

    const handleApplyImport = async () => {
        if (!backupDataText.trim()) {
            Alert.alert('Empty Data', 'Please paste valid JSON settings to import.');
            return;
        }
        try {
            const ok = await SensyIr.importSettings(backupDataText.trim());
            if (ok) {
                Alert.alert('Import Successful', 'Settings have been imported!');
                setIsBackupDialogOpen(false);
            } else {
                Alert.alert('Import Failed', 'Invalid settings format.');
            }
        } catch (e: any) {
            Alert.alert('Import Error', e.message);
        }
    };

    const handlePullFromTv = async () => {
        if (!isPaired || !authToken) {
            Alert.alert('Pairing Required', 'Please pair with your TV first.');
            return;
        }
        setIsOperatingBackup(true);
        try {
            const tvSettings = await TvBeamingService.pullSettingsFromTv(tvIp, authToken);
            const formatted = JSON.stringify(tvSettings, null, 2);
            setBackupDataText(formatted);
            await SensyIr.importSettings(formatted);
            setIsBackupDialogOpen(true);
            Alert.alert('Settings Pulled', 'Successfully synced settings from CleanTV!');
        } catch (e: any) {
            Alert.alert('Sync Error', e.message);
        } finally {
            setIsOperatingBackup(false);
        }
    };

    const handlePushToTv = async () => {
        if (!isPaired || !authToken) {
            Alert.alert('Pairing Required', 'Please pair with your TV first.');
            return;
        }
        setIsOperatingBackup(true);
        try {
            let dataToPush: any = {};
            if (backupDataText.trim()) {
                dataToPush = JSON.parse(backupDataText.trim());
            } else {
                const local = await SensyIr.exportSettings();
                dataToPush = JSON.parse(local);
            }
            const ok = await TvBeamingService.pushSettingsToTv(tvIp, dataToPush, authToken);
            if (ok) {
                Alert.alert('Pushed to TV', 'Settings successfully applied on your TV!');
            } else {
                Alert.alert('Push Failed', 'TV rejected the settings update.');
            }
        } catch (e: any) {
            Alert.alert('Push Error', e.message);
        } finally {
            setIsOperatingBackup(false);
        }
    };

    const sendKey = async (keyName: string) => {
        let hex = customRemote.keys[keyName] || keyMap[keyName];

        // Fallbacks for special keys
        if (!hex && keyName === 'RECALL') {
            for (const alias of ['PREV', 'LAST', 'LAST_CH', 'KEY_40', 'RETURN']) {
                if (customRemote.keys[alias] || keyMap[alias]) {
                    hex = customRemote.keys[alias] || keyMap[alias];
                    break;
                }
            }
            if (!hex) hex = '0x14eb8a75'; // Default candidate #2
        }

        if (!hex) {
            Logger.warn('Transmitter', `Key "${keyName}" not in remote profile`);
            return;
        }

        const activeRemote = candidateRemotes[selectedRemoteIndex];
        const bitLength = activeRemote?.bitLength || 32;

        try {
            await blastHexCode(hex, bitLength, keyName);
        } catch (err: any) {
            Logger.error('Transmitter', `Failed to send ${keyName}: ${err.message}`);
        }
    };

    const handleAssignKeyFromScanner = (keyName: string, hexCode: string) => {
        setCustomRemote(prev => ({
            ...prev,
            keys: {
                ...prev.keys,
                [keyName.toUpperCase()]: hexCode.toLowerCase(),
            },
        }));
    };

    const handleSyncCustomRemoteToTv = async () => {
        if (!isPaired || !authToken) {
            Alert.alert('Pairing Required', 'Pair with TV first to save custom remotes.');
            setShowTvConfig(true);
            return;
        }
        await TvBeamingService.saveCustomRemote(tvIp, customRemote, authToken);
        setLastLog('Custom remote synced to Mi TV!');
    };

    return (
        <SafeAreaView style={styles.container}>
            <StatusBar barStyle="light-content" />

            {/* Navbar / App Header */}
            <View style={styles.navbar}>
                <View style={styles.brandRow}>
                    <View style={styles.brandIconBadge}>
                        <Text style={styles.brandIconText}>IR</Text>
                    </View>
                    <View>
                        <Text style={styles.brandTitle}>CleanTV Remote</Text>
                        <Text style={styles.brandSubtitle}>Universal TV &amp; STB Remote</Text>
                    </View>
                </View>

                {/* Connection Status Badges */}
                <View style={{ flexDirection: 'row', gap: 6 }}>
                    <TouchableOpacity onPress={() => setShowTvConfig(!showTvConfig)}>
                        <Badge variant={isTvConnected ? (isPaired ? 'success' : 'warning') : 'outline'}>
                            <View
                                style={[
                                    styles.statusDot,
                                    { backgroundColor: isTvConnected ? (isPaired ? '#38BDF8' : '#F59E0B') : theme.colors.mutedForeground },
                                ]}
                            />
                            <Text style={styles.badgeText}>
                                {isTvConnected ? (isPaired ? 'CleanTV Paired' : 'TV (Unpaired)') : 'TV Offline'}
                            </Text>
                        </Badge>
                    </TouchableOpacity>

                    {status.connected && (
                        <Badge variant={status.hasPermission ? 'success' : 'warning'}>
                            <View
                                style={[
                                    styles.statusDot,
                                    { backgroundColor: status.hasPermission ? '#38BDF8' : '#F59E0B' },
                                ]}
                            />
                            <Text style={styles.badgeText}>USB</Text>
                        </Badge>
                    )}
                </View>
            </View>

            {/* TV IP & Pairing Settings Dropdown */}
            {showTvConfig && (
                <View style={styles.tvConfigBanner}>
                    <Text style={styles.tvConfigLabel}>TV Address (Port 8090):</Text>
                    <View style={styles.tvConfigRow}>
                        <Input
                            value={tvIp}
                            onChangeText={setTvIp}
                            placeholder="e.g. 100.97.35.51"
                            autoCapitalize="none"
                            autoCorrect={false}
                            style={styles.tvConfigInput}
                        />
                        <Button
                            variant="default"
                            size="sm"
                            style={styles.tvConfigBtn}
                            onPress={handleManualConnect}
                            disabled={isConnecting}>
                            <Text style={styles.btnBoldText}>
                                {isConnecting ? '...' : 'Connect'}
                            </Text>
                        </Button>
                    </View>

                    {/* TV Companion Pairing Control */}
                    <Separator style={{ marginVertical: 10 }} />
                    <Text style={styles.tvConfigLabel}>Companion Security &amp; Pairing:</Text>
                    {isPaired ? (
                        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 4 }}>
                            <Text style={{ color: '#38BDF8', fontSize: 13, fontWeight: 'bold' }}>
                                Paired &amp; Authorized
                            </Text>
                            <Button variant="destructive" size="sm" onPress={handleUnpair}>
                                <Text style={{ color: '#FFFFFF', fontWeight: 'bold', fontSize: 11 }}>Unpair TV</Text>
                            </Button>
                        </View>
                    ) : (
                        <View style={{ marginTop: 6, gap: 6 }}>
                            <Text style={{ color: theme.colors.mutedForeground, fontSize: 11 }}>
                                Enter 6-digit PIN from TV Settings &gt; Mobile Companion:
                            </Text>
                            <View style={{ flexDirection: 'row', gap: 6 }}>
                                <Input
                                    value={pairingPin}
                                    onChangeText={setPairingPin}
                                    placeholder="6-digit PIN"
                                    keyboardType="number-pad"
                                    maxLength={6}
                                    style={{ flex: 1, letterSpacing: 4, fontFamily: 'monospace', fontWeight: 'bold' }}
                                />
                                <Button variant="default" size="sm" onPress={handlePair} disabled={isPairing}>
                                    <Text style={{ color: '#FFFFFF', fontWeight: 'bold', fontSize: 12 }}>
                                        {isPairing ? 'Pairing...' : 'Pair'}
                                    </Text>
                                </Button>
                            </View>
                        </View>
                    )}

                    {/* Database Sync Control */}
                    <Separator style={{ marginVertical: 10 }} />
                    <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                        <Text style={styles.tvConfigLabel}>Universal LCN DB: v{dbVersion}</Text>
                        <Button variant="outline" size="sm" onPress={handleSyncDb} disabled={isSyncingDb}>
                            <Text style={{ color: '#38BDF8', fontWeight: 'bold', fontSize: 11 }}>
                                {isSyncingDb ? 'Syncing...' : 'Sync from CDN'}
                            </Text>
                        </Button>
                    </View>

                    {/* Settings Backup & Sync */}
                    <Separator style={{ marginVertical: 10 }} />
                    <Text style={styles.tvConfigLabel}>Settings Backup &amp; Sync:</Text>
                    <View style={{ flexDirection: 'row', gap: 6, marginTop: 4 }}>
                        <Button variant="outline" size="sm" style={{ flex: 1 }} onPress={handleOpenExport}>
                            <Text style={{ color: '#F1F5F9', fontSize: 11, fontWeight: 'bold' }}>Export / Edit</Text>
                        </Button>
                        <Button variant="outline" size="sm" style={{ flex: 1 }} onPress={handlePullFromTv} disabled={isOperatingBackup || !isPaired}>
                            <Text style={{ color: '#38BDF8', fontSize: 11, fontWeight: 'bold' }}>
                                {isOperatingBackup ? '...' : 'Pull from TV'}
                            </Text>
                        </Button>
                        <Button variant="outline" size="sm" style={{ flex: 1 }} onPress={handlePushToTv} disabled={isOperatingBackup || !isPaired}>
                            <Text style={{ color: '#10B981', fontSize: 11, fontWeight: 'bold' }}>Push to TV</Text>
                        </Button>
                    </View>
                </View>
            )}

            {/* Connection Gate: When Disconnected */}
            {!isTvConnected ? (
                <ScrollView contentContainerStyle={styles.scrollContent}>
                    <Card style={{ borderColor: theme.colors.border, backgroundColor: theme.colors.card }}>
                        <CardHeader>
                            <CardTitle>Connect to CleanTV</CardTitle>
                            <CardDescription>
                                Enter your Mi TV IP address to control live TV playback, channels, and IR blaster.
                            </CardDescription>
                        </CardHeader>
                        <CardContent style={{ gap: 12 }}>
                            <View>
                                <Text style={{ fontSize: 12, color: theme.colors.mutedForeground, marginBottom: 6 }}>
                                    TV IP Address (Port 8090):
                                </Text>
                                <Input
                                    value={tvIp}
                                    onChangeText={setTvIp}
                                    placeholder="e.g. 100.97.35.51"
                                    autoCapitalize="none"
                                    autoCorrect={false}
                                    style={{ fontFamily: 'monospace' }}
                                />
                            </View>
                            <Button
                                variant="default"
                                size="default"
                                onPress={handleManualConnect}
                                disabled={isConnecting}>
                                {isConnecting ? (
                                    <ActivityIndicator size="small" color="#FFFFFF" />
                                ) : (
                                    <Text style={{ color: '#FFFFFF', fontWeight: 'bold' }}>Connect to CleanTV</Text>
                                )}
                            </Button>
                        </CardContent>
                    </Card>

                    {/* Communication Log */}
                    <Card style={styles.consoleCard}>
                        <View style={styles.consoleHeader}>
                            <Text style={styles.consoleTitle}>COMMUNICATION LOG</Text>
                        </View>
                        {logEntries.length === 0 ? (
                            <Text style={styles.consoleText}>$ Ready. Enter TV IP and press Connect.</Text>
                        ) : (
                            logEntries.slice(0, 5).map((entry, idx) => {
                                const timeStr = new Date(entry.timestamp).toTimeString().split(' ')[0];
                                const color =
                                    entry.level === 'TX'
                                        ? '#38BDF8'
                                        : entry.level === 'ERROR'
                                        ? '#EF4444'
                                        : entry.level === 'WARN'
                                        ? '#F59E0B'
                                        : '#94A3B8';
                                return (
                                    <View key={idx} style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginVertical: 1 }}>
                                        <Text style={{ color: '#64748B', fontFamily: 'monospace', fontSize: 10 }}>{timeStr}</Text>
                                        <Text style={{ color, fontFamily: 'monospace', fontSize: 11, fontWeight: 'bold' }}>[{entry.level}]</Text>
                                        <Text style={{ color: '#E2E8F0', fontFamily: 'monospace', fontSize: 11, flex: 1 }} numberOfLines={1}>
                                            {entry.message}
                                        </Text>
                                    </View>
                                );
                            })
                        )}
                    </Card>
                </ScrollView>
            ) : !isPaired ? (
                /* Connection Gate: When Connected but Unpaired */
                <ScrollView contentContainerStyle={styles.scrollContent}>
                    <Card style={{ borderColor: '#0284C7', backgroundColor: '#151D2A' }}>
                        <CardHeader>
                            <CardTitle style={{ color: '#38BDF8' }}>Pair Companion with CleanTV</CardTitle>
                            <CardDescription>
                                CleanTV detected at {tvIp}! Enter the 6-digit PIN shown on your TV screen (TV Settings &gt; Mobile Companion) to securely pair.
                            </CardDescription>
                        </CardHeader>
                        <CardContent style={{ gap: 12 }}>
                            <Input
                                value={pairingPin}
                                onChangeText={setPairingPin}
                                placeholder="6-digit PIN"
                                keyboardType="number-pad"
                                maxLength={6}
                                style={{ letterSpacing: 6, fontFamily: 'monospace', fontWeight: 'bold', fontSize: 20, textAlign: 'center' }}
                            />
                            <Button variant="default" size="default" onPress={handlePair} disabled={isPairing}>
                                <Text style={{ color: '#FFFFFF', fontWeight: 'bold' }}>
                                    {isPairing ? 'Pairing...' : 'Pair Device'}
                                </Text>
                            </Button>
                            <Button variant="outline" size="sm" onPress={() => TvConnectionManager.disconnect()}>
                                <Text style={{ color: theme.colors.mutedForeground }}>Disconnect / Change IP</Text>
                            </Button>
                        </CardContent>
                    </Card>

                    {/* Communication Log */}
                    <Card style={styles.consoleCard}>
                        <View style={styles.consoleHeader}>
                            <Text style={styles.consoleTitle}>COMMUNICATION LOG</Text>
                        </View>
                        {logEntries.length === 0 ? (
                            <Text style={styles.consoleText}>$ CleanTV connected. Awaiting PIN pairing.</Text>
                        ) : (
                            logEntries.slice(0, 5).map((entry, idx) => {
                                const timeStr = new Date(entry.timestamp).toTimeString().split(' ')[0];
                                const color =
                                    entry.level === 'TX'
                                        ? '#38BDF8'
                                        : entry.level === 'ERROR'
                                        ? '#EF4444'
                                        : entry.level === 'WARN'
                                        ? '#F59E0B'
                                        : '#94A3B8';
                                return (
                                    <View key={idx} style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginVertical: 1 }}>
                                        <Text style={{ color: '#64748B', fontFamily: 'monospace', fontSize: 10 }}>{timeStr}</Text>
                                        <Text style={{ color, fontFamily: 'monospace', fontSize: 11, fontWeight: 'bold' }}>[{entry.level}]</Text>
                                        <Text style={{ color: '#E2E8F0', fontFamily: 'monospace', fontSize: 11, flex: 1 }} numberOfLines={1}>
                                            {entry.message}
                                        </Text>
                                    </View>
                                );
                            })
                        )}
                    </Card>
                </ScrollView>
            ) : (
                /* Connected & Paired View */
                <>
                    {/* Navigation Tabs Switcher */}
                    <View style={styles.tabNavRow}>
                        <TouchableOpacity
                            style={[styles.navTabBtn, activeTab === 'remote' && styles.navTabBtnActive]}
                            onPress={() => setActiveTab('remote')}>
                            <Text style={[styles.navTabText, activeTab === 'remote' && styles.navTabTextActive]}>
                                Remote
                            </Text>
                        </TouchableOpacity>

                        <TouchableOpacity
                            style={[styles.navTabBtn, activeTab === 'scanner' && styles.navTabBtnActive]}
                            onPress={() => setActiveTab('scanner')}>
                            <Text style={[styles.navTabText, activeTab === 'scanner' && styles.navTabTextActive]}>
                                Scanner
                            </Text>
                        </TouchableOpacity>

                        <TouchableOpacity
                            style={[styles.navTabBtn, activeTab === 'custom' && styles.navTabBtnActive]}
                            onPress={() => setActiveTab('custom')}>
                            <Text style={[styles.navTabText, activeTab === 'custom' && styles.navTabTextActive]}>
                                Studio
                            </Text>
                        </TouchableOpacity>
                    </View>

                    {/* Tab 1: Tactile Remote View */}
                    {activeTab === 'remote' && (
                        <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
                            {/* Active Provider Card */}
                            <Card>
                                <CardHeader style={styles.providerCardHeader}>
                                    <View style={{ flex: 1 }}>
                                        <CardTitle>
                                            {selectedProvider ? selectedProvider.title : 'Select Provider'}
                                        </CardTitle>
                                        <CardDescription>
                                            {selectedProvider
                                                ? `${selectedProvider.type} • ID ${selectedProvider.id} • ${candidateRemotes.length} remotes`
                                                : 'Choose from 3,755 providers in database'}
                                        </CardDescription>
                                    </View>
                                    <Button
                                        variant="secondary"
                                        size="sm"
                                        onPress={() => setIsSearchOpen(true)}>
                                        Search
                                    </Button>
                                </CardHeader>

                                {/* Candidate Remote Tabs */}
                                {candidateRemotes.length > 0 && (
                                    <CardContent>
                                        <Separator style={{ marginBottom: 10 }} />
                                        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
                                            <TabsList>
                                                {candidateRemotes.map((r, idx) => (
                                                    <TabsTrigger
                                                        key={r.id}
                                                        value={idx.toString()}
                                                        activeValue={selectedRemoteIndex.toString()}
                                                        onPress={() => handleSelectRemoteIndex(idx)}>
                                                        Model #{r.id}
                                                    </TabsTrigger>
                                                ))}
                                            </TabsList>
                                        </ScrollView>
                                    </CardContent>
                                )}
                            </Card>

                            {/* Tactile Remote Card */}
                            <Card style={styles.remoteCard}>
                                {/* Power & Mute Bar */}
                                <View style={styles.powerRow}>
                                    <Button
                                        variant="destructive"
                                        size="icon"
                                        style={styles.powerButton}
                                        onPress={() => sendKey('POWER')}>
                                        <View style={styles.powerSymbolWrapper}>
                                            <View style={styles.powerSymbolCircle} />
                                            <View style={styles.powerSymbolLine} />
                                        </View>
                                    </Button>

                                    <View style={{ flex: 1 }} />

                                    <Button
                                        variant="secondary"
                                        size="icon"
                                        style={styles.muteButton}
                                        onPress={() => sendKey('VOL MUTE')}>
                                        <Text style={styles.muteIcon}>⊘</Text>
                                    </Button>
                                </View>

                                {/* Volume & Channel Rockers */}
                                <View style={styles.rockersRow}>
                                    {/* Volume Rocker */}
                                    <View style={styles.rockerCard}>
                                        <TouchableOpacity
                                            style={styles.rockerSideBtn}
                                            activeOpacity={0.6}
                                            onPress={() => sendKey('VOL UP')}>
                                            <Text style={styles.rockerSymbol}>+</Text>
                                        </TouchableOpacity>
                                        <Text style={styles.rockerTag}>VOL</Text>
                                        <TouchableOpacity
                                            style={styles.rockerSideBtn}
                                            activeOpacity={0.6}
                                            onPress={() => sendKey('VOL DOWN')}>
                                            <Text style={styles.rockerSymbol}>-</Text>
                                        </TouchableOpacity>
                                    </View>

                                    {/* Channel Rocker */}
                                    <View style={styles.rockerCard}>
                                        <TouchableOpacity
                                            style={styles.rockerSideBtn}
                                            activeOpacity={0.6}
                                            onPress={() => sendKey('CH UP')}>
                                            <Text style={styles.rockerSymbol}>+</Text>
                                        </TouchableOpacity>
                                        <Text style={styles.rockerTag}>CH</Text>
                                        <TouchableOpacity
                                            style={styles.rockerSideBtn}
                                            activeOpacity={0.6}
                                            onPress={() => sendKey('CH DOWN')}>
                                            <Text style={styles.rockerSymbol}>-</Text>
                                        </TouchableOpacity>
                                    </View>
                                </View>

                                {/* Tactile D-Pad Navigator */}
                                <View style={styles.dpadCircle}>
                                    <TouchableOpacity
                                        style={[styles.dpadAction, styles.dpadUp]}
                                        activeOpacity={0.6}
                                        onPress={() => sendKey('UP')}>
                                        <Text style={styles.dpadArrow}>▲</Text>
                                    </TouchableOpacity>

                                    <View style={styles.dpadMiddle}>
                                        <TouchableOpacity
                                            style={[styles.dpadAction, styles.dpadLeft]}
                                            activeOpacity={0.6}
                                            onPress={() => sendKey('LEFT')}>
                                            <Text style={styles.dpadArrow}>◀</Text>
                                        </TouchableOpacity>

                                        <TouchableOpacity
                                            style={styles.dpadOk}
                                            activeOpacity={0.7}
                                            onPress={() => sendKey('OK')}>
                                            <Text style={styles.dpadOkText}>OK</Text>
                                        </TouchableOpacity>

                                        <TouchableOpacity
                                            style={[styles.dpadAction, styles.dpadRight]}
                                            activeOpacity={0.6}
                                            onPress={() => sendKey('RIGHT')}>
                                            <Text style={styles.dpadArrow}>▶</Text>
                                        </TouchableOpacity>
                                    </View>

                                    <TouchableOpacity
                                        style={[styles.dpadAction, styles.dpadDown]}
                                        activeOpacity={0.6}
                                        onPress={() => sendKey('DOWN')}>
                                        <Text style={styles.dpadArrow}>▼</Text>
                                    </TouchableOpacity>
                                </View>

                                {/* Standard Action Keys */}
                                <View style={styles.actionRow}>
                                    <Button
                                        variant="secondary"
                                        size="sm"
                                        style={styles.actionButton}
                                        onPress={() => sendKey('MENU')}>
                                        MENU
                                    </Button>
                                    <Button
                                        variant="secondary"
                                        size="sm"
                                        style={styles.actionButton}
                                        onPress={() => sendKey('BACK')}>
                                        BACK
                                    </Button>
                                    <Button
                                        variant="secondary"
                                        size="sm"
                                        style={styles.actionButton}
                                        onPress={() => sendKey('EXIT')}>
                                        EXIT
                                    </Button>
                                </View>

                                {/* Extra STB Features: RECALL / AUDIO / SUBTITLE */}
                                <View style={[styles.actionRow, { marginTop: 8 }]}>
                                    <Button
                                        variant="default"
                                        size="sm"
                                        style={[styles.actionButton, { backgroundColor: '#0284C7' }]}
                                        onPress={() => sendKey('RECALL')}>
                                        RECALL
                                    </Button>
                                    <Button
                                        variant="secondary"
                                        size="sm"
                                        style={styles.actionButton}
                                        onPress={() => sendKey('AUDIO')}>
                                        AUDIO
                                    </Button>
                                    <Button
                                        variant="secondary"
                                        size="sm"
                                        style={styles.actionButton}
                                        onPress={() => sendKey('SUBTITLE')}>
                                        SUBTITLE
                                    </Button>
                                </View>

                                {/* Number Pad Toggle */}
                                <Separator style={{ marginVertical: 14 }} />
                                <Button
                                    variant="ghost"
                                    size="sm"
                                    onPress={() => setShowKeypad(!showKeypad)}>
                                    {showKeypad ? 'Hide Numeric Keypad' : 'Show Numeric Keypad'}
                                </Button>

                                {/* Number Pad 3x4 Grid */}
                                {showKeypad && (
                                    <View style={styles.keypadGrid}>
                                        {['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'].map(num => (
                                            <Button
                                                key={num}
                                                variant="outline"
                                                size="default"
                                                style={styles.keypadBtn}
                                                onPress={() => sendKey(num)}>
                                                <Text style={styles.keypadBtnText}>{num}</Text>
                                            </Button>
                                        ))}
                                    </View>
                                )}
                            </Card>

                            {/* Console Log Footer */}
                            <Card style={styles.consoleCard}>
                                <View style={styles.consoleHeader}>
                                    <Text style={styles.consoleTitle}>IR TRANSMISSION LOG</Text>
                                    {isTransmitting && <ActivityIndicator size="small" color={theme.colors.primary} />}
                                </View>
                                {logEntries.length === 0 ? (
                                    <Text style={styles.consoleText}>$ Ready. Press any remote key.</Text>
                                ) : (
                                    logEntries.slice(0, 5).map((entry, idx) => {
                                        const timeStr = new Date(entry.timestamp).toTimeString().split(' ')[0];
                                        const color =
                                            entry.level === 'TX'
                                                ? '#38BDF8'
                                                : entry.level === 'ERROR'
                                                ? '#EF4444'
                                                : entry.level === 'WARN'
                                                ? '#F59E0B'
                                                : '#94A3B8';
                                        return (
                                            <View key={idx} style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginVertical: 1 }}>
                                                <Text style={{ color: '#64748B', fontFamily: 'monospace', fontSize: 10 }}>{timeStr}</Text>
                                                <Text style={{ color, fontFamily: 'monospace', fontSize: 11, fontWeight: 'bold' }}>[{entry.level}]</Text>
                                                <Text style={{ color: '#E2E8F0', fontFamily: 'monospace', fontSize: 11, flex: 1 }} numberOfLines={1}>
                                                    {entry.message}
                                                </Text>
                                            </View>
                                        );
                                    })
                                )}
                            </Card>
                        </ScrollView>
                    )}

                    {/* Tab 2: Key Discovery & Scanner View */}
                    {activeTab === 'scanner' && (
                        <View style={{ flex: 1, paddingHorizontal: 16, paddingTop: 10 }}>
                            <KeyScannerView
                                tvIp={tvIp}
                                isTvConnected={isTvConnected}
                                onAssignKey={handleAssignKeyFromScanner}
                                onBlastDirect={(hex) => blastHexCode(hex)}
                            />
                        </View>
                    )}

                    {/* Tab 3: Custom Remote Studio */}
                    {activeTab === 'custom' && (
                        <View style={{ flex: 1, paddingHorizontal: 16, paddingTop: 10 }}>
                            <CustomRemoteView
                                tvIp={tvIp}
                                isTvConnected={isTvConnected}
                                customRemote={customRemote}
                                onUpdateCustomRemote={setCustomRemote}
                                onBlastDirect={(hex) => blastHexCode(hex)}
                                onSyncToTv={handleSyncCustomRemoteToTv}
                            />
                        </View>
                    )}
                </>
            )}

            {/* Provider Search Dialog */}
            <Dialog open={isSearchOpen} onOpenChange={setIsSearchOpen}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>Search Universal Database</DialogTitle>
                        <DialogClose onPress={() => setIsSearchOpen(false)} />
                    </DialogHeader>

                    {/* Search Input Field */}
                    <Input
                        placeholder="Search provider (e.g. Tata, Airtel, Sun...)"
                        value={searchQuery}
                        onChangeText={text => handleSearch(text)}
                        autoFocus={true}
                        style={{ marginBottom: 12 }}
                    />

                    {/* Results FlatList */}
                    <FlatList
                        data={searchResults}
                        keyExtractor={item => item.id.toString()}
                        contentContainerStyle={{ gap: 8, paddingBottom: 16 }}
                        renderItem={({ item }) => (
                            <TouchableOpacity
                                style={styles.dialogItem}
                                activeOpacity={0.7}
                                onPress={() => selectProvider(item)}>
                                <View style={{ flex: 1 }}>
                                    <Text style={styles.dialogItemTitle}>{item.title}</Text>
                                    <Text style={styles.dialogItemSub}>
                                        {item.type} {item.brandTitle ? `• ${item.brandTitle}` : ''}
                                    </Text>
                                </View>
                                <Badge variant="secondary">Select</Badge>
                            </TouchableOpacity>
                        )}
                        ListEmptyComponent={
                            !isSearching ? (
                                <Text style={styles.emptyText}>
                                    Type to search 3,755 DTH/Cable/TV providers.
                                </Text>
                            ) : (
                                <ActivityIndicator size="small" color={theme.colors.primary} style={{ marginTop: 20 }} />
                            )
                        }
                    />
                </DialogContent>
            </Dialog>

            {/* Backup & Restore Settings Dialog */}
            <Dialog open={isBackupDialogOpen} onOpenChange={setIsBackupDialogOpen}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>Backup &amp; Restore Settings</DialogTitle>
                        <DialogClose onPress={() => setIsBackupDialogOpen(false)} />
                    </DialogHeader>

                    <Text style={{ color: theme.colors.mutedForeground, fontSize: 12, marginBottom: 8 }}>
                        Unified CleanTV JSON format. You can copy, edit, or paste settings from the TV or another companion device:
                    </Text>

                    <TextInput
                        value={backupDataText}
                        onChangeText={setBackupDataText}
                        multiline={true}
                        numberOfLines={10}
                        style={{
                            height: 200,
                            backgroundColor: '#0B0F19',
                            color: '#38BDF8',
                            fontFamily: 'monospace',
                            fontSize: 11,
                            padding: 10,
                            borderWidth: 1,
                            borderColor: theme.colors.border,
                            borderRadius: 0,
                            textAlignVertical: 'top',
                            marginBottom: 12,
                        }}
                    />

                    <View style={{ flexDirection: 'row', gap: 8 }}>
                        <Button variant="default" style={{ flex: 1 }} onPress={handleApplyImport}>
                            <Text style={{ color: '#FFFFFF', fontWeight: 'bold', fontSize: 12 }}>Import JSON</Text>
                        </Button>
                        <Button variant="outline" style={{ flex: 1 }} onPress={() => setIsBackupDialogOpen(false)}>
                            <Text style={{ color: theme.colors.foreground, fontSize: 12 }}>Close</Text>
                        </Button>
                    </View>
                </DialogContent>
            </Dialog>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: theme.colors.background,
    },
    navbar: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 16,
        paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight ?? 24) + 26 : 16,
        paddingBottom: 12,
        borderBottomWidth: 1,
        borderBottomColor: theme.colors.border,
        backgroundColor: theme.colors.background,
    },
    brandRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
    },
    brandIconBadge: {
        width: 34,
        height: 34,
        borderRadius: theme.radii.md,
        backgroundColor: theme.colors.card,
        borderColor: theme.colors.border,
        borderWidth: 1,
        justifyContent: 'center',
        alignItems: 'center',
    },
    brandIconText: {
        color: theme.colors.primary,
        fontWeight: '900',
        fontSize: 13,
    },
    brandTitle: {
        color: theme.colors.foreground,
        fontSize: 16,
        fontWeight: 'bold',
    },
    brandSubtitle: {
        color: theme.colors.mutedForeground,
        fontSize: 11,
    },
    badgeText: {
        fontSize: 12,
        fontWeight: '600',
        color: theme.colors.foreground,
    },
    statusDot: {
        width: 7,
        height: 7,
        borderRadius: 0,
        marginRight: 4,
    },
    tvConfigBanner: {
        paddingHorizontal: 16,
        paddingVertical: 10,
        backgroundColor: '#18181B',
        borderBottomWidth: 1,
        borderBottomColor: theme.colors.border,
    },
    tvConfigLabel: {
        fontSize: 11,
        color: theme.colors.mutedForeground,
        marginBottom: 6,
    },
    tvConfigRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
    },
    tvConfigInput: {
        flex: 1,
        height: 38,
        fontFamily: 'monospace',
    },
    tvConfigBtn: {
        height: 38,
        paddingHorizontal: 16,
    },
    btnBoldText: {
        fontSize: 12,
        fontWeight: 'bold',
        color: theme.colors.primaryForeground,
    },
    tabNavRow: {
        flexDirection: 'row',
        paddingHorizontal: 16,
        paddingVertical: 8,
        gap: 6,
        borderBottomWidth: 1,
        borderBottomColor: theme.colors.border,
        backgroundColor: theme.colors.background,
    },
    navTabBtn: {
        flex: 1,
        paddingVertical: 8,
        borderRadius: 0,
        alignItems: 'center',
        backgroundColor: theme.colors.muted,
        borderWidth: 1,
        borderColor: 'transparent',
    },
    navTabBtnActive: {
        backgroundColor: '#27272A',
        borderColor: '#38BDF8',
    },
    navTabText: {
        fontSize: 12,
        fontWeight: '600',
        color: theme.colors.mutedForeground,
    },
    navTabTextActive: {
        color: '#FFFFFF',
        fontWeight: 'bold',
    },
    scrollContent: {
        padding: 16,
        gap: 16,
    },
    providerCardHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    remoteCard: {
        padding: 16,
        alignItems: 'center',
    },
    powerRow: {
        flexDirection: 'row',
        width: '100%',
        alignItems: 'center',
        marginBottom: 20,
    },
    powerButton: {
        width: 52,
        height: 52,
        borderRadius: 0,
        backgroundColor: '#ef4444',
        justifyContent: 'center',
        alignItems: 'center',
    },
    powerSymbolWrapper: {
        width: 26,
        height: 26,
        position: 'relative',
        justifyContent: 'center',
        alignItems: 'center',
    },
    powerSymbolCircle: {
        position: 'absolute',
        width: 20,
        height: 20,
        borderRadius: 10,
        borderWidth: 2.8,
        borderColor: '#ffffff',
        borderTopColor: 'transparent',
    },
    powerSymbolLine: {
        position: 'absolute',
        top: 0,
        width: 2.8,
        height: 11,
        backgroundColor: '#ffffff',
        borderRadius: 0,
    },
    muteButton: {
        width: 48,
        height: 48,
        borderRadius: 0,
        justifyContent: 'center',
        alignItems: 'center',
    },
    muteIcon: {
        fontSize: 20,
        color: '#FFFFFF',
        fontWeight: 'bold',
    },
    rockersRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        width: '100%',
        paddingHorizontal: 20,
        marginBottom: 24,
    },
    rockerCard: {
        width: 76,
        height: 140,
        borderRadius: 0,
        backgroundColor: theme.colors.muted,
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingVertical: 6,
        borderWidth: 1,
        borderColor: theme.colors.border,
    },
    rockerSideBtn: {
        width: '100%',
        height: 50,
        justifyContent: 'center',
        alignItems: 'center',
    },
    rockerSymbol: {
        fontSize: 26,
        color: theme.colors.foreground,
        fontWeight: 'bold',
    },
    rockerTag: {
        fontSize: 12,
        fontWeight: '800',
        color: theme.colors.mutedForeground,
        letterSpacing: 1,
    },
    dpadCircle: {
        width: 210,
        height: 210,
        borderRadius: 0,
        backgroundColor: theme.colors.muted,
        borderWidth: 1,
        borderColor: theme.colors.border,
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingVertical: 12,
        marginBottom: 24,
    },
    dpadMiddle: {
        flexDirection: 'row',
        width: '100%',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingHorizontal: 12,
    },
    dpadAction: {
        width: 50,
        height: 50,
        justifyContent: 'center',
        alignItems: 'center',
    },
    dpadUp: {},
    dpadDown: {},
    dpadLeft: {},
    dpadRight: {},
    dpadArrow: {
        fontSize: 20,
        color: theme.colors.foreground,
    },
    dpadOk: {
        width: 76,
        height: 76,
        borderRadius: 0,
        backgroundColor: theme.colors.card,
        borderWidth: 1.5,
        borderColor: theme.colors.primary,
        justifyContent: 'center',
        alignItems: 'center',
    },
    dpadOkText: {
        fontSize: 18,
        fontWeight: '900',
        color: theme.colors.primary,
    },
    actionRow: {
        flexDirection: 'row',
        gap: 12,
        width: '100%',
    },
    actionButton: {
        flex: 1,
        height: 42,
    },
    keypadGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
        justifyContent: 'center',
        width: '100%',
        marginTop: 10,
    },
    keypadBtn: {
        width: '30%',
        height: 46,
    },
    keypadBtnText: {
        fontSize: 18,
        fontWeight: 'bold',
        color: theme.colors.foreground,
    },
    consoleCard: {
        backgroundColor: '#09090b',
        borderWidth: 1,
        borderColor: '#27272a',
        padding: 12,
    },
    consoleHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 6,
    },
    consoleTitle: {
        fontSize: 10,
        fontWeight: 'bold',
        color: theme.colors.mutedForeground,
        letterSpacing: 1,
    },
    consoleText: {
        fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
        fontSize: 11,
        color: '#34d399',
    },
    dialogItem: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 12,
        borderRadius: theme.radii.md,
        backgroundColor: theme.colors.muted,
        borderWidth: 1,
        borderColor: theme.colors.border,
    },
    dialogItemTitle: {
        fontSize: 14,
        fontWeight: '600',
        color: theme.colors.foreground,
    },
    dialogItemSub: {
        fontSize: 11,
        color: theme.colors.mutedForeground,
        marginTop: 2,
    },
    emptyText: {
        color: theme.colors.mutedForeground,
        textAlign: 'center',
        paddingVertical: 20,
    },
});
