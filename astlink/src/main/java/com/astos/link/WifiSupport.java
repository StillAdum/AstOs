package com.astos.link;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.provider.Settings;

/** Shared station-mode transition. Call only from a worker thread. */
public final class WifiSupport {
    private WifiSupport() {}
    public static WifiManager station(Context context) throws Exception {
        WifiManager w = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (w == null) throw new Exception("Wi-Fi service unavailable");
        // This tablet cannot scan/join while its single radio is hosting setup.
        java.lang.reflect.Method state = WifiManager.class.getMethod("getWifiApState");
        int ap = ((Integer) state.invoke(w)) % 10;
        if (ap == 2 || ap == 3) {
            Object stopped = WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                    .invoke(w, null, false);
            if (Boolean.FALSE.equals(stopped)) throw new Exception("Could not stop setup hotspot");
            long end = SystemClock.elapsedRealtime() + 12000;
            while (((Integer) state.invoke(w)) % 10 != 1) {
                if (SystemClock.elapsedRealtime() > end) throw new Exception("Hotspot is still stopping; retry");
                Thread.sleep(200);
            }
        }
        if (!w.isWifiEnabled() && !w.setWifiEnabled(true)) throw new Exception("Could not enable Wi-Fi");
        long end = SystemClock.elapsedRealtime() + 12000;
        while (!w.isWifiEnabled()) {
            if (SystemClock.elapsedRealtime() > end) throw new Exception("Wi-Fi startup timed out");
            Thread.sleep(200);
        }
        try {
            if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) == 0)
                Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE, 3);
        } catch (Exception ignored) {}
        return w;
    }
    public static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    public static int join(Context context, String ssid, String password) throws Exception {
        if (ssid == null || ssid.isEmpty()) throw new Exception("Enter a network name");
        WifiManager w = station(context);
        WifiConfiguration cfg = new WifiConfiguration();
        cfg.SSID = quote(ssid);
        cfg.hiddenSSID = true; // also supports manually entered hidden networks
        if (password == null || password.isEmpty()) {
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            if (!password.matches("[0-9a-fA-F]{64}") && (password.length() < 8 || password.length() > 63))
                throw new Exception("WPA2 passwords must contain 8–63 characters (or a 64-digit hex key)");
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            cfg.preSharedKey = password.matches("[0-9a-fA-F]{64}") ? password : quote(password);
        }
        java.util.List<WifiConfiguration> saved = w.getConfiguredNetworks();
        int id = -1;
        if (saved != null) for (WifiConfiguration old : saved) {
            if (cfg.SSID.equals(old.SSID)) { cfg.networkId = old.networkId; id = w.updateNetwork(cfg); break; }
        }
        if (id < 0) { cfg.networkId = -1; id = w.addNetwork(cfg); }
        if (id < 0) throw new Exception("Could not save network; use a 2.4 GHz WPA/WPA2-Personal network");
        w.disconnect();
        if (!w.enableNetwork(id, true) || !w.reconnect()) throw new Exception("Wi-Fi connection request refused");
        w.saveConfiguration();
        return id;
    }
}
