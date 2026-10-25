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
 * PairServer v4 — the phone half of AstOs setup, in the pixel look.
 *
 * Setup is FULLY phone-driven: the tablet opens the AstSetup_xxxx hotspot,
 * the phone connects to it, opens the QR'd localhost URL and pushes the
 * ENTIRE configuration from this page: home WiFi credentials (picked from
 * the tablet's live scan list), city, Groq key, Home Assistant, theme,
 * 24-hour clock. When the hotspot cannot start (side-loaded dev build),
 * the server also answers on the LAN and the same page works over the
 * shared network.
 *
 * Zero dependencies: ServerSocket + manual HTTP/1.1 parsing. The page is
 * fully self-contained (no external resources — the setup hotspot has no
 * internet) and styled like the tablet: paper background, ink borders,
 * hard shadows, segmented buttons.
 */
public class PairServer {

    public interface Callback {
        void onPosted(String wifiSsid, String wifiPass, String haUrl,
                      String haToken, String city, String groq,
                      String theme, String h24);
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
                                params.get("theme"), params.get("h24"));
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
        w.print("Cache-Control: no-store\r\n");
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
        sb.append("<title>AstOS setup</title><style>");
        // the tablet's pixel look: dune paper, ink lines, hard shadows
        sb.append(":root{--bg:#efe7d8;--panel:#f8f2e4;--ink:#2a241c;--soft:#9a8d78;");
        sb.append("--accent:#c4742a;--on:#cfdca6;--off:#dfd3bd;--shadow:#d9ccb2}");
        sb.append("*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}");
        sb.append("body{margin:0;font-family:ui-monospace,Menlo,Consolas,'Courier New',monospace;");
        sb.append("background:var(--bg);color:var(--ink);padding:20px 18px 70px;");
        sb.append("background-image:radial-gradient(var(--shadow) 1px,transparent 1.2px);");
        sb.append("background-size:18px 18px}");
        sb.append(".wrap{max-width:440px;margin:0 auto}");
        sb.append("h1{font-size:38px;font-weight:400;letter-spacing:.04em;margin:4px 0;");
        sb.append("font-family:'VT323',ui-monospace,monospace;color:var(--ink)}");
        sb.append(".sub{font-size:10px;letter-spacing:.22em;text-transform:uppercase;");
        sb.append("color:var(--soft);margin:0 0 20px}");
        sb.append("p{color:var(--ink);font-size:12px;line-height:1.6}");
        sb.append(".card{position:relative;background:var(--panel);border:2px solid var(--ink);");
        sb.append("box-shadow:6px 6px 0 var(--shadow);padding:16px 16px 14px;margin-bottom:16px}");
        sb.append(".tag{position:absolute;top:-11px;left:10px;background:var(--ink);color:var(--bg);");
        sb.append("font-size:10px;font-weight:600;letter-spacing:.2em;padding:2px 9px}");
        sb.append("label{display:block;margin:12px 0 5px;font-size:9px;letter-spacing:.18em;");
        sb.append("text-transform:uppercase;color:var(--soft)}");
        sb.append("input,select{width:100%;padding:12px 10px;font-size:14px;background:var(--bg);");
        sb.append("color:var(--ink);border:2px solid var(--ink);border-radius:0;");
        sb.append("font-family:inherit;-webkit-appearance:none}");
        sb.append("button{width:100%;margin-top:6px;padding:15px;font-size:13px;font-weight:700;");
        sb.append("letter-spacing:.14em;text-transform:uppercase;");
        sb.append("background:var(--accent);color:#f8f2e4;border:2px solid var(--ink);");
        sb.append("box-shadow:3px 3px 0 var(--ink);font-family:inherit}");
        sb.append("button:active{transform:translate(3px,3px);box-shadow:0 0 0 var(--ink)}");
        sb.append(".ok{display:none;margin-top:14px;background:var(--on);border:2px solid var(--ink);");
        sb.append("padding:13px;font-size:12px;line-height:1.5}");
        sb.append(".r{display:block;margin:9px 0;font-size:13px}");
        sb.append(".r input,.net input{width:auto;display:inline-block;margin-right:9px}");
        sb.append(".net{display:block;margin:8px 0;font-size:13px}");
        sb.append(".sig{color:var(--soft);font-size:11px}");
        sb.append("#pwrow{display:none}");
        sb.append(".hint{font-size:10px;color:var(--soft);margin-top:4px;line-height:1.5}");
        sb.append(".chips{display:inline-block;margin-right:8px;vertical-align:middle}");
        sb.append(".chips i{display:inline-block;width:10px;height:10px;margin-right:1px;");
        sb.append("border:1px solid rgba(0,0,0,.25)}");
        sb.append(".check{display:block;margin:10px 0;font-size:13px}");
        sb.append(".check input{width:auto;display:inline-block;margin-right:9px}");
        sb.append("</style></head><body><div class='wrap'>");
        sb.append("<h1>AstOS setup</h1>");
        sb.append("<div class='sub'>").append(esc(Build.MANUFACTURER)).append(' ')
          .append(esc(Build.MODEL)).append(" &middot; pair by phone</div>");
        sb.append("<p>Everything you enter here is pushed straight to the tablet over its ");
        sb.append("setup network. Nothing leaves the room.</p>");

        sb.append("<form id='f'>");

        // ---- home wifi (the reason setup is phone-driven) ----
        sb.append("<div class='card'><span class='tag'>WIFI</span>");
        if (nets.isEmpty()) {
            sb.append("<label>Network name (SSID)</label>");
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
            sb.append("<label>Other network (optional)</label>");
            sb.append("<input name='wifi_ssid_other' placeholder='type a different name' autocomplete='off'>");
        }
        sb.append("<div id='pwrow'><label>Wifi password</label>");
        sb.append("<input name='wifi_pass' placeholder='password' autocomplete='off'>");
        sb.append("<div class='hint'>left blank for open networks</div></div>");
        sb.append("</div>");

        // ---- services ----
        sb.append("<div class='card'><span class='tag'>SERVICES</span>");
        sb.append("<label>Weather city</label>");
        sb.append("<input name='city' placeholder='e.g. Berlin' autocomplete='off'>");
        sb.append("<label>Groq API key &middot; free, console.groq.com</label>");
        sb.append("<input name='groq' placeholder='gsk_...' autocomplete='off'>");
        sb.append("<label>Home Assistant URL (optional)</label>");
        sb.append("<input name='ha_url' placeholder='http://homeassistant.local:8123' autocomplete='off'>");
        sb.append("<label>Home Assistant token (optional)</label>");
        sb.append("<input name='ha_token' placeholder='long-lived access token' autocomplete='off'>");
        sb.append("</div>");

        // ---- theme (with pixel color chips) ----
        sb.append("<div class='card'><span class='tag'>THEME</span>");
        for (AsciiTheme.Palette p : AsciiTheme.PALETTES) {
            sb.append("<label class='r'><input type='radio' name='theme' value='")
              .append(esc(p.name)).append('\'')
              .append(p == AsciiTheme.PALETTES[0] ? " checked" : "")
              .append("> <span class='chips'>")
              .append("<i style='background:#").append(Integer.toHexString(p.bg & 0xFFFFFF)).append("'></i>")
              .append("<i style='background:#").append(Integer.toHexString(p.on & 0xFFFFFF)).append("'></i>")
              .append("<i style='background:#").append(Integer.toHexString(p.accent & 0xFFFFFF)).append("'></i>")
              .append("<i style='background:#").append(Integer.toHexString(p.fg & 0xFFFFFF)).append("'></i></span>")
              .append(esc(p.name)).append("</label>");
        }
        sb.append("<label class='check'><input type='checkbox' name='h24' value='1' checked>");
        sb.append("24-hour clock</label>");
        sb.append("</div>");

        sb.append("<button type='submit'>Send to tablet</button></form>");
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
        sb.append("});</script></div></body></html>");
        return sb.toString();
    }
}
