package com.astos.shell;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Home Assistant REST API client.
 * Create a long-lived access token in HA: Profile -> Security -> Long-lived access tokens.
 * Everything stays on your local network — no cloud involved.
 */
public final class HomeAssistantClient {

    public static class Entity {
        public String id;       // e.g. light.living_room
        public String name;     // friendly name
        public String domain;
        public boolean on;
        public boolean isScene;
    }

    public interface Callback<T> {
        void onResult(T data, String error);
    }

    private HomeAssistantClient() {}

    private static String url(Context c) {
        String u = AppSettings.haUrl(c).trim();
        if (u.isEmpty()) return null;
        if (!u.startsWith("http")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String request(Context c, String method, String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url(c) + path).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod(method);
        String token = AppSettings.haToken(c).trim();
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = conn.getResponseCode();
        BufferedReader rd = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) sb.append(line);
        rd.close();
        conn.disconnect();
        if (code >= 400) throw new RuntimeException("HTTP " + code);
        return sb.toString();
    }

    private static void async(final Runnable r) { new Thread(r, "ha-client").start(); }
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    /** Fetch states and filter to controllable entities. */
    public static void listEntities(final Context c, final Callback<List<Entity>> cb) {
        async(new Runnable() { @Override public void run() {
            try {
                JSONArray arr = new JSONArray(request(c, "GET", "/api/states", null));
                final List<Entity> out = new ArrayList<Entity>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject st = arr.getJSONObject(i);
                    String eid = st.optString("entity_id", "");
                    String domain = eid.contains(".") ? eid.substring(0, eid.indexOf('.')) : "";
                    if (!(domain.equals("light") || domain.equals("switch")
                            || domain.equals("fan") || domain.equals("input_boolean")
                            || domain.equals("scene"))) continue;
                    Entity e = new Entity();
                    e.id = eid;
                    e.domain = domain;
                    e.isScene = domain.equals("scene");
                    String friendly = st.optString("name", "");
                    if (friendly == null || friendly.isEmpty() || friendly.equals("null")) {
                        friendly = eid.substring(eid.indexOf('.') + 1).replace('_', ' ');
                    }
                    e.name = friendly;
                    e.on = "on".equals(st.optString("state", ""));
                    out.add(e);
                }
                main().post(new Runnable() { @Override public void run() { cb.onResult(out, null); }});
            } catch (final Exception e) {
                final String msg = e.getMessage();
                main().post(new Runnable() { @Override public void run() { cb.onResult(null, msg); }});
            }
        }});
    }

    /** call a service, e.g. domain=light, service=turn_on, entity=light.desk */
    public static void call(final Context c, final String domain, final String service,
                            final String entityId, final Callback<Boolean> cb) {
        async(new Runnable() { @Override public void run() {
            try {
                String body = new JSONObject().put("entity_id", entityId).toString();
                request(c, "POST", "/api/services/" + domain + "/" + service, body);
                main().post(new Runnable() { @Override public void run() { cb.onResult(true, null); }});
            } catch (final Exception e) {
                final String msg = e.getMessage();
                main().post(new Runnable() { @Override public void run() { cb.onResult(false, msg); }});
            }
        }});
    }

    public static void toggle(final Context c, final Entity e, final Callback<Boolean> cb) {
        if (e.isScene) call(c, "scene", "turn_on", e.id, cb);
        else call(c, e.domain, "toggle", e.id, cb);
    }

    public static void turn(final Context c, final Entity e, final boolean on, final Callback<Boolean> cb) {
        if (e.isScene) { if (on) call(c, "scene", "turn_on", e.id, cb); else cb.onResult(true, null); return; }
        call(c, e.domain, on ? "turn_on" : "turn_off", e.id, cb);
    }

    /** Voice helper: find the best-matching entity for a spoken name. */
    public static Entity bestMatch(List<Entity> entities, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return null;
        Entity best = null;
        int bestScore = 0;
        for (Entity e : entities) {
            String n = normalize(e.name);
            String i = normalize(e.id);
            int score = 0;
            if (n.equals(q) || i.equals(q)) score = 100;
            else if (n.contains(q) || i.contains(q)) score = 60;
            else if (q.contains(n) && n.length() > 3) score = 40;
            if (score > bestScore) { bestScore = score; best = e; }
        }
        return bestScore >= 40 ? best : null;
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase()
                .replace("the ", " ").replace("_", " ")
                .replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
    }
}
