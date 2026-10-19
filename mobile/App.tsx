import React, { useState, useEffect } from 'react';
import {
    SafeAreaView,
    ScrollView,
    StatusBar,
    StyleSheet,
    Text,
    TouchableOpacity,
    View,
    ActivityIndicator,
    Alert
} from 'react-native';
import { SensyIr, DeviceStatus } from './src/SensyIrNative';
import { KERALA_VISION_REMOTES, RemoteDefinition } from './src/stb_profiles';

export default function App() {
    const [status, setStatus] = useState<DeviceStatus>({
        connected: false,
        hasPermission: false,
        message: 'Checking USB status...'
    });
    const [selectedRemoteIndex, setSelectedRemoteIndex] = useState<number>(0);
    const [isTransmitting, setIsTransmitting] = useState<boolean>(false);
    const [lastLog, setLastLog] = useState<string>('Ready. Connect IR dongle via OTG cable.');

    const activeRemote: RemoteDefinition = KERALA_VISION_REMOTES[selectedRemoteIndex] || KERALA_VISION_REMOTES[0];

    const checkUsb = async () => {
        try {
            const st = await SensyIr.getStatus();
            setStatus(st);
            if (st.connected && st.hasPermission) {
                setLastLog(`Connected: Xiaomi / Sensy Blaster (Remote ID: ${activeRemote.id})`);
            } else if (st.connected && !st.hasPermission) {
                setLastLog('Dongle detected! Tap "Grant Permission" above.');
            } else {
                setLastLog('Dongle not found. Plug into phone with USB-OTG adapter.');
            }
        } catch (e: any) {
            setLastLog(`Error checking USB: ${e.message}`);
        }
    };

    useEffect(() => {
        checkUsb();
        const timer = setInterval(checkUsb, 3000);
        return () => clearInterval(timer);
    }, [activeRemote.id]);

    const handleRequestPermission = async () => {
        try {
            await SensyIr.requestPermission();
            await checkUsb();
        } catch (e: any) {
            Alert.alert('Permission Error', e.message);
        }
    };

    const sendKey = async (keyName: string) => {
        const hex = activeRemote.keys[keyName];
        if (!hex) {
            setLastLog(`Key "${keyName}" not defined in Remote ID ${activeRemote.id}`);
            return;
        }

        setIsTransmitting(true);
        setLastLog(`Sending ${keyName} (${hex})...`);
        try {
            await SensyIr.transmitNec(hex, activeRemote.bitLength);
            setLastLog(`Sent ${keyName} [${hex}] (Remote ${activeRemote.id})`);
        } catch (err: any) {
            setLastLog(`TX Error: ${err.message}`);
        } finally {
            setIsTransmitting(false);
        }
    };

    const autoTestAll = async () => {
        setLastLog('Auto-testing all 7 Kerala Vision remotes (Power)...');
        for (let i = 0; i < KERALA_VISION_REMOTES.length; i++) {
            const r = KERALA_VISION_REMOTES[i];
            const pwr = r.keys['POWER'];
            if (pwr) {
                setSelectedRemoteIndex(i);
                setLastLog(`Testing Remote ${r.id} POWER (${pwr})...`);
                try {
                    await SensyIr.transmitNec(pwr, r.bitLength);
                } catch (_) {}
                await new Promise(res => setTimeout(res, 2000));
            }
        }
        setLastLog('Auto-test cycle finished. Select the model that worked.');
    };

    return (
        <SafeAreaView style={styles.container}>
            <StatusBar barStyle="light-content" backgroundColor="#121820" />
            <ScrollView contentContainerStyle={styles.scroll}>
                {/* Header */}
                <View style={styles.header}>
                    <Text style={styles.title}>Kerala Vision Remote</Text>
                    <Text style={styles.subtitle}>Xiaomi / Sensy USB IR Blaster</Text>
                </View>

                {/* USB Status Badge */}
                <View style={[styles.statusBar, status.connected && status.hasPermission ? styles.statusOk : styles.statusWarn]}>
                    <View style={styles.statusIndicatorRow}>
                        <View style={[styles.statusDot, status.connected && status.hasPermission ? styles.dotGreen : styles.dotOrange]} />
                        <Text style={styles.statusText}>
                            {status.connected
                                ? (status.hasPermission ? 'USB IR Blaster Connected' : 'USB Attached - Permission Required')
                                : 'Blaster Disconnected (Plug OTG)'}
                        </Text>
                    </View>
                    {status.connected && !status.hasPermission && (
                        <TouchableOpacity style={styles.permButton} onPress={handleRequestPermission}>
                            <Text style={styles.permButtonText}>Grant Permission</Text>
                        </TouchableOpacity>
                    )}
                </View>

                {/* Remote Model Selector */}
                <Text style={styles.sectionTitle}>STB Remote Model (Profile):</Text>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.modelScroll}>
                    {KERALA_VISION_REMOTES.map((r, idx) => (
                        <TouchableOpacity
                            key={r.id}
                            style={[styles.modelTab, selectedRemoteIndex === idx && styles.modelTabActive]}
                            onPress={() => setSelectedRemoteIndex(idx)}>
                            <Text style={[styles.modelTabText, selectedRemoteIndex === idx && styles.modelTabTextActive]}>
                                ID {r.id} {idx === 0 ? '(Default)' : ''}
                            </Text>
                        </TouchableOpacity>
                    ))}
                </ScrollView>

                {/* Remote Controls */}
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

                    {/* Vol & Channel Controls */}
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

                    {/* Center D-Pad Navigation */}
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

                    {/* Menu & Exit */}
                    <View style={styles.row}>
                        <TouchableOpacity style={[styles.btn, styles.btnSecondary]} onPress={() => sendKey('MENU')}>
                            <Text style={styles.btnText}>MENU</Text>
                        </TouchableOpacity>
                        <TouchableOpacity style={[styles.btn, styles.btnSecondary]} onPress={() => sendKey('BACK')}>
                            <Text style={styles.btnText}>BACK</Text>
                        </TouchableOpacity>
                    </View>

                    {/* Numeric Keypad (0-9) */}
                    <Text style={styles.sectionTitle}>Numeric Keys:</Text>
                    <View style={styles.keypad}>
                        {['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'].map(num => (
                            <TouchableOpacity key={num} style={styles.keyNum} onPress={() => sendKey(num)}>
                                <Text style={styles.keyNumText}>{num}</Text>
                            </TouchableOpacity>
                        ))}
                    </View>

                    {/* Auto-test Button */}
                    <TouchableOpacity style={styles.autoTestBtn} onPress={autoTestAll}>
                        <Text style={styles.autoTestBtnText}>⚡ Auto-Test All 7 Kerala Remotes</Text>
                    </TouchableOpacity>
                </View>

                {/* Status / Log Footer */}
                <View style={styles.footerLog}>
                    <Text style={styles.logTitle}>Transmission Log:</Text>
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
        marginBottom: 16
    },
    title: {
        fontSize: 22,
        fontWeight: 'bold',
        color: '#ffffff'
    },
    subtitle: {
        fontSize: 13,
        color: '#8da0b3',
        marginTop: 2
    },
    statusBar: {
        width: '100%',
        padding: 12,
        borderRadius: 8,
        marginBottom: 16
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
    sectionTitle: {
        alignSelf: 'flex-start',
        color: '#8da0b3',
        fontSize: 13,
        fontWeight: 'bold',
        marginVertical: 8
    },
    modelScroll: {
        flexDirection: 'row',
        marginBottom: 16,
        width: '100%'
    },
    modelTab: {
        paddingVertical: 8,
        paddingHorizontal: 14,
        borderRadius: 20,
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
        marginVertical: 12
    },
    btnDpad: {
        backgroundColor: '#263342',
        width: 60,
        height: 48
    },
    btnOk: {
        backgroundColor: '#00bcd4',
        width: 68,
        height: 48,
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
        marginVertical: 8
    },
    keyNum: {
        backgroundColor: '#1f2a37',
        width: 60,
        height: 44,
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
    autoTestBtn: {
        backgroundColor: '#34495e',
        width: '100%',
        paddingVertical: 12,
        borderRadius: 8,
        alignItems: 'center',
        marginTop: 14
    },
    autoTestBtnText: {
        color: '#00bcd4',
        fontWeight: 'bold',
        fontSize: 14
    },
    footerLog: {
        width: '100%',
        marginTop: 16,
        padding: 12,
        backgroundColor: '#121820',
        borderRadius: 8
    },
    logTitle: {
        color: '#8da0b3',
        fontSize: 12,
        fontWeight: 'bold'
    },
    logText: {
        color: '#2bb673',
        fontSize: 13,
        marginTop: 4,
        fontFamily: 'monospace'
    }
});
