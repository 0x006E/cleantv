import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet, ViewProps } from 'react-native';
import { theme } from '../../lib/theme';

export interface TabsListProps extends ViewProps {}
export function TabsList({ style, children, ...props }: TabsListProps) {
    return (
        <View style={[styles.tabsList, style]} {...props}>
            {children}
        </View>
    );
}

export interface TabsTriggerProps {
    value: string;
    activeValue: string;
    onPress: (val: string) => void;
    children: React.ReactNode;
}

export function TabsTrigger({
    value,
    activeValue,
    onPress,
    children,
}: TabsTriggerProps) {
    const isActive = value === activeValue;
    return (
        <TouchableOpacity
            style={[styles.tabTrigger, isActive && styles.tabTriggerActive]}
            activeOpacity={0.7}
            onPress={() => onPress(value)}>
            {React.isValidElement(children) ? (
                children
            ) : (
                <Text style={[styles.tabTriggerText, isActive && styles.tabTriggerTextActive]}>
                    {children}
                </Text>
            )}
        </TouchableOpacity>
    );
}

const styles = StyleSheet.create({
    tabsList: {
        flexDirection: 'row',
        backgroundColor: theme.colors.background,
        borderRadius: theme.radii.lg,
        padding: 4,
        borderColor: theme.colors.border,
        borderWidth: 1,
        gap: 4,
    },
    tabTrigger: {
        paddingVertical: 6,
        paddingHorizontal: 12,
        borderRadius: theme.radii.md,
        alignItems: 'center',
        justifyContent: 'center',
    },
    tabTriggerActive: {
        backgroundColor: theme.colors.card,
        borderColor: theme.colors.borderSubtle,
        borderWidth: 1,
    },
    tabTriggerText: {
        color: theme.colors.mutedForeground,
        fontSize: 12,
        fontWeight: '600',
    },
    tabTriggerTextActive: {
        color: theme.colors.foreground,
        fontWeight: '700',
    },
});
