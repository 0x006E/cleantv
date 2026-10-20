import React from 'react';
import { View, Text, StyleSheet, ViewProps, TextStyle } from 'react-native';
import { theme } from '../../lib/theme';

export type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline' | 'success' | 'warning';

export interface BadgeProps extends ViewProps {
    variant?: BadgeVariant;
    children?: React.ReactNode;
}

export function Badge({ variant = 'default', style, children, ...props }: BadgeProps) {
    const content = React.isValidElement(children) ? (
        children
    ) : (
        <Text style={[styles.text, textStyles[variant]]}>{children}</Text>
    );

    return (
        <View style={[styles.base, badgeStyles[variant], style]} {...props}>
            {content}
        </View>
    );
}

const styles = StyleSheet.create({
    base: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 3,
        paddingHorizontal: 8,
        borderRadius: theme.radii.full,
        borderWidth: 1,
        alignSelf: 'flex-start',
    },
    text: {
        fontSize: 11,
        fontWeight: '700',
        letterSpacing: 0.2,
    },
});

const badgeStyles = StyleSheet.create({
    default: {
        backgroundColor: theme.colors.primary,
        borderColor: theme.colors.primary,
    },
    secondary: {
        backgroundColor: theme.colors.secondary,
        borderColor: theme.colors.border,
    },
    destructive: {
        backgroundColor: '#450a0a',
        borderColor: '#991b1b',
    },
    outline: {
        backgroundColor: 'transparent',
        borderColor: theme.colors.border,
    },
    success: {
        backgroundColor: '#064e3b',
        borderColor: '#059669',
    },
    warning: {
        backgroundColor: '#451a03',
        borderColor: '#b45309',
    },
});

const textStyles = StyleSheet.create({
    default: {
        color: theme.colors.primaryForeground,
    },
    secondary: {
        color: theme.colors.secondaryForeground,
    },
    destructive: {
        color: '#f87171',
    },
    outline: {
        color: theme.colors.mutedForeground,
    },
    success: {
        color: '#34d399',
    },
    warning: {
        color: '#fbbf24',
    },
});
