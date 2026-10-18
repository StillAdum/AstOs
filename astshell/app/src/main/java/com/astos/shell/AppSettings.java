package com.astos.shell;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny typed wrapper over SharedPreferences. */
public final class AppSettings {

    private static final String PREFS = "astos";

    private AppSettings() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Home Assistant
    public static String haUrl(Context c)            { return p(c).getString("ha_url", ""); }
    public static void setHaUrl(Context c, String v) { p(c).edit().putString("ha_url", v).apply(); }

    public static String haToken(Context c)            { return p(c).getString("ha_token", ""); }
    public static void setHaToken(Context c, String v) { p(c).edit().putString("ha_token", v).apply(); }

    // Weather
    public static String city(Context c)             { return p(c).getString("city", ""); }
    public static void setCity(Context c, String v)  { p(c).edit().putString("city", v).apply(); }

    public static float lat(Context c)                { return p(c).getFloat("lat", Float.NaN); }
    public static float lon(Context c)                { return p(c).getFloat("lon", Float.NaN); }
    public static void setGeo(Context c, float la, float lo) {
        p(c).edit().putFloat("lat", la).putFloat("lon", lo).apply();
    }

    public static String resolvedCity(Context c)              { return p(c).getString("city_resolved", ""); }
    public static void setResolvedCity(Context c, String v)   { p(c).edit().putString("city_resolved", v).apply(); }

    // Behaviour
    public static boolean kiosk(Context c)             { return p(c).getBoolean("kiosk", true); }
    public static void setKiosk(Context c, boolean v)  { p(c).edit().putBoolean("kiosk", v).apply(); }

    public static boolean keepScreenOn(Context c)             { return p(c).getBoolean("keep_screen", true); }
    public static void setKeepScreenOn(Context c, boolean v)  { p(c).edit().putBoolean("keep_screen", v).apply(); }

    public static boolean speakEnabled(Context c)             { return p(c).getBoolean("speak", true); }
    public static void setSpeakEnabled(Context c, boolean v)  { p(c).edit().putBoolean("speak", v).apply(); }

    public static boolean bootLaunch(Context c)             { return p(c).getBoolean("boot_launch", true); }
    public static void setBootLaunch(Context c, boolean v)  { p(c).edit().putBoolean("boot_launch", v).apply(); }
}
