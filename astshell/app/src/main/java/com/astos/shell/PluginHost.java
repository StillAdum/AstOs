package com.astos.shell;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PluginHost — the .astpl mod system.
 *
 * An .astpl file is a ZIP:
 *   manifest.json  {"id","name","version","author","description",
 *                   "permissions":["ui","settings","ha","media","voice",
 *                                 "network","files","system"],
 *                   "main":"plugin.js", "tickSeconds":30}
 *   plugin.js       the mod itself (JavaScript, runs on Rhino)
 *   assets/…        optional files, extracted beside the plugin
 *
 * Trusted scripts have unrestricted Java interop within AstShell's OS
 * permissions. They are NOT isolated from this process. Watchdogs catch
 * interpreted loops, but cannot safely terminate blocking Java/native code.
 */
public final class PluginHost {

    // ------------------------------------------------------------- model

    public static final class Card {
        public final String pluginId;
        public String id, title, value, accent;
        public Card(String pluginId) { this.pluginId = pluginId; }
    }

    public interface Listener {
        void onCardsChanged();
        void onToast(String msg);
    }

    static final class Plugin {
        final String id;
        final File dir;
        final JSONObject manifest;
        String error = "";
        boolean enabled;
        Scriptable scope;          // null until loaded
        boolean loadFailed;
        Function onLoad, onUnload, onTick, onVoice, onCommand, onCardTap;
        long lastTickMs;
        final Map<String, Function> cardTaps = new HashMap<String, Function>();
        final List<Card> cards = new ArrayList<Card>();

        Plugin(String id, File dir, JSONObject manifest, boolean enabled) {
            this.id = id; this.dir = dir; this.manifest = manifest;
            this.enabled = enabled;
        }
        boolean has(String perm) {
            try {
                JSONArray a = manifest.optJSONArray("permissions");
                if (a == null) return false;
                for (int i = 0; i < a.length(); i++)
                    if (perm.equals(a.optString(i))) return true;
            } catch (Exception ignored) {}
            return false;
        }
        long tickSeconds() { return Math.max(5, manifest.optLong("tickSeconds", 30)); }
        String name() { return manifest.optString("name", id); }
        String version() { return manifest.optString("version", "?"); }
        String author() { return manifest.optString("author", ""); }
        String[] permissions() {
            JSONArray a = manifest.optJSONArray("permissions");
            int n = a == null ? 0 : a.length();
            String[] out = new String[n];
            for (int i = 0; i < n; i++) out[i] = a.optString(i);
            return out;
        }
    }

    // ------------------------------------------------------------- state

    private static volatile PluginHost sInstance;

    public static synchronized PluginHost init(android.content.Context ctx) {
        PluginHost h = sInstance;
        if (h == null) sInstance = h = new PluginHost(ctx.getApplicationContext());
        return h;
    }

    public static PluginHost get() { return sInstance; }

    private final android.content.Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExec = Executors.newSingleThreadExecutor();
    private final Map<String, Plugin> mPlugins = new LinkedHashMap<String, Plugin>();
    private volatile Listener mListener;
    private boolean mVoiceHooked;

    private PluginHost(android.content.Context ctx) { mCtx = ctx; }

    /** App context — used by PluginApi. */
    android.content.Context context() { return mCtx; }

    public void setListener(Listener l) { mListener = l; }

