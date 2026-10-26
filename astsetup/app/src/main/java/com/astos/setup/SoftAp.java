package com.astos.setup;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * SoftAp — the AstSetup_xxxx setup hotspot (v2.4).
 *
 * Setup is FULLY phone-driven now: the tablet opens its own WiFi hotspot,
 * the user connects their phone to it, scans the QR / opens the localhost
 * URL and pushes the whole configuration (home WiFi credentials, city, AI
 * key, theme) from the phone page. The SSID carries the device's unique id:
 * "AstSetup_xxxx" where xxxx = last 4 hex of ANDROID_ID.
 *
 * Android 7.1 mechanics (all reflection — the APIs are hidden but fully
 * functional on N, and AstSetup is platform-signed in the ROM):
 *   - WifiManager.setWifiApConfiguration(WifiConfiguration) — persist SSID
 *   - WifiManager.setWifiApEnabled(null, true)               — bring AP up
 *   - WifiManager.isWifiApEnabled() / getWifiApState()       — poll state
 * The MTK MT8127 exposes the AP as interface "ap0" (per the device tree's
 * wifi.tethering.interface); its address is read live from the interface
 * list, never guessed.
 */
public final class SoftAp {

    public interface Callback {
        void onApReady(String ssid, String ip);
        void onApFailed(String reason);
    }

    private static final long POLL_MS = 500;
    private static final long TIMEOUT_MS = 24_000;
    private static final String FALLBACK_IP = "192.168.43.1";

    /** The SSID the AP is ACTUALLY broadcasting (read back after start;
     *  falls back to ssid(ctx) until known). */
    private static volatile String sApSsid;

    private SoftAp() {}

    /** xxxx — the unique id of this device (last 4 hex of ANDROID_ID). */
    public static String deviceId(Context c) {
        try {
            String aid = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (aid != null && aid.length() >= 4) {
                return aid.substring(aid.length() - 4);
            }
        } catch (Throwable ignored) {}
        return "1000";
    }

    /** The setup hotspot SSID as the user must see it: AstSetup_xxxx,
     *  NEVER with quote characters. */
    public static String ssid(Context c) {
        String s = sApSsid;
        return (s != null && !s.isEmpty()) ? s : "AstSetup_" + deviceId(c);
    }

    /** True while the fallback (LAN) pairing mode is active — display hook. */
    public static boolean isLanFallback() { return sLanFallback; }
    private static volatile boolean sLanFallback;

    // poll bookkeeping so stop() can cancel a running watcher
    private static volatile Handler sPollHandler;
    private static volatile Runnable sPollTask;

    /**
     * v3.1: the SSID for the ACCESS-POINT config must be UNQUOTED on
     * Android 7.1. Client WifiConfigurations quote the SSID
     * (\"AstSetup_xxxx\") because wpa_supplicant wants that on the wire —
     * but the softap path passes the stored value to hostapd as-is, so a
     * quoted value was broadcast LITERALLY: phones saw a network called
     * \"AstSetup_xxxx\" with quote characters in the name (the reported
     * bug). Only ever display ssid(); never cfg.SSID.
     */
    public static void start(final Context ctx, final Callback cb) {
        final WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            cb.onApFailed("no wifi service");
            return;
        }

