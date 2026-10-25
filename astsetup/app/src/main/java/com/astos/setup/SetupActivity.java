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
 * The AstOs setup wizard v3 — fully phone-driven, pixel-canvas UI.
 *
 * v2.3 problems this rewrites: the ASCII wizard rendered the QR as an
 * ImageView floating over character-grid text (it overlapped and the text
 * underneath was unreadable), setup steps were split between tablet pages
 * and the phone page, and every animation was ASCII art. Now:
 *
 *   WELCOME  pixel-block AstOs logo typing itself in (canvas blocks)
 *   PAIR     the tablet opens its own hotspot "AstSetup_xxxx" and shows a
 *            clean 3-card layout: CONNECT -> SCAN (QR, never overlapping
 *            anything) -> SEND. The phone page carries the WHOLE setup:
 *            home wifi credentials (from the tablet's live scan), city,
 *            Groq key, Home Assistant, theme.
 *   LOCAL    tiny fallback for phone-less setups: pick wifi on the tablet
 *   CONNECT  joining the home wifi received from the phone
 *   DOWNLOADS  the offline voice model (smooth progress bar)
 *   DONE     enter AstOs
 *
 * Everything is laid out in dp from the measured view size — no fixed
 * pixel math, nothing out of frame. Animations are sine-wave graphics and
 * pixel blocks; ASCII art now lives ONLY in the boot animation.
 */
public class SetupActivity extends Activity implements PairServer.Callback {

    private static final int PAGE_WELCOME = 0;
    private static final int PAGE_PAIR = 1;
    private static final int PAGE_LOCAL = 2;
    private static final int PAGE_CONNECT = 3;
    private static final int PAGE_DOWNLOADS = 4;
    private static final int PAGE_DONE = 5;
    private static final String[] STAGES =
            {"WELCOME", "PAIR", "CONNECT", "DOWNLOADS", "DONE"};

    private PixelUi mUi;
    private AsciiTheme.Palette mPal;
    private FrameLayout mRoot;
    private final android.graphics.Paint mPaint = new android.graphics.Paint();

    private int mPage = PAGE_WELCOME;

    // welcome animation
    private int mLogoBlocks = 0;              // blocks revealed
    private int mTagShown = 0;                // tagline chars revealed

    // choices (defaults; the phone page overrides them)
    private String mPaletteName;
    private String mBgMode;
    private boolean mH24 = true;
    private String mCity = "";
    private String mCityResolved = "";
    private String mGroqKey = "";
    private String mHaUrl = "";
    private String mHaToken = "";
    private String mWifiSsid = "";
    private String mWifiPass = "";

    // pair state
    private PairServer mPair;
    private List<ScanResult> mNetworks = new ArrayList<ScanResult>();
    /** start | ready | lan | fail */
    private String mPairStage = "start";
    private String mPairStatus = "";
    private String mApSsid = "";
    private String mUrl = "";
    private Bitmap mQr;
    private boolean mReceived = false;

    // local wifi fallback
    private int mLocalSel = -1;
    private String mLocalStatus = "";

    // connect state
    /** joining | ok | fail */
    private String mConnState = "joining";
    private long mConnT0;

    // model download state
    private int mModelPct = -2;              // -2 idle, -1 indeterminate, 0..100
    private String mModelNote = "";
    private boolean mModelBusy = false;
    private boolean mModelDlFailed = false;
    private boolean mModelAutoAdvanced = false;

    private String mErr = "";

    private final Handler mH = new Handler(Looper.getMainLooper());
    private final Runnable mConnPoll = new Runnable() {
        @Override public void run() { pollConnect(); }
    };

    // ------------------------------------------------------------------ create

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        mPal = AsciiTheme.PALETTES[0];
        mPaletteName = mPal.name;
        mBgMode = mPal.mode;
        mApSsid = SoftAp.ssid(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // kiosk lockdown: no shade, no lock screen, AstOs keyboard as default
        kioskLockdown(this);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(mPal.bg);

        mUi = new PixelUi(this);
        mUi.setPalette(mPal);
        mUi.setBgMode(mBgMode);
        mRoot.addView(mUi, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(mRoot);

        mUi.setRenderer(new PixelUi.Renderer() {
            @Override public void onFrame(PixelUi ui, android.graphics.Canvas c, long tms) {
                drawFrame(ui, c, tms);
            }
        });
        mUi.start();

        // full-screen kiosk feel
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
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

    private void drawFrame(PixelUi ui, android.graphics.Canvas c, long tms) {
        float w = ui.widthDp(), h = ui.heightDp();
        ui.drawBackground(c, tms);

        // stage dots + wordmark
        ui.text(c, "AstOs setup", 16, 24, 14, mPal.fg, true, true);
        float dx = w - 16 - (STAGES.length * 16);
        for (int i = 0; i < STAGES.length; i++) {
            int stage = pageToStage(mPage);
            boolean done = i < stage;
            boolean cur = i == stage;
            ui.pill(c, dx + i * 16, 14, 10, 10, cur || done, 2);
            if (cur) {
                ui.pill(c, dx + i * 16 + 3, 27, 4, 4, true, 1);
            }
        }

        switch (mPage) {
            case PAGE_WELCOME:  drawWelcome(ui, c, w, h, tms); break;
            case PAGE_PAIR:     drawPair(ui, c, w, h, tms); break;
            case PAGE_LOCAL:    drawLocal(ui, c, w, h, tms); break;
            case PAGE_CONNECT:  drawConnect(ui, c, w, h, tms); break;
            case PAGE_DOWNLOADS: drawDownloads(ui, c, w, h, tms); break;
            case PAGE_DONE:     drawDone(ui, c, w, h, tms); break;
        }

        if (!mErr.isEmpty()) {
            ui.textCentered(c, clip(mErr, 70), w / 2f, h - 10, 12, 0xFFFF5A5A, false, false);
        }
    }

    private static int pageToStage(int page) {
        switch (page) {
            case PAGE_WELCOME: return 0;
            case PAGE_PAIR: return 1;
            case PAGE_LOCAL: return 1;              // detour of PAIR
            case PAGE_CONNECT: return 2;
            case PAGE_DOWNLOADS: return 3;
            default: return 4;
        }
    }

    // ---- WELCOME --------------------------------------------------------

    private void drawWelcome(PixelUi ui, android.graphics.Canvas c, float w,
                             float h, long tms) {
        float midX = w / 2f;
        int total = PixelIcon.countBlocks("AstOs");
        if (mLogoBlocks < total) {
            if (tms / 40 != mLastTick) {           // reveal pace
                mLogoBlocks += 3;
            }
            mLastTick = tms / 40;
        }
        mLogoBlocks = Math.min(mLogoBlocks, total);

        float block = Math.min(w / 60f, h / 34f);
        float lw = PixelIcon.measureBlockText("AstOs", block);
        float lh = block * 5;
        float ly = h * 0.30f;
        PixelIcon.drawBlockText(c, "AstOs", midX - lw / 2f, ly, block,
                mPal.accent, mPaint, mLogoBlocks);

        String tag = "welcome to YOUR operating system";
        if (mLogoBlocks >= total) {
            if (mTagShown < tag.length() && tms / 70 != mLastTick2) mTagShown++;
            mLastTick2 = tms / 70;
            ui.textCentered(c, tag.substring(0, mTagShown)
                            + (tms / 500 % 2 == 0 ? "_" : ""),
                    midX, ly + lh + 44, 15, mPal.fg, false, false);
            if (mTagShown >= tag.length()
                    && tms % 1200 < 800) {
                ui.textCentered(c, "[ tap anywhere to begin ]",
                        midX, ly + lh + 96, 14, mPal.dim, true, true);
            }
        }

        ui.hit(0, 0, w, h, new Runnable() {
            @Override public void run() {
                if (mTagShown >= "welcome to YOUR operating system".length()) {
                    go(PAGE_PAIR);
                }
            }
        });
        ui.textCentered(c, "AstOs 2.4 first-boot setup", midX, h - 18, 11,
                mPal.dim, false, true);
    }

    private long mLastTick = -1;
    private long mLastTick2 = -1;

    // ---- PAIR ------------------------------------------------------------

    private void drawPair(PixelUi ui, android.graphics.Canvas c, float w,
                          float h, long tms) {
        float midX = w / 2f;
        ui.textCentered(c, "SET UP FROM YOUR PHONE", midX, 62, 20, mPal.fg,
                true, true);
        ui.textCentered(c, "this tablet just opened its own setup network",
                midX, 84, 12, mPal.dim, false, false);

        float margin = 18, gap = 14;
        float colW = (w - margin * 2 - gap * 2) / 3f;
        float cardY = 100;
        float statusRowY = h - 64;
        float cardH = statusRowY - cardY - 14;

        // ---- card 1: CONNECT ----
        float x1 = margin;
        ui.card(c, x1, cardY, colW, cardH, 12, "start".equals(mPairStage));
        numChip(ui, c, x1 + 16, cardY + 14, 1);
        ui.text(c, "CONNECT", x1 + 52, cardY + 30, 13, mPal.accent, true, true);
        float c1midY = cardY + cardH * 0.42f;
        ui.icon(c, PixelIcon.WIFI, x1 + colW / 2f, c1midY - 26, 44);
        // the setup SSID gets the VT323 display face, accent colored
        ui.textCenteredVt(c, mApSsdForDisplay(), x1 + colW / 2f, c1midY + 34,
                26, mPal.accent);
        String sub1 = "lan".equals(mPairStage)
                ? "hotspot unavailable - the tablet is on your wifi instead"
                : "open network - no password needed";
        List<String> l1 = ui.wrap(sub1, 11, colW - 28, 2);
        for (int i = 0; i < l1.size(); i++) {
            ui.textCentered(c, l1.get(i), x1 + colW / 2f, c1midY + 52 + i * 15,
                    11, mPal.dim, false, false);
        }

        // ---- card 2: SCAN (the QR — inside its own card, overlaps nothing) ----
        float x2 = margin + colW + gap;
        ui.card(c, x2, cardY, colW, cardH, 12, true);
        numChip(ui, c, x2 + 16, cardY + 14, 2);
        ui.text(c, "SCAN", x2 + 52, cardY + 30, 13, mPal.accent, true, true);

        float qrSize = Math.min(colW - 44, cardH - 110);
        if (mQr != null) {
            // cream plate + dark-module QR = instantly scannable
            float qx = x2 + (colW - qrSize) / 2f;
            float qy = cardY + 44;
            android.graphics.RectF plate = new android.graphics.RectF(
                    qx - 10, qy - 10, qx + qrSize + 10, qy + qrSize + 10);
            android.graphics.Paint p = mPaint;
            p.setAntiAlias(true);
            p.setColor(0xFFF2EBDD);
            c.drawRoundRect(plate, 12, 12, p);
            ui.bitmap(c, mQr, qx, qy, qrSize, true);
            // url under the code
            ui.textCentered(c, mUrl, x2 + colW / 2f, qy + qrSize + 30, 12,
                    mPal.accent, true, true);
            ui.textCentered(c, "(or type it in your phone's browser)",
                    x2 + colW / 2f, qy + qrSize + 48, 10, mPal.dim, false, false);
        } else {
            // hotspot still spinning up
            ui.spinner(c, x2 + colW / 2f, cardY + 44 + qrSize / 2f,
                    Math.min(26, qrSize / 3f), tms, mPal.accent);
            ui.textCentered(c, mPairStatus.isEmpty()
                            ? "opening the setup network..." : clip(mPairStatus, 30),
                    x2 + colW / 2f, cardY + cardH - 16, 11, mPal.dim, false, false);
        }

        // ---- card 3: SEND ----
        float x3 = margin + (colW + gap) * 2;
        ui.card(c, x3, cardY, colW, cardH, 12, false);
        numChip(ui, c, x3 + 16, cardY + 14, 3);
        ui.text(c, "SEND", x3 + 52, cardY + 30, 13, mPal.accent, true, true);
        float c3y = cardY + 56;
        ui.icon(c, PixelIcon.PHONE, x3 + colW / 2f, c3y + 16, 34);
        List<String> l3 = ui.wrap("the short form does the whole setup: your home "
                        + "wifi, city, AI key and theme",
                12, colW - 28, 4);
        for (int i = 0; i < l3.size(); i++) {
            ui.textCentered(c, l3.get(i), x3 + colW / 2f, c3y + 54 + i * 17,
                    12, mPal.fg, false, false);
        }
        if (!mReceived) {
            ui.spinner(c, x3 + colW / 2f, cardY + cardH - 34, 12, tms, mPal.accent);
            ui.textCentered(c, "waiting for your phone...",
                    x3 + colW / 2f, cardY + cardH - 12, 11, mPal.dim, false, false);
        } else {
            ui.icon(c, PixelIcon.CHECK, x3 + colW / 2f, cardY + cardH - 30, 18);
            ui.textCentered(c, "RECEIVED - setting up...",
                    x3 + colW / 2f, cardY + cardH - 12, 11, mPal.accent, true, false);
        }

        // ---- status row ----
        String st = !mPairStatus.isEmpty() ? mPairStatus : " ";
        ui.textCentered(c, clip(st, 64), midX, statusRowY + 16,
                13, mReceived ? mPal.accent : mPal.dim, mReceived, false);

        // "no phone" fallback pill
        String noPhone = "no phone? set up wifi on the tablet";
        float pw = ui.textWidth(noPhone, 11, true) + 24;
        ui.pill(c, midX - pw / 2f, statusRowY + 26, pw, 30, false, 9);
        ui.textCentered(c, noPhone, midX, statusRowY + 46, 11, mPal.fg, true, true);
        ui.hit(midX - pw / 2f, statusRowY + 26, pw, 30, new Runnable() {
            @Override public void run() { go(PAGE_LOCAL); }
        });
    }

    private String mApSsdForDisplay() {
        return "lan".equals(mPairStage) ? "(on your wifi)" : mApSsid;
    }

    private void numChip(PixelUi ui, android.graphics.Canvas c, float x, float y, int n) {
        ui.pill(c, x, y, 26, 26, true, 6);
        ui.textCentered(c, String.valueOf(n), x + 13, y + 18, 13, mPal.bg, true, true);
    }

    // ---- LOCAL (no-phone fallback) ---------------------------------------

    private void drawLocal(PixelUi ui, android.graphics.Canvas c, float w,
                           float h, long tms) {
        float midX = w / 2f;
        ui.textCentered(c, "PICK YOUR HOME WIFI", midX, 56, 18, mPal.fg, true, true);
        ui.textCentered(c, "this is the phone-less fallback - everything else "
                        + "can be set later in AstOs",
                midX, 76, 11, mPal.dim, false, false);

        float listW = Math.min(w - 60, 620);
        float lx = midX - listW / 2f;
        float ly = 92;
        int shown = Math.min(6, mNetworks.size());
        float rowH = 46;
        ui.card(c, lx, ly, listW, 16 + shown * rowH, 12, false);
        if (mNetworks.isEmpty()) {
            ui.textCentered(c, mLocalStatus.isEmpty()
                            ? "scanning for networks..." : mLocalStatus,
                    midX, ly + 40, 13, mPal.dim, false, false);
        }
        for (int i = 0; i < shown; i++) {
            ScanResult s = mNetworks.get(i);
            float ry = ly + 10 + i * rowH;
            boolean sel = i == mLocalSel;
            if (sel) ui.pill(c, lx + 8, ry + 2, listW - 16, rowH - 6, false, 8);
            String sig = s.level > -60 ? "###" : s.level > -75 ? "## " : "#  ";
            boolean locked = isLocked(s);
            ui.text(c, clip(s.SSID, 30), lx + 24, ry + 20, 14,
                    sel ? mPal.accent : mPal.fg, sel, false);
            ui.text(c, sig + (locked ? "  locked" : "  open"),
                    lx + listW - 24 - ui.textWidth(sig + (locked ? "  locked" : "  open"), 11, true),
                    ry + 26, 11, mPal.dim, true, true);
            final int idx = i;
            ui.hit(lx + 8, ry + 2, listW - 16, rowH - 6, new Runnable() {
                @Override public void run() { localTap(idx); }
            });
        }

        float by = ly + 16 + shown * rowH + 22;
        float bw = 190;
        ui.pill(c, midX - bw - 10, by, bw, 40, false, 10);
        ui.textCentered(c, "RESCAN", midX - 10 - bw / 2f, by + 26, 13, mPal.fg, true, true);
        ui.hit(midX - bw - 10, by, bw, 40, new Runnable() {
            @Override public void run() { rescan(); }
        });
        ui.pill(c, midX + 10, by, bw, 40, false, 10);
        ui.textCentered(c, "SKIP WIFI", midX + 10 + bw / 2f, by + 26, 13,
                mPal.fg, true, true);
        ui.hit(midX + 10, by, bw, 40, new Runnable() {
            @Override public void run() { go(PAGE_DOWNLOADS); }
        });
    }

    private void localTap(final int i) {
        mLocalSel = i;
        final ScanResult s = mNetworks.get(i);
        if (!isLocked(s)) {
            joinWifi(s.SSID, null);
            go(PAGE_CONNECT);
            return;
        }
        final EditText in = new EditText(this);
        in.setTypeface(Typeface.MONOSPACE);
        in.setTextSize(16f);
        in.setTextColor(0xFFF2E7DA);
        in.setHint("password for " + s.SSID);
        in.setHintTextColor(0xFF8A6F4D);
        in.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        new AlertDialog.Builder(this, R.style.AstSetupDialog)
                .setTitle("Join " + s.SSID)
                .setView(in)
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        joinWifi(s.SSID, in.getText().toString());
                        go(PAGE_CONNECT);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static boolean isLocked(ScanResult s) {
        return s.capabilities != null && (s.capabilities.contains("WPA")
                || s.capabilities.contains("WEP") || s.capabilities.contains("EAP"));
    }

    // ---- CONNECT ----------------------------------------------------------

    private void drawConnect(PixelUi ui, android.graphics.Canvas c, float w,
                             float h, long tms) {
        float midX = w / 2f;
        float cy = h * 0.34f;
        boolean ok = "ok".equals(mConnState);
        boolean fail = "fail".equals(mConnState);

        if (!fail) {
            // concentric pulse rings (smooth graphics, not ascii)
            for (int i = 0; i < 3; i++) {
                float ph = ((tms / 1200.0f) + i / 3f) % 1f;
                float r = 30 + ph * 60;
                mPaint.reset();
                mPaint.setAntiAlias(true);
                mPaint.setStyle(android.graphics.Paint.Style.STROKE);
                mPaint.setStrokeWidth(2);
                mPaint.setColor(AsciiTheme.dimColor(mPal.accent,
                        0.5f * (1f - ph), mPal.bg));
                c.drawCircle(midX, cy, r, mPaint);
            }
        }
        ui.icon(c, PixelIcon.WIFI, midX, cy, 52);
        ui.textCentered(c, ok ? "CONNECTED" : fail ? "COULDN'T JOIN"
                        : "JOINING " + clip(mWifiSsid, 24) + "...",
                midX, cy + 84, 18, ok ? mPal.accent : mPal.fg, true, true);

        if (!ok && !fail) {
            ui.spinner(c, midX, cy + 124, 14, tms, mPal.accent);
        }

        String sub;
        if (ok) {
            sub = mCity.isEmpty() ? "moving on..." : "locating " + mCity + "...";
        } else if (fail) {
            sub = "check the password / move closer to the router";
        } else {
            sub = "the tablet left the setup hotspot and is joining your home wifi";
        }
        ui.textCentered(c, sub, midX, cy + 156, 12, mPal.dim, false, false);

        if (fail) {
            float bw = 210;
            float by = cy + 186;
            ui.pill(c, midX - bw - 10, by, bw, 42, true, 10);
            ui.textCentered(c, "TRY AGAIN", midX - 10 - bw / 2f, by + 27, 13,
                    mPal.bg, true, true);
            ui.hit(midX - bw - 10, by, bw, 42, new Runnable() {
                @Override public void run() {
                    joinWifi(mWifiSsid, mWifiPass);
                }
            });
            ui.pill(c, midX + 10, by, bw, 42, false, 10);
            ui.textCentered(c, "PICK ANOTHER", midX + 10 + bw / 2f, by + 27, 13,
                    mPal.fg, true, true);
            ui.hit(midX + 10, by, bw, 42, new Runnable() {
                @Override public void run() {
                    rescan();
                    go(PAGE_LOCAL);
                }
            });
        }
    }

    // ---- DOWNLOADS --------------------------------------------------------

    private void drawDownloads(PixelUi ui, android.graphics.Canvas c, float w,
                               float h, long tms) {
        float midX = w / 2f;
        ui.textCentered(c, "Setup is almost finished,", midX, h * 0.30f, 18,
                mPal.fg, true, false);
        ui.textCentered(c, "just downloading a few more things...", midX,
                h * 0.30f + 28, 18, mPal.fg, true, false);
        ui.textCentered(c, "(the offline voice model - ~40 MB, one-time)",
                midX, h * 0.30f + 54, 12, mPal.dim, false, false);

        float barW = Math.min(w - 90, 420);
        float barX = midX - barW / 2f;
        float barY = h * 0.30f + 86;
        if (mModelPct >= 0) {
            ui.progressBar(c, barX, barY, barW, 8, mModelPct / 100f, true);
            ui.textCentered(c, mModelPct + "%", midX, barY + 26, 14,
                    mPal.accent, true, true);
        } else if (mModelPct == -1) {
            float frac = 0.5f + 0.45f * (float) Math.sin(tms / 400.0);
            ui.progressBar(c, barX, barY, barW, 8, frac, true);
            ui.textCentered(c, "starting...", midX, barY + 26, 12,
                    mPal.dim, false, false);
        }

        if (!mModelNote.isEmpty()) {
            List<String> ln = ui.wrap(mModelNote, 12, barW + 60, 2);
            for (int i = 0; i < ln.size(); i++) {
                ui.textCentered(c, ln.get(i), midX, barY + 52 + i * 17, 12,
                        mPal.dim, false, false);
            }
        }

        float by = barY + 100;
        if (mModelDlFailed) {
            float bw = 160;
            ui.pill(c, midX - bw / 2f, by, bw, 40, true, 10);
            ui.textCentered(c, "RETRY", midX, by + 26, 13, mPal.bg, true, true);
            ui.hit(midX - bw / 2f, by, bw, 40, new Runnable() {
                @Override public void run() { startModelDownload(); }
            });
            by += 52;
        }
        if (!mModelBusy && (mModelPct >= 100 || mModelPct == -2)) {
            float bw = 200;
            ui.pill(c, midX - bw / 2f, by, bw, 42, true, 10);
            ui.textCentered(c, mModelPct >= 100 ? "CONTINUE" : "SKIP FOR NOW",
                    midX, by + 27, 13, mPal.bg, true, true);
            ui.hit(midX - bw / 2f, by, bw, 42, new Runnable() {
                @Override public void run() { go(PAGE_DONE); }
            });
        }
    }

    // ---- DONE -------------------------------------------------------------

    private void drawDone(PixelUi ui, android.graphics.Canvas c, float w,
                          float h, long tms) {
        float midX = w / 2f;
        float block = Math.min(w / 60f, h / 34f);
        float lw = PixelIcon.measureBlockText("AstOs", block);
        float ly = h * 0.26f;
        // gentle breathing pulse between accent and fg (pixel blocks)
        boolean pulse = tms / 600 % 2 == 0;
        PixelIcon.drawBlockText(c, "AstOs", midX - lw / 2f, ly, block,
                pulse ? mPal.accent : mPal.fg, mPaint,
                PixelIcon.countBlocks("AstOs"));

        ui.textCentered(c, "AstOs is ready.", midX, ly + block * 5 + 52, 18,
                mPal.fg, true, false);
        String dateLine = new SimpleDateFormat("EEEE, MMMM d - HH:mm", Locale.US)
                .format(new Date());
        ui.textCentered(c, dateLine + "   your OS is set up", midX,
                ly + block * 5 + 80, 12, mPal.dim, false, false);

        float bw = 240;
        float by = ly + block * 5 + 110;
        boolean blink = tms / 500 % 2 == 0;
        ui.pill(c, midX - bw / 2f, by, bw, 48, true, 12);
        ui.textCentered(c, blink ? "[  ENTER ASTOS  ]" : "[ ENTER ASTOS ]",
                midX, by + 30, 15, mPal.bg, true, true);
        ui.hit(midX - bw / 2f, by, bw, 48, new Runnable() {
            @Override public void run() { finishSetup(); }
        });
    }

    // ------------------------------------------------------------------ flow

    private void go(int page) {
        mErr = "";
        int old = mPage;
        mPage = page;

        if (old == PAGE_PAIR && page != PAGE_CONNECT && page != PAGE_DOWNLOADS) {
            // leaving pair without a config: tear the hotspot + server down
            if (!mReceived) onPairTeardown();
        }
        switch (page) {
            case PAGE_PAIR:
                onPairEnter();
                break;
            case PAGE_LOCAL:
                if (mNetworks.isEmpty()) rescan();
                break;
            case PAGE_DOWNLOADS:
                onDownloadsEnter();
                break;
        }
        if (old == PAGE_PAIR && (page == PAGE_CONNECT || page == PAGE_DOWNLOADS)) {
            onPairTeardown();
        }
    }

    // ---- pair lifecycle ----------------------------------------------------

    private void onPairEnter() {
        mReceived = false;
        mPairStage = "start";
        mQr = null;
        mUrl = "";
        mPairStatus = "opening the setup network...";

        // 1) live scan for the phone page (the wifi client is still up now)
        rescanQuiet();

        // 2) the http server (binds all interfaces: hotspot + lan)
        if (mPair == null) mPair = new PairServer(this, this);
        mPair.setNetworks(mNetworks);
        mPair.start();

        // 3) the AstSetup_xxxx hotspot
        SoftAp.start(this, new SoftAp.Callback() {
            @Override public void onApReady(String ssid, String ip) {
                mPairStage = "ready";
                mUrl = "http://" + ip + ":" + PairServer.PORT;
                mQr = Qr.encode(mUrl, 480, 0xFF2A241C, 0xFFF8F2E4); // ink on panel
                mPairStatus = "waiting for your phone...";
            }

            @Override public void onApFailed(String reason) {
                // dev / side-loaded build: LAN pairing fallback
                mPairStage = "lan";
                String ip = PairServer.lanIp();
                if (ip != null) {
                    mUrl = "http://" + ip + ":" + PairServer.PORT;
                    mQr = Qr.encode(mUrl, 480, 0xFF2A241C, 0xFFF8F2E4);
                    mPairStatus = "hotspot unavailable - join the same wifi, then scan";
                } else {
                    mPairStatus = "no network - use the fallback below";
                }
            }
        });
    }

    private void onPairTeardown() {
        if (mPair != null) mPair.stop();
        // NOTE: SoftAp.stop is called explicitly when we actually LEAVE for
        // connect/downloads (after the config arrived); backing out to
        // welcome/local keeps the hotspot up so the user can come right back.
    }

    // ---- wifi scanning / joining ------------------------------------------

    private void rescan() {
        rescanQuiet();
        mLocalStatus = mNetworks.isEmpty() ? "no networks found - rescan"
                : mNetworks.size() + " networks";
    }

    private void rescanQuiet() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
                return;
            }
        } catch (Throwable ignored) {
            // targetSdk 25 on a 7.1 ROM: runtime check exists but is granted
            // by default; keep going on any failure
        }
        try {
            // Android 7 gates scan results behind the system location switch;
            // we are platform-signed, so turn it on quietly if it is off.
            try {
                if (Settings.Secure.getInt(getContentResolver(),
                        Settings.Secure.LOCATION_MODE, 0) == 0) {
                    Settings.Secure.putInt(getContentResolver(),
                            Settings.Secure.LOCATION_MODE, 3);
                }
            } catch (Throwable ignored) {}
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm == null) return;
            wm.startScan();
            List<ScanResult> found = wm.getScanResults();
            if (found == null) found = new ArrayList<ScanResult>();
            List<ScanResult> out = new ArrayList<ScanResult>();
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (ScanResult s : found) {
                if (s.SSID == null || s.SSID.isEmpty() || seen.contains(s.SSID)) continue;
                seen.add(s.SSID);
                out.add(s);
            }
            java.util.Collections.sort(out, new java.util.Comparator<ScanResult>() {
                @Override public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level);
                }
            });
            mNetworks = out;
            if (mPair != null) mPair.setNetworks(out);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 7) rescanQuiet();
    }

    private void joinWifi(String ssid, String pass) {
        mWifiSsid = ssid;
        mWifiPass = pass == null ? "" : pass;
        mConnState = "joining";
        mConnT0 = android.os.SystemClock.elapsedRealtime();
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = "\"" + ssid + "\"";
            if (mWifiPass.isEmpty()) {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                cfg.preSharedKey = "\"" + mWifiPass + "\"";
            }
            int id = wm.addNetwork(cfg);
            if (id != -1) {
                wm.disconnect();
                wm.enableNetwork(id, true);
                wm.reconnect();
            } else {
                mConnState = "fail";
                mErr = "wifi join failed";
                return;
            }
        } catch (Throwable e) {
            mConnState = "fail";
            mErr = "wifi error: " + e.getMessage();
            return;
        }
        mH.removeCallbacks(mConnPoll);
        mH.postDelayed(mConnPoll, 1200);
    }

    private void pollConnect() {
        if (mPage != PAGE_CONNECT || !"joining".equals(mConnState)) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            String cur = wm.getConnectionInfo().getSSID();
            boolean joined = cur != null
                    && cur.replace("\"", "").equals(mWifiSsid)
                    && networkConnected();
            if (joined) {
                mConnState = "ok";
                onWifiJoined();
                return;
            }
        } catch (Throwable ignored) {}
        if (android.os.SystemClock.elapsedRealtime() - mConnT0 > 45_000) {
            mConnState = "fail";
            return;
        }
        mH.postDelayed(mConnPoll, 1200);
    }

    private void onWifiJoined() {
        if (!mCity.isEmpty()) geocodeCity(mCity);
        mH.postDelayed(new Runnable() {
            @Override public void run() {
                if (mPage == PAGE_CONNECT) go(PAGE_DOWNLOADS);
            }
        }, 1400);
    }

    private boolean networkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    private void geocodeCity(final String city) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String json = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + URLEncoder.encode(city, "UTF-8"));
                    JSONObject root = new JSONObject(json);
                    if (root.getJSONArray("results").length() > 0) {
                        JSONObject r = root.getJSONArray("results").getJSONObject(0);
                        final float lat = (float) r.getDouble("latitude");
                        final float lon = (float) r.getDouble("longitude");
                        final String name = r.optString("name", city);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                mCityResolved = name;
                                try {
                                    Settings.Global.putFloat(getContentResolver(),
                                            "astos_lat", lat);
                                    Settings.Global.putFloat(getContentResolver(),
                                            "astos_lon", lon);
                                } catch (Throwable ignored) {}
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            }
        }, "geo").start();
    }

    // ---- downloads ---------------------------------------------------------

    private void onDownloadsEnter() {
        mModelAutoAdvanced = false;
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
                if (!mModelAutoAdvanced) {
                    mModelAutoAdvanced = true;
                    mH.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (mPage == PAGE_DOWNLOADS) go(PAGE_DONE);
                        }
                    }, 1600);
                }
            }
            @Override public void onError(String msg) {
                mModelBusy = false;
                mModelDlFailed = true;
                mModelNote = "download failed: " + msg;
            }
        });
    }

    // ---- PairServer.Callback -----------------------------------------------

    @Override public void onPosted(final String wifiSsid, final String wifiPass,
                                   final String haUrl, final String haToken,
                                   final String city, final String groq,
                                   final String theme, final String h24) {
        mReceived = true;
        mPairStatus = "RECEIVED - settings saved from your phone";
        if (haUrl != null && !haUrl.trim().isEmpty()) mHaUrl = haUrl.trim();
        if (haToken != null && !haToken.trim().isEmpty()) mHaToken = haToken.trim();
        if (groq != null && !groq.trim().isEmpty()) mGroqKey = groq.trim();
        if (city != null && !city.trim().isEmpty()) mCity = city.trim();
        if (h24 != null) mH24 = !("0".equals(h24.trim()) || "false".equalsIgnoreCase(h24.trim()));
        if (theme != null && !theme.trim().isEmpty()) {
            mPaletteName = AsciiTheme.byName(theme.trim()).name; // validated
            mBgMode = "DOTS";                                  // the pixel backdrop
            applyLook();
        }

        mH.postDelayed(new Runnable() {
            @Override public void run() {
                if (!mReceived) return;
                boolean hasWifi = wifiSsid != null && !wifiSsid.trim().isEmpty();
                SoftAp.stop(SetupActivity.this);      // hotspot down, client up
                if (hasWifi) {
                    joinWifi(wifiSsid.trim(),
                            wifiPass == null ? "" : wifiPass.trim());
                    go(PAGE_CONNECT);
                } else {
                    go(PAGE_DOWNLOADS);
                }
            }
        }, 1000);
    }

    @Override public void onLog(String line) { /* quiet */ }

    private void applyLook() {
        mPal = AsciiTheme.byName(mPaletteName);
        mUi.setPalette(mPal);
        mUi.setBgMode(mBgMode);
        mRoot.setBackgroundColor(mPal.bg);
    }

    // ---- finish -------------------------------------------------------------

    private void finishSetup() {
        ContentResolver cr = getContentResolver();
        try {
            Settings.Global.putString(cr, "astos_theme", mPaletteName);
            Settings.Global.putString(cr, "astos_palette", mPaletteName); // legacy 2.x readers
            Settings.Global.putString(cr, "astos_bg", "DOTS");
            Settings.Global.putString(cr, "astos_h24", mH24 ? "1" : "0");
            Settings.Global.putString(cr, "astos_anim", "1");
            if (!mGroqKey.isEmpty()) Settings.Global.putString(cr, "astos_groq_key", mGroqKey);
            if (!mCity.isEmpty()) Settings.Global.putString(cr, "astos_city", mCity);
            if (!mCityResolved.isEmpty() && !"located later".equals(mCityResolved))
                Settings.Global.putString(cr, "astos_city_resolved", mCityResolved);
            if (!mHaUrl.isEmpty()) Settings.Global.putString(cr, "astos_ha_url", mHaUrl);
            if (!mHaToken.isEmpty()) Settings.Global.putString(cr, "astos_ha_token", mHaToken);

            // provision the device ourselves — no CM setup wizard exists in
            // this ROM, so we write the flags the framework expects after setup
            Settings.Global.putInt(cr, Settings.Global.DEVICE_PROVISIONED, 1);
            Settings.Secure.putInt(cr, "user_setup_complete", 1);
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
        if (mPage == PAGE_PAIR) {
            if (!mReceived) onPairTeardown();
            mPage = PAGE_WELCOME;
            return;
        }
        if (mPage == PAGE_LOCAL) { mPage = PAGE_PAIR; onPairEnter(); return; }
        if (mPage == PAGE_DOWNLOADS) { mPage = PAGE_LOCAL; return; }
        // welcome / connect / done: no back out of setup
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mH.removeCallbacksAndMessages(null);
        if (mPair != null) mPair.stop();
    }
}
