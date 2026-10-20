import React from 'react';
import {
    TouchableOpacity,
    Text,
    StyleSheet,
    TouchableOpacityProps,
    TextStyle,
    ViewStyle,
} from 'react-native';
import { theme } from '../../lib/theme';

export type ButtonVariant = 'default' | 'destructive' | 'outline' | 'secondary' | 'ghost' | 'link';
export type ButtonSize = 'default' | 'sm' | 'lg' | 'icon';

export interface ButtonProps extends TouchableOpacityProps {
    variant?: ButtonVariant;
    size?: ButtonSize;
    children?: React.ReactNode;
}

export function Button({
    variant = 'default',
    size = 'default',
    style,
    children,
    activeOpacity = 0.7,
    ...props
}: ButtonProps) {
    const buttonStyle = [
        styles.base,
        variantStyles[variant],
        sizeStyles[size],
        style,
    ];

    // If children is not a valid React element (e.g. string, number, array of strings), wrap it in styled text
    const content = React.isValidElement(children) ? (
        children
    ) : (
        <Text style={[styles.baseText, textVariantStyles[variant], textSizeStyles[size]]}>
            {children}
        </Text>
    );

    return (
        <TouchableOpacity style={buttonStyle} activeOpacity={activeOpacity} {...props}>
            {content}
        </TouchableOpacity>
    );
}

export function ButtonText({
    variant = 'default',
    size = 'default',
    style,
    children,
}: {
    variant?: ButtonVariant;
    size?: ButtonSize;
    style?: TextStyle;
    children: React.ReactNode;
}) {
    return (
        <Text style={[styles.baseText, textVariantStyles[variant], textSizeStyles[size], style]}>
            {children}
        </Text>
    );
}

const styles = StyleSheet.create({
    base: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        borderRadius: theme.radii.md,
    },
    baseText: {
        fontWeight: '600',
        letterSpacing: -0.2,
    },
});

const variantStyles = StyleSheet.create<Record<ButtonVariant, ViewStyle>>({
    default: {
        backgroundColor: theme.colors.primary,
    },
    destructive: {
        backgroundColor: theme.colors.destructive,
    },
    outline: {
        backgroundColor: 'transparent',
        borderWidth: 1,
        borderColor: theme.colors.border,
    },
    secondary: {
        backgroundColor: theme.colors.secondary,
        borderWidth: 1,
        borderColor: theme.colors.border,
    },
    ghost: {
        backgroundColor: 'transparent',
    },
    link: {
        backgroundColor: 'transparent',
    },
});

const textVariantStyles = StyleSheet.create<Record<ButtonVariant, TextStyle>>({
    default: {
        color: theme.colors.primaryForeground,
    },
    destructive: {
        color: theme.colors.destructiveForeground,
    },
    outline: {
        color: theme.colors.cardForeground,
    },
    secondary: {
        color: theme.colors.secondaryForeground,
    },
    ghost: {
        color: theme.colors.cardForeground,
    },
    link: {
        color: theme.colors.primary,
        textDecorationLine: 'underline',
    },
});

const sizeStyles = StyleSheet.create<Record<ButtonSize, ViewStyle>>({
    default: {
        paddingVertical: 10,
        paddingHorizontal: 16,
        minHeight: 40,
    },
    sm: {
        paddingVertical: 6,
        paddingHorizontal: 10,
        minHeight: 32,
        borderRadius: theme.radii.sm,
    },
    lg: {
        paddingVertical: 12,
        paddingHorizontal: 24,
        minHeight: 48,
        borderRadius: theme.radii.lg,
    },
    icon: {
        width: 40,
        height: 40,
        padding: 0,
    },
});

const textSizeStyles = StyleSheet.create<Record<ButtonSize, TextStyle>>({
    default: {
        fontSize: 14,
    },
    sm: {
        fontSize: 12,
    },
    lg: {
        fontSize: 16,
    },
    icon: {
        fontSize: 14,
    },
});
