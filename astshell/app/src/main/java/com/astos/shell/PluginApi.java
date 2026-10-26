package com.astos.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.Function;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PluginApi — the object scripts see as `astos`. Every capability is gated
 * by a manifest permission:
 *
 *   ui        astos.ui.card(...) / astos.ui.toast(...)
 *   settings  astos.settings.get(key) / set(key, value)
 *   ha        astos.ha.entities() / astos.ha.call(domain, service, id)
 *   media     astos.media.play/pause/next/prev/volumeUp/volumeDown
 *   voice     astos.voice.say(text)
 *   network   astos.http.get(url [, timeoutMs]) / postJson(url, body)
 *   files     astos.files.write(name, text) / read(name) / list()
 *   system    astos.system.shell(cmd) / prop(k[,v]) / input(cmd)
 *             / setting(table, key[, value]) / intent(component)
 *   store     always available: astos.store.get/put (per-plugin prefs)
 *
 * `system` mods also require the master switch (AstOs Settings → Plugins).
 * The `system` grant is the WHOLE-OS modding: shell commands, system
 * properties, secure/global settings and input injection.
 */
public class PluginApi {

    private final PluginHost mHost;
    private final PluginHost.Plugin mPlugin;
    private final Context mCtx;

    PluginApi(PluginHost host, PluginHost.Plugin plugin) {
        mHost = host;
        mPlugin = plugin;
        mCtx = host.context();
    }

    private boolean can(String perm) {
        return mPlugin.has(perm);
    }

    private String deny(String what) {
        return "permission '" + what + "' missing in manifest.json";
    }

    // ---------------------------------------------------------------- store

    public static final class Store {
        private final SharedPreferences mP;
        Store(SharedPreferences p) { mP = p; }
        public String get(String k, String d) { return mP.getString(k, d); }
        public void put(String k, String v) { mP.edit().putString(k, v == null ? "" : v).apply(); }
    }

    /**
     * THE API SHAPE: every capability is a PROPERTY (bean getter), never a
     * call — scripts write astos.ui.card(...), astos.store.get(...),
     * astos.system.shell(...). Rhino turns plain Java methods into
     * functions (a method named ui() would shadow the property); getters
     * are the only form where BOTH the property access works and the
     * permission check fires at access time.
     */
    public Store getStore() {
        return new Store(mCtx.getSharedPreferences(
                "plugin_" + mPlugin.id, Context.MODE_PRIVATE));
    }

    // ---- bean getters: they expose the SAME objects as properties, so
    // scripts can write astos.ui.card(...) instead of astos.ui().card(...)
    // (Rhino turns plain methods into functions, but bean getters into
    // property objects — the example mods use the property form).

    // ---------------------------------------------------------------- device info

    /** Always available: the device's own battery state. */
    public static final class Battery {
        public final int pct;
        public final boolean charging;
        Battery(int p, boolean c) { pct = p; charging = c; }
    }

    /** Always available: uptime + build info (read-only, no permission). */
    public static final class Device {
        public final long uptimeMs;
        public final String model;
        public final String astosVersion;
        Device(long up, String m, String v) { uptimeMs = up; model = m; astosVersion = v; }
    }

    public Device getDevice() {
        return new Device(android.os.SystemClock.elapsedRealtime(),
                android.os.Build.MODEL, com.astos.link.LinkServer.osVersion());
    }

    public Battery getBattery() {
        try {
            android.content.Intent i = mCtx.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            return new Battery(Math.round(level * 100f / scale), plugged != 0);
        } catch (Throwable t) {
            return new Battery(-1, false);
        }
    }

    // ---------------------------------------------------------------- ui

    public static final class Ui {
        private final PluginHost mH;
        private final PluginHost.Plugin mP;
        Ui(PluginHost h, PluginHost.Plugin p) { mH = h; mP = p; }

        /** Push/update a card on the home dashboard's PLUGINS row.
         *  opts: {id, title, value, accent?, onTap?} — onTap is a JS
         *  function invoked when the card is tapped. */
        public void card(Object opts) {
            try {
                JSONObject o = coerce(opts);
                String id = o.optString("id", "card");
                PluginHost.Card c = null;
                for (PluginHost.Card existing : mP.cards) {
                    if (existing.id.equals(id)) { c = existing; break; }
                }
                if (c == null) {
                    c = new PluginHost.Card(mP.id);
                    c.id = id;
                    mP.cards.add(c);
                }
                c.title = o.optString("title", mP.name());
                c.value = o.optString("value", "");
                c.accent = o.optString("accent", "");
                Object tap = o.opt("onTap");
                if (tap instanceof Function) {
                    mP.cardTaps.put(id, (Function) tap);
                }
                mH.cardsChanged();
            } catch (Exception e) {
                PluginHost.Log.e("card(): " + e);
            }
        }

        public void removeCard(String id) {
            for (int i = 0; i < mP.cards.size(); i++) {
                if (mP.cards.get(i).id.equals(id)) { mP.cards.remove(i); break; }
            }
            mP.cardTaps.remove(id);
            mH.cardsChanged();
        }

