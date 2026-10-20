export const theme = {
    colors: {
        background: '#0B0F19',         // OLED Deep Space Black
        foreground: '#FFFFFF',         // Pure White
        card: '#151D2A',               // CleanTV Elevated Surface
        cardForeground: '#FFFFFF',
        popover: '#151D2A',
        popoverForeground: '#FFFFFF',
        primary: '#0284C7',            // CleanTV Sky Blue Accent
        primaryForeground: '#FFFFFF',
        secondary: '#1E293B',          // Slate Dark Surface
        secondaryForeground: '#F1F5F9',
        muted: '#1E293B',
        mutedForeground: '#94A3B8',    // Slate Dim Text
        accent: '#38BDF8',             // Light Sky Accent
        accentForeground: '#0B0F19',
        destructive: '#EF4444',        // Red
        destructiveForeground: '#FFFFFF',
        border: '#1E293B',             // Crisp Low-Contrast Border
        borderSubtle: '#334155',       // Highlight Border
        input: '#151D2A',
        ring: '#0284C7',
    },
    radii: {
        sm: 0,
        md: 0,
        lg: 0,
        xl: 0,
        full: 0,
    }
} as const;

export type Theme = typeof theme;

