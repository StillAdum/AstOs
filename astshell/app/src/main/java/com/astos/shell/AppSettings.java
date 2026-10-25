package com.astos.shell;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tiny typed settings wrapper (AstShell).
 *
 * Values the first-boot wizard (AstSetup, a separate platform-signed
 * app) collects arrive through Settings.Global — the shared store both
 * apps can see. Everything else lives in local SharedPreferences.
 */
public final class AppSettings {

    private static final String PREFS = "astos";

    private AppSettings() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String global(Context c, String key) {
        try {
            String g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), key);
            if (g != null && !g.isEmpty()) return g;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void setGlobal(Context c, String key, String v) {
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(), key, v);
        } catch (Throwable ignored) {}
    }

    // ---- Music / AI providers ----------------------------------------------

    public static String groqKey(Context c) {
        String g = global(c, "astos_groq_key");
        if (g != null) return g;
        return p(c).getString("groq_key", "");
    }

    public static void setGroqKey(Context c, String v) {
        v = v == null ? "" : v.trim();
        p(c).edit().putString("groq_key", v).apply();
        setGlobal(c, "astos_groq_key", v);
    }

    // ---- Home Assistant -----------------------------------------------------

    public static String haUrl(Context c) {
        String g = global(c, "astos_ha_url");
        if (g != null) return g;
        return p(c).getString("ha_url", "");
    }
    public static void setHaUrl(Context c, String v) {
        v = v == null ? "" : v;
        p(c).edit().putString("ha_url", v).apply();
        setGlobal(c, "astos_ha_url", v);
    }

    public static String haToken(Context c) {
        String g = global(c, "astos_ha_token");
        if (g != null) return g;
        return p(c).getString("ha_token", "");
    }
    public static void setHaToken(Context c, String v) {
        v = v == null ? "" : v;
        p(c).edit().putString("ha_token", v).apply();
        setGlobal(c, "astos_ha_token", v);
    }

    public static boolean haEnabled(Context c)             { return p(c).getBoolean("ha_enabled", true); }
    public static void setHaEnabled(Context c, boolean v) { p(c).edit().putBoolean("ha_enabled", v).apply(); }

    // ---- Look & feel ----------------------------------------------------------

    /** pixel UI theme: dune / forest / rust / mono / ember / pine */
    public static String themeKey(Context c) {
        String g = global(c, "astos_theme");
        if (g != null && Ui.THEMES.containsKey(g)) return g;
        // legacy wizard value: old palette names map onto the closest theme
        String legacy = global(c, "astos_palette");
        if (legacy != null) {
            String l = legacy.toLowerCase(java.util.Locale.US);
            if (l.contains("dark") || l.contains("ember") || l.contains("night")) return "ember";
            if (l.contains("forest") || l.contains("green")) return "forest";
            if (l.contains("rust") || l.contains("terra")) return "rust";
            if (l.contains("mono")) return "mono";
            if (l.contains("pine")) return "pine";
        }
        return p(c).getString("theme", "dune");
    }

    public static void setThemeKey(Context c, String v) {
        p(c).edit().putString("theme", v).apply();
        setGlobal(c, "astos_theme", v);
    }

    public static boolean h24(Context c) {
        try {
            String g = global(c, "astos_h24");
            if (g != null) return "1".equals(g) || "true".equals(g);
        } catch (Throwable ignored) {}
        return p(c).getBoolean("h24", true);
    }
    public static void setH24(Context c, boolean v) {
        p(c).edit().putBoolean("h24", v).apply();
        setGlobal(c, "astos_h24", v ? "1" : "0");
    }

    /** drifting motes on the dotted-paper backdrop */
    public static boolean backdropAnim(Context c) {
        try {
            String g = global(c, "astos_anim");
            if (g != null) return "1".equals(g) || "true".equals(g);
        } catch (Throwable ignored) {}
        return p(c).getBoolean("backdrop_anim", true);
    }
    public static void setBackdropAnim(Context c, boolean v) {
        p(c).edit().putBoolean("backdrop_anim", v).apply();
        setGlobal(c, "astos_anim", v ? "1" : "0");
    }

    // ---- Weather ----------------------------------------------------------------

    public static String city(Context c) {
        String g = global(c, "astos_city");
        if (g != null) return g;
        return p(c).getString("city", "");
    }

    public static void setCity(Context c, String v) {
        p(c).edit().putString("city", v).apply();
        setGlobal(c, "astos_city", v);
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
        String g = global(c, "astos_city_resolved");
        if (g != null) return g;
        return p(c).getString("city_resolved", "");
    }

    public static void setResolvedCity(Context c, String v) {
        p(c).edit().putString("city_resolved", v).apply();
        setGlobal(c, "astos_city_resolved", v);
    }

    // ---- Behaviour -----------------------------------------------------------------

    public static boolean kiosk(Context c)             { return p(c).getBoolean("kiosk", true); }
    public static void setKiosk(Context c, boolean v)  { p(c).edit().putBoolean("kiosk", v).apply(); }

    public static boolean keepScreenOn(Context c)             { return p(c).getBoolean("keep_screen", true); }
    public static void setKeepScreenOn(Context c, boolean v)  { p(c).edit().putBoolean("keep_screen", v).apply(); }

    public static boolean speakEnabled(Context c)             { return p(c).getBoolean("speak", true); }
    public static void setSpeakEnabled(Context c, boolean v)  { p(c).edit().putBoolean("speak", v).apply(); }

    public static boolean bootLaunch(Context c)             { return p(c).getBoolean("boot_launch", true); }
    public static void setBootLaunch(Context c, boolean v)  { p(c).edit().putBoolean("boot_launch", v).apply(); }
}
