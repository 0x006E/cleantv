import React from 'react';
import {
    Modal,
    View,
    Text,
    TouchableOpacity,
    StyleSheet,
    ModalProps,
    ViewProps,
    TextProps,
} from 'react-native';
import { theme } from '../../lib/theme';

export interface DialogProps extends ModalProps {
    open: boolean;
    onOpenChange: (open: boolean) => void;
}

export function Dialog({ open, onOpenChange, children, ...props }: DialogProps) {
    return (
        <Modal
            visible={open}
            transparent={true}
            animationType="fade"
            onRequestClose={() => onOpenChange(false)}
            {...props}>
            <View style={styles.overlay}>{children}</View>
        </Modal>
    );
}

export function DialogContent({ style, children, ...props }: ViewProps) {
    return (
        <View style={[styles.content, style]} {...props}>
            {children}
        </View>
    );
}

export function DialogHeader({ style, children, ...props }: ViewProps) {
    return (
        <View style={[styles.header, style]} {...props}>
            {children}
        </View>
    );
}

export function DialogTitle({ style, children, ...props }: TextProps) {
    return (
        <Text style={[styles.title, style]} {...props}>
            {children}
        </Text>
    );
}

export function DialogDescription({ style, children, ...props }: TextProps) {
    return (
        <Text style={[styles.description, style]} {...props}>
            {children}
        </Text>
    );
}

export function DialogClose({ onPress, children }: { onPress: () => void; children?: React.ReactNode }) {
    return (
        <TouchableOpacity style={styles.closeBtn} activeOpacity={0.7} onPress={onPress}>
            {children || <Text style={styles.closeBtnText}>✕</Text>}
        </TouchableOpacity>
    );
}

const styles = StyleSheet.create({
    overlay: {
        flex: 1,
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        justifyContent: 'center',
        alignItems: 'center',
        padding: 20,
    },
    content: {
        width: '100%',
        maxWidth: 440,
        backgroundColor: theme.colors.card,
        borderRadius: theme.radii.xl,
        borderColor: theme.colors.border,
        borderWidth: 1,
        padding: 20,
        maxHeight: '85%',
    },
    header: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 14,
    },
    title: {
        color: theme.colors.foreground,
        fontSize: 17,
        fontWeight: '700',
        letterSpacing: -0.3,
    },
    description: {
        color: theme.colors.mutedForeground,
        fontSize: 12,
        marginTop: 2,
    },
    closeBtn: {
        width: 28,
        height: 28,
        borderRadius: 14,
        backgroundColor: theme.colors.background,
        borderColor: theme.colors.border,
        borderWidth: 1,
        justifyContent: 'center',
        alignItems: 'center',
    },
    closeBtnText: {
        color: theme.colors.mutedForeground,
        fontSize: 13,
    },
});

