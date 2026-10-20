import React, { useState } from 'react';
import {
    View,
    Text,
    StyleSheet,
    ScrollView,
    TouchableOpacity,
    Alert,
} from 'react-native';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './ui/card';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { Input } from './ui/input';
import { Separator } from './ui/separator';
import { CustomRemoteConfig, TvBeamingService } from '../services/TvBeamingService';
import { theme } from '../lib/theme';

interface CustomRemoteViewProps {
    tvIp: string;
    isTvConnected: boolean;
    customRemote: CustomRemoteConfig;
    onUpdateCustomRemote: (updated: CustomRemoteConfig) => void;
    onBlastDirect: (hexCode: string) => Promise<boolean>;
    onSyncToTv: () => Promise<void>;
}

export function CustomRemoteView({
    tvIp,
    isTvConnected,
    customRemote,
    onUpdateCustomRemote,
    onBlastDirect,
    onSyncToTv,
}: CustomRemoteViewProps) {
    const [newKeyName, setNewKeyName] = useState<string>('');
    const [newKeyHex, setNewKeyHex] = useState<string>('0x14eb');
    const [isSaving, setIsSaving] = useState<boolean>(false);

    const handleAddKey = () => {
        const name = newKeyName.trim().toUpperCase();
        const hex = newKeyHex.trim().toLowerCase();
        if (!name || !hex) {
            Alert.alert('Invalid Input', 'Please enter a key name and hex code.');
            return;
        }
        if (!hex.startsWith('0x') || hex.length !== 10) {
            Alert.alert('Invalid Hex', 'NEC code must be a 32-bit hex string, e.g. 0x14eb8a75');
            return;
        }

        const updatedKeys = { ...customRemote.keys, [name]: hex };
        onUpdateCustomRemote({ ...customRemote, keys: updatedKeys });
        setNewKeyName('');
        setNewKeyHex('0x14eb');
    };

    const handleDeleteKey = (name: string) => {
        const updatedKeys = { ...customRemote.keys };
        delete updatedKeys[name];
        onUpdateCustomRemote({ ...customRemote, keys: updatedKeys });
    };

    const handleSync = async () => {
        setIsSaving(true);
        try {
            await onSyncToTv();
            Alert.alert('Success', 'Custom remote beamed and saved to Mi TV successfully!');
        } catch (e: any) {
            Alert.alert('Save Failed', e.message);
        } finally {
            setIsSaving(false);
        }
    };

    const keyEntries = Object.entries(customRemote.keys);

    return (
        <ScrollView style={styles.container} contentContainerStyle={styles.content}>
            <Card>
                <CardHeader>
                    <View style={styles.headerRow}>
                        <View style={{ flex: 1 }}>
                            <CardTitle>🛠️ Custom Remote Studio</CardTitle>
                            <CardDescription>
                                {customRemote.name} • {keyEntries.length} custom overrides
                            </CardDescription>
                        </View>
                        <Button
                            variant="default"
                            size="sm"
                            style={styles.syncBtn}
                            onPress={handleSync}
                            disabled={isSaving || !isTvConnected}>
                            <Text style={styles.syncBtnText}>
                                {isSaving ? 'Beaming...' : '🚀 Save to TV'}
                            </Text>
                        </Button>
                    </View>
                </CardHeader>
            </Card>

            {/* Add New Key Form */}
            <Card style={styles.sectionCard}>
                <CardHeader>
                    <CardTitle style={styles.subTitle}>+ Add or Override Key</CardTitle>
                </CardHeader>
                <CardContent>
                    <View style={styles.formRow}>
                        <Input
                            placeholder="Name (e.g. RECALL)"
                            value={newKeyName}
                            onChangeText={setNewKeyName}
                            style={styles.nameInput}
                        />
                        <Input
                            placeholder="Hex (0x14eb...)"
                            value={newKeyHex}
                            onChangeText={setNewKeyHex}
                            style={styles.hexInput}
                        />
                        <Button
                            variant="secondary"
                            size="sm"
                            style={styles.addBtn}
                            onPress={handleAddKey}>
                            <Text style={styles.addBtnText}>Add</Text>
                        </Button>
                    </View>
                </CardContent>
            </Card>

            {/* Configured Keys List */}
            <Card style={styles.sectionCard}>
                <CardHeader>
                    <CardTitle style={styles.subTitle}>Active Custom Mappings</CardTitle>
                    <CardDescription>
                        These overrides take priority over the default Remote #43:
                    </CardDescription>
                </CardHeader>
                <CardContent>
                    {keyEntries.length === 0 ? (
                        <Text style={styles.emptyText}>
                            No custom overrides yet. Use the Key Scanner tab or add a key above.
                        </Text>
                    ) : (
                        keyEntries.map(([name, hex]) => (
                            <View key={name} style={styles.keyRow}>
                                <View style={{ flex: 1 }}>
                                    <Text style={styles.keyName}>{name}</Text>
                                    <Text style={styles.keyHex}>{hex}</Text>
                                </View>
                                <View style={styles.keyActions}>
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        style={styles.testBtn}
                                        onPress={() => onBlastDirect(hex)}>
                                        <Text style={styles.testBtnText}>Test ⚡</Text>
                                    </Button>
                                    <TouchableOpacity
                                        style={styles.deleteBtn}
                                        onPress={() => handleDeleteKey(name)}>
                                        <Text style={styles.deleteBtnText}>✕</Text>
                                    </TouchableOpacity>
                                </View>
                            </View>
                        ))
                    )}
                </CardContent>
            </Card>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
    },
    content: {
        paddingBottom: 40,
    },
    headerRow: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    syncBtn: {
        backgroundColor: '#38BDF8',
        paddingHorizontal: 12,
        height: 36,
    },
    syncBtnText: {
        color: '#09090B',
        fontWeight: 'bold',
        fontSize: 12,
    },
    sectionCard: {
        marginTop: 14,
    },
    subTitle: {
        fontSize: 16,
    },
    formRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
    },
    nameInput: {
        flex: 1.2,
        height: 40,
    },
    hexInput: {
        flex: 1.5,
        height: 40,
        fontFamily: 'monospace',
    },
    addBtn: {
        height: 40,
        paddingHorizontal: 14,
    },
    addBtnText: {
        fontSize: 12,
        fontWeight: 'bold',
        color: theme.colors.foreground,
    },
    emptyText: {
        color: theme.colors.mutedForeground,
        fontSize: 13,
        textAlign: 'center',
        paddingVertical: 14,
    },
    keyRow: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 10,
        borderBottomWidth: 1,
        borderBottomColor: theme.colors.border,
    },
    keyName: {
        fontSize: 14,
        fontWeight: 'bold',
        color: theme.colors.foreground,
    },
    keyHex: {
        fontSize: 12,
        fontFamily: 'monospace',
        color: '#38BDF8',
        marginTop: 2,
    },
    keyActions: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
    },
    testBtn: {
        height: 32,
        paddingHorizontal: 10,
    },
    testBtnText: {
        fontSize: 11,
        fontWeight: 'bold',
        color: theme.colors.foreground,
    },
    deleteBtn: {
        width: 32,
        height: 32,
        borderRadius: 6,
        backgroundColor: '#27272A',
        alignItems: 'center',
        justifyContent: 'center',
    },
    deleteBtnText: {
        color: '#EF4444',
        fontSize: 14,
        fontWeight: 'bold',
    },
});

