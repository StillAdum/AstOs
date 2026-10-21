package com.astos.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

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
 * No CyanogenMod screens, no Android look: animated ASCII logo, then
 *   welcome -> wifi -> city -> Groq -> PAIR (QR / localhost phone page)
 *   -> look -> downloads ("Setup is almost finished, just downloading a
 *   few more things…") -> done.
 *
 * Platform-signed: on completion it marks the device provisioned and
 * stores the user's choices in Settings.Global for AstShell.
 */
public class SetupActivity extends Activity implements PairServer.Callback {

    private static final int PAGE_WELCOME = 0;
    private static final int PAGE_WIFI = 1;
    private static final int PAGE_CITY = 2;
    private static final int PAGE_GROQ = 3;
    private static final int PAGE_PAIR = 4;
    private static final int PAGE_LOOK = 5;
    private static final int PAGE_DOWNLOADS = 6;
    private static final int PAGE_DONE = 7;

    private static final String[] SPIN = {"|", "/", "-", "\\"};

    private AsciiView mAscii;
    private BackgroundFX mBgFx;
    private AsciiTheme.Palette mPal;
    private FrameLayout mRoot;
    private ImageView mQr;

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
    private String mHaUrl = "";
    private String mHaToken = "";

    // pairing state
    private PairServer mPair;
    private int mSpinT;
    private String mPairStatus = "";
    private boolean mReceivedFromPhone;

    // model download state
    private int mModelPct = -2;              // -2 idle, -1 indeterminate, 0..100
    private String mModelNote = "";
    private boolean mModelBusy = false;
    private boolean mModelDlFailed = false;

    private String mErr = "";

    private final Handler mH = new Handler(Looper.getMainLooper());
    private final Runnable mSpinTick = new Runnable() {
        @Override public void run() {
            if (mPage != PAGE_PAIR || mReceivedFromPhone) return;
            mSpinT = (mSpinT + 1) % SPIN.length;
            mPairStatus = "[" + SPIN[mSpinT] + "] waiting for your phone...";
            mH.postDelayed(this, 160);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        mPal = AsciiTheme.PALETTES[0];
        mPaletteName = mPal.name;
        mBgMode = mPal.mode;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // kiosk lockdown: no shade, no lock screen, AstOs keyboard as default
        kioskLockdown(this);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(mPal.bg);
        mAscii = new AsciiView(this);
        mAscii.setBgColor(mPal.bg);
        mAscii.setFontPx(20);
        mAscii.setFps(24);
        mRoot.addView(mAscii, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        // QR code overlay (the ASCII grid cannot draw bitmaps)
        mQr = new ImageView(this);
        mQr.setVisibility(View.GONE);
        mQr.setPadding(8, 8, 8, 8);
        mRoot.addView(mQr);
        setContentView(mRoot);

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

    /** No shade / no lock screen / our keyboard — platform-signed powers. */
    static void kioskLockdown(Context c) {
        try {
            Settings.Global.putString(c.getContentResolver(), "policy_control",
                    "immersive.status=* immersive.navigation=*");
        } catch (Throwable ignored) {}
        try {
            Object sbm = c.getSystemService("statusbar");
            if (sbm != null) {
                final int flags = 0x00010000 | 0x00020000 | 0x00080000 | 0x00100000;
                sbm.getClass().getMethod("disable", int.class).invoke(sbm, flags);
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> lpu = Class.forName("com.android.internal.widget.LockPatternUtils");
            Object utils = lpu.getConstructor(Context.class).newInstance(c);
            lpu.getMethod("setLockScreenDisabled", boolean.class, int.class)
                    .invoke(utils, Boolean.TRUE, 0);
        } catch (Throwable ignored) {}
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            String mine = "com.astos.shell/.AstKeyboardService";
            String enabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
            if (enabled == null || !enabled.contains(mine)) {
                enabled = (enabled == null || enabled.isEmpty()) ? mine : mine + ":" + enabled;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, enabled);
            }
            Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, mine);
        } catch (Throwable ignored) {}
        try {
            Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(c.getContentResolver(), Settings.System.USER_ROTATION, 1);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ frame

    private void drawFrame(AsciiView v, long frame) {
        v.wipe();
        mBgFx.render(v, frame, mPal);
        int cols = v.cols(), rows = v.rows();
        int cx = cols / 2;

        switch (mPage) {
            case PAGE_WELCOME:  drawWelcome(v, cols, rows, frame); break;
            case PAGE_WIFI:      drawWifi(v, cols, rows, frame); break;
            case PAGE_CITY:      drawCity(v, cols, rows, frame); break;
            case PAGE_GROQ:      drawGroq(v, cols, rows, frame); break;
            case PAGE_PAIR:      drawPair(v, cols, rows, frame); break;
            case PAGE_LOOK:      drawLook(v, cols, rows, frame); break;
            case PAGE_DOWNLOADS: drawDownloads(v, cols, rows, frame); break;
            case PAGE_DONE:      drawDone(v, cols, rows, frame); break;
        }

        if (!mErr.isEmpty()) v.putC(cx, rows - 2, clip(mErr, cols - 8), 0xFFFF5A5A);
    }

    // ---- WELCOME: animated ASCII logo -------------------------------------

    private void drawWelcome(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        String[] logo = Figlet.render("AstOs");
        int lw = logo[0].length();
        int lx = cx - lw / 2;

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
            int cx2 = (showCols / 2) % lw;
            v.setCellChar(lx + cx2, 3 + (showCols / 2 / lw) % 5, '<', mPal.fg);
        } else {
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < lw; c++) {
                    char ch = logo[r].charAt(c);
                    if (ch == ' ') continue;
                    int ph = (int) ((c + frame / 2) % 14);
                    int col = ph < 3 ? mPal.accent : mPal.fg;
                    v.setCellChar(lx + c, 3 + r, ch, col);
                }
            }
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
        v.putC(cx, 2, "AstOs 2.3 setup", mPal.dim);
    }

    // ---- WIFI ---------------------------------------------------------------

    private void drawWifi(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Figlet.draw(v, cx, 1, "WIFI", mPal.fg);
        v.putC(cx, 7, mWifiStatus, mPal.dim);

        int shown = Math.min(5, mNetworks.size());
        v.box(6, 9, Math.min(cols - 12, 84), 2 + shown * 2 + 1, mPal.accent, "networks");
        for (int i = 0; i < shown; i++) {
            ScanResult s = mNetworks.get(i);
            String sig = s.level > -60 ? "****" : s.level > -75 ? "*** " : "**  ";
            String sel = i == mWifiSel ? ">" : " ";
            String line = sel + " [" + sig + "] " + clip(s.SSID, 38)
                    + (isLocked(s) ? " (locked)" : "");
            v.put(8, 10 + i * 2, line, i == mWifiSel ? mPal.accent : mPal.fg);
            v.hit(8, 10 + i * 2, cols - 20, 2, wifiTap(i));
        }
        if (mNetworks.isEmpty()) {
            v.put(8, 10, "  scanning for networks...", mPal.dim);
        }

        String hint = "tap a network to connect - [ N ] rescan - [ SKIP ]";
        v.putC(cx, rows - 4, hint, mPal.dim);
        int hx = cx - hint.length() / 2;
        v.hit(hx + hint.indexOf("[ N ]"), rows - 4, 3, 2, new Runnable() { @Override public void run() { scanWifi(); }});
        v.hit(hx + hint.indexOf("[ SKIP ]"), rows - 4, 7, 2, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private static boolean isLocked(ScanResult s) {
        return s.capabilities != null && (s.capabilities.contains("WPA")
                || s.capabilities.contains("WEP") || s.capabilities.contains("EAP"));
    }

    private Runnable wifiTap(final int i) {
        return new Runnable() { @Override public void run() {
            mWifiSel = i;
            final ScanResult s = mNetworks.get(i);
            if (!isLocked(s)) {
                connect(s.SSID, null);
                return;
            }
            final EditText in = new EditText(SetupActivity.this);
            in.setTypeface(Typeface.MONOSPACE);
            in.setTextSize(18f);
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
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
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
            // Android 7 gates scan results behind the system location switch;
            // we are platform-signed, so turn it on quietly if it is off.
            try {
                if (Settings.Secure.getInt(getContentResolver(),
                        Settings.Secure.LOCATION_MODE, 0) == 0) {
                    Settings.Secure.putInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 3);
                }
            } catch (Throwable ignored) {}
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            boolean ok = wm.startScan();
            List<ScanResult> found = wm.getScanResults();
            if (found == null) found = new ArrayList<ScanResult>();
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
        v.hit(cx - line.length() / 2, 10, line.length(), 2, new Runnable() {
            @Override public void run() { askCity(); }
        });
        v.putC(cx, 13, "[ SKIP ]  [ NEXT > ]", mPal.dim);
        int bx = cx - 9;
        v.hit(bx, 13, 7, 2, new Runnable() { @Override public void run() { nextPage(); }});
        v.hit(bx + 10, 13, 8, 2, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private void askCity() {
        final EditText in = new EditText(this);
        in.setTypeface(Typeface.MONOSPACE);
        in.setTextSize(18f);
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
        v.hit(cx - line.length() / 2, 14, line.length(), 2, new Runnable() {
            @Override public void run() { askGroq(); }
        });
        v.putC(cx, 17, "[ SKIP ]  [ NEXT > ]", mPal.dim);
        int bx = cx - 9;
        v.hit(bx, 17, 7, 2, new Runnable() { @Override public void run() { nextPage(); }});
        v.hit(bx + 10, 17, 8, 2, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private void askGroq() {
        final EditText in = new EditText(this);
        in.setTypeface(Typeface.MONOSPACE);
        in.setTextSize(18f);
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

    // ---- PAIR (QR code / localhost, finish setup from your phone) ----------

    private void drawPair(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Figlet.draw(v, cx, 0, "PAIR", mPal.fg);

        v.putC(cx, 6, "finish setup from your phone:", mPal.fg);
        v.putC(cx, 8, "scan the QR code  -  or open the address below in", mPal.dim);
        v.putC(cx, 9, "your phone's browser:", mPal.dim);

        String url = mPair != null && mPair.url() != null ? mPair.url() : "...";
        String line = "[ " + url + " ]";
        v.putC(cx, 11, line, mPal.accent);

        if (!mPairStatus.isEmpty()) v.putC(cx, 13, clip(mPairStatus, cols - 8),
                mReceivedFromPhone ? mPal.accent : mPal.dim);

        v.putC(cx, rows - 4, "[ SKIP ]  [ NEXT > ]", mPal.dim);
        int bx = cx - 9;
        v.hit(bx, rows - 4, 7, 2, new Runnable() { @Override public void run() { nextPage(); }});
        v.hit(bx + 10, rows - 4, 8, 2, new Runnable() { @Override public void run() { nextPage(); }});
    }

    private void onPairEnter() {
        mReceivedFromPhone = false;
        if (mPair == null) mPair = new PairServer(this, this);
        if (!networkConnected()) {
            mPairStatus = "no network - continue without pairing if you like";
            mQr.setVisibility(View.GONE);
            return;
        }
        mPair.start();
        String url = mPair.url();
        if (url == null) {
            mPairStatus = "no network - continue without pairing if you like";
            mQr.setVisibility(View.GONE);
            return;
        }
        Bitmap bm = Qr.encode(url, 460, mPal.fg, 0xFF14100A);
        mQr.setBackgroundColor(0xFF14100A);
        int px = Math.round(getResources().getDisplayMetrics().density * 200);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px, px, Gravity.CENTER);
        lp.topMargin = Math.round(getResources().getDisplayMetrics().density * 26);
        mQr.setLayoutParams(lp);
        mQr.setImageBitmap(bm);
        mQr.setVisibility(View.VISIBLE);
        mPairStatus = "[" + SPIN[0] + "] waiting for your phone...";
        mH.removeCallbacks(mSpinTick);
        mSpinTick.run();
    }

    private void onPairExit() {
        mH.removeCallbacks(mSpinTick);
        mQr.setVisibility(View.GONE);
        if (mPair != null) mPair.stop();
    }

    // ---- LOOK -----------------------------------------------------------------

    private void drawLook(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Figlet.draw(v, cx, 1, "LOOK", mPal.fg);
        v.putC(cx, 7, "choose your vibe - live preview right now", mPal.dim);

        v.putC(cx, 9, "THEME  <  " + mPaletteName + "  >", mPal.fg);
        String tl = "THEME  <  " + mPaletteName + "  >";
        int tx = cx - tl.length() / 2;
        v.hit(tx + 5, 9, 3, 2, palShift(-1));
        v.hit(tx + tl.length() - 7, 9, 3, 2, palShift(1));

        v.putC(cx, 11, "BACKGROUND  <  " + mBgMode + "  >", mPal.fg);
        String bl = "BACKGROUND  <  " + mBgMode + "  >";
        int bx = cx - bl.length() / 2;
        v.hit(bx + 11, 11, 3, 2, bgShift(-1));
        v.hit(bx + bl.length() - 7, 11, 3, 2, bgShift(1));

        v.putC(cx, 15, "[ CONTINUE ]", mPal.accent);
        v.hit(cx - 6, 15, 12, 2, new Runnable() { @Override public void run() { nextPage(); }});
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
        mRoot.setBackgroundColor(mPal.bg);
    }

    // ---- DOWNLOADS ---------------------------------------------------------------

    private void drawDownloads(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        v.putC(cx, 3, "Setup is almost finished,", mPal.fg);
        v.putC(cx, 4, "just downloading a few more things...", mPal.fg);
        v.putC(cx, 6, "(the offline voice model - ~40 MB, one-time)", mPal.dim);

        // ASCII progress bar
        int barW = Math.min(44, cols - 16);
        int barX = cx - barW / 2;
        int filled = mModelPct >= 0 ? (barW * mModelPct) / 100 : 0;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < barW; i++) bar.append(i < filled ? '=' : '-');
        String spinner = mModelPct < 0 && mModelBusy ? " [" + SPIN[(int) (frame / 8 % 4)] + "]" : "";
        v.put(barX - 1, 9, "|" + bar + "|" + spinner, mPal.accent);
        if (mModelPct >= 0) {
            String pct = mModelPct + "%";
            v.putC(cx, 10, pct, mPal.accent);
        }
        if (!mModelNote.isEmpty()) {
            v.putC(cx, 12, clip(mModelNote, cols - 8), mPal.dim);
        }

        int by = 14;
        if (mModelDlFailed) {
            v.putC(cx, by, "[ RETRY ]", mPal.accent);
            v.hit(cx - 4, by, 8, 2, new Runnable() { @Override public void run() { startModelDownload(); }});
            by += 2;
        }
        if (!mModelBusy) {
            v.putC(cx, by, mModelPct >= 100 || !mModelDlFailed ? "[ CONTINUE > ]" : "[ SKIP ]", mPal.dim);
            int w = mModelPct >= 100 ? 12 : 8;
            v.hit(cx - w / 2, by, w, 2, new Runnable() { @Override public void run() { nextPage(); }});
        }
    }

    private void onDownloadsEnter() {
        if (mModelPct >= 100 || ModelZip.present()) {
            if (!ModelZip.present()) startModelDownload();
            return;
        }
        if (!networkConnected()) {
            mModelDlFailed = true;
            mModelNote = "no network - the model downloads later from the home screen";
            return;
        }
        startModelDownload();
    }

    private void startModelDownload() {
        if (mModelBusy) return;
        mModelBusy = true;
        mModelDlFailed = false;
        mModelPct = -1;
        mModelNote = "connecting...";
        ModelZip.download(this, new ModelZip.Listener() {
            @Override public void onProgress(int pct, String note) {
                mModelPct = pct;
                if (note != null) mModelNote = note;
            }
            @Override public void onDone() {
                mModelBusy = false;
                mModelPct = 100;
                mModelNote = "voice model downloaded - AstShell unpacks it on first start";
            }
            @Override public void onError(String msg) {
                mModelBusy = false;
                mModelDlFailed = true;
                mModelNote = "download failed: " + msg;
            }
        });
    }

    private boolean networkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Throwable t) { return false; }
    }

    // ---- DONE -------------------------------------------------------------------

    private void drawDone(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
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
        v.hit(cx - 8, 15, 16, 2, new Runnable() { @Override public void run() {
            finishSetup();
        }});
    }

    // ------------------------------------------------------------------ flow

    private void nextPage() {
        mErr = "";
        if (mPage == PAGE_PAIR) onPairExit();
        mPage++;
        switch (mPage) {
            case PAGE_WIFI:
                scanWifi();
                break;
            case PAGE_PAIR:
                onPairEnter();
                break;
            case PAGE_DOWNLOADS:
                onDownloadsEnter();
                break;
            default:
                break;
        }
        if (mPage > PAGE_DONE) mPage = PAGE_DONE;
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
            if (!mHaUrl.isEmpty()) Settings.Global.putString(cr, "astos_ha_url", mHaUrl);
            if (!mHaToken.isEmpty()) Settings.Global.putString(cr, "astos_ha_token", mHaToken);

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

    // ------------------------------------------------------------------ PairServer.Callback

    @Override public void onPosted(final String haUrl, final String haToken,
                                   final String city, final String groq,
                                   final String theme, final String bg) {
        mReceivedFromPhone = true;
        mH.removeCallbacks(mSpinTick);
        if (haUrl != null && !haUrl.trim().isEmpty()) mHaUrl = haUrl.trim();
        if (haToken != null && !haToken.trim().isEmpty()) mHaToken = haToken.trim();
        if (groq != null && !groq.trim().isEmpty()) mGroqKey = groq.trim();
        if (city != null && !city.trim().isEmpty() && mCity.isEmpty()) {
            mCity = city.trim();
            mCityResolved = "locating...";
            new Thread(new Runnable() { @Override public void run() {
                try {
                    String json = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + URLEncoder.encode(mCity, "UTF-8"));
                    JSONObject root = new JSONObject(json);
                    if (root.getJSONArray("results").length() > 0) {
                        JSONObject r = root.getJSONArray("results").getJSONObject(0);
                        final float lat = (float) r.getDouble("latitude");
                        final float lon = (float) r.getDouble("longitude");
                        final String name = r.optString("name", mCity);
                        runOnUiThread(new Runnable() { @Override public void run() {
                            mCityResolved = name;
                            try {
                                Settings.Global.putFloat(getContentResolver(), "astos_lat", lat);
                                Settings.Global.putFloat(getContentResolver(), "astos_lon", lon);
                            } catch (Throwable ignored) {}
                        }});
                    }
                } catch (Throwable ignored) {}
            }}, "geo2").start();
        }
        if (theme != null && !theme.trim().isEmpty()) {
            mPaletteName = AsciiTheme.byName(theme).name; // validate against known palettes
            applyLook();
        }
        if (bg != null && !bg.trim().isEmpty()) {
            for (String m : AsciiTheme.BG_MODES) {
                if (m.equalsIgnoreCase(bg.trim())) { mBgMode = m; break; }
            }
            applyLook();
        }
        mPairStatus = "[ RECEIVED ] settings saved from your phone";
        mH.postDelayed(new Runnable() { @Override public void run() {
            if (mPage == PAGE_PAIR) nextPage();
        }}, 900);
    }

    @Override public void onLog(String line) { /* quiet */ }

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
            if (mPage == PAGE_PAIR) onPairExit();
            mPage--;
            if (mPage == PAGE_WIFI) scanWifi();
        }
        // welcome/done: no back out of setup
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mH.removeCallbacksAndMessages(null);
        if (mPair != null) mPair.stop();
    }
}
