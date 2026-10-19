package com.astos.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The AstOs setup wizard — a clean, custom first-boot experience.
 * No CyanogenMod screens, no Android look: animated ASCII logo, five
 * short steps (welcome, wifi, city, Groq key, look), done.
 *
 * Platform-signed: on completion it marks the device provisioned and
 * stores the user's choices in Settings.Global for AstShell.
 */
public class SetupActivity extends Activity {

    private static final int PAGE_WELCOME = 0;
    private static final int PAGE_WIFI = 1;
    private static final int PAGE_CITY = 2;
    private static final int PAGE_GROQ = 3;
    private static final int PAGE_LOOK = 4;
    private static final int PAGE_DONE = 5;

    private AsciiView mAscii;
    private BackgroundFX mBgFx;
    private AsciiTheme.Palette mPal;

    private int mPage = PAGE_WELCOME;
    private int mLogoShown = 0;              // welcome typing animation
    private String mWelcomeLine = "";
    private int mWelcomeShown = 0;

    // wifi state
    private List<ScanResult> mNetworks = new ArrayList<ScanResult>();
    private String mWifiStatus = "scanning...";
    private int mWifiSel = 0;

    // choices
    private String mPaletteName;
    private String mBgMode;
    private String mCity = "";
    private String mCityResolved = "";
    private String mGroqKey = "";

