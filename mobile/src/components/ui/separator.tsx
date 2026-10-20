import React from 'react';
import { View, StyleSheet, ViewProps } from 'react-native';
import { theme } from '../../lib/theme';

export interface SeparatorProps extends ViewProps {
    orientation?: 'horizontal' | 'vertical';
}

export function Separator({
    orientation = 'horizontal',
    style,
    ...props
}: SeparatorProps) {
    return (
        <View
            style={[
                orientation === 'horizontal' ? styles.horizontal : styles.vertical,
                style,
            ]}
            {...props}
        />
    );
}

const styles = StyleSheet.create({
    horizontal: {
        height: 1,
        width: '100%',
        backgroundColor: theme.colors.border,
    },
    vertical: {
        width: 1,
        height: '100%',
        backgroundColor: theme.colors.border,
    },
});

