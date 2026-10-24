package com.astos.setup;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PairServer v3 — the phone half of AstOs setup.
 *
 * Setup is FULLY phone-driven: the tablet opens the AstSetup_xxxx hotspot,
 * the phone connects to it, opens the QR'd localhost URL and pushes the
 * ENTIRE configuration from this page: home WiFi credentials (picked from
 * the tablet's live scan list), city, Groq key, Home Assistant, theme.
 * When the hotspot cannot start (side-loaded dev build), the server also
 * answers on the LAN and the same page works over the shared network.
 *
 * Zero dependencies: ServerSocket + manual HTTP/1.1 parsing. The page is
 * fully self-contained (no external resources — the setup hotspot has no
 * internet).
 */
public class PairServer {

    public interface Callback {
        void onPosted(String wifiSsid, String wifiPass, String haUrl,
                      String haToken, String city, String groq,
                      String theme, String bg);
        void onLog(String line);
    }

    public static final int PORT = 8420;

    private final Context mCtx;
    private final Callback mCb;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ServerSocket mServer;
    private volatile boolean mRunning = false;

    /** Live wifi scan (tablet side), shown on the phone page. */
    private volatile List<ScanResult> mNetworks = new ArrayList<ScanResult>();

    public PairServer(Context ctx, Callback cb) {
        mCtx = ctx.getApplicationContext();
        mCb = cb;
    }

    public void setNetworks(List<ScanResult> networks) {
        if (networks != null) mNetworks = networks;
    }

    public synchronized void start() {
        if (mRunning) return;
        try {
            // 0.0.0.0: answers on the AstSetup hotspot (ap0) AND the LAN
            mServer = new ServerSocket(PORT);
            mRunning = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "pair-server");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            log("pair server: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        mRunning = false;
        try { if (mServer != null) mServer.close(); } catch (Exception ignored) {}
        mServer = null;
    }

    public boolean isRunning() { return mRunning; }

    /** The URL baked into the QR: the hotspot ip while the AP is up. */
    public String url() {
        String ip = SoftAp.hotspotIp();          // ap0 while hotspot is on
        if (ip == null) ip = lanIp();            // LAN fallback (dev builds)
        if (ip == null) return null;
        return "http://" + ip + ":" + PORT;
    }

    private void acceptLoop() {
        while (mRunning) {
            try {
                Socket s = mServer.accept();
                handle(s);
            } catch (Exception e) {
                if (mRunning) log("accept: " + e.getMessage());
            }
        }
    }

    private void handle(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String requestLine = in.readLine();
            if (requestLine == null) { s.close(); return; }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { s.close(); return; }
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (Exception ignored) {}
                }
            }

            if ("GET".equals(method)) {
                if (path.startsWith("/favicon")) {
                    respond(s, 404, "image/png", "");
                } else {
                    respond(s, 200, "text/html; charset=utf-8", page());
                }
            } else if ("POST".equals(method) && path.startsWith("/api")) {
                char[] body = new char[Math.min(contentLength, 64 * 1024)];
                int read = 0, n;
                while (read < body.length && (n = in.read(body, read, body.length - read)) > 0) {
                    read += n;
                }
                final Map<String, String> params = parse(new String(body, 0, read));
                respond(s, 200, "application/json", "{\"ok\":true}");
                mMain.post(new Runnable() {
                    @Override public void run() {
                        mCb.onPosted(params.get("wifi_ssid"), params.get("wifi_pass"),
                                params.get("ha_url"), params.get("ha_token"),
                                params.get("city"), params.get("groq"),
                                params.get("theme"), params.get("bg"));
                    }
                });
            } else {
                respond(s, 404, "text/plain", "not found");
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void respond(Socket s, int code, String mime, String body) throws Exception {
        OutputStream os = s.getOutputStream();
        PrintWriter w = new PrintWriter(os);
        byte[] b = body.getBytes("UTF-8");
        w.print("HTTP/1.1 " + code + " OK\r\n");
        w.print("Content-Type: " + mime + "\r\n");
        w.print("Content-Length: " + b.length + "\r\n");
        w.print("Connection: close\r\n\r\n");
        w.flush();
        os.write(b);
        os.flush();
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> out = new HashMap<String, String>();
        if (body == null || body.isEmpty()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private void log(final String l) {
        if (mCb == null) return;
        mMain.post(new Runnable() { @Override public void run() { mCb.onLog(l); }});
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (!ni.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress()) continue;
                    String s = a.getHostAddress();
                    if (s != null && s.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return s;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ----------------------------------------------------------------- page

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String bars(int level) {
        if (level > -55) return "\u2588\u2588\u2588\u2588";
        if (level > -66) return "\u2588\u2588\u2588\u2584";
        if (level > -77) return "\u2588\u2588\u2584\u2584";
        return "\u2588\u2584\u2584\u2584";
    }

    private String page() {
        // dedupe + sort the live scan by signal strength
        java.util.LinkedHashMap<String, int[]> nets = new java.util.LinkedHashMap<String, int[]>();
        List<ScanResult> scan = mNetworks;
        try {
            java.util.Collections.sort(scan, new java.util.Comparator<ScanResult>() {
                @Override public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level);
                }
            });
        } catch (Throwable ignored) {}
        for (ScanResult r : scan) {
            if (r.SSID == null || r.SSID.isEmpty()) continue;
            boolean locked = r.capabilities != null
                    && (r.capabilities.contains("WPA") || r.capabilities.contains("WEP")
                    || r.capabilities.contains("EAP"));
            if (nets.containsKey(r.SSID)) continue;
            nets.put(r.SSID, new int[]{r.level, locked ? 1 : 0});
        }

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        sb.append("<title>AstOs Setup</title><style>");
        sb.append("body{background:#14100A;color:#F2D9A0;font-family:Menlo,Consolas,monospace;");
        sb.append("margin:0;padding:20px 18px 60px;max-width:640px;}");
        sb.append("h1{color:#FFB84D;font-size:20px;letter-spacing:2px;margin:0 0 2px}");
        sb.append(".sub{color:#8A6F4D;font-size:12px;margin-bottom:18px}");
        sb.append("p{color:#8A6F4D;font-size:13px;line-height:1.5}");
        sb.append("label{display:block;margin:14px 0 6px;font-size:12px;color:#C8A96A;letter-spacing:1px}");
        sb.append("input,select{width:100%;box-sizing:border-box;padding:14px;font-size:15px;");
        sb.append("background:#1E1810;color:#F2D9A0;border:1px solid #4A3B26;");
        sb.append("border-radius:10px;font-family:inherit}");
        sb.append("button{width:100%;margin-top:24px;padding:16px;font-size:16px;font-weight:bold;");
        sb.append("background:#FFB84D;color:#14100A;border:0;border-radius:10px;font-family:inherit}");
        sb.append(".ok{display:none;margin-top:16px;color:#FFB84D;font-size:15px;");
        sb.append("border:1px solid #C96F4A;padding:14px;border-radius:10px;line-height:1.5}");
        sb.append("fieldset{margin-top:14px;border:1px solid #4A3B26;border-radius:10px;padding:10px 14px}");
        sb.append("legend{color:#C8A96A;font-size:11px;letter-spacing:1px}");
        sb.append(".r{display:block;margin:10px 0;font-size:15px}");
        sb.append(".r input{width:auto;display:inline-block;margin-right:10px}");
        sb.append(".net{display:block;margin:8px 0;font-size:15px}");
        sb.append(".net input{width:auto;display:inline-block;margin-right:10px}");
        sb.append(".sig{color:#8A6F4D;font-size:12px}");
        sb.append("#pwrow{display:none}");
        sb.append(".hint{font-size:11px;color:#8A6F4D;margin-top:4px}");
        sb.append("</style></head><body>");
        sb.append("<h1>AstOs SETUP</h1>");
        sb.append("<div class='sub'>").append(esc(Build.MANUFACTURER)).append(' ')
          .append(esc(Build.MODEL)).append(" &middot; pair by phone</div>");
        sb.append("<p>Everything you enter here is pushed straight to the tablet over its ");
        sb.append("setup network. Nothing leaves the room.</p>");

        sb.append("<form id='f'>");

        // ---- home wifi (the reason setup is phone-driven) ----
        sb.append("<fieldset><legend>YOUR HOME WIFI &middot; the tablet will join it</legend>");
        if (nets.isEmpty()) {
            sb.append("<label>NETWORK NAME (SSID)</label>");
            sb.append("<input name='wifi_ssid' placeholder='your wifi name' autocomplete='off'>");
        } else {
            int i = 0;
            for (Map.Entry<String, int[]> e : nets.entrySet()) {
                String ssid = e.getKey();
                int level = e.getValue()[0];
                boolean locked = e.getValue()[1] == 1;
                sb.append("<label class='net'><input type='radio' name='wifi_ssid' value='")
                  .append(esc(ssid)).append('\'');
                if (i == 0) sb.append(" checked");
                sb.append(" onchange='pwtoggle(this)'> ")
                  .append(esc(ssid)).append(" <span class='sig'>")
                  .append(bars(level)).append(locked ? " &middot; &#128274;" : "")
                  .append("</span></label>");
                i++;
            }
            sb.append("<label>OTHER NETWORK (optional)</label>");
            sb.append("<input name='wifi_ssid_other' placeholder='type a different name' autocomplete='off'>");
        }
        sb.append("<div id='pwrow'><label>WIFI PASSWORD</label>");
        sb.append("<input name='wifi_pass' placeholder='password' autocomplete='off'>");
        sb.append("<div class='hint'>left blank for open networks</div></div>");
        sb.append("</fieldset>");

        // ---- services ----
        sb.append("<label>WEATHER CITY</label>");
        sb.append("<input name='city' placeholder='e.g. Berlin' autocomplete='off'>");
        sb.append("<label>GROQ API KEY &middot; free, console.groq.com</label>");
        sb.append("<input name='groq' placeholder='gsk_...' autocomplete='off'>");
        sb.append("<label>HOME ASSISTANT URL (optional)</label>");
        sb.append("<input name='ha_url' placeholder='http://homeassistant.local:8123' autocomplete='off'>");
        sb.append("<label>HOME ASSISTANT TOKEN (optional)</label>");
        sb.append("<input name='ha_token' placeholder='long-lived access token' autocomplete='off'>");

        // ---- theme ----
        sb.append("<fieldset><legend>THEME</legend>");
        for (AsciiTheme.Palette p : AsciiTheme.PALETTES) {
            sb.append("<label class='r'><input type='radio' name='theme' value='")
              .append(esc(p.name)).append('\'')
              .append(p == AsciiTheme.PALETTES[0] ? " checked" : "")
              .append("> ").append(esc(p.name)).append("</label>");
        }
        sb.append("</fieldset><fieldset><legend>BACKGROUND FX</legend>");
        for (String m : AsciiTheme.BG_MODES) {
            if ("IMAGE".equals(m)) continue;
            sb.append("<label class='r'><input type='radio' name='bg' value='").append(m)
              .append('\'').append("RAIN".equals(m) ? " checked" : "")
              .append("> ").append(m).append("</label>");
        }
        sb.append("</fieldset>");

        sb.append("<button type='submit'>SEND TO TABLET</button></form>");
        sb.append("<div class='ok' id='ok'>&#10003; SENT &mdash; watch your tablet.<br>");
        sb.append("It will now leave the setup hotspot and join your home wifi.</div>");

        sb.append("<script>");
        sb.append("document.getElementById('f').addEventListener('submit',function(ev){");
        sb.append("ev.preventDefault();");
        sb.append("var fd=new FormData(ev.target);");
        sb.append("var other=(fd.get('wifi_ssid_other')||'').trim();");
        sb.append("if(other){fd.set('wifi_ssid',other);}");
        sb.append("fd.delete('wifi_ssid_other');");
        sb.append("fetch('/api',{method:'POST',body:new URLSearchParams(fd).toString()})");
        sb.append(".then(function(){document.getElementById('ok').style.display='block';");
        sb.append("document.querySelector('button').disabled=true;});");
        sb.append("});</script></body></html>");
        return sb.toString();
    }
}
