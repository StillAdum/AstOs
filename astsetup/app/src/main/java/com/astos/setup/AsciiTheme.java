package com.astos.setup;

/**
 * AstSetup theme palettes — the six pixel themes shared with AstShell's
 * Ui engine (same names/keys, so astos_theme round-trips between apps).
 *
 * Default is Dune: warm paper, ink text, terracotta accent — the approved
 * mock palette. Mode "DOTS" is the dotted-paper backdrop.
 */
public final class AsciiTheme {

    public static class Palette {
        public final String name;    // == AstShell Ui theme key
        public final int fg;        // ink
        public final int dim;       // soft
        public final int accent;
        public final int bg;
        public final int panel;      // card surface
        public final int on;        // sage "active" tint
        public final String mode;   // default background FX

        Palette(String name, int fg, int dim, int accent, int bg, int panel, int on, String mode) {
            this.name = name; this.fg = fg; this.dim = dim;
            this.accent = accent; this.bg = bg; this.panel = panel; this.on = on; this.mode = mode;
        }
    }

    public static final Palette[] PALETTES = {
            new Palette("dune",   0xFF2A241C, 0xFF9A8D78, 0xFFC4742A, 0xFFEFE7D8, 0xFFF8F2E4, 0xFFCFDCA6, "DOTS"),
            new Palette("forest", 0xFF1F2819, 0xFF7C8C6A, 0xFF8A5A22, 0xFFE6EAD8, 0xFFF2F4E6, 0xFFBCD69A, "DOTS"),
            new Palette("rust",   0xFF2B1C15, 0xFF957462, 0xFFB9481F, 0xFFF0E2D6, 0xFFF9EEE4, 0xFFF0C9A0, "DOTS"),
            new Palette("mono",   0xFF1C1C1C, 0xFF8C8C88, 0xFF2E2E2E, 0xFFE9E8E3, 0xFFF6F5F0, 0xFFC9C9C2, "DOTS"),
            new Palette("ember",  0xFFEFE2CB, 0xFF8A7B62, 0xFFE39A48, 0xFF15110D, 0xFF1E1812, 0xFF4A421F, "DOTS"),
            new Palette("pine",   0xFFE3EAD6, 0xFF7D8D72, 0xFFD2A94F, 0xFF101510, 0xFF182118, 0xFF2F4A28, "DOTS"),
    };

    /** legacy bg modes kept for compatibility; DOTS is the pixel default */
    public static final String[] BG_MODES = {
            "DOTS", "SOLID", "STARS", "RAIN", "WAVES", "PLASMA", "SCANLINES", "IMAGE"
    };

    public static Palette byName(String name) {
        if (name != null) {
            for (Palette p : PALETTES) if (p.name.equals(name)) return p;
            // legacy 2.x palette names -> nearest pixel theme
            String l = name.toLowerCase();
            if (l.contains("cream")) return PALETTES[0];
            if (l.contains("ember") || l.contains("sunset")) return PALETTES[4];
            if (l.contains("mint") || l.contains("phosphor")) return PALETTES[1];
            if (l.contains("mono")) return PALETTES[3];
        }
        return PALETTES[0];
    }

    /** Dim a color toward the background for subtle background FX. */
    public static int dimColor(int color, float factor, int bg) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int br = (bg >> 16) & 0xFF, bgG = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        r = (int) (br + (r - br) * factor);
        g = (int) (bgG + (g - bgG) * factor);
        b = (int) (bb + (b - bb) * factor);
        return (r << 16) | (g << 8) | b | 0xFF000000;
    }

    private AsciiTheme() {}
}
