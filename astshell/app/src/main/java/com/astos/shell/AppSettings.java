package com.astos.shell;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny typed wrapper over SharedPreferences (AstShell settings). */
public final class AppSettings {

    private static final String PREFS = "astos";

    private AppSettings() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Music / AI providers -------------------------------------------------

    /** Groq key: shared with the setup wizard via Settings.Global (both
     *  apps are platform-signed; Global is the shared store). */
    public static String groqKey(Context c) {
        String g = "";
        try {
            g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "astos_groq_key");
        } catch (Throwable ignored) {}
        if (g == null) g = "";
        if (!g.isEmpty()) return g;
        String v = p(c).getString("groq_key", "");
        if (!v.isEmpty()) return v;
        return "";
    }

    public static void setGroqKey(Context c, String v) {
        v = v == null ? "" : v.trim();
        p(c).edit().putString("groq_key", v).apply();
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(),
                    "astos_groq_key", v);
        } catch (Throwable ignored) {}
    }

    // ---- Home Assistant (shared with the setup wizard via Global) ------------

    public static String haUrl(Context c) {
        String g = "";
        try {
            g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "astos_ha_url");
        } catch (Throwable ignored) {}
        if (g != null && !g.isEmpty()) return g;
        return p(c).getString("ha_url", "");
    }
    public static void setHaUrl(Context c, String v) {
        v = v == null ? "" : v;
        p(c).edit().putString("ha_url", v).apply();
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(), "astos_ha_url", v);
        } catch (Throwable ignored) {}
    }

    public static String haToken(Context c) {
        String g = "";
        try {
            g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "astos_ha_token");
        } catch (Throwable ignored) {}
        if (g != null && !g.isEmpty()) return g;
        return p(c).getString("ha_token", "");
    }
    public static void setHaToken(Context c, String v) {
        v = v == null ? "" : v;
        p(c).edit().putString("ha_token", v).apply();
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(), "astos_ha_token", v);
        } catch (Throwable ignored) {}
    }

    // ---- Look & feel ------------------------------------------------------------

    public static String palette(Context c) {
        String g = null;
        try {
            g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "astos_palette");
        } catch (Throwable ignored) {}
        if (g != null && !g.isEmpty()) return g;
        return p(c).getString("palette", AsciiTheme.PALETTES[0].name);
    }

    public static void setPalette(Context c, String v) {
        p(c).edit().putString("palette", v).apply();
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(),
                    "astos_palette", v);
        } catch (Throwable ignored) {}
    }

    public static String bgMode(Context c) {
        String g = null;
        try {
            g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "astos_bg");
        } catch (Throwable ignored) {}
        if (g != null && !g.isEmpty()) return g;
        return p(c).getString("bg_mode", "RAIN");
    }

    public static void setBgMode(Context c, String v) {
        p(c).edit().putString("bg_mode", v).apply();
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(),
                    "astos_bg", v);
        } catch (Throwable ignored) {}
    }

    /** font px for the ASCII grid: 16 (small), 20 (medium), 24 (large) */
    public static int fontPx(Context c)                  { return p(c).getInt("font_px", 20); }
    public static void setFontPx(Context c, int v)       { p(c).edit().putInt("font_px", v).apply(); }

    /** 0 = animations off (static screen), 1 = full animations */
    public static boolean animations(Context c)          { return p(c).getBoolean("animations", true); }
    public static void setAnimations(Context c, boolean v) { p(c).edit().putBoolean("animations", v).apply(); }

    /** render album covers as ASCII art instead of bitmap */
    public static boolean asciiCover(Context c)          { return p(c).getBoolean("ascii_cover", false); }
    public static void setAsciiCover(Context c, boolean v) { p(c).edit().putBoolean("ascii_cover", v).apply(); }

    // ---- Weather -----------------------------------------------------------------

    public static String city(Context c) {
        String g = null;
        try {
            g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "astos_city");
        } catch (Throwable ignored) {}
        if (g != null && !g.isEmpty()) return g;
        return p(c).getString("city", "");
    }

    public static void setCity(Context c, String v) {
        p(c).edit().putString("city", v).apply();
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(),
                    "astos_city", v);
        } catch (Throwable ignored) {}
    }

    public static float lat(Context c) {
        try {
            float g = android.provider.Settings.Global.getFloat(
                    c.getContentResolver(), "astos_lat", Float.NaN);
            if (!Float.isNaN(g)) return g;
        } catch (Throwable ignored) {}
        return p(c).getFloat("lat", Float.NaN);
    }

    public static float lon(Context c) {
        try {
            float g = android.provider.Settings.Global.getFloat(
                    c.getContentResolver(), "astos_lon", Float.NaN);
            if (!Float.isNaN(g)) return g;
        } catch (Throwable ignored) {}
        return p(c).getFloat("lon", Float.NaN);
    }

    public static void setGeo(Context c, float la, float lo) {
        p(c).edit().putFloat("lat", la).putFloat("lon", lo).apply();
        try {
            android.provider.Settings.Global.putFloat(c.getContentResolver(), "astos_lat", la);
            android.provider.Settings.Global.putFloat(c.getContentResolver(), "astos_lon", lo);
        } catch (Throwable ignored) {}
    }

    public static String resolvedCity(Context c) {
        String g = null;
        try {
            g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "astos_city_resolved");
        } catch (Throwable ignored) {}
        if (g != null && !g.isEmpty()) return g;
        return p(c).getString("city_resolved", "");
    }

    public static void setResolvedCity(Context c, String v) {
        p(c).edit().putString("city_resolved", v).apply();
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(),
                    "astos_city_resolved", v);
        } catch (Throwable ignored) {}
    }

    // ---- Behaviour -------------------------------------------------------------------

    public static boolean kiosk(Context c)             { return p(c).getBoolean("kiosk", true); }
    public static void setKiosk(Context c, boolean v)  { p(c).edit().putBoolean("kiosk", v).apply(); }

    public static boolean keepScreenOn(Context c)             { return p(c).getBoolean("keep_screen", true); }
    public static void setKeepScreenOn(Context c, boolean v)  { p(c).edit().putBoolean("keep_screen", v).apply(); }

    public static boolean speakEnabled(Context c)             { return p(c).getBoolean("speak", true); }
    public static void setSpeakEnabled(Context c, boolean v)  { p(c).edit().putBoolean("speak", v).apply(); }

    public static boolean bootLaunch(Context c)             { return p(c).getBoolean("boot_launch", true); }
    public static void setBootLaunch(Context c, boolean v)  { p(c).edit().putBoolean("boot_launch", v).apply(); }

    public static boolean haEnabled(Context c)             { return p(c).getBoolean("ha_enabled", true); }
    public static void setHaEnabled(Context c, boolean v) { p(c).edit().putBoolean("ha_enabled", v).apply(); }
}