        WifiConfiguration cfg = new WifiConfiguration();
        cfg.SSID = ssid(ctx);                     // UNQUOTED — see the v3.1 note
        cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);

        // 1) persist our SSID as THE ap configuration (7.1 hidden api)
        boolean cfgOk = false;
        try {
            Object r = WifiManager.class
                    .getMethod("setWifiApConfiguration", WifiConfiguration.class)
                    .invoke(wm, cfg);
            cfgOk = !(r instanceof Boolean) || (Boolean) r;   // some builds return void
            // read back what was actually stored and unquote it for display
            Object stored = WifiManager.class
                    .getMethod("getWifiApConfiguration")
                    .invoke(wm);
            if (stored instanceof WifiConfiguration) {
                String s = ((WifiConfiguration) stored).SSID;
                if (s != null) {
                    sApSsid = s.replace("\"", "");
                }
            }
        } catch (Throwable ignored) {}

        // 2) bring the AP up — with the STORED config when the persist worked
        //    (cleanest path; a fresh quoted cfg here is what leaked quotes)
        boolean started = false;
        try {
            Object r = WifiManager.class
                    .getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                    .invoke(wm, cfgOk ? null : cfg, Boolean.TRUE);
            started = !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            started = false;
        }
        if (!started) {
            // second chance: tether-control path with the stored/default config
            try {
                Object r = WifiManager.class
                        .getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                        .invoke(wm, null, Boolean.TRUE);
                started = !(r instanceof Boolean) || (Boolean) r;
            } catch (Throwable ignored) {}
        }
        if (!started) {
            cb.onApFailed("setWifiApEnabled refused (side-loaded build?)");
            return;
        }

        // 3) poll until the AP is up and ap0/wlan0 carries an address
        final Handler h = new Handler(Looper.getMainLooper());
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() {
            boolean reported = false;
            @Override public void run() {
                boolean up = false;
                try {
                    Object r = WifiManager.class.getMethod("isWifiApEnabled")
                            .invoke(wm);
                    up = (r instanceof Boolean) && (Boolean) r;
                } catch (Throwable ignored) {}

                String ip = hotspotIp();
                long waited = android.os.SystemClock.elapsedRealtime() - t0;

                if (up && ip != null) {
                    if (!ip.equals(sReportedIp)) {
                        sReportedIp = ip;
                        cb.onApReady(ssid(ctx), ip);  // re-fires with the real
                                                      // address after a
                                                      // fallback guess
                    }
                    // keep watching for 30 more seconds: some devices take
                    // a moment to settle on their final softap address
                    if (waited < 54_000) h.postDelayed(this, POLL_MS * 2);
                    return;
                }
                if (waited >= TIMEOUT_MS) {
                    if (up) {
                        if (!reported) {
                            reported = true;
                            sReportedIp = FALLBACK_IP;
                            cb.onApReady(ssid(ctx), FALLBACK_IP); // slow dhcp —
                            // the poller above refreshes the QR when the
                            // true address shows up
                        }
                        h.postDelayed(this, POLL_MS * 2);
                        return;
                    }
                    sLanFallback = true;
                    cb.onApFailed("hotspot did not come up");
                    return;
                }
                h.postDelayed(this, POLL_MS);
            }
        };
        sPollHandler = h;
        sPollTask = poll[0];
        h.postDelayed(poll[0], POLL_MS);
    }

    private static volatile String sReportedIp = null;

    /** Tear the hotspot down and bring the wifi client back. */
    public static void stop(Context ctx) {
        sReportedIp = null;
        sApSsid = null;
        sLanFallback = false;
        try {
            Handler h = sPollHandler;
            Runnable r = sPollTask;
            if (h != null && r != null) h.removeCallbacks(r);
        } catch (Throwable ignored) {}
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiManager.class
                        .getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                        .invoke(wm, null, Boolean.FALSE);
            }
        } catch (Throwable ignored) {}
        // client wifi back on (joining the home network happens right after)
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) wm.setWifiEnabled(true);
        } catch (Throwable ignored) {}
    }

    /** Live address of the AP interface: ap0 (MTK) first, then wlan0, else any. */
    public static String hotspotIp() {
        try {
            NetworkInterface ap = null, wlan = null, any = null;
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if ("ap0".equals(n)) ap = ni;
                else if (n.startsWith("wlan")) wlan = ni;
                else if (any == null) any = ni;
            }
            if (ap != null) { String ip = firstIpv4(ap); if (ip != null) return ip; }
            if (wlan != null) { String ip = firstIpv4(wlan); if (ip != null) return ip; }
            if (any != null) { String ip = firstIpv4(any); if (ip != null) return ip; }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String firstIpv4(NetworkInterface ni) {
        try {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a.isLoopbackAddress()) continue;
                String s = a.getHostAddress();
                if (s != null && s.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                        && (s.startsWith("192.168.") || s.startsWith("10."))) {
                    return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
