import React from 'react';
import { View, Text, StyleSheet, ViewProps, TextProps } from 'react-native';
import { theme } from '../../lib/theme';

export interface CardProps extends ViewProps {}
export function Card({ style, children, ...props }: CardProps) {
    return (
        <View style={[styles.card, style]} {...props}>
            {children}
        </View>
    );
}

export function CardHeader({ style, children, ...props }: ViewProps) {
    return (
        <View style={[styles.cardHeader, style]} {...props}>
            {children}
        </View>
    );
}

export function CardTitle({ style, children, ...props }: TextProps) {
    return (
        <Text style={[styles.cardTitle, style]} {...props}>
            {children}
        </Text>
    );
}

export function CardDescription({ style, children, ...props }: TextProps) {
    return (
        <Text style={[styles.cardDescription, style]} {...props}>
            {children}
        </Text>
    );
}

export function CardContent({ style, children, ...props }: ViewProps) {
    return (
        <View style={[styles.cardContent, style]} {...props}>
            {children}
        </View>
    );
}

export function CardFooter({ style, children, ...props }: ViewProps) {
    return (
        <View style={[styles.cardFooter, style]} {...props}>
            {children}
        </View>
    );
}

const styles = StyleSheet.create({
    card: {
        backgroundColor: theme.colors.card,
        borderRadius: theme.radii.xl,
        borderColor: theme.colors.border,
        borderWidth: 1,
        padding: 16,
    },
    cardHeader: {
        marginBottom: 8,
    },
    cardTitle: {
        color: theme.colors.cardForeground,
        fontSize: 16,
        fontWeight: '700',
        letterSpacing: -0.3,
    },
    cardDescription: {
        color: theme.colors.mutedForeground,
        fontSize: 12,
        marginTop: 2,
    },
    cardContent: {
        marginTop: 4,
    },
    cardFooter: {
        marginTop: 12,
        flexDirection: 'row',
        alignItems: 'center',
    },
});