    private String mErr = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        mPal = AsciiTheme.PALETTES[0];
        mPaletteName = mPal.name;
        mBgMode = mPal.mode;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(mPal.bg);
        mAscii = new AsciiView(this);
        mAscii.setBgColor(mPal.bg);
        mAscii.setFontPx(18);
        mAscii.setFps(24);
        root.addView(mAscii, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        mBgFx = new BackgroundFX(mBgMode);
        mAscii.setBackgroundFx(mBgFx);
        mAscii.setRenderer(new AsciiView.Renderer() {
            @Override public void onFrame(AsciiView v, long frame) {
                drawFrame(v, frame);
            }
        });
        mAscii.start();

        // full-screen kiosk feel
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // tap anywhere on welcome / done pages to advance
        mAscii.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                    mAscii.dispatchTap(ev);
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ frame

    private void drawFrame(AsciiView v, long frame) {
        v.wipe();
        mBgFx.render(v, frame, mPal);
        int cols = v.cols(), rows = v.rows();
        int cx = cols / 2;

        switch (mPage) {
            case PAGE_WELCOME: drawWelcome(v, cols, rows, frame); break;
            case PAGE_WIFI:    drawWifi(v, cols, rows, frame); break;
            case PAGE_CITY:    drawCity(v, cols, rows, frame); break;
            case PAGE_GROQ:    drawGroq(v, cols, rows, frame); break;
            case PAGE_LOOK:    drawLook(v, cols, rows, frame); break;
            case PAGE_DONE:    drawDone(v, cols, rows, frame); break;
        }

        if (!mErr.isEmpty()) v.putC(cx, rows - 2, clip(mErr, cols - 8), Color.rgb(255, 90, 90));
    }

    // ---- WELCOME: animated ASCII logo -------------------------------------

    private void drawWelcome(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        String[] logo = Figlet.render("AstOs");
        int lw = logo[0].length();
        int lx = cx - lw / 2;

        // type the logo on, column by column, then shimmer it forever
        int totalCols = lw * 5;
        if (mLogoShown < totalCols) {
            if (frame % 2 == 0) mLogoShown += 3;
            int showCols = Math.min(totalCols, mLogoShown);
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < lw; c++) {
                    int idx = r * lw + c;
                    if (idx * 2 < showCols) {
                        char ch = logo[r].charAt(c);
                        if (ch != ' ') v.setCellChar(lx + c, 3 + r, ch, mPal.accent);
                    }
                }
            }
            // typing cursor
            int cx2 = (showCols / 2) % lw;
            v.setCellChar(lx + cx2, 3 + (showCols / 2 / lw) % 5, '<', mPal.fg);
        } else {
            // shimmer sweep
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < lw; c++) {
                    char ch = logo[r].charAt(c);
                    if (ch == ' ') continue;
                    int ph = (int) ((c + frame / 2) % 14);
                    int col = ph < 3 ? mPal.accent : mPal.fg;
                    v.setCellChar(lx + c, 3 + r, ch, col);
                }
            }
            // typewriter tagline
            String tag = "welcome to YOUR operating system";
            if (mWelcomeShown < tag.length() && frame % 2 == 0) mWelcomeShown++;
            v.putC(cx, 10, tag.substring(0, mWelcomeShown)
                    + (frame / 8 % 2 == 0 ? "_" : " "), mPal.fg);
            if (mWelcomeShown >= tag.length()) {
                if (frame % 24 < 16) v.putC(cx, 13, "[ tap anywhere to begin ]", mPal.dim);
            }
        }
        v.hit(0, 0, cols, rows, new Runnable() { @Override public void run() {
            if (mWelcomeShown >= 31) nextPage();
        }});
        v.putC(cx, 2, "AstOs 2.0 setup", mPal.dim);
    }

    // ---- WIFI ---------------------------------------------------------------

    private void drawWifi(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Figlet.draw(v, cx, 1, "WIFI", mPal.fg);
        v.putC(cx, 7, mWifiStatus, mPal.dim);

        v.box(6, 9, Math.min(cols - 12, 84), Math.min(12, rows - 14), mPal.accent, "networks");
        int max = Math.min(8, Math.min(12, rows - 14) - 2);
        for (int i = 0; i < Math.min(max, mNetworks.size()); i++) {
            ScanResult s = mNetworks.get(i);
            String sig = s.level > -60 ? "****" : s.level > -75 ? "*** " : "**  ";
            String sel = i == mWifiSel ? ">" : " ";
            String line = sel + " [" + sig + "] " + clip(s.SSID, 38)
                    + (s.capabilities != null && s.capabilities.contains("WPA") ? " (locked)" : "");
            v.put(8, 10 + i, line, i == mWifiSel ? mPal.accent : mPal.fg);
            v.hit(8, 10 + i, cols - 20, 1, wifiTap(i));
        }
        if (mNetworks.isEmpty()) {
            v.put(8, 10, "  scanning for networks...", mPal.dim);
        }

        String hint = "tap a network to connect - [ N ] rescan - [ SKIP ]";
        v.putC(cx, rows - 4, hint, mPal.dim);
        v.hit(cx - 24, rows - 4, 2, 1, new Runnable() { @Override public void run() { scanWifi(); }});
        int sx = cx - hint.length() / 2 + hint.indexOf("[ SKIP ]");
        v.hit(sx, rows - 4, 7, 1, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private Runnable wifiTap(final int i) {
        return new Runnable() { @Override public void run() {
            mWifiSel = i;
            final ScanResult s = mNetworks.get(i);
            boolean locked = s.capabilities != null && s.capabilities.contains("WPA");
            if (!locked) {
                connect(s.SSID, null);
                return;
            }
            final EditText in = new EditText(SetupActivity.this);
            in.setTypeface(Typeface.MONOSPACE);
            in.setHint("password for " + s.SSID);
            in.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            new AlertDialog.Builder(SetupActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Join " + s.SSID)
                    .setView(in)
                    .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            connect(s.SSID, in.getText().toString());
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        }};
    }

    private void connect(String ssid, String pass) {
        mWifiStatus = "connecting to " + ssid + "...";
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + ssid + "\"";
            if (pass == null) {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                c.preSharedKey = "\"" + pass + "\"";
            }
            int id = wm.addNetwork(c);
            if (id != -1) {
                wm.disconnect();
                wm.enableNetwork(id, true);
                wm.reconnect();
                mWifiStatus = "connected - " + ssid;
                nextPage();
            } else {
                mErr = "wifi join failed";
            }
        } catch (Throwable e) {
            mErr = "wifi error: " + e.getMessage();
        }
    }

    private void scanWifi() {
        mWifiStatus = "scanning...";
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            boolean ok = wm.startScan();
            List<ScanResult> found = wm.getScanResults();
            if (found == null) found = new ArrayList<ScanResult>();
            // dedupe by SSID
            List<ScanResult> out = new ArrayList<ScanResult>();
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (ScanResult s : found) {
                if (s.SSID == null || s.SSID.isEmpty() || seen.contains(s.SSID)) continue;
                seen.add(s.SSID);
                out.add(s);
            }
            mNetworks = out;
            mWifiStatus = mNetworks.isEmpty()
                    ? (ok ? "no networks found - [ N ] rescan" : "scan failed - [ N ] retry")
                    : mNetworks.size() + " networks - tap one to join";
        } catch (Throwable e) {
            mWifiStatus = "wifi unavailable - [ SKIP ]";
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 7) scanWifi();
    }

    // ---- CITY --------------------------------------------------------------

    private void drawCity(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Figlet.draw(v, cx, 1, "WEATHER", mPal.fg);
        v.putC(cx, 7, "where do you live? (for weather + local answers)", mPal.dim);
        String cityShown = mCityResolved.isEmpty() ? mCity : mCityResolved;
        String line = "[ " + (cityShown.isEmpty() ? "SET CITY" : cityShown.toUpperCase()) + " ]";
        v.putC(cx, 10, line, mPal.accent);
        v.hit(cx - line.length() / 2, 10, line.length(), 1, new Runnable() {
            @Override public void run() { askCity(); }
        });
        v.putC(cx, 13, "[ SKIP ]  [ NEXT > ]", mPal.dim);
        int bx = cx - 9;
        v.hit(bx, 13, 7, 1, new Runnable() { @Override public void run() { nextPage(); }});
        v.hit(bx + 10, 13, 8, 1, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private void askCity() {
        final EditText in = new EditText(this);
        in.setTypeface(Typeface.MONOSPACE);
        in.setHint("e.g. Mumbai / London / Austin");
        in.setText(mCity);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Your city")
                .setView(in)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String c = in.getText().toString().trim();
                        if (c.isEmpty()) return;
                        mCity = c;
                        mCityResolved = "locating...";
                        new Thread(new Runnable() { @Override public void run() {
                            try {
                                String json = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                                        + URLEncoder.encode(c, "UTF-8"));
                                JSONObject root = new JSONObject(json);
                                if (root.getJSONArray("results").length() > 0) {
                                    JSONObject r = root.getJSONArray("results").getJSONObject(0);
                                    final float lat = (float) r.getDouble("latitude");
                                    final float lon = (float) r.getDouble("longitude");
                                    final String name = r.optString("name", c);
                                    runOnUiThread(new Runnable() { @Override public void run() {
                                        mCityResolved = name;
                                        mPal = mPal; // no-op keep
                                        Settings.Global.putFloat(getContentResolver(), "astos_lat", lat);
                                        Settings.Global.putFloat(getContentResolver(), "astos_lon", lon);
                                    }});
                                } else {
                                    runOnUiThread(new Runnable() { @Override public void run() {
                                        mCityResolved = ""; mErr = "city not found: " + c;
                                    }});
                                }
                            } catch (Throwable t) {
                                runOnUiThread(new Runnable() { @Override public void run() {
                                    mCityResolved = "located later"; 
                                }});
                            }
                        }}, "geo").start();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- GROQ ---------------------------------------------------------------

    private void drawGroq(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Figlet.draw(v, cx, 1, "AI", mPal.fg);
        v.putC(cx, 7, "AstOs thinks with Groq - a free, fast AI cloud", mPal.fg);
        v.putC(cx, 9, "1. visit console.groq.com on any device", mPal.dim);
        v.putC(cx, 10, "2. make a free API key (gsk_...)", mPal.dim);
        v.putC(cx, 11, "3. paste it below - then ask me anything", mPal.dim);
        String line = "[ " + (mGroqKey.isEmpty() ? "PASTE API KEY" : "gsk_...*** SAVED") + " ]";
        v.putC(cx, 14, line, mPal.accent);
        v.hit(cx - line.length() / 2, 14, line.length(), 1, new Runnable() {
            @Override public void run() { askGroq(); }
        });
        v.putC(cx, 17, "[ SKIP ]  [ NEXT > ]", mPal.dim);
        int bx = cx - 9;
        v.hit(bx, 17, 7, 1, new Runnable() { @Override public void run() { nextPage(); }});
        v.hit(bx + 10, 17, 8, 1, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private void askGroq() {
        final EditText in = new EditText(this);
        in.setTypeface(Typeface.MONOSPACE);
        in.setHint("gsk_...");
        in.setText(mGroqKey);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Groq API key")
                .setView(in)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        mGroqKey = in.getText().toString().trim();
                    }
                })
                .setNeutralButton("Skip", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int x) { nextPage(); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- LOOK -----------------------------------------------------------------

    private void drawLook(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Figlet.draw(v, cx, 1, "LOOK", mPal.fg);
        v.putC(cx, 7, "choose your vibe - live preview right now", mPal.dim);

        v.putC(cx, 9, "THEME  <  " + mPaletteName + "  >", mPal.fg);
        String tl = "THEME  <  " + mPaletteName + "  >";
        int tx = cx - tl.length() / 2;
        v.hit(tx + 5, 9, 1, 1, palShift(-1));
        v.hit(tx + tl.length() - 6, 9, 1, 1, palShift(1));

        v.putC(cx, 11, "BACKGROUND  <  " + mBgMode + "  >", mPal.fg);
        String bl = "BACKGROUND  <  " + mBgMode + "  >";
        int bx = cx - bl.length() / 2;
        v.hit(bx + 11, 11, 1, 1, bgShift(-1));
        v.hit(bx + bl.length() - 6, 11, 1, 1, bgShift(1));

        v.putC(cx, 15, "[ CONTINUE ]", mPal.accent);
        v.hit(cx - 6, 15, 12, 1, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private Runnable palShift(final int d) {
        return new Runnable() { @Override public void run() {
            List<String> names = new ArrayList<String>();
            for (AsciiTheme.Palette p : AsciiTheme.PALETTES) names.add(p.name);
            int i = Math.max(0, names.indexOf(mPaletteName));
            mPaletteName = names.get((i + d + names.size()) % names.size());
            applyLook();
        }};
    }

    private Runnable bgShift(final int d) {
        return new Runnable() { @Override public void run() {
            List<String> modes = new ArrayList<String>();
            for (String m : AsciiTheme.BG_MODES) if (!"IMAGE".equals(m)) modes.add(m);
            int i = Math.max(0, modes.indexOf(mBgMode));
            mBgMode = modes.get((i + d + modes.size()) % modes.size());
            applyLook();
        }};
    }

    private void applyLook() {
        mPal = AsciiTheme.byName(mPaletteName);
        mBgFx = new BackgroundFX(mBgMode);
        mAscii.setBackgroundFx(mBgFx);
        mAscii.setBgColor(mPal.bg);
    }

    // ---- DONE -------------------------------------------------------------------

    private void drawDone(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        // pulsing logo
        String[] logo = Figlet.render("AstOs");
        int lw = logo[0].length();
        int lx = cx - lw / 2;
        boolean pulse = frame / 6 % 2 == 0;
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < lw; c++) {
                char ch = logo[r].charAt(c);
                if (ch != ' ') v.setCellChar(lx + c, 3 + r, ch,
                        pulse ? mPal.accent : mPal.fg);
            }
        v.putC(cx, 10, "AstOs is ready.", mPal.fg);
        String dateLine = new SimpleDateFormat("EEEE, MMMM d - HH:mm", Locale.US)
                .format(new Date());
        v.putC(cx, 12, dateLine + "   your OS is set up", mPal.dim);
        String next = frame / 10 % 2 == 0 ? "[ enter AstOs ]" : "[  enter AstOs  ]";
        v.putC(cx, 15, next, mPal.accent);
        v.hit(cx - 8, 15, 16, 1, new Runnable() { @Override public void run() {
            finishSetup();
        }});
    }

    // ------------------------------------------------------------------ flow

    private void nextPage() {
        mErr = "";
        if (mPage == PAGE_WELCOME) {
            mPage = PAGE_WIFI;
            scanWifi();
        } else if (mPage == PAGE_WIFI) {
            mPage = PAGE_CITY;
        } else if (mPage == PAGE_CITY) {
            mPage = PAGE_GROQ;
        } else if (mPage == PAGE_GROQ) {
            mPage = PAGE_LOOK;
        } else if (mPage == PAGE_LOOK) {
            mPage = PAGE_DONE;
        }
    }

    private void finishSetup() {
        ContentResolver cr = getContentResolver();
        try {
            Settings.Global.putString(cr, "astos_palette", mPaletteName);
            Settings.Global.putString(cr, "astos_bg", mBgMode);
            if (!mGroqKey.isEmpty()) Settings.Global.putString(cr, "astos_groq_key", mGroqKey);
            if (!mCity.isEmpty()) Settings.Global.putString(cr, "astos_city", mCity);
            if (!mCityResolved.isEmpty() && !"located later".equals(mCityResolved))
                Settings.Global.putString(cr, "astos_city_resolved", mCityResolved);

            // provision the device ourselves — no CM setup wizard exists in
            // this ROM, so we write the flags the framework expects after setup
            Settings.Global.putInt(cr, Settings.Global.DEVICE_PROVISIONED, 1);
            Settings.Secure.putInt(cr, "user_setup_complete", 1); // USER_SETUP_COMPLETE
            Settings.Global.putInt(cr, "astos_setup_done", 1);
        } catch (Throwable e) {
            mErr = "could not save settings: " + e.getMessage();
            return;
        }

        // enter the OS
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(home);
        finish();
    }

    // ------------------------------------------------------------------ helpers

    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        BufferedReader rd = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) sb.append(line);
        rd.close();
        c.disconnect();
        return sb.toString();
    }

    private static String clip(String s, int w) {
        if (s == null) return "";
        return s.length() <= w ? s : s.substring(0, Math.max(0, w - 3)) + "...";
    }

    @Override
    public void onBackPressed() {
        if (mPage > PAGE_WELCOME && mPage < PAGE_DONE) {
            mPage--;
            if (mPage == PAGE_WIFI) scanWifi();
        }
        // welcome/done: no back out of setup
    }
}
