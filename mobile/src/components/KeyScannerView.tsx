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
import {
    TvBeamingService,
    KNOWN_KERALA_CANDIDATE_KEYS,
    CandidateKey,
} from '../services/TvBeamingService';
import { theme } from '../lib/theme';

interface KeyScannerViewProps {
    tvIp: string;
    isTvConnected: boolean;
    activeRemote?: { id: number; protocol: number; bitLength: number; gap: number };
    onAssignKey: (keyName: string, hexCode: string) => void;
    onBlastDirect: (hexCode: string) => Promise<boolean>;
}

export function KeyScannerView({
    tvIp,
    isTvConnected,
    activeRemote,
    onAssignKey,
    onBlastDirect,
}: KeyScannerViewProps) {
    const [selectedCategory, setSelectedCategory] = useState<string>('RECALL');
    const [currentStepByte, setCurrentStepByte] = useState<number>(0x80);
    const [lastTestedCode, setLastTestedCode] = useState<string | null>(null);
    const [isTesting, setIsTesting] = useState<boolean>(false);
    const [customAssignName, setCustomAssignName] = useState<string>('RECALL');

    const address = 0x14; // Kerala Vision standard address
    const computedStepHex = TvBeamingService.buildNecHex(address, currentStepByte);

    const handleTestCandidate = async (candidate: CandidateKey) => {
        setIsTesting(true);
        try {
            await onBlastDirect(candidate.hex);
            setLastTestedCode(candidate.hex);
        } catch (e: any) {
            Alert.alert('Transmission Failed', e.message);
        } finally {
            setIsTesting(false);
        }
    };

    const handleTestStepper = async () => {
        setIsTesting(true);
        try {
            await onBlastDirect(computedStepHex);
            setLastTestedCode(computedStepHex);
        } catch (e: any) {
            Alert.alert('Transmission Failed', e.message);
        } finally {
            setIsTesting(false);
        }
    };

    const handleNextStep = () => {
        setCurrentStepByte((prev) => (prev + 1) % 256);
    };

    const handlePrevStep = () => {
        setCurrentStepByte((prev) => (prev - 1 + 256) % 256);
    };

    return (
        <ScrollView style={styles.container} contentContainerStyle={styles.content}>
            {/* Header / Intro Card */}
            <Card>
                <CardHeader>
                    <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                        <CardTitle>STB Key Discovery &amp; Scanner</CardTitle>
                        {activeRemote && (
                            <Badge variant="outline" style={{ borderColor: '#38BDF8', borderRadius: 0 }}>
                                <Text style={{ color: '#38BDF8', fontSize: 11, fontWeight: 'bold' }}>
                                    Model #{activeRemote.id}
                                </Text>
                            </Badge>
                        )}
                    </View>
                    <CardDescription>
                        Test missing commands against your STB for Model #{activeRemote?.id ?? 43}. When your STB reacts, lock it in!
                    </CardDescription>
                </CardHeader>
            </Card>

            {/* Mode 1: Known Candidate Keys */}
            <Card style={styles.sectionCard}>
                <CardHeader>
                    <CardTitle style={styles.subTitle}>1. Target Key Candidates</CardTitle>
                    <CardDescription>
                        Best candidate codes discovered across Indian STBs with address 0x14EB:
                    </CardDescription>
                </CardHeader>
                <CardContent>
                    {/* Category Selector */}
                    <View style={styles.categoryRow}>
                        {Object.keys(KNOWN_KERALA_CANDIDATE_KEYS).map((cat) => (
                            <TouchableOpacity
                                key={cat}
                                style={[
                                    styles.categoryButton,
                                    selectedCategory === cat && styles.categoryButtonActive,
                                ]}
                                onPress={() => {
                                    setSelectedCategory(cat);
                                    setCustomAssignName(cat);
                                }}>
                                <Text
                                    style={[
                                        styles.categoryButtonText,
                                        selectedCategory === cat && styles.categoryButtonTextActive,
                                    ]}>
                                    {cat}
                                </Text>
                            </TouchableOpacity>
                        ))}
                    </View>

                    <Separator style={{ marginVertical: 12 }} />

                    {/* Candidate List */}
                    {KNOWN_KERALA_CANDIDATE_KEYS[selectedCategory]?.map((cand, idx) => (
                        <View key={cand.hex} style={styles.candidateRow}>
                            <View style={{ flex: 1 }}>
                                <View style={styles.candidateHeader}>
                                    <Text style={styles.candidateName}>{cand.name}</Text>
                                    <Badge variant="outline" style={styles.hexBadge}>
                                        <Text style={styles.hexBadgeText}>{cand.hex}</Text>
                                    </Badge>
                                </View>
                                <Text style={styles.candidateDesc}>
                                    Cmd: 0x{cand.commandByte.toString(16).padStart(2, '0')} • {cand.source}
                                </Text>
                                <Text style={styles.candidateExtra}>{cand.description}</Text>
                            </View>

                            <View style={styles.candidateActions}>
                                <Button
                                    variant="secondary"
                                    size="sm"
                                    style={styles.testBtn}
                                    onPress={() => handleTestCandidate(cand)}
                                    disabled={isTesting}>
                                    <Text style={styles.btnText}>Test</Text>
                                </Button>
                                <Button
                                    variant="default"
                                    size="sm"
                                    style={styles.assignBtn}
                                    onPress={() => {
                                        onAssignKey(selectedCategory, cand.hex);
                                        Alert.alert('Key Assigned', `Assigned ${cand.hex} to ${selectedCategory}!`);
                                    }}>
                                    <Text style={styles.btnText}>Assign</Text>
                                </Button>
                            </View>
                        </View>
                    ))}
                </CardContent>
            </Card>

            {/* Mode 2: Full Byte Stepper (0x00 to 0xFF) */}
            <Card style={styles.sectionCard}>
                <CardHeader>
                    <CardTitle style={styles.subTitle}>2. Brute-Force Command Stepper</CardTitle>
                    <CardDescription>
                        Step through all 256 possible commands for Address 0x14EB:
                    </CardDescription>
                </CardHeader>
                <CardContent>
                    <View style={styles.stepperContainer}>
                        <View style={styles.stepperDisplay}>
                            <Text style={styles.stepperLabel}>Current Command Byte:</Text>
                            <Text style={styles.stepperValue}>
                                0x{currentStepByte.toString(16).toUpperCase().padStart(2, '0')}
                                <Text style={styles.stepperDecimal}> ({currentStepByte}/255)</Text>
                            </Text>
                            <Text style={styles.stepperHex}>Code: {computedStepHex}</Text>
                        </View>

                        <View style={styles.stepperNavRow}>
                            <Button
                                variant="outline"
                                size="sm"
                                style={styles.stepBtn}
                                onPress={handlePrevStep}>
                                <Text style={styles.btnText}>Prev (-1)</Text>
                            </Button>
                            <Button
                                variant="destructive"
                                size="default"
                                style={styles.blastBtn}
                                onPress={handleTestStepper}
                                disabled={isTesting}>
                                <Text style={styles.blastBtnText}>Blast on TV</Text>
                            </Button>
                            <Button
                                variant="outline"
                                size="sm"
                                style={styles.stepBtn}
                                onPress={handleNextStep}>
                                <Text style={styles.btnText}>Next (+1)</Text>
                            </Button>
                        </View>

                        <Separator style={{ marginVertical: 14 }} />

                        {/* Assign Stepper Result */}
                        <View style={styles.assignRow}>
                            <Input
                                value={customAssignName}
                                onChangeText={setCustomAssignName}
                                placeholder="Key Name (e.g. RECALL)"
                                style={styles.assignInput}
                            />
                            <Button
                                variant="default"
                                size="sm"
                                style={styles.lockInBtn}
                                onPress={() => {
                                    if (!customAssignName.trim()) return;
                                    onAssignKey(customAssignName.trim().toUpperCase(), computedStepHex);
                                    Alert.alert(
                                        'Key Locked In',
                                        `Assigned ${computedStepHex} to ${customAssignName.toUpperCase()}!`
                                    );
                                }}>
                                <Text style={styles.btnText}>Save Key</Text>
                            </Button>
                        </View>
                    </View>
                </CardContent>
            </Card>

            {lastTestedCode && (
                <View style={styles.statusBox}>
                    <Text style={styles.statusBoxText}>Last Transmitted: {lastTestedCode}</Text>
                </View>
            )}
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
    sectionCard: {
        marginTop: 14,
    },
    subTitle: {
        fontSize: 16,
    },
    categoryRow: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 6,
    },
    categoryButton: {
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 0,
        backgroundColor: theme.colors.muted,
        borderWidth: 1,
        borderColor: theme.colors.border,
    },
    categoryButtonActive: {
        backgroundColor: theme.colors.primary,
        borderColor: theme.colors.primary,
    },
    categoryButtonText: {
        fontSize: 12,
        fontWeight: 'bold',
        color: theme.colors.mutedForeground,
    },
    categoryButtonTextActive: {
        color: theme.colors.primaryForeground,
    },
    candidateRow: {
        paddingVertical: 10,
        borderBottomWidth: 1,
        borderBottomColor: theme.colors.border,
        flexDirection: 'row',
        alignItems: 'center',
    },
    candidateHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
    },
    candidateName: {
        fontSize: 14,
        fontWeight: 'bold',
        color: theme.colors.foreground,
    },
    hexBadge: {
        backgroundColor: '#1E293B',
        borderColor: '#38BDF8',
    },
    hexBadgeText: {
        fontSize: 11,
        color: '#38BDF8',
        fontWeight: 'bold',
        fontFamily: 'monospace',
    },
    candidateDesc: {
        fontSize: 11,
        color: theme.colors.mutedForeground,
        marginTop: 2,
    },
    candidateExtra: {
        fontSize: 10,
        color: '#94A3B8',
        marginTop: 1,
    },
    candidateActions: {
        flexDirection: 'column',
        gap: 6,
        marginLeft: 10,
    },
    testBtn: {
        paddingHorizontal: 10,
        height: 32,
    },
    assignBtn: {
        paddingHorizontal: 10,
        height: 32,
        backgroundColor: theme.colors.primary,
        borderRadius: 0,
    },
    btnText: {
        fontSize: 12,
        fontWeight: 'bold',
        color: '#FFFFFF',
    },
    stepperContainer: {
        alignItems: 'center',
    },
    stepperDisplay: {
        alignItems: 'center',
        marginBottom: 14,
    },
    stepperLabel: {
        fontSize: 12,
        color: theme.colors.mutedForeground,
    },
    stepperValue: {
        fontSize: 28,
        fontWeight: '900',
        color: '#F43F5E',
        fontFamily: 'monospace',
    },
    stepperDecimal: {
        fontSize: 14,
        color: theme.colors.mutedForeground,
        fontWeight: 'normal',
    },
    stepperHex: {
        fontSize: 14,
        fontFamily: 'monospace',
        color: '#38BDF8',
        marginTop: 2,
    },
    stepperNavRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 12,
        width: '100%',
        justifyContent: 'center',
    },
    stepBtn: {
        flex: 1,
        height: 40,
        borderRadius: 0,
    },
    blastBtn: {
        flex: 1.4,
        height: 44,
        backgroundColor: '#F43F5E',
        borderRadius: 0,
    },
    blastBtnText: {
        fontSize: 13,
        fontWeight: 'bold',
        color: '#FFFFFF',
    },
    assignRow: {
        flexDirection: 'row',
        alignItems: 'center',
        width: '100%',
        gap: 8,
    },
    assignInput: {
        flex: 1,
        height: 40,
        borderRadius: 0,
    },
    lockInBtn: {
        height: 40,
        paddingHorizontal: 16,
        backgroundColor: theme.colors.primary,
        borderRadius: 0,
    },
    statusBox: {
        marginTop: 12,
        padding: 10,
        backgroundColor: theme.colors.muted,
        borderRadius: 0,
        alignItems: 'center',
    },
    statusBoxText: {
        fontSize: 12,
        color: theme.colors.foreground,
        fontFamily: 'monospace',
    },
});

