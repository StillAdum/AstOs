package com.astos.setup;

import android.content.Context;
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
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny hand-rolled HTTP server for phone pairing: the wizard shows a QR
 * code (and prints the http://<lan-ip>:8420/ address); the user opens it
 * on their phone and pushes Home Assistant credentials, Groq key, city and
 * theme straight into the wizard.
 *
 * Zero dependencies: ServerSocket + manual HTTP/1.1 parsing.
 */
public class PairServer {

    public interface Callback {
        void onPosted(String haUrl, String haToken, String city, String groq, String theme, String bg);
        void onLog(String line);
    }

    public static final int PORT = 8420;

    private final Context mCtx;
    private final Callback mCb;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ServerSocket mServer;
    private volatile boolean mRunning = false;

    public PairServer(Context ctx, Callback cb) {
        mCtx = ctx.getApplicationContext();
        mCb = cb;
    }

    public synchronized void start() {
        if (mRunning) return;
        try {
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

    public String url() {
        String ip = lanIp(mCtx);
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
                respond(s, 200, "text/html; charset=utf-8", page());
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
                        mCb.onPosted(params.get("ha_url"), params.get("ha_token"),
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
        w.print("HTTP/1.1 " + code + " OK\r\n"); // 404 OK is sloppy but harmless
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

    /** First site-local IPv4, preferring wlan0. */
    static String lanIp(Context ctx) {
        try {
            NetworkInterface wlan = null;
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (ni.getName().startsWith("wlan")) { wlan = ni; break; }
            }
            if (wlan != null) {
                String ip = firstIp(wlan);
                if (ip != null) return ip;
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String ip = firstIp(ni);
                if (ip != null) return ip;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String firstIp(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a.isLoopbackAddress()) continue;
            String s = a.getHostAddress();
            if (s != null && s.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return s;
        }
        return null;
    }

    // ----------------------------------------------------------------- page

    private String page() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        sb.append("<title>AstOs Setup</title><style>");
        sb.append("body{background:#14100A;color:#F2D9A0;font-family:Menlo,Consolas,monospace;");
        sb.append("margin:0;padding:24px;max-width:640px;}");
        sb.append("h1{color:#FFB84D;font-size:18px;letter-spacing:2px;margin:0 0 4px}");
        sb.append(".banner{white-space:pre;color:#FFB84D;font-size:11px;line-height:1.1;margin-bottom:18px}");
        sb.append("p{color:#8A6F4D;font-size:13px;line-height:1.5}");
        sb.append("label{display:block;margin:14px 0 6px;font-size:12px;color:#C8A96A;letter-spacing:1px}");
        sb.append("input,select{width:100%;padding:14px;font-size:15px;background:#1E1810;");
        sb.append("color:#F2D9A0;border:1px solid #4A3B26;border-radius:10px;font-family:inherit}");
        sb.append("button{width:100%;margin-top:22px;padding:16px;font-size:16px;font-weight:bold;");
        sb.append("background:#FFB84D;color:#14100A;border:0;border-radius:10px;font-family:inherit}");
        sb.append(".ok{display:none;margin-top:16px;color:#C8A96A;font-size:14px;border:1px solid #4A3B26;");
        sb.append("padding:14px;border-radius:10px}");
        sb.append("fieldset{margin-top:14px;border:1px solid #4A3B26;border-radius:10px;padding:10px 14px}");
        sb.append("legend{color:#C8A96A;font-size:11px;letter-spacing:1px}");
        sb.append(".r{display:block;margin:8px 0;font-size:14px}");
        sb.append(".r input{width:auto;display:inline-block;margin-right:8px;}</style></head><body>");
        sb.append("<div class='banner'>   _       _    ___  ___\n  /_\\   __| |_ / _ \\/ __|\n / _ \\ (_-&lt;  _| (_) \\__ \\\n/_/ \\_\\__/\\__|\\___/|___/</div>");
        sb.append("<h1>PAIRING</h1>");
        sb.append("<p>Send these settings straight to your AstOs tablet. Everything is optional — you can change it all later in the AstOs SETUP page.</p>");
        sb.append("<form id='f'>");
        sb.append("<label>HOME ASSISTANT URL</label>");
        sb.append("<input name='ha_url' placeholder='http://homeassistant.local:8123' autocomplete='off'>");
        sb.append("<label>HOME ASSISTANT TOKEN</label>");
        sb.append("<input name='ha_token' placeholder='Profile - Security - long-lived access token' autocomplete='off'>");
        sb.append("<label>WEATHER CITY</label>");
        sb.append("<input name='city' placeholder='e.g. Berlin' autocomplete='off'>");
        sb.append("<label>GROQ API KEY (free, console.groq.com)</label>");
        sb.append("<input name='groq' placeholder='gsk_...' autocomplete='off'>");
        sb.append("<fieldset><legend>THEME</legend>");
        for (AsciiTheme.Palette p : AsciiTheme.PALETTES) {
            sb.append("<label class='r'><input type='radio' name='theme' value='").append(p.name)
              .append("'").append(p == AsciiTheme.PALETTES[0] ? " checked" : "")
              .append("> ").append(p.name).append("</label>");
        }
        sb.append("</fieldset><fieldset><legend>BACKGROUND FX</legend>");
        for (String m : AsciiTheme.BG_MODES) {
            if ("IMAGE".equals(m)) continue;
            sb.append("<label class='r'><input type='radio' name='bg' value='").append(m)
              .append("'").append("RAIN".equals(m) ? " checked" : "")
              .append("> ").append(m).append("</label>");
        }
        sb.append("</fieldset>");
        sb.append("<button type='submit'>SEND TO TABLET</button></form>");
        sb.append("<div class='ok' id='ok'>[ SENT ] Settings received — check your tablet.</div>");
        sb.append("<script>document.getElementById('f').addEventListener('submit',function(ev){");
        sb.append("ev.preventDefault();");
        sb.append("fetch('/api',{method:'POST',body:new URLSearchParams(new FormData(ev.target)).toString()})");
        sb.append(".then(function(){document.getElementById('ok').style.display='block';ev.target.reset();});");
        sb.append("});</script></body></html>");
        return sb.toString();
    }
}
