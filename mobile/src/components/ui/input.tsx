import React, { useState } from 'react';
import { TextInput, StyleSheet, TextInputProps } from 'react-native';
import { theme } from '../../lib/theme';

export interface InputProps extends TextInputProps {}

export function Input({ style, onFocus, onBlur, ...props }: InputProps) {
    const [isFocused, setIsFocused] = useState(false);

    return (
        <TextInput
            style={[
                styles.input,
                isFocused && styles.focused,
                style,
            ]}
            placeholderTextColor={theme.colors.mutedForeground}
            onFocus={(e) => {
                setIsFocused(true);
                onFocus?.(e);
            }}
            onBlur={(e) => {
                setIsFocused(false);
                onBlur?.(e);
            }}
            {...props}
        />
    );
}

const styles = StyleSheet.create({
    input: {
        height: 42,
        backgroundColor: theme.colors.background,
        borderColor: theme.colors.border,
        borderWidth: 1,
        borderRadius: theme.radii.md,
        paddingHorizontal: 12,
        color: theme.colors.foreground,
        fontSize: 14,
    },
    focused: {
        borderColor: theme.colors.ring,
    },
});