        public void toast(String msg) { mH.toast(String.valueOf(msg)); }
    }

    public Ui getUi() {
        if (!can("ui")) throw new SecurityException(deny("ui"));
        return new Ui(mHost, mPlugin);
    }


    // ---------------------------------------------------------------- settings

    public static final class Settings {
        private final Context mC;
        Settings(Context c) { mC = c; }

        public String get(String key) {
            if (key.startsWith("astos_")) {
                try {
                    String g = android.provider.Settings.Global.getString(
                            mC.getContentResolver(), key);
                    if (g != null) return g;
                } catch (Exception ignored) {}
            }
            return AppSettings.raw(mC, key);
        }
        /** Only astos_* shell keys (plus theme/h24) — system.* is for the
         *  `system` permission. */
        public void set(String key, String value) {
            if (!key.startsWith("astos_")) throw new SecurityException(
                    "settings.set only allows astos_* keys");
            try {
                android.provider.Settings.Global.putString(
                        mC.getContentResolver(), key, value);
            } catch (Exception e) {
                throw new RuntimeException("set refused: " + e);
            }
            AppSettings.setRaw(mC, key, value);
        }
    }

    public Settings getSettings() {
        if (!can("settings")) throw new SecurityException(deny("settings"));
        return new Settings(mCtx);
    }

    // ---------------------------------------------------------------- ha

    public static final class Ha {
        private final Context mC;
        Ha(Context c) { mC = c; }

        public List<Map<String, Object>> entities() {
            final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            final Object[] res = new Object[1];
            Thread t = new Thread(new Runnable() {
                @Override public void run() { res[0] = HomeAssistantClient.blockingEntities(mC); }
            });
            t.start();
            try { t.join(6000); } catch (Exception ignored) {}
            if (res[0] instanceof List) {
                for (Object o : (List<?>) res[0]) {
                    HomeAssistantClient.Entity e = (HomeAssistantClient.Entity) o;
                    java.util.HashMap<String, Object> m = new java.util.HashMap<String, Object>();
                    m.put("id", e.id);
                    m.put("name", e.name);
                    m.put("domain", e.domain);
                    m.put("on", e.on);
                    m.put("scene", e.isScene);
                    out.add(m);
                }
            }
            return out;
        }

        public boolean call(String domain, String service, String entityId) {
            final Object[] res = new Object[1];
            final String d = domain == null ? "light" : domain;
            final String s = service == null ? "toggle" : service;
            HomeAssistantClient.callBlocking(mC, d, s, entityId,
                    new HomeAssistantClient.Callback<Boolean>() {
                        @Override public void onResult(Boolean ok, String err) { res[0] = ok; }
                    });
            return Boolean.TRUE.equals(res[0]);
        }
    }

    public Ha getHa() {
        if (!can("ha")) throw new SecurityException(deny("ha"));
        return new Ha(mCtx);
    }

    // ---------------------------------------------------------------- media

    public static final class Media {
        private final Context mC;
        Media(Context c) { mC = c; }
        public void play()   {
            if (MediaControlService.hasActive()) MediaControlService.play();
            else MusicEngine.toggle(mC);
        }
        public void pause() {
            if (MediaControlService.hasActive()) MediaControlService.pause();
            else MusicEngine.toggle(mC);
        }
        public void next()  { if (MediaControlService.hasActive()) MediaControlService.next(); else MusicEngine.next(mC); }
        public void prev()  { if (MediaControlService.hasActive()) MediaControlService.prev(); else MusicEngine.prev(mC); }
        public void volumeUp()   { MediaControlService.volumeUp(mC); }
        public void volumeDown() { MediaControlService.volumeDown(mC); }
    }

    public Media getMedia() {
        if (!can("media")) throw new SecurityException(deny("media"));
        return new Media(mCtx);
    }

    // ---------------------------------------------------------------- voice

    public static final class Voice {
        private final Context mC;
        Voice(Context c) { mC = c; }
        public void say(String text) {
            if (text == null || text.toString().isEmpty()) return;
            Speak.say(mC, String.valueOf(text));
        }
    }

    public Voice getVoice() {
        if (!can("voice")) throw new SecurityException(deny("voice"));
        return new Voice(mCtx);
    }

    // ---------------------------------------------------------------- network

    public static final class Http {
        public String get(String url, int timeoutMs) {
            return fetch(url, "GET", null, timeoutMs);
        }
        public String get(String url) { return get(url, 6000); }
        public String postJson(String url, String json, int timeoutMs) {
            return fetch(url, "POST", json, timeoutMs);
        }
        public String postJson(String url, String json) { return postJson(url, json, 8000); }

