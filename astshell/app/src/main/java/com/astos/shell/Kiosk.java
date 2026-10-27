package com.astos.shell;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

/**
 * System-level kiosk lockdown for the AstOs ROM.
 *
 * Everything here degrades gracefully when AstShell is sideloaded on a
 * normal ROM (each block is guarded); when it ships as the ROM's
 * platform-signed privileged system app, every block works:
 *
 *   - immersive policy_control: hides the status bar + nav bar system-wide,
 *     edge swipes can no longer pull up the Android notification shade
 *   - StatusBarManager.disable(): the shade can never be expanded
 *   - LockPatternUtils.setLockScreenDisabled(true): NO lock screen at all,
 *     power-on goes straight to the dashboard
 *   - default IME switched to the built-in AstOs keyboard
 *   - accelerometer rotation off + rotation pinned to landscape
 */
public final class Kiosk {

    public static final String IME_ID = "com.astos.shell/.AstKeyboardService";

    private Kiosk() {}

    /** True when the first-boot setup wizard should run.
     *  astos_setup_done defaults to 0 — a fresh flash ALWAYS gets the wizard. */
    public static boolean needsSetup(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(),
                    "astos_setup_done", 0) != 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** Mark the device provisioned (called when setup completes). */
    public static void markProvisioned(Context c) {
        try {
            Settings.Global.putInt(c.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 1);
        } catch (Exception ignored) {}
        try {
            Settings.Secure.putInt(c.getContentResolver(), "user_setup_complete", 1);
        } catch (Exception ignored) {}
    }

    /** The full lockdown bundle — call on boot and on every home resume. */
    public static void apply(Context c) {
        hideSystemBars(c);
        disableShade(c);
        disableKeyguard(c);
        selectOurKeyboard(c);
        pinLandscape(c);
    }

    /** Public re-assert used by KioskService / HomeActivity heartbeats. */
    public static void disableShadePublic(Context c) {
        disableShade(c);
    }

    /** Public re-assert of the immersive policy_control (KioskService). */
    public static void hideSystemBarsPublic(Context c) {
        hideSystemBars(c);
    }

    /** Hide status + navigation bars everywhere (survives reboot). */
    static void hideSystemBars(Context c) {
        try {
            // framework reads this on window layout: immersive everywhere
            Settings.Global.putString(c.getContentResolver(), "policy_control",
                    "immersive.status=*");
        } catch (Exception ignored) {}
    }

    /** Shade can never expand; no notification icons or ticker anywhere.
     *  StatusBarManager.disable() is hidden API -> reflection (values are the
     *  View.STATUS_BAR_DISABLE_* constants from the 7.1 framework).
     *
     *  IMPORTANT: SystemUI binds these flags to the CALLER's binder token and
     *  clears them when that process dies or when SystemUI itself restarts —
     *  that is why the v2.3 shade still opened sometimes. KioskService now
     *  holds this disable from a persistent foreground process and re-asserts
     *  it on a 20 s heartbeat + SCREEN_ON / TIME_TICK / USER_PRESENT. */
    static void disableShade(Context c) {
        try {
            Object sbm = c.getSystemService("statusbar");
            if (sbm == null) return;
            final int flags = 0x00010000 /* EXPAND */
                    | 0x00020000 /* NOTIFICATION_ICONS */
                    | 0x00080000 /* NOTIFICATION_TICKER */
                    | 0x00100000 /* SYSTEM_INFO */;
            sbm.getClass().getMethod("disable", int.class).invoke(sbm, flags);
            sbm.getClass().getMethod("disable2", int.class).invoke(sbm, 1 /* QUICK_SETTINGS */);
        } catch (Throwable ignored) {
            // side-loaded case or API drift: graceful degradation
        }
    }

    /**
     * Remove the lock screen entirely. LockSettingsService requires the
     * signature permission ACCESS_KEYGUARD_SECURE_STORAGE (declared in the
     * manifest, granted to the platform-signed privileged build).
     */
    static void disableKeyguard(Context c) {
        try {
            Class<?> lpu = Class.forName("com.android.internal.widget.LockPatternUtils");
            Object utils = lpu.getConstructor(Context.class).newInstance(c);
            lpu.getMethod("setLockScreenDisabled", boolean.class, int.class)
                    .invoke(utils, Boolean.TRUE, 0);
        } catch (Throwable ignored) {
            // side-loaded case or pre-7.0 framework: graceful degradation
        }
    }

    /** Make the built-in AstOs keyboard the enabled + default IME. */
    static void selectOurKeyboard(Context c) {
        try {
            String mine = new ComponentName(c, AstKeyboardService.class).flattenToString();
            android.content.ContentResolver cr = c.getContentResolver();
            String enabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
            if (enabled == null || !enabled.contains(mine)) {
                enabled = (enabled == null || enabled.isEmpty()) ? mine : mine + ":" + enabled;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, enabled);
            }
            String cur = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
            if (!mine.equals(cur)) {
                Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, mine);
            }
        } catch (Exception ignored) {}
    }

    /** No accelerometer surprises; rotation pinned to landscape. */
    static void pinLandscape(Context c) {
        try {
            Settings.System.putInt(c.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
        } catch (Exception ignored) {}
        try {
            Settings.System.putInt(c.getContentResolver(),
                    Settings.System.USER_ROTATION, 1 /* landscape */);
        } catch (Exception ignored) {}
    }
}
