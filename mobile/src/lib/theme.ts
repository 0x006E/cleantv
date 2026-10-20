export const theme = {
    colors: {
        background: '#09090b',         // zinc-950
        foreground: '#fafafa',         // zinc-50
        card: '#18181b',               // zinc-900
        cardForeground: '#fafafa',
        popover: '#18181b',
        popoverForeground: '#fafafa',
        primary: '#10b981',            // emerald-500
        primaryForeground: '#09090b',
        secondary: '#27272a',          // zinc-800
        secondaryForeground: '#f4f4f5',
        muted: '#27272a',
        mutedForeground: '#a1a1aa',    // zinc-400
        accent: '#27272a',
        accentForeground: '#fafafa',
        destructive: '#ef4444',        // red-500
        destructiveForeground: '#fafafa',
        border: '#27272a',             // zinc-800
        borderSubtle: '#3f3f46',       // zinc-700
        input: '#27272a',
        ring: '#10b981',
    },
    radii: {
        sm: 6,
        md: 8,
        lg: 12,
        xl: 16,
        full: 9999,
    }
} as const;

export type Theme = typeof theme;