        private String fetch(String u, String method, String body, int timeoutMs) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(Math.min(4000, timeoutMs));
                c.setReadTimeout(timeoutMs);
                c.setRequestMethod(method);
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.getOutputStream().write(body.getBytes("UTF-8"));
                }
                int code = c.getResponseCode();
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                c.disconnect();
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException("http: " + e);
            }
        }
    }

    public Http getHttp() {
        if (!can("network")) throw new SecurityException(deny("network"));
        return new Http();
    }

    // ---------------------------------------------------------------- files

    public static final class Files {
        private final Context mC;
        private final PluginHost.Plugin mP;
        Files(Context c, PluginHost.Plugin p) { mC = c; mP = p; }

        private File dir() {
            File d = new File(new File(Environment.getExternalStorageDirectory(),
                    "AstOs/plugin-data"), mP.id);
            d.mkdirs();
            return d;
        }
        private File safe(String name) {
            String n = name.replace("..", "_").replace('/', '_').replace('\\', '_');
            return new File(dir(), n);
        }

        public String write(String name, String text) {
            try {
                File f = safe(name);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write((text == null ? "" : text).getBytes("UTF-8"));
                fo.close();
                return f.getAbsolutePath();
            } catch (Exception e) {
                throw new RuntimeException("write: " + e);
            }
        }

        public String read(String name) {
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(safe(name)), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                r.close();
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        public List<String> list() {
            List<String> out = new ArrayList<String>();
            File[] kids = dir().listFiles();
            if (kids != null) for (File k : kids) out.add(k.getName());
            return out;
        }

        public boolean delete(String name) { return safe(name).delete(); }
    }

    public Files getFiles() {
        if (!can("files")) throw new SecurityException(deny("files"));
        return new Files(mCtx, mPlugin);
    }

    // ---------------------------------------------------------------- system

    /** The whole-OS grant. Needs BOTH the manifest permission and the
     *  master switch in AstOs Settings → Plugins. */
    public static final class System_ {
        private final Context mC;
        System_(Context c) { mC = c; }

        private void gate() {
            if (!AppSettings.pluginsEnabled(mC)) {
                throw new SecurityException("system mods are disabled in AstOs Settings");
            }
        }

        public String shell(String cmd) {
            gate();
            return ShellUtil.run(mC, cmd, 8000);
        }

        public String prop(String key, String valueIfSet) {
            gate();
            if (valueIfSet == null) return SystemProp.get(mC, key);
            return SystemProp.set(mC, key, valueIfSet);
        }

        public String setting(String table, String key, String valueIfSet) {
            gate();
            try {
                android.content.ContentResolver cr = mC.getContentResolver();
                if (valueIfSet == null) {
                    if ("global".equals(table))
                        return android.provider.Settings.Global.getString(cr, key);
                    if ("secure".equals(table))
                        return android.provider.Settings.Secure.getString(cr, key);
                    return android.provider.Settings.System.getString(cr, key);
                }
                if ("global".equals(table))
                    android.provider.Settings.Global.putString(cr, key, valueIfSet);
                else if ("secure".equals(table))
                    android.provider.Settings.Secure.putString(cr, key, valueIfSet);
                else
                    android.provider.Settings.System.putString(cr, key, valueIfSet);
                return valueIfSet;
            } catch (Exception e) {
                throw new RuntimeException("setting refused: " + e);
            }
        }

        public String input(String cmd) {
            gate();
            if (!(cmd.startsWith("keyevent ") || cmd.startsWith("text ")
                    || cmd.startsWith("tap ") || cmd.startsWith("swipe "))) {
                throw new SecurityException("input allows keyevent/text/tap/swipe only");
            }
            return ShellUtil.run(mC, "input " + cmd, 6000);
        }

        public String intent(String component) {
            gate();
            if (component.contains(";") || component.contains(" ")) {
                throw new SecurityException("bad component");
            }
            return ShellUtil.run(mC, "am start -n " + component, 8000);
        }
    }

    public System_ getSystem() {
        if (!can("system")) throw new SecurityException(deny("system"));
        return new System_(mCtx);
    }

    // ---------------------------------------------------------------- helpers

    static JSONObject coerce(Object o) throws Exception {
        if (o instanceof CharSequence) return new JSONObject(o.toString());
        // a NativeObject from Rhino reaches us through Context.jsToJava
        Object conv = org.mozilla.javascript.Context.jsToJava(o, Map.class);
        if (conv instanceof Map) {
            JSONObject j = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) conv).entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map || v instanceof List) {
                    v = new org.json.JSONArray(new java.util.ArrayList<Object>(
                            ((java.util.Collection<?>) v)));
                    // nested maps: flatten one level via string form
                    v = org.mozilla.javascript.Context.jsToJava(v, Object.class);
                }
                j.put(String.valueOf(e.getKey()), v instanceof CharSequence
                        ? String.valueOf(v) : v);
            }
            return j;
        }
        return new JSONObject();
    }

    static JSONArray coerceArray(Object o) throws Exception {
        Object conv = org.mozilla.javascript.Context.jsToJava(o, List.class);
        JSONArray a = new JSONArray();
        if (conv instanceof List) {
            for (Object v : (List<?>) conv) a.put(String.valueOf(v));
        }
        return a;
    }
}
