package com.astos.shell;

import android.graphics.Color;

/**
 * AstOs ASCII-theme palettes. Every palette is warm by default (the no-blue
 * rule), but the user can switch any of them in Settings — including the
 * ones with blue, because the user chooses.
 */
public final class AsciiTheme {

    public static class Palette {
        public final String name;
        public final int fg;       // main text
        public final int dim;      // secondary text
        public final int accent;    // highlights / logo / buttons
        public final int bg;       // screen background
        public final String mode;   // default background FX for this palette

        Palette(String name, int fg, int dim, int accent, int bg, String mode) {
            this.name = name; this.fg = fg; this.dim = dim;
            this.accent = accent; this.bg = bg; this.mode = mode;
        }
    }

    public static final Palette[] PALETTES = {
        new Palette("Amber CRT",   0xFFF2D9A0, 0xFF8A6F4D, 0xFFFFB84D, 0xFF14100A, "RAIN"),
        new Palette("Cream Paper",  0xFF3B2F24, 0xFF9A8C7A, 0xFFB3541E, 0xFFF2EBDD, "SOLID"),
        new Palette("Ember",        0xFFFFD8B8, 0xFF8C5B43, 0xFFFF6B35, 0xFF1A0E08, "WAVES"),
        new Palette("Mint Terminal",0xFFB8F2D8, 0xFF5F8A78, 0xFF4DE8A8, 0xFF0A1210, "PLASMA"),
        new Palette("Sunset",       0xFFFFE3D0, 0xFFC08A6A, 0xFFFF8A5C, 0xFF241016, "STARS"),
        new Palette("Mono Slate",   0xFFE8E8E8, 0xFF7A7A7A, 0xFFB0B0B0, 0xFF101012, "SCANLINES"),
        new Palette("Cyan Ghost",   0xFFCFF7FF, 0xFF5D8A93, 0xFF4DE0FC, 0xFF081114, "PLASMA"),
        new Palette("Violet Dusk",  0xFFE6D8FF, 0xFF7A6494, 0xFFB279FF, 0xFF120E1C, "STARS"),
    };

    public static final String[] BG_MODES = {
        "SOLID", "STARS", "RAIN", "WAVES", "PLASMA", "SCANLINES", "IMAGE"
    };

    public static Palette byName(String name) {
        if (name != null) {
            for (Palette p : PALETTES) if (p.name.equals(name)) return p;
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
        return Color.rgb(r, g, b);
    }

    private AsciiTheme() {}
}
