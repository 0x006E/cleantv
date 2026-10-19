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
    FlatList
} from 'react-native';
import { SensyIr, DeviceStatus, ProviderItem, RemoteCandidate } from './src/SensyIrNative';

export default function App() {
    const [status, setStatus] = useState<DeviceStatus>({
        connected: false,
        hasPermission: false,
        message: 'Checking USB status...'
    });

    // Provider search state
    const [searchQuery, setSearchQuery] = useState<string>('');
    const [searchResults, setSearchResults] = useState<ProviderItem[]>([]);
    const [selectedProvider, setSelectedProvider] = useState<ProviderItem | null>(null);
    const [isSearching, setIsSearching] = useState<boolean>(false);

    // Candidate remotes and active keymap
    const [candidateRemotes, setCandidateRemotes] = useState<RemoteCandidate[]>([]);
    const [selectedRemoteIndex, setSelectedRemoteIndex] = useState<number>(0);
    const [keyMap, setKeyMap] = useState<Record<string, string>>({});
    const [isLoadingKeys, setIsLoadingKeys] = useState<boolean>(false);

    const [isTransmitting, setIsTransmitting] = useState<boolean>(false);
    const [lastLog, setLastLog] = useState<string>('Ready. Plug IR dongle via USB-OTG.');

    // Check USB state periodically
    const checkUsb = async () => {
        try {
            const st = await SensyIr.getStatus();
            setStatus(st);
        } catch (e: any) {
            setLastLog(`USB Error: ${e.message}`);
        }
    };

    useEffect(() => {
        checkUsb();
        const timer = setInterval(checkUsb, 3000);
        return () => clearInterval(timer);
    }, []);

    // Initial search for default provider list on launch
    useEffect(() => {
        handleSearch('Kerala Vision');
    }, []);

    const handleSearch = async (text: string) => {
        setSearchQuery(text);
        if (!text || text.trim().length === 0) {
            setSearchResults([]);
            return;
        }

        setIsSearching(true);
        try {
            const results = await SensyIr.searchProviders(text.trim(), 15);
            setSearchResults(results);
            // If selecting first time
            if (!selectedProvider && results.length > 0) {
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
        setSearchQuery('');
        setSearchResults([]);
        setLastLog(`Selected provider: ${provider.title}`);

        try {
            const remotes = await SensyIr.getRemotesForProvider(provider.id);
            setCandidateRemotes(remotes);
            setSelectedRemoteIndex(0);
            if (remotes.length > 0) {
                await loadRemoteKeys(remotes[0].id);
            } else {
                setKeyMap({});
                setLastLog(`No candidate remotes found for ${provider.title}`);
            }
        } catch (e: any) {
            setLastLog(`Error loading remotes: ${e.message}`);
        }
    };

    const loadRemoteKeys = async (remoteId: number) => {
        setIsLoadingKeys(true);
        try {
            const keys = await SensyIr.getKeyCodes(remoteId);
            setKeyMap(keys);
            setLastLog(`Loaded Remote ID ${remoteId} (${Object.keys(keys).length} keys ready)`);
        } catch (e: any) {
            setLastLog(`Error loading keys: ${e.message}`);
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

    const sendKey = async (keyName: string) => {
        const hex = keyMap[keyName];
        if (!hex) {
            setLastLog(`Key "${keyName}" not defined in this remote`);
            return;
        }

        const activeRemote = candidateRemotes[selectedRemoteIndex];
        const bitLength = activeRemote?.bitLength || 32;

        setIsTransmitting(true);
        setLastLog(`Transmitting ${keyName} (${hex})...`);
        try {
            await SensyIr.transmitNec(hex, bitLength);
            setLastLog(`Sent ${keyName} [${hex}] (Remote ID ${activeRemote?.id})`);
        } catch (err: any) {
            setLastLog(`TX Error: ${err.message}`);
        } finally {
            setIsTransmitting(false);
        }
    };

    const activeRemote = candidateRemotes[selectedRemoteIndex];

    return (
        <SafeAreaView style={styles.container}>
            <StatusBar barStyle="light-content" backgroundColor="#121820" />
            <ScrollView contentContainerStyle={styles.scroll}>
                {/* Header */}
                <View style={styles.header}>
                    <Text style={styles.title}>Universal STB / TV Remote</Text>
                    <Text style={styles.subtitle}>Xiaomi / Sensy USB Blaster (SQLite lcndb.db)</Text>
                </View>

                {/* USB Status Badge */}
                <View style={[styles.statusBar, status.connected && status.hasPermission ? styles.statusOk : styles.statusWarn]}>
                    <View style={styles.statusIndicatorRow}>
                        <View style={[styles.statusDot, status.connected && status.hasPermission ? styles.dotGreen : styles.dotOrange]} />
                        <Text style={styles.statusText}>
                            {status.connected
                                ? (status.hasPermission ? 'USB IR Blaster Connected' : 'USB Attached - Grant Permission')
                                : 'Blaster Disconnected (Plug OTG Cable)'}
                        </Text>
                    </View>
                    {status.connected && !status.hasPermission && (
                        <TouchableOpacity style={styles.permButton} onPress={handleRequestPermission}>
                            <Text style={styles.permButtonText}>Grant USB Permission</Text>
                        </TouchableOpacity>
                    )}
                </View>

                {/* SQLite Provider Search Input */}
                <View style={styles.searchContainer}>
                    <Text style={styles.sectionTitle}>Search Provider (DTH / Cable / TV):</Text>
                    <View style={styles.searchBarRow}>
                        <TextInput
                            style={styles.searchInput}
                            placeholder="e.g. Tata Play, Airtel, Kerala Vision, Sun..."
                            placeholderTextColor="#6c7a89"
                            value={searchQuery}
                            onChangeText={handleSearch}
                        />
                        {isSearching && <ActivityIndicator size="small" color="#00bcd4" style={{ marginLeft: 8 }} />}
                    </View>

                    {/* Search Results Dropdown */}
                    {searchResults.length > 0 && (
                        <View style={styles.dropdown}>
                            {searchResults.map(item => (
                                <TouchableOpacity key={item.id} style={styles.dropdownItem} onPress={() => selectProvider(item)}>
                                    <Text style={styles.dropdownItemText}>{item.title}</Text>
                                    <Text style={styles.dropdownItemSub}>
                                        {item.type} {item.brandTitle ? `• ${item.brandTitle}` : ''}
                                    </Text>
                                </TouchableOpacity>
                            ))}
                        </View>
                    )}
                </View>

                {/* Selected Provider Info */}
                {selectedProvider && (
                    <View style={styles.providerCard}>
                        <Text style={styles.providerCardTitle}>{selectedProvider.title}</Text>
                        <Text style={styles.providerCardSub}>
                            Type: {selectedProvider.type} | Group ID: {selectedProvider.groupId}
                        </Text>
                    </View>
                )}

                {/* Candidate Remotes Tabs */}
                {candidateRemotes.length > 0 && (
                    <View style={styles.modelSection}>
                        <Text style={styles.sectionTitle}>
                            Candidate Remotes ({candidateRemotes.length} models found):
                        </Text>
                        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.modelScroll}>
                            {candidateRemotes.map((r, idx) => (
                                <TouchableOpacity
                                    key={r.id}
                                    style={[styles.modelTab, selectedRemoteIndex === idx && styles.modelTabActive]}
                                    onPress={() => handleSelectRemoteIndex(idx)}>
                                    <Text style={[styles.modelTabText, selectedRemoteIndex === idx && styles.modelTabTextActive]}>
                                        Model {r.id} {r.powerCode ? `(${r.powerCode.slice(0, 6)}..)` : ''}
                                    </Text>
                                </TouchableOpacity>
                            ))}
                        </ScrollView>
                    </View>
                )}

                {/* Remote Keypad */}
                <View style={styles.remoteBody}>
                    {/* Top Row: Power & Mute */}
                    <View style={styles.row}>
                        <TouchableOpacity style={[styles.btn, styles.btnPower]} onPress={() => sendKey('POWER')}>
                            <Text style={styles.btnPowerText}>⏻ POWER</Text>
                        </TouchableOpacity>
                        <TouchableOpacity style={[styles.btn, styles.btnSecondary]} onPress={() => sendKey('VOL MUTE')}>
                            <Text style={styles.btnText}>🔇 MUTE</Text>
                        </TouchableOpacity>
                    </View>

                    {/* Volume & Channel */}
                    <View style={styles.row}>
                        <View style={styles.column}>
                            <TouchableOpacity style={[styles.btn, styles.btnAction]} onPress={() => sendKey('VOL UP')}>
                                <Text style={styles.btnText}>VOL +</Text>
                            </TouchableOpacity>
                            <TouchableOpacity style={[styles.btn, styles.btnAction]} onPress={() => sendKey('VOL DOWN')}>
                                <Text style={styles.btnText}>VOL -</Text>
                            </TouchableOpacity>
                        </View>
                        <View style={styles.column}>
                            <TouchableOpacity style={[styles.btn, styles.btnAction]} onPress={() => sendKey('CH UP')}>
                                <Text style={styles.btnText}>CH ∧</Text>
                            </TouchableOpacity>
                            <TouchableOpacity style={[styles.btn, styles.btnAction]} onPress={() => sendKey('CH DOWN')}>
                                <Text style={styles.btnText}>CH ∨</Text>
                            </TouchableOpacity>
                        </View>
                    </View>

                    {/* D-Pad Navigation */}
                    <View style={styles.dpadContainer}>
                        <TouchableOpacity style={[styles.btn, styles.btnDpad]} onPress={() => sendKey('UP')}>
                            <Text style={styles.btnText}>▲</Text>
                        </TouchableOpacity>
                        <View style={styles.row}>
                            <TouchableOpacity style={[styles.btn, styles.btnDpad]} onPress={() => sendKey('LEFT')}>
                                <Text style={styles.btnText}>◀</Text>
                            </TouchableOpacity>
                            <TouchableOpacity style={[styles.btn, styles.btnOk]} onPress={() => sendKey('OK')}>
                                <Text style={styles.btnOkText}>OK</Text>
                            </TouchableOpacity>
                            <TouchableOpacity style={[styles.btn, styles.btnDpad]} onPress={() => sendKey('RIGHT')}>
                                <Text style={styles.btnText}>▶</Text>
                            </TouchableOpacity>
                        </View>
                        <TouchableOpacity style={[styles.btn, styles.btnDpad]} onPress={() => sendKey('DOWN')}>
                            <Text style={styles.btnText}>▼</Text>
                        </TouchableOpacity>
                    </View>

                    {/* Menu & Back */}
                    <View style={styles.row}>
                        <TouchableOpacity style={[styles.btn, styles.btnSecondary]} onPress={() => sendKey('MENU')}>
                            <Text style={styles.btnText}>MENU</Text>
                        </TouchableOpacity>
                        <TouchableOpacity style={[styles.btn, styles.btnSecondary]} onPress={() => sendKey('BACK')}>
                            <Text style={styles.btnText}>BACK</Text>
                        </TouchableOpacity>
                    </View>

                    {/* 0-9 Keypad */}
                    <Text style={styles.sectionTitle}>Numeric Keypad:</Text>
                    <View style={styles.keypad}>
                        {['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'].map(num => (
                            <TouchableOpacity key={num} style={styles.keyNum} onPress={() => sendKey(num)}>
                                <Text style={styles.keyNumText}>{num}</Text>
                            </TouchableOpacity>
                        ))}
                    </View>
                </View>

                {/* Footer Log */}
                <View style={styles.footerLog}>
                    <Text style={styles.logTitle}>Status & Transmission Log:</Text>
                    <Text style={styles.logText}>{lastLog}</Text>
                    {isTransmitting && <ActivityIndicator color="#00bcd4" style={{ marginTop: 6 }} />}
                </View>
            </ScrollView>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#0e1217'
    },
    scroll: {
        padding: 16,
        alignItems: 'center'
    },
    header: {
        alignItems: 'center',
        marginBottom: 14
    },
    title: {
        fontSize: 22,
        fontWeight: 'bold',
        color: '#ffffff'
    },
    subtitle: {
        fontSize: 12,
        color: '#8da0b3',
        marginTop: 2
    },
    statusBar: {
        width: '100%',
        padding: 12,
        borderRadius: 8,
        marginBottom: 12
    },
    statusOk: {
        backgroundColor: '#133526',
        borderColor: '#2bb673',
        borderWidth: 1
    },
    statusWarn: {
        backgroundColor: '#352514',
        borderColor: '#f39c12',
        borderWidth: 1
    },
    statusIndicatorRow: {
        flexDirection: 'row',
        alignItems: 'center'
    },
    statusDot: {
        width: 10,
        height: 10,
        borderRadius: 5,
        marginRight: 8
    },
    dotGreen: {
        backgroundColor: '#2bb673'
    },
    dotOrange: {
        backgroundColor: '#f39c12'
    },
    statusText: {
        color: '#ffffff',
        fontSize: 13,
        fontWeight: '600'
    },
    permButton: {
        backgroundColor: '#f39c12',
        paddingVertical: 6,
        paddingHorizontal: 12,
        borderRadius: 4,
        alignSelf: 'flex-start',
        marginTop: 8
    },
    permButtonText: {
        color: '#000000',
        fontWeight: 'bold',
        fontSize: 12
    },
    searchContainer: {
        width: '100%',
        marginBottom: 12
    },
    searchBarRow: {
        flexDirection: 'row',
        alignItems: 'center'
    },
    searchInput: {
        flex: 1,
        backgroundColor: '#161c24',
        borderColor: '#263342',
        borderWidth: 1,
        borderRadius: 8,
        paddingHorizontal: 12,
        paddingVertical: 10,
        color: '#ffffff',
        fontSize: 14
    },
    dropdown: {
        backgroundColor: '#1b232e',
        borderRadius: 8,
        marginTop: 4,
        maxHeight: 200,
        borderColor: '#263342',
        borderWidth: 1
    },
    dropdownItem: {
        padding: 10,
        borderBottomColor: '#263342',
        borderBottomWidth: 1
    },
    dropdownItemText: {
        color: '#ffffff',
        fontSize: 14,
        fontWeight: '600'
    },
    dropdownItemSub: {
        color: '#8da0b3',
        fontSize: 11,
        marginTop: 2
    },
    providerCard: {
        width: '100%',
        backgroundColor: '#1b232e',
        padding: 10,
        borderRadius: 8,
        marginBottom: 12,
        borderLeftColor: '#00bcd4',
        borderLeftWidth: 4
    },
    providerCardTitle: {
        color: '#ffffff',
        fontSize: 15,
        fontWeight: 'bold'
    },
    providerCardSub: {
        color: '#8da0b3',
        fontSize: 12,
        marginTop: 2
    },
    modelSection: {
        width: '100%',
        marginBottom: 12
    },
    sectionTitle: {
        color: '#8da0b3',
        fontSize: 12,
        fontWeight: 'bold',
        marginBottom: 6
    },
    modelScroll: {
        flexDirection: 'row',
        width: '100%'
    },
    modelTab: {
        paddingVertical: 8,
        paddingHorizontal: 12,
        borderRadius: 16,
        backgroundColor: '#1b232e',
        marginRight: 8
    },
    modelTabActive: {
        backgroundColor: '#00bcd4'
    },
    modelTabText: {
        color: '#8da0b3',
        fontSize: 12,
        fontWeight: '600'
    },
    modelTabTextActive: {
        color: '#000000',
        fontWeight: 'bold'
    },
    remoteBody: {
        width: '100%',
        backgroundColor: '#161c24',
        borderRadius: 16,
        padding: 16,
        alignItems: 'center'
    },
    row: {
        flexDirection: 'row',
        justifyContent: 'space-around',
        width: '100%',
        marginVertical: 6
    },
    column: {
        flexDirection: 'column',
        alignItems: 'center'
    },
    btn: {
        borderRadius: 8,
        justifyContent: 'center',
        alignItems: 'center',
        margin: 4
    },
    btnPower: {
        backgroundColor: '#e74c3c',
        width: 130,
        height: 48
    },
    btnPowerText: {
        color: '#ffffff',
        fontWeight: 'bold',
        fontSize: 15
    },
    btnSecondary: {
        backgroundColor: '#263342',
        width: 130,
        height: 48
    },
    btnAction: {
        backgroundColor: '#1f2a37',
        width: 100,
        height: 52,
        marginVertical: 4
    },
    btnText: {
        color: '#ffffff',
        fontWeight: '600',
        fontSize: 14
    },
    dpadContainer: {
        alignItems: 'center',
        marginVertical: 10
    },
    btnDpad: {
        backgroundColor: '#263342',
        width: 60,
        height: 46
    },
    btnOk: {
        backgroundColor: '#00bcd4',
        width: 68,
        height: 46,
        borderRadius: 8,
        justifyContent: 'center',
        alignItems: 'center',
        margin: 4
    },
    btnOkText: {
        color: '#000000',
        fontWeight: 'bold',
        fontSize: 15
    },
    keypad: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        justifyContent: 'center',
        width: '100%',
        marginVertical: 6
    },
    keyNum: {
        backgroundColor: '#1f2a37',
        width: 58,
        height: 42,
        borderRadius: 6,
        justifyContent: 'center',
        alignItems: 'center',
        margin: 4
    },
    keyNumText: {
        color: '#ffffff',
        fontSize: 16,
        fontWeight: '600'
    },
    footerLog: {
        width: '100%',
        marginTop: 14,
        padding: 10,
        backgroundColor: '#121820',
        borderRadius: 8
    },
    logTitle: {
        color: '#8da0b3',
        fontSize: 11,
        fontWeight: 'bold'
    },
    logText: {
        color: '#2bb673',
        fontSize: 12,
        marginTop: 4,
        fontFamily: 'monospace'
    }
});