    private File root() { return new File(mCtx.getFilesDir(), "plugins"); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences("plugins", android.content.Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- install

    /** Install a .astpl from anywhere on storage. Returns the id or throws. */
    public synchronized String install(File zipFile) throws Exception {
        JSONObject mf = readManifest(zipFile);
        final String id = mf.optString("id", "");
        if (!id.matches("[a-z0-9_]{2,32}"))
            throw new IllegalArgumentException("bad manifest id '" + id + "' (a-z 0-9 _)");
        if (!mf.has("name")) throw new IllegalArgumentException("manifest has no name");
        File dir = new File(root(), id);
        // wipe any previous install of this id
        deleteRecursive(dir);
        dir.mkdirs();
        try (ZipFile z = new ZipFile(zipFile)) {
            String main = mf.optString("main", "plugin.js");
            boolean sawMain = false;
            java.util.Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                String n = en.getName();
                if (en.isDirectory() || n.contains("..")) continue;
                File out = new File(dir, n);
                if (n.contains("/")) out.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(en);
                     FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] b = new byte[16384]; int r;
                    while ((r = in.read(b)) > 0) fo.write(b, 0, r);
                }
                if (n.equals(main)) sawMain = true;
            }
            if (!sawMain) throw new IllegalArgumentException("zip has no " + main);
        }
        boolean hasSystem = false;
        JSONArray pa = mf.optJSONArray("permissions");
        if (pa != null) for (int i = 0; i < pa.length(); i++)
            if ("system".equals(pa.optString(i))) hasSystem = true;
        // system-grade mods start DISABLED: the user flips them on knowingly
        boolean enabled = false;
        prefs().edit().putBoolean("on_" + id, false).putBoolean("trusted_" + id, false).apply();
        Plugin p = new Plugin(id, dir, mf, enabled);
        mPlugins.put(id, p);
        if (enabled) load(p);
        return id;
    }

    private static JSONObject readManifest(File zip) throws Exception {
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry e = z.getEntry("manifest.json");
            if (e == null) throw new IllegalArgumentException("no manifest.json in .astpl");
            BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return new JSONObject(sb.toString());
        }
    }

    public synchronized boolean remove(String id) {
        Plugin p = mPlugins.remove(id);
        if (p == null) return false;
        unloadQuietly(p);
        prefs().edit().remove("on_" + id).apply();
        deleteRecursive(p.dir);
        notifyCards();
        return true;
    }

    public synchronized boolean setEnabled(String id, boolean on) {
        Plugin p = mPlugins.get(id);
        if (p == null) return false;
        if (on && !AppSettings.pluginsEnabled(mCtx)) {
            return false;   // system mods need the master switch too
        }
        prefs().edit().putBoolean("on_" + id, on).putBoolean("trusted_" + id, on).apply();
        p.enabled = on;
        if (on) { load(p); } else { unloadQuietly(p); p.cards.clear(); }
        notifyCards();
        return true;
    }

    // ------------------------------------------------------------- discovery

    public synchronized void reloadAll() {
        Map<String, Plugin> old = new LinkedHashMap<String, Plugin>(mPlugins);
        mPlugins.clear();
        File[] dirs = root().listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                File mf = new File(d, "manifest.json");
                if (!mf.isFile()) continue;
                try (FileInputStream fi = new FileInputStream(mf)) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(fi, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                    Plugin p = new Plugin(d.getName(), d, new JSONObject(sb.toString()),
                            prefs().getBoolean("on_" + d.getName(), false) && prefs().getBoolean("trusted_" + d.getName(), false) && AppSettings.pluginsEnabled(mCtx));
                    mPlugins.put(p.id, p);
                } catch (Exception ignored) {}
            }
        }
        // keep runtime error state of same-id plugins
        for (Plugin p : mPlugins.values()) {
            Plugin prev = old.get(p.id);
            if (prev != null) { p.error = prev.error; p.loadFailed = prev.loadFailed; }
            if (p.enabled) load(p);
        }
        notifyCards();
    }

    public synchronized List<Plugin> list() {
        return new ArrayList<Plugin>(mPlugins.values());
    }

    public synchronized Plugin find(String id) { return mPlugins.get(id); }

    public synchronized List<Card> cards() {
        List<Card> out = new ArrayList<Card>();
        for (Plugin p : mPlugins.values())
            if (p.enabled) out.addAll(p.cards);
        return out;
    }

    // ------------------------------------------------------------- runtime

    private void load(final Plugin p) {
        p.loadFailed = false;
        p.error = "";
        runIsolated(p, "load", new Job() {
            @Override public void run(Context cx, ScriptableObject shared) throws Exception {
                if (p.scope == null) {
                    Scriptable scope = cx.initStandardObjects();
                    File main = new File(p.dir, p.manifest.optString("main", "plugin.js"));
                    StringBuilder sb = new StringBuilder();
                    try (FileInputStream fi = new FileInputStream(main)) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(fi, "UTF-8"));
                        String l;
                        while ((l = r.readLine()) != null) sb.append(l).append('\n');
                    }
                    // Trusted plugins run with AstShell process privileges.
                    PluginApi api = new PluginApi(PluginHost.this, p);
                    Object wrapped = Context.javaToJS(api, scope);
                    scope.put("astos", scope, wrapped);
                    scope.put("context", scope, Context.javaToJS(mCtx, scope));
                    cx.evaluateString(scope, sb.toString(), p.id + "/plugin.js", 1, null);
                    p.scope = scope;
                    p.onLoad = asFn(scope, "onLoad");
                    p.onUnload = asFn(scope, "onUnload");
                    p.onTick = asFn(scope, "onTick");
                    p.onVoice = asFn(scope, "onVoice");
                    p.onCommand = asFn(scope, "onCommand");
                    if (p.onVoice != null) mVoiceHooked = true;
                }
                if (p.onLoad != null)
                    p.onLoad.call(cx, p.scope, p.scope, new Object[0]);
            }
        });
        if (p.loadFailed && p.error.isEmpty()) p.error = "onLoad failed";
    }

    private void unloadQuietly(final Plugin p) {
        try {
            if (p.scope != null && p.onUnload != null) {
                runIsolated(p, "unload", new Job() {
                    @Override public void run(Context cx, ScriptableObject shared) throws Exception {
                        p.onUnload.call(cx, p.scope, p.scope, new Object[0]);
                    }
                });
            }
        } catch (Throwable ignored) {}
        p.scope = null;
        p.onLoad = p.onUnload = p.onTick = p.onVoice = p.onCommand = p.onCardTap = null;
        p.cardTaps.clear();
    }

    /** All runtime event dispatch goes through the guarded executor. */
    interface Job { void run(Context cx, ScriptableObject shared) throws Exception; }

    private void runIsolated(final Plugin p, final String what, final Job job) {
        Future<?> f = mExec.submit(new Runnable() {
            @Override public void run() {
                Context cx = null;
                try {
                    cx = sFactory.enterContext();
                    cx.setOptimizationLevel(-1);       // pure interpretation —
                    // generating JVM bytecode does NOT work on Android
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    sDeadline.set(System.currentTimeMillis() + 5000);
                    cx.setInstructionObserverThreshold(10000);
                    job.run(cx, sSharedScope);
                } catch (Throwable t) {
                    p.loadFailed = true;
                    p.error = what + ": " + String.valueOf(t.getMessage());
                    Log.e("plugin " + p.id + " " + what + ": " + t);
                    if ("load".equals(what)) {
                        // a crashing mod must never take the shell down twice
                        p.enabled = false;
                        prefs().edit().putBoolean("on_" + p.id, false).apply();
                        p.cards.clear();
                        notifyCards();
                    }
                } finally {
                    sDeadline.remove();
                    if (cx != null) Context.exit();
                }
            }
        });
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            p.loadFailed = true;
            p.error = what + ": watchdog timeout (5s)";
            if ("load".equals(what)) {
                p.enabled = false;
                prefs().edit().putBoolean("on_" + p.id, false).apply();
                p.cards.clear();
                notifyCards();
            }
        } catch (Exception ignored) {
        }
    }

    private static Function asFn(Scriptable scope, String name) {
        Object o = scope.get(name, scope);
        return o instanceof Function ? (Function) o : null;
    }

    /** Context factory with an instruction watchdog. */
    private static final class AstFactory extends ContextFactory {
        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            long now = System.currentTimeMillis();
            Long deadline = sDeadline.get();
            if (deadline != null && now > deadline) {
                throw new RuntimeException("plugin watchdog: script ran too long");
            }
        }
        @Override
        protected boolean hasFeature(Context cx, int featureId) {
            if (featureId == Context.FEATURE_ENABLE_JAVA_MAP_ACCESS) return false;
            return super.hasFeature(cx, featureId);
        }
    }

    private static final ThreadLocal<Long> sDeadline = new ThreadLocal<Long>();
    private static final AstFactory sFactory = new AstFactory();
    private static final ScriptableObject sSharedScope = makeSharedScope();

    private static ScriptableObject makeSharedScope() {
        Context cx = sFactory.enterContext();
        try {
            cx.setOptimizationLevel(-1);
            return cx.initStandardObjects();
        } finally { Context.exit(); }
    }

    // ------------------------------------------------------------- events

    public void tick() {
        final long now = System.currentTimeMillis();
        final List<Plugin> due = new ArrayList<Plugin>();
        synchronized (this) {
            for (Plugin p : mPlugins.values()) {
                if (!p.enabled || p.scope == null || p.onTick == null) continue;
                if (now - p.lastTickMs >= p.tickSeconds() * 1000L) {
                    p.lastTickMs = now;
                    due.add(p);
                }
            }
        }
        for (final Plugin p : due) {
            runIsolated(p, "tick", new Job() {
                @Override public void run(Context cx, ScriptableObject shared) throws Exception {
                    sDeadline.set(System.currentTimeMillis() + 4000);
                    p.onTick.call(cx, p.scope, p.scope, new Object[0]);
                    sDeadline.remove();
                }
            });
        }
    }

    public void onVoice(final String text) {
        final List<Plugin> due = new ArrayList<Plugin>();
        synchronized (this) {
            if (!mVoiceHooked) return;
            for (Plugin p : mPlugins.values())
                if (p.enabled && p.scope != null && p.onVoice != null) due.add(p);
        }
        for (final Plugin p : due) {
            runIsolated(p, "voice", new Job() {
                @Override public void run(Context cx, ScriptableObject shared) throws Exception {
                    sDeadline.set(System.currentTimeMillis() + 3000);
                    p.onVoice.call(cx, p.scope, p.scope, new Object[]{text});
                    sDeadline.remove();
                }
            });
        }
    }

    /** A plugin card was tapped on the home screen. */
    public void cardTap(final String pluginId, final String cardId) {
        final Plugin p = find(pluginId);
        if (p == null || !p.enabled || p.scope == null) return;
        final Function tap = p.cardTaps.get(cardId);
        runIsolated(p, "cardtap", new Job() {
            @Override public void run(Context cx, ScriptableObject shared) throws Exception {
                sDeadline.set(System.currentTimeMillis() + 3000);
                if (tap != null) tap.call(cx, p.scope, p.scope, new Object[0]);
                else if (p.onCommand != null)
                    p.onCommand.call(cx, p.scope, p.scope, new Object[]{"card:" + cardId});
                sDeadline.remove();
            }
        });
    }

    // ------------------------------------------------------------- plumbing

    void post(Runnable r) { mExec.submit(r); }

    void toast(final String msg) {
        final Listener l = mListener;
        if (l != null) mMain.post(new Runnable() {
            @Override public void run() { l.onToast(msg); }
        });
    }

    void cardsChanged() { notifyCards(); }

    private void notifyCards() {
        final Listener l = mListener;
        if (l != null) mMain.post(new Runnable() {
            @Override public void run() { l.onCardsChanged(); }
        });
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        try { f.delete(); } catch (Exception ignored) {}
    }

    static final class Log {
        static void e(String s) { android.util.Log.e("AstPlugins", s); }
        static void i(String s) { android.util.Log.i("AstPlugins", s); }
    }

    /** Import helper — kept public for the companion ops adapter. */
    public static HashSet<String> permissionSet(JSONObject manifest) {
        HashSet<String> out = new HashSet<String>();
        JSONArray a = manifest.optJSONArray("permissions");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}
