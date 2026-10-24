package com.astos.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AstShell 2.4 — the pixel home of AstOs.
 *
 * The v2.x ASCII character-grid dashboard was too cluttered, mis-scaled
 * and hard to read, so the shell now renders a clean, spacious canvas UI
 * (PixelUi + PixelIcon): big blocky pixel clock, warm cards, chunky
 * pixel-art icons, and smooth sine-wave animations — ASCII art now only
 * lives in the boot animation, where it belongs.
 *
 * Four pages: HOME, MUSIC (Discord-bot style on-demand songs), ASSIST
 * (weather/news/date/AI), SETUP (theme, background, keys).
 * Everything is laid out in dp from the measured view size, so it scales
 * properly on the 1024x600 screen — nothing out of frame anymore.
 */
public class HomeActivity extends Activity
        implements CommandRouter.Actions, VoiceEngine.Callback, MusicEngine.Listener {

    public static volatile Context sAppContext;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_MUSIC = 1;
    private static final int PAGE_ASSIST = 2;
    private static final int PAGE_SETUP = 3;
    private static final String[] TABS = {"HOME", "MUSIC", "ASSIST", "SETUP"};
    private static final String[] TAB_ICONS =
            {PixelIcon.HOME, PixelIcon.NOTE, PixelIcon.CHAT, PixelIcon.SLIDERS};

    private PixelUi mUi;
    private AsciiTheme.Palette mPal;
    private EditText mInput;
    private FrameLayout mRoot;

    private int mPage = PAGE_HOME;
    private String mInputMode = ""; // "", "song", "groq", "city", "ha"

    // state
    private WeatherProvider.WeatherInfo mWeather;
    private List<NewsProvider.Headline> mNews;
    private String mReply = "";              // assistant reply (typewriter)
    private String mReplyFull = "";
    private int mReplyShown = 0;
    private String mPartial = "";            // live voice partial
    private Bitmap mCoverArt;
    private CountDownTimer mTimer;
    private long mTimerEnds;

    // page transition animation (smooth slide, not ascii wipe)
    private long mPageAnimStart = -1;
    private int mPageAnimDir;

    // voice model download progress (slim bar on HOME)
    private int mModelPct = -2;              // -2 idle, -1 indeterminate, 0..100
    private String mModelNote = "";
    private boolean mModelBusy = false;

    // smooth equalizer state (0..100 per bar)
    private final int[] mEq = new int[9];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // first boot: run the AstOs setup wizard before anything else
        // (astos_setup_done defaults to 0 — a fresh flash ALWAYS gets the wizard)
        if (!setupDone(this)) {
            try {
                startActivity(new Intent().setClassName("com.astos.setup",
                        "com.astos.setup.SetupActivity"));
                finish();
                return;
            } catch (Throwable ignored) {
                // wizard not installed (dev build) — carry on
            }
        }

        sAppContext = this;
        Speak.init(this);
        MusicEngine.listen(this);

        // system lockdown: no lock screen, no shade, no nav bar, our keyboard
        Kiosk.apply(this);
        // persistent shade-killer heartbeat (v2.4: the shade must NEVER open)
        KioskService.ensureRunning(this);

        buildUi();

        if (AppSettings.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (AppSettings.kiosk(this)) applyFullscreen(true);

        registerReceiver(mTickRecv, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(mTickRecv, new IntentFilter(Intent.ACTION_TIME_TICK));
        registerReceiver(mTickRecv, new IntentFilter(Intent.ACTION_SCREEN_ON));

        // voice model loads in background — it is NOT bundled anymore:
        // after setup, on WiFi, it downloads on-device with a progress line
        VoiceEngine.get().prepare(this, new VoiceEngine.ReadyListener() {
            @Override public void onReady(boolean ok, String message) {
                if (!ok) {
                    if (VoiceEngine.MISSING_MODEL.equals(message)) onModelMissing();
                    else toast(message);
                }
            }
        });

        WeatherProvider.refresh(this, new WeatherProvider.Callback() {
            @Override public void onWeather(WeatherProvider.WeatherInfo w, String error) {
                if (w != null) mWeather = w;
            }
        });
    }

    private static boolean setupDone(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), "astos_setup_done", 0) == 1;
        } catch (Throwable t) { return false; }
    }

    // ---------------------------------------------------------------- voice model

    /** Model not on the device: auto-download on WiFi, else prompt on the MIC. */
    private void onModelMissing() {
        if (mModelBusy) return;
        if (networkConnected()) {
            downloadModel();
        } else {
            mModelPct = -2;
            mModelNote = "voice model: connect WiFi, then tap MIC to download";
        }
    }

    private boolean networkConnected() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Throwable t) { return false; }
    }

    private void downloadModel() {
        if (mModelBusy) return;
        mModelBusy = true;
        mModelPct = -1;
        mModelNote = "Setup is almost finished, just downloading a few more things...";
        ModelDownloader.download(this, new ModelDownloader.Listener() {
            @Override public void onProgress(int pct, String note) {
                mModelPct = pct;
                if (note != null) mModelNote = note;
            }
            @Override public void onDone() {
                mModelBusy = false;
                mModelPct = -2;
                mModelNote = "";
                VoiceEngine.get().prepare(HomeActivity.this, new VoiceEngine.ReadyListener() {
                    @Override public void onReady(boolean ok, String message) {
                        if (ok) toast("Voice ready");
                        else toast(message);
                    }
                });
            }
            @Override public void onError(String msg) {
                mModelBusy = false;
                mModelPct = -2;
                mModelNote = "voice model download failed - tap MIC to retry";
            }
        });
    }

    // ---------------------------------------------------------------- UI construction

    private void buildUi() {
        mPal = AsciiTheme.byName(AppSettings.palette(this));

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(mPal.bg);

        mUi = new PixelUi(this);
        mUi.setPalette(mPal);
        mUi.setUiScale(uiScale());
        mUi.setBgMode(AppSettings.bgMode(this));
        mRoot.addView(mUi, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        // terminal-style input bar overlay (restyled, IME-safe)
        mInput = new EditText(this);
        mInput.setTypeface(Typeface.MONOSPACE);
        mInput.setTextColor(mPal.fg);
        mInput.setTextSize(15f);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(10));
        inputBg.setColor(Color.argb(230, Color.red(mPal.bg), Color.green(mPal.bg),
                Color.blue(mPal.bg)));
        inputBg.setStroke(Math.max(2, (int) dp(0.7f)),
                AsciiTheme.dimColor(mPal.accent, 0.6f, mPal.bg));
        mInput.setBackground(inputBg);
        mInput.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));
        mInput.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        int mar = (int) dp(16);
        lp.leftMargin = mar;
        lp.rightMargin = mar;
        lp.bottomMargin = (int) dp(10);
        mRoot.addView(mInput, lp);
        mInput.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    submitInput();
                    return true;
                }
                return false;
            }
        });

        setContentView(mRoot);

        mUi.setRenderer(new PixelUi.Renderer() {
            @Override public void onFrame(PixelUi ui, android.graphics.Canvas c, long tms) {
                drawFrame(ui, c, tms);
            }
        });
        mUi.setSwipeListener(new PixelUi.SwipeListener() {
            @Override public void onSwipe(int dir) {
                switchPage(dir < 0 ? (mPage + 1) % 4 : (mPage + 3) % 4);
            }
        });
        mUi.start();
    }

    private float uiScale() {
        int f = AppSettings.fontPx(this);
        if (f >= 24) return 1.18f;
        if (f <= 16) return 0.92f;
        return 1f;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- frame render

    private void drawFrame(PixelUi ui, android.graphics.Canvas c, long tms) {
        AsciiTheme.Palette pal = AsciiTheme.byName(AppSettings.palette(this));
        if (pal != mPal) {
            mPal = pal;
            ui.setPalette(pal);
            mRoot.setBackgroundColor(pal.bg);
        }

        float w = ui.widthDp(), h = ui.heightDp();
        ui.drawBackground(c, tms);

        // top status strip + bottom nav frame every page
        float topH = drawTopBar(ui, c, w);
        float navY = h - 72 - 10;
        drawNav(ui, c, w, h, navY);

        // page content, with a smooth slide transition
        float cy = topH + 6;
        float ch = navY - 12 - cy;
        int save = c.save();
        float slide = 0f, alpha = 1f;
        if (mPageAnimStart > 0) {
            float p = Math.min(1f, (tms - mPageAnimStart) / 220f);
            float ease = 1f - (1f - p) * (1f - p);   // ease-out quad
            slide = mPageAnimDir * (1f - ease) * w * 0.22f;
            alpha = 0.25f + 0.75f * ease;
            if (p >= 1f) mPageAnimStart = -1;
        }
        c.translate(slide * dp(1), 0);
        c.saveLayerAlpha(0, cy * dp(1), w * dp(1), (cy + ch) * dp(1),
                (int) (alpha * 255));
        switch (mPage) {
            case PAGE_HOME:  drawHome(ui, c, w, cy, ch, tms); break;
            case PAGE_MUSIC: drawMusic(ui, c, w, cy, ch, tms); break;
            case PAGE_ASSIST: drawAssist(ui, c, w, cy, ch, tms); break;
            case PAGE_SETUP: drawSetup(ui, c, w, cy, ch, tms); break;
        }
        c.restore();
        c.restoreToCount(save);

        // live voice partial — always visible while listening
        if (!mPartial.isEmpty()) {
            String s = clip(mPartial, 48);
            ui.textCentered(c, s, w / 2f, navY - 12, 14, mPal.accent, true, false);
        }

        // timer chip (top-right, below the bar)
        if (mTimer != null) {
            long left = Math.max(0, mTimerEnds - System.currentTimeMillis());
            String s = "timer " + fmtMs(left);
            float tw = ui.textWidth(s, 12, true);
            ui.pill(c, w - tw - 22, topH + 4, tw + 14, 22, false, 6);
            ui.text(c, s, w - tw - 15, topH + 20, 12, mPal.accent, true, true);
        }
    }

    /** Top status strip: wordmark left, connectivity + battery right. */
    private float drawTopBar(PixelUi ui, android.graphics.Canvas c, float w) {
        // wordmark: blocky AstOs + two accent pixels
        ui.text(c, "AstOs", 14, 22, 17, mPal.fg, true, true);
        ui.pill(c, 58, 10, 5, 5, true, 1);
        ui.pill(c, 58, 17, 5, 5, false, 1);

        String stat = batteryPct() + "%";
        float sw = ui.textWidth(stat, 13, true);
        ui.text(c, stat, w - sw - 8, 22, 13, mPal.dim, true, true);
        ui.icon(c, PixelIcon.BATTERY, w - sw - 30, 16, 14);
        ui.icon(c, wifiUp() ? PixelIcon.WIFI : PixelIcon.CHAT,
                w - sw - 52, 16, 14);
        return 34f;
    }

    /** Bottom navigation: four big, readable cards — tap or swipe. */
    private void drawNav(PixelUi ui, android.graphics.Canvas c, float w, float h,
                         float navY) {
        float gap = 10;
        float bw = (w - gap * 5) / 4f;
        float bh = 60;
        float y = h - bh - 10;
        for (int i = 0; i < 4; i++) {
            float x = gap + i * (bw + gap);
            boolean active = i == mPage;
            ui.card(c, x, y, bw, bh, 10, active);
            float cx = x + bw / 2f;
            ui.icon(c, TAB_ICONS[i], cx, y + bh * 0.42f, active ? 20 : 18);
            String label = active ? "[" + TABS[i] + "]" : TABS[i];
            ui.textCentered(c, label, cx, y + bh - 10, 11,
                    active ? mPal.accent : mPal.dim, active, true);
            final int page = i;
            ui.hit(x, y, bw, bh, new Runnable() {
                @Override public void run() { switchPage(page); }
            });
        }
    }

    // ---------------------------------------------------------------- HOME

    private void drawHome(PixelUi ui, android.graphics.Canvas c, float w,
                          float cy, float ch, long tms) {
        float midX = w / 2f;
        boolean anims = AppSettings.animations(this);

        // ---- big blocky pixel clock (the star of the show) ----
        String hm = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        float block = ch >= 300 ? 9f : 7f;
        float clockH = block * 7;
        float clockW = PixelIcon.measurePixelNumber(hm, block);
        PixelIcon.drawPixelNumber(c, hm, midX - clockW / 2f, cy + 8,
                block, mPal.fg, mPixelPaint);
        if (anims) {
            // subtle warm underline that breathes (smooth, not ascii)
            float breathe = 0.5f + 0.5f * (float) Math.sin(tms / 900.0);
            ui.pill(c, midX - 70, cy + 8 + clockH + 6, 140 * breathe, 3, true, 2);
        }

        // ---- date + weather line ----
        float dy = cy + 8 + clockH + 18;
        String date = new SimpleDateFormat("EEEE  ·  MMMM d", Locale.US).format(new Date());
        ui.textCentered(c, date.toUpperCase(Locale.US), midX, dy, 14, mPal.dim, false, false);

        if (mWeather != null) {
            float wy = dy + 26;
            String line = Math.round(mWeather.temp) + "\u00B0  "
                    + WeatherProvider.describe(mWeather.code).toUpperCase(Locale.US)
                    + "   ·   " + mWeather.city.toUpperCase(Locale.US);
            float tw = ui.textWidth(line, 14, false);
            ui.pill(c, midX - tw / 2f - 16, wy - 18, tw + 32, 30, false, 8);
            ui.icon(c, weatherIcon(mWeather.code), midX - tw / 2f - 4, wy - 5, 14);
            ui.text(c, line, midX - tw / 2f + 10, wy, 14, mPal.accent, true, false);
        } else {
            ui.textCentered(c, "weather loads once you set your city (SETUP)",
                    midX, dy + 20, 12, mPal.dim, false, false);
        }

        // ---- now playing card ----
        SongSearch.Track t = MusicEngine.current();
        float npY = dy + (mWeather != null ? 44 : 24);
        if (t != null || MusicEngine.isBusy()) {
            float cw = Math.min(w - 40, 500);
            float cx0 = midX - cw / 2f;
            ui.card(c, cx0, npY, cw, 62, 10, false);
            // art thumb (pixelated covers per setting)
            if (mCoverArt != null) {
                drawArtThumb(ui, c, cx0 + 31, npY + 31, 22, mCoverArt);
            } else {
                ui.icon(c, PixelIcon.NOTE, cx0 + 31, npY + 31, 20);
            }
            String status = MusicEngine.isBusy() && !MusicEngine.isPlaying()
                    ? MusicEngine.searchStatus() : t.title;
            ui.text(c, clip(status, 30), cx0 + 62, npY + 24, 14, mPal.fg, true, false);
            String sub = t == null ? "searching..." : t.artist.isEmpty() ? "unknown"
                    : t.artist;
            ui.text(c, clip(sub, 34) + (MusicEngine.isPlaying() ? "   · playing" : "   · paused"),
                    cx0 + 62, npY + 44, 11, mPal.dim, false, false);
            ui.hit(cx0, npY, cw, 62, new Runnable() {
                @Override public void run() { switchPage(PAGE_MUSIC); }
            });
            npY += 74;
        }

        // ---- model download progress ----
        if (!mModelNote.isEmpty()) {
            float bw2 = Math.min(w - 60, 380);
            String s = clip(mModelNote, 52)
                    + (mModelPct >= 0 ? "  " + mModelPct + "%" : "");
            ui.textCentered(c, s, midX, npY + 14, 11, mPal.accent, true, false);
            if (mModelPct >= 0) {
                ui.progressBar(c, midX - bw2 / 2f, npY + 24, bw2, 5,
                        mModelPct / 100f, true);
            } else if (mModelPct == -1) {
                ui.progressBar(c, midX - bw2 / 2f, npY + 24, bw2, 5,
                        0.5f + 0.45f * (float) Math.sin(tms / 400.0), true);
            }
            npY += 44;
        }

        // ---- MIC orb (voice) ----
        float orbY = Math.max(npY + 56, cy + ch - 64);
        boolean listening = VoiceEngine.get().isListening();
        ui.micOrb(c, midX, orbY, 30, listening && AppSettings.animations(this), tms);
        ui.hit(midX - 46, orbY - 46, midX + 46, orbY + 46, new Runnable() {
            @Override public void run() { startVoice(); }
        });
        ui.textCentered(c, listening ? "listening..." : "tap to speak",
                midX, orbY + 54, 11,
                listening ? mPal.accent : mPal.dim, listening, false);

        // ---- hint ----
        ui.textCentered(c, "say \u201Cplay despacito\u201D · \u201Cwhat\u2019s the news\u201D · swipe for pages",
                midX, cy + ch - 6, 11, mPal.dim, false, false);
    }

    /** Render "HH:MM" with the blocky pixel font. */
    private String clockString(Date now) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(now);
    }

    private final android.graphics.Paint mPixelPaint = new android.graphics.Paint();

    private String weatherIcon(int code) {
        if (code == 0) return PixelIcon.SUN;
        if (code <= 2) return PixelIcon.CLOUD;
        if (code == 3) return PixelIcon.CLOUD;
        if (code <= 48) return PixelIcon.CLOUD;
        if (code <= 57) return PixelIcon.RAIN;
        if (code <= 67) return PixelIcon.RAIN;
        if (code <= 77) return PixelIcon.SNOW;
        if (code <= 82) return PixelIcon.RAIN;
        return PixelIcon.CLOUD;
    }

    /** Draw album art thumb, optionally pixelated (the "pixel kinda" covers). */
    private void drawArtThumb(PixelUi ui, android.graphics.Canvas c, float cx, float cy,
                              float sizeDp, Bitmap art) {
        if (art == null) {
            ui.icon(c, PixelIcon.NOTE, cx, cy, sizeDp * 0.8f);
            return;
        }
        if (AppSettings.asciiCover(this)) {
            // pixelate: downscale hard, then draw nearest-neighbor big
            Bitmap small = Bitmap.createScaledBitmap(art, 24, 24, false);
            Bitmap big = Bitmap.createScaledBitmap(small,
                    (int) ui.dp(sizeDp), (int) ui.dp(sizeDp), false);
            android.graphics.Paint p = mPixelPaint;
            p.setFilterBitmap(false);
            c.drawBitmap(big, cx - ui.dp(sizeDp) / 2f, cy - ui.dp(sizeDp) / 2f, p);
            p.setFilterBitmap(true);
        } else {
            android.graphics.Paint p = mPixelPaint;
            p.setFilterBitmap(true);
            c.drawBitmap(art, null,
                    new android.graphics.RectF(cx - ui.dp(sizeDp) / 2f,
                            cy - ui.dp(sizeDp) / 2f, cx + ui.dp(sizeDp) / 2f,
                            cy + ui.dp(sizeDp) / 2f), p);
        }
    }

    // ---------------------------------------------------------------- MUSIC

    private void drawMusic(PixelUi ui, android.graphics.Canvas c, float w,
                           float cy, float ch, long tms) {
        float midX = w / 2f;
        SongSearch.Track t = MusicEngine.current();

        // ---- empty state ----
        if (t == null && !MusicEngine.isBusy()) {
            ui.icon(c, PixelIcon.NOTE, midX, cy + 70, 72);
            ui.textCentered(c, "nothing playing", midX, cy + 150, 20, mPal.fg,
                    true, false);
            ui.textCentered(c, "say \u201Cplay <song name>\u201D - free, no account",
                    midX, cy + 178, 13, mPal.dim, false, false);

            // big friendly search button
            float bw = 230;
            float bx = midX - bw / 2f;
            float by = cy + 200;
            ui.pill(c, bx, by, bw, 48, true, 12);
            ui.icon(c, PixelIcon.SEARCH, bx + 34, by + 24, 18);
            ui.textCentered(c, "SEARCH A SONG", midX + 16, by + 30, 15,
                    mPal.bg, true, true);
            ui.hit(bx, by, bw, 48, new Runnable() {
                @Override public void run() { openInput("song", ""); }
            });

            float ly = by + 66;
            float lw = 210;
            float lx = midX - lw / 2f;
            ui.pill(c, lx, ly, lw, 34, false, 10);
            ui.textCentered(c, "play my local music", midX, ly + 22, 12,
                    mPal.dim, false, false);
            ui.hit(lx, ly, lw, 34, new Runnable() {
                @Override public void run() { playLocalMusic(); }
            });
            return;
        }

        // ---- searching state ----
        if (t == null) {
            String status = MusicEngine.searchStatus();
            ui.spinner(c, midX, cy + ch * 0.32f, 26, tms, mPal.accent);
            ui.textCentered(c, clip(status, 40), midX, cy + ch * 0.32f + 52, 14,
                    mPal.accent, true, false);
            ui.textCentered(c, "searching on-device (like a Discord bot)",
                    midX, cy + ch * 0.32f + 76, 12, mPal.dim, false, false);
            return;
        }

        // ---- now playing ----
        float artSize = Math.min(190, ch * 0.72f);
        float left = Math.max(24, midX - 430);
        float artY = cy + (ch - artSize) / 2f;

        // album art card
        ui.card(c, left, artY, artSize, artSize, 12, true);
        float artInset = artSize * 0.08f;
        if (mCoverArt != null) {
            drawArtThumb(ui, c, left + artSize / 2f, artY + artSize / 2f,
                    artSize - artInset, mCoverArt);
        } else {
            ui.icon(c, PixelIcon.NOTE, left + artSize / 2f, artY + artSize / 2f,
                    artSize * 0.4f);
        }

        // info column
        float ix = left + artSize + 24;
        float iw = w - ix - 24;
        float ty = artY + 8;
        List<String> titleLines = ui.wrap(t.title, 19, iw, 3);
        for (int i = 0; i < titleLines.size(); i++) {
            ui.text(c, titleLines.get(i), ix, ty + 22 + i * 26, 19, mPal.fg,
                    true, false);
        }
        ty += 22 + titleLines.size() * 26;
        ui.text(c, "by " + (t.artist.isEmpty() ? "unknown" : t.artist),
                ix, ty, 13, mPal.dim, false, false);
        ty += 18;
        String tag = sourceTag(t);
        if (!tag.isEmpty()) {
            float tw = ui.textWidth(tag, 11, true) + 16;
            ui.pill(c, ix, ty - 13, tw, 22, true, 6);
            ui.text(c, tag, ix + 8, ty + 2, 11, mPal.bg, true, true);
        }

        // progress + times
        float py = ty + 34;
        float p = MusicEngine.progress();
        ui.progressBar(c, ix, py, iw, 8, p, true);
        String times = fmtMs(MusicEngine.positionMs()) + " / "
                + fmtMs(t.durationMs > 0 ? (int) t.durationMs : MusicEngine.durationMs());
        ui.text(c, times, ix, py + 24, 11, mPal.dim, true, true);

        // controls: PREV  PLAY/PAUSE  NEXT  STOP  SEARCH
        float coy = py + 44;
        float bs = 52;
        float gap = 14;
        String[] icons = {PixelIcon.PREV,
                MusicEngine.isPlaying() ? PixelIcon.PAUSE : PixelIcon.PLAY,
                PixelIcon.NEXT, PixelIcon.STOP, PixelIcon.SEARCH};
        final String[] acts = {"PREV", "TOGGLE", "NEXT", "STOP", "SEARCH"};
        float total = icons.length * bs + (icons.length - 1) * gap;
        float x = ix + Math.max(0, (iw - total) / 2);
        for (int i = 0; i < icons.length; i++) {
            boolean main = i == 1;
            ui.pill(c, x, coy, bs, bs, main, 10);
            // icons render in palette fg; on the main (accent) button we
            // need the inverted-colored variant
            drawButtonIcon(ui, c, icons[i], x + bs / 2f, coy + bs / 2f,
                    22, main);
            final String act = acts[i];
            ui.hit(x, coy, bs, bs, new Runnable() {
                @Override public void run() { musicBtn(act); }
            });
            x += bs + gap;
        }

        // equalizer + queue
        float eqY = coy + bs + 18;
        float eqW = Math.min(iw, 360);
        ui.equalizer(c, ix, eqY, eqW, 26, MusicEngine.isPlaying(), tms, mEq);

        List<SongSearch.Track> q = MusicEngine.queue();
        float qy = eqY + 34;
        if (!q.isEmpty()) {
            ui.text(c, "up next", ix, qy, 11, mPal.dim, true, true);
            for (int i = 0; i < q.size() && i < 3; i++) {
                ui.text(c, (i + 1) + ".  " + clip(q.get(i).title + "  ·  "
                                + q.get(i).artist, 40),
                        ix, qy + 16 + i * 16, 11, mPal.dim, false, false);
            }
        }
    }

    /** Draw a control icon; on the accent-filled button, tint against bg. */
    private void drawButtonIcon(PixelUi ui, android.graphics.Canvas c, String icon,
                                float cx, float cy, float sizeDp, boolean invert) {
        if (!invert) {
            ui.icon(c, icon, cx, cy, sizeDp);
            return;
        }
        int fg = mPal.fg;
        int saveAccent = mPal.accent;
        // trick: temporarily re-render the icon with bg-colored pixels by
        // swapping palette roles in a tiny bitmap
        Bitmap b = PixelIcon.render(icon, (int) ui.dp(sizeDp), mPal.bg,
                mPal.bg, mPal.bg);
        android.graphics.Paint p = mPixelPaint;
        p.setFilterBitmap(false);
        c.drawBitmap(b, cx - ui.dp(sizeDp) / 2f, cy - ui.dp(sizeDp) / 2f, p);
        p.setFilterBitmap(true);
    }

    private void musicBtn(String tag) {
        if ("PREV".equals(tag)) MusicEngine.prev(this);
        else if ("TOGGLE".equals(tag)) MusicEngine.toggle(this);
        else if ("NEXT".equals(tag)) MusicEngine.next(this);
        else if ("STOP".equals(tag)) MusicEngine.stop(this);
        else openInput("song", "");
    }

    private String sourceTag(SongSearch.Track t) {
        if (t == null) return "";
        if ("stream".equals(t.source)) return "FULL YOUTUBE STREAM";
        if ("preview".equals(t.source)) return "30s PREVIEW";
        if ("local".equals(t.source)) return "LOCAL FILE";
        return "";
    }

    // ---------------------------------------------------------------- ASSIST

    private void drawAssist(PixelUi ui, android.graphics.Canvas c, float w,
                            float cy, float ch, long tms) {
        float midX = w / 2f;
        boolean listening = VoiceEngine.get().isListening();
        boolean anims = AppSettings.animations(this);

        // mic orb
        float orbY = cy + 42;
        ui.micOrb(c, midX, orbY, 22, listening && anims, tms);
        ui.hit(midX - 34, orbY - 34, midX + 34, orbY + 34, new Runnable() {
            @Override public void run() { startVoice(); }
        });
        ui.textCentered(c, listening ? "listening..." : "tap to speak",
                midX, orbY + 42, 12, listening ? mPal.accent : mPal.dim,
                listening, false);

        // quick chips
        float chipY = orbY + 64;
        String[][] chips = {{"WEATHER", "weather"}, {"NEWS", "news"},
                {"DATE", "date"}, {"TIME", "time"}, {"MUSIC", "music"}};
        float cw = 84, gap = 12;
        float total = chips.length * cw + (chips.length - 1) * gap;
        float x = midX - total / 2f;
        for (String[] chip : chips) {
            boolean hot = "weather".equals(chip[1]) && mWeather != null;
            ui.pill(c, x, chipY, cw, 32, false, 9);
            ui.textCentered(c, chip[0], x + cw / 2f, chipY + 21, 11,
                    mPal.fg, true, true);
            final String tag = chip[1];
            ui.hit(x, chipY, cw, 32, new Runnable() {
                @Override public void run() { chipTap(tag); }
            });
            x += cw + gap;
        }

        // reply card with typewriter + thinking spinner
        float rcW = Math.min(w - 32, 640);
        float rcX = midX - rcW / 2f;
        float rcY = chipY + 44;
        String thinking = "thinking...";
        boolean isThinking = mReplyFull != null && mReplyFull.equals(thinking);

        // reveal typewriter chars
        if (mReplyShown == 0 && mReplyFull != null && !mReplyFull.isEmpty()) {
            mReplyShown = 1; mReply = "";
        }
        if (mReplyShown > 0 && mReplyFull != null && !mReplyFull.isEmpty()
                && mReplyShown < mReplyFull.length() && anims) {
            mReplyShown += 1;
        }
        String shown = (mReplyShown > 0 && mReplyFull != null && !mReplyFull.isEmpty())
                ? mReplyFull.substring(0, Math.min(mReplyShown, mReplyFull.length()))
                : "";
        List<String> lines = ui.wrap(shown, 14, rcW - 44, 7);
        float rcH = Math.max(56, 22 + lines.size() * 20 + 14);
        ui.card(c, rcX, rcY, rcW, rcH, 12, true);
        ui.icon(c, PixelIcon.CHAT, rcX + 22, rcY + 20, 14);
        float ty = rcY + 26;
        for (int i = 0; i < lines.size(); i++) {
            ui.text(c, lines.get(i), rcX + 44, ty + i * 20, 14, mPal.fg, false, false);
        }
        if (isThinking) ui.spinner(c, rcX + rcW - 26, rcY + 22, 10, tms, mPal.accent);

        // weather + news digest under the reply
        float dy = rcY + rcH + 18;
        if (mWeather != null) {
            ui.icon(c, weatherIcon(mWeather.code), rcX + 14, dy + 4, 14);
            ui.text(c, Math.round(mWeather.temp) + "\u00B0 "
                            + WeatherProvider.describe(mWeather.code)
                            + "  (" + Math.round(mWeather.tempMin) + " to "
                            + Math.round(mWeather.tempMax) + ")  ·  " + mWeather.city,
                    rcX + 34, dy + 10, 12, mPal.dim, false, false);
            dy += 24;
        }
        if (mNews != null && !mNews.isEmpty()) {
            for (int i = 0; i < Math.min(4, mNews.size()); i++) {
                NewsProvider.Headline h = mNews.get(i);
                ui.text(c, "· " + clip(h.title, 52), rcX + 14, dy + 12 + i * 16,
                        11, mPal.dim, false, false);
            }
        }
    }

    private void chipTap(String tag) {
        if ("weather".equals(tag)) weather();
        else if ("news".equals(tag)) news();
        else if ("date".equals(tag)) date();
        else if ("time".equals(tag)) time();
        else switchPage(PAGE_MUSIC);
    }

    // ---------------------------------------------------------------- SETUP

    private void drawSetup(PixelUi ui, android.graphics.Canvas c, float w,
                            float cy, float ch, long tms) {
        float gap = 12;
        float colW = (w - gap * 3) / 2f;
        float lx = gap;
        float rx = gap * 2 + colW;
        float colH = ch - 66;

        // ---- left card: look & feel ----
        ui.card(c, lx, cy, colW, colH, 12, false);
        ui.text(c, "LOOK & FEEL", lx + 18, cy + 26, 13, mPal.accent, true, true);
        float y = cy + 44;
        y = settingRow(ui, c, lx + 18, y, colW - 36, "BACKGROUND",
                AppSettings.bgMode(this), "bg");
        y = settingRow(ui, c, lx + 18, y, colW - 36, "THEME",
                AppSettings.palette(this), "pal");
        y = settingRow(ui, c, lx + 18, y, colW - 36, "TEXT SIZE",
                fontLabel(), "font");
        y = toggleRow(ui, c, lx + 18, y, colW - 36, "PIXEL COVERS",
                AppSettings.asciiCover(this), "ascii_cover");
        y = toggleRow(ui, c, lx + 18, y, colW - 36, "ANIMATIONS",
                AppSettings.animations(this), "animations");
        toggleRow(ui, c, lx + 18, y, colW - 36, "KEEP SCREEN ON",
                AppSettings.keepScreenOn(this), "screen");

        // ---- right card: services ----
        ui.card(c, rx, cy, colW, colH, 12, false);
        ui.text(c, "SERVICES", rx + 18, cy + 26, 13, mPal.accent, true, true);
        y = cy + 44;
        String key = AppSettings.groqKey(this);
        y = actionRow(ui, c, rx + 18, y, colW - 36, "GROQ AI",
                key.isEmpty() ? "add free API key" : "gsk_...*** saved", "groq");
        String city = AppSettings.resolvedCity(this);
        if (city.isEmpty()) city = AppSettings.city(this);
        y = actionRow(ui, c, rx + 18, y, colW - 36, "WEATHER CITY",
                city.isEmpty() ? "set city" : city, "city");
        y = actionRow(ui, c, rx + 18, y, colW - 36, "HOME ASSISTANT",
                AppSettings.haUrl(this).isEmpty() ? "connect server"
                        : "server saved", "ha");
        toggleRow(ui, c, rx + 18, y, colW - 36, "SPOKEN REPLIES",
                AppSettings.speakEnabled(this), "speak");

        // ---- bottom card: system ----
        float sy = cy + colH + 12;
        float syH = ch - colH - 12;
        ui.card(c, lx, sy, w - gap * 2, syH, 12, false);
        float bx = lx + 18;
        float by = sy + 14;
        float bh = syH - 28;
        toggleRow(ui, c, bx, by, 150, "KIOSK", AppSettings.kiosk(this), "kiosk");
        toggleRow(ui, c, bx + 170, by, 150, "ON BOOT", AppSettings.bootLaunch(this), "boot");

        String[][] acts = {
                {"RUN SETUP WIZARD", "wizard", PixelIcon.PHONE},
                {"ANDROID SETTINGS", "android", PixelIcon.SLIDERS},
                {"NOTIFICATION ACCESS", "notif", PixelIcon.CHAT}};
        float axx = lx + 360;
        for (String[] act : acts) {
            float tw = ui.textWidth(act[0], 11, true) + 58;
            ui.pill(c, axx, by, tw, bh, false, 8);
            ui.icon(c, act[2], axx + 24, by + bh / 2f, 15);
            ui.text(c, act[0], axx + 44, by + bh / 2f + 4, 11, mPal.fg, true, true);
            final String tag = act[1];
            ui.hit(axx, by, tw, bh, new Runnable() {
                @Override public void run() { sysTap(tag); }
            });
            axx += tw + 10;
        }

        // footer
        ui.textCentered(c, "AstOs 2.4 · AstShell 2.4 · LineageOS 14.1 core",
                w / 2f, sy + syH - 6, 9, mPal.dim, false, false);
    }

    private String fontLabel() {
        int f = AppSettings.fontPx(this);
        if (f >= 24) return "LARGE";
        if (f <= 16) return "SMALL";
        return "MEDIUM";
    }

    /** A "LABEL   < value >" row with arrow buttons on both sides. */
    private float settingRow(PixelUi ui, android.graphics.Canvas c, float x, float y,
                              float w, String label, String value, final String key) {
        float h = 34;
        ui.text(c, label, x, y + 22, 11, mPal.fg, true, true);
        float vw = ui.textWidth(value, 11, true) + 18;
        float vx = x + w - vw - 64;
        ui.pill(c, vx, y + 4, vw, h - 8, false, 7);
        ui.textCentered(c, value, vx + vw / 2f, y + 22, 11, mPal.accent, true, true);
        // arrows
        ui.icon(c, PixelIcon.LEFT, vx - 26, y + 15, 13);
        ui.icon(c, PixelIcon.RIGHT, vx + vw + 26, y + 15, 13);
        ui.hit(vx - 40, y, 40, h, new Runnable() {
            @Override public void run() { shiftSetting(key, -1); }
        });
        ui.hit(vx + vw, y, 40, h, new Runnable() {
            @Override public void run() { shiftSetting(key, 1); }
        });
        return y + h + 8;
    }

    /** A "LABEL   [toggle]" row with a chunky pixel switch. */
    private float toggleRow(PixelUi ui, android.graphics.Canvas c, float x, float y,
                             float w, String label, boolean on, final String key) {
        float h = 34;
        ui.text(c, label, x, y + 22, 11, on ? mPal.fg : mPal.dim, true, true);
        float tw = 46, th = 22;
        float tx = x + w - tw;
        float ty = y + (h - th) / 2f;
        // track
        android.graphics.RectF tr = new android.graphics.RectF(tx, ty, tx + tw, ty + th);
        android.graphics.Paint p = mPixelPaint;
        p.setColor(on ? mPal.accent : AsciiTheme.dimColor(mPal.fg, 0.12f, mPal.bg));
        c.drawRoundRect(tr, th / 2f, th / 2f, p);
        // knob (pixel-ish rounded square)
        float kx = on ? tx + tw - th + 3 : tx + 3;
        p.setColor(on ? mPal.bg : mPal.fg);
        c.drawRoundRect(new android.graphics.RectF(kx, ty + 3, kx + th - 6,
                ty + th - 3), 4, 4, p);
        ui.hit(x, y, w, h, new Runnable() {
            @Override public void run() { toggleSetting(key); }
        });
        return y + h + 8;
    }

    /** A tappable "LABEL   value ..." row. */
    private float actionRow(PixelUi ui, android.graphics.Canvas c, float x, float y,
                            float w, String label, String value, final String key) {
        float h = 34;
        ui.text(c, label, x, y + 22, 11, mPal.fg, true, true);
        String v = value.isEmpty() ? "tap to set" : value;
        float vw = ui.textWidth(v, 11, true) + 30;
        float vx = x + w - vw;
        ui.pill(c, vx, y + 4, vw, h - 8, false, 7);
        ui.icon(c, PixelIcon.RIGHT, vx + vw - 15, y + 15, 10);
        ui.text(c, v, vx + 8, y + 22, 11, mPal.accent, true, true);
        ui.hit(vx, y, vw, h, new Runnable() {
            @Override public void run() { settingTap(key); }
        });
        return y + h + 8;
    }

    private void shiftSetting(String key, int dir) {
        if ("bg".equals(key)) {
            List<String> modes = new ArrayList<String>();
            for (String m : AsciiTheme.BG_MODES) modes.add(m);
            int i = Math.max(0, modes.indexOf(AppSettings.bgMode(this)));
            String next = modes.get((i + dir + modes.size()) % modes.size());
            AppSettings.setBgMode(this, next);
            mUi.setBgMode(next);
            if ("IMAGE".equals(next)) promptBackgroundImage();
        } else if ("pal".equals(key)) {
            List<String> names = new ArrayList<String>();
            for (AsciiTheme.Palette p : AsciiTheme.PALETTES) names.add(p.name);
            int i = Math.max(0, names.indexOf(AppSettings.palette(this)));
            AppSettings.setPalette(this,
                    names.get((i + dir + names.size()) % names.size()));
        } else if ("font".equals(key)) {
            int cur = AppSettings.fontPx(this);
            int[] sizes = {16, 20, 24};
            int idx = cur >= 24 ? 2 : (cur <= 16 ? 0 : 1);
            AppSettings.setFontPx(this, sizes[(idx + dir + 3) % 3]);
            mUi.setUiScale(uiScale());
        }
    }

    private void toggleSetting(String key) {
        boolean cur;
        if ("ascii_cover".equals(key)) { cur = AppSettings.asciiCover(this);
            AppSettings.setAsciiCover(this, !cur); }
        else if ("animations".equals(key)) { cur = AppSettings.animations(this);
            AppSettings.setAnimations(this, !cur); }
        else if ("speak".equals(key)) { cur = AppSettings.speakEnabled(this);
            AppSettings.setSpeakEnabled(this, !cur); }
        else if ("kiosk".equals(key)) { cur = AppSettings.kiosk(this);
            AppSettings.setKiosk(this, !cur); }
        else if ("boot".equals(key)) { cur = AppSettings.bootLaunch(this);
            AppSettings.setBootLaunch(this, !cur); }
        else { cur = AppSettings.keepScreenOn(this);
            AppSettings.setKeepScreenOn(this, !cur); }
    }

    private void settingTap(String key) {
        if ("groq".equals(key)) openInput("groq", AppSettings.groqKey(this));
        else if ("city".equals(key)) openInput("city", AppSettings.city(this));
        else openInput("ha", AppSettings.haUrl(this));
    }

    private void sysTap(String tag) {
        if ("wizard".equals(tag)) {
            try {
                startActivity(new Intent().setClassName("com.astos.setup",
                        "com.astos.setup.SetupActivity"));
            } catch (Throwable e) { toast("Setup wizard not installed"); }
        } else if ("android".equals(tag)) {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        } else if ("notif".equals(tag)) {
            openNotificationAccess();
        }
    }

    // ---------------------------------------------------------------- transitions

    private void switchPage(int p) {
        if (p == mPage) return;
        mPageAnimDir = p > mPage ? 1 : -1;
        mPage = p;
        mPageAnimStart = System.currentTimeMillis()
                - (mUi != null ? 0 : 0) + 0;
        mPageAnimStart = android.os.SystemClock.elapsedRealtime();
        if (mInputMode != null && !mInputMode.isEmpty()) closeInput();
        if (p == PAGE_MUSIC) refreshCoverArt();
    }

    // ---------------------------------------------------------------- input

    private void openInput(String mode, String initial) {
        mInputMode = mode;
        mInput.setText(initial == null ? "" : initial);
        mInput.setHint("song".equals(mode) ? "type a song name..."
                : "groq".equals(mode) ? "paste gsk_ key from console.groq.com"
                : "city".equals(mode) ? "city name (e.g. Mumbai)"
                : "home assistant URL (http://IP:8123)");
        mInput.setVisibility(View.VISIBLE);
        mInput.requestFocus();
        showKeyboard(true);
    }

    /** Show the AstOs keyboard; retry shortly after (robust against the
     *  focus churn that sometimes ate it in v2.3). */
    private void showKeyboard(final boolean focus) {
        final android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (focus) mInput.requestFocus();
        mInput.post(new Runnable() {
            @Override public void run() {
                try { imm.showSoftInput(mInput, 0); } catch (Throwable ignored) {}
            }
        });
        // second attempt: some IME focus transitions race the window
        mInput.postDelayed(new Runnable() {
            @Override public void run() {
                try { imm.showSoftInput(mInput, 0); } catch (Throwable ignored) {}
            }
        }, 180);
    }

    private void closeInput() {
        mInputMode = "";
        mInput.setVisibility(View.GONE);
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
    }

    private void submitInput() {
        String val = mInput.getText().toString().trim();
        String mode = mInputMode;
        closeInput();
        if (val.isEmpty()) return;
        if ("song".equals(mode)) {
            Speak.say(this, "Playing " + val);
            MusicEngine.playQuery(this, val);
            switchPage(PAGE_MUSIC);
        } else if ("groq".equals(mode)) {
            AppSettings.setGroqKey(this, val);
            Speak.say(this, "Groq AI key saved");
            toast("Groq AI connected");
        } else if ("city".equals(mode)) {
            AppSettings.setCity(this, val);
            WeatherProvider.geocode(this, val, new WeatherProvider.GeoCallback() {
                @Override public void onGeo(String resolved, String error) {
                    if (resolved != null) {
                        toast("Weather set to " + resolved);
                        WeatherProvider.refresh(HomeActivity.this, new WeatherProvider.Callback() {
                            @Override public void onWeather(WeatherProvider.WeatherInfo w, String e) {
                                if (w != null) mWeather = w;
                            }
                        });
                    } else toast("Couldn't find that city");
                }
            });
        } else if ("ha".equals(mode)) {
            AppSettings.setHaUrl(this, val);
            toast("Home Assistant URL saved");
        }
    }

    // ---------------------------------------------------------------- voice

    private void startVoice() {
        if (VoiceEngine.get().isListening()) {
            VoiceEngine.get().stopListening();
            return;
        }
        if (!VoiceEngine.modelReady(this)) {
            if (!networkConnected()) {
                toast("No network - connect WiFi to download the voice model");
                return;
            }
            downloadModel();
            return;
        }
        try {
            mPartial = "";
            VoiceEngine.get().setCallback(this);
            VoiceEngine.get().startListening();
            toast("Listening...");
        } catch (Throwable e) {
            toast("Voice not ready: " + e.getMessage());
        }
    }

    @Override
    public void onPartial(String text) { mPartial = text; }

    @Override
    public void onResult(String text) {
        mPartial = "";
        try { VoiceEngine.get().stopListening(); } catch (Throwable ignored) {}
        handleCommand(text);
    }

    @Override
    public void onError(String message) {
        mPartial = "";
        if ("timeout".equals(message)) return;
        toast(message);
    }

    private void handleCommand(String raw) {
        String resp = CommandRouter.route(raw, this);
        if (resp != null) {
            mReplyFull = resp;
            mReplyShown = 0;
            Speak.say(this, resp);
        }
    }

    // ---------------------------------------------------------------- CommandRouter.Actions

    @Override public void openApp(final String query) {
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.US);
            int score = 0;
            for (String tok : query.split("\\s+")) {
                if (tok.length() > 2 && label.contains(tok)) score += tok.length();
            }
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best != null) {
            startActivity(pm.getLaunchIntentForPackage(best.activityInfo.packageName));
        } else {
            toast("App not found: " + query);
        }
    }

    @Override public void mediaAction(String action, int value) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if ("play".equals(action) || "pause".equals(action) || "toggle".equals(action)) {
            if (MusicEngine.isPlaying() || MusicEngine.current() != null) MusicEngine.toggle(this);
            else MediaControlService.toggle();
        } else if ("next".equals(action)) {
            if (MusicEngine.current() != null) MusicEngine.next(this);
            else MediaControlService.next();
        } else if ("prev".equals(action)) {
            if (MusicEngine.current() != null) MusicEngine.prev(this);
            else MediaControlService.prev();
        } else if ("volup".equals(action)) { MediaControlService.volumeUp(this); }
        else if ("voldown".equals(action)) { MediaControlService.volumeDown(this); }
        else if ("volset".equals(action)) { MediaControlService.volumePercent(this, value); }
    }

    @Override public void playLocalMusic() {
        MusicEngine.local(this);
        switchPage(PAGE_MUSIC);
    }

    @Override public void playSong(String query) {
        MusicEngine.playQuery(this, query);
        switchPage(PAGE_MUSIC);
    }

    @Override public void weather() {
        switchPage(PAGE_ASSIST);
        WeatherProvider.refresh(this, new WeatherProvider.Callback() {
            @Override public void onWeather(WeatherProvider.WeatherInfo w, String error) {
                if (w != null) {
                    mWeather = w;
                    String line = "It's " + Math.round(w.temp) + " degrees, "
                            + WeatherProvider.describe(w.code) + ", in " + w.city + ".";
                    mReplyFull = line;
                    mReplyShown = 0;
                    Speak.say(HomeActivity.this, line);
                } else {
                    mReplyFull = "Weather unavailable: " + error;
                    mReplyShown = 0;
                    Speak.say(HomeActivity.this, mReplyFull);
                }
            }
        });
    }

    @Override public void news() {
        switchPage(PAGE_ASSIST);
        mReplyFull = "Fetching the latest headlines...";
        mReplyShown = 1;
        NewsProvider.fetch(false, new NewsProvider.Callback() {
            @Override public void onNews(List<NewsProvider.Headline> headlines, String error) {
                if (headlines != null) {
                    mNews = headlines;
                    StringBuilder sb = new StringBuilder("Here are the headlines. ");
                    for (int i = 0; i < Math.min(3, headlines.size()); i++) {
                        sb.append(headlines.get(i).title).append(". ");
                    }
                    String say = sb.toString();
                    if (say.length() > 400) say = say.substring(0, 400);
                    mReplyFull = say;
                    mReplyShown = 0;
                    Speak.say(HomeActivity.this, say);
                } else {
                    mReplyFull = "News unavailable: " + error;
                    mReplyShown = 0;
                }
            }
        });
    }

    @Override public void time() {
        String t = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        mReplyFull = "It's " + t + ".";
        mReplyShown = 0;
        Speak.say(this, mReplyFull);
        switchPage(PAGE_ASSIST);
    }

    @Override public void date() {
        String d = new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date());
        mReplyFull = "Today is " + d + ".";
        mReplyShown = 0;
        Speak.say(this, mReplyFull);
        switchPage(PAGE_ASSIST);
    }

    @Override public void whatsPlaying() {
        SongSearch.Track t = MusicEngine.current();
        String line = t == null ? "Nothing is playing."
                : "Playing " + t.title + " by " + (t.artist.isEmpty() ? "unknown" : t.artist)
                + ", " + sourceTag(t).toLowerCase();
        mReplyFull = line;
        mReplyShown = 0;
        Speak.say(this, line);
        switchPage(PAGE_MUSIC);
    }

    @Override public void setTimer(long ms) {
        cancelTimerRaw();
        mTimerEnds = System.currentTimeMillis() + ms;
        mTimer = new CountDownTimer(ms, 1000) {
            @Override public void onTick(long l) {}
            @Override public void onFinish() {
                mTimer = null;
                Speak.say(HomeActivity.this, "Timer done.");
                mReplyFull = "TIMER DONE.";
                mReplyShown = 0;
            }
        }.start();
    }

    @Override public void cancelTimer() { cancelTimerRaw(); }

    private void cancelTimerRaw() {
        if (mTimer != null) { mTimer.cancel(); mTimer = null; }
    }

    @Override public void timerStatus() {
        if (mTimer == null) { Speak.say(this, "No timer running."); return; }
        long left = Math.max(0, mTimerEnds - System.currentTimeMillis());
        String line = "Timer has " + fmtMs(left) + " left.";
        mReplyFull = line; mReplyShown = 0;
        Speak.say(this, line);
    }

    @Override public void homeAssistant(final String name, final Boolean on) {
        HomeAssistantClient.listEntities(this, new HomeAssistantClient.Callback<List<HomeAssistantClient.Entity>>() {
            @Override public void onResult(List<HomeAssistantClient.Entity> entities, String error) {
                if (entities == null) { toast("Home Assistant: " + error); return; }
                HomeAssistantClient.Entity e = HomeAssistantClient.bestMatch(entities, name);
                if (e == null) { toast("No device matched \"" + name + "\""); return; }
                if (on == null) {
                    HomeAssistantClient.toggle(HomeActivity.this, e, null);
                } else {
                    HomeAssistantClient.turn(HomeActivity.this, e, on, null);
                }
            }
        });
    }

    @Override public void scene(String name) {
        HomeAssistantClient.call(this, "scene", "turn_on", name, null);
    }

    @Override public void brightness(String action, Integer level) {
        try {
            android.view.Window wnd = getWindow();
            WindowManager.LayoutParams lp = wnd.getAttributes();
            float cur = android.provider.Settings.System.getFloat(
                    getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
            float next = "up".equals(action) ? Math.min(1f, cur + 0.2f)
                    : "down".equals(action) ? Math.max(0.05f, cur - 0.2f)
                    : Math.max(0.02f, Math.min(1f, level / 100f));
            lp.screenBrightness = next;
            wnd.setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    @Override public void fullscreen(boolean on) { applyFullscreen(on); }

    @Override public void openSettings() { switchPage(PAGE_SETUP); }

    @Override public void openAndroidSettings() {
        startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
    }

    @Override public void openNotificationAccess() {
        startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    @Override public void homeChooser() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        startActivity(Intent.createChooser(i, "Home app"));
    }

    @Override public void allApps() {
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        startActivity(Intent.createChooser(main, "All apps"));
    }

    @Override public void askGroq(String question) {
        switchPage(PAGE_ASSIST);
        if (AppSettings.groqKey(this).isEmpty()) {
            mReplyFull = "I can answer anything once you add a free Groq API key "
                    + "(SETUP -> SERVICES -> GROQ AI). Meanwhile: weather, news, music, "
                    + "timers all work.";
            mReplyShown = 0;
            Speak.say(this, mReplyFull);
            return;
        }
        mReplyFull = "thinking...";
        mReplyShown = 1;
        GroqClient.ask(this, question, new GroqClient.Callback() {
            @Override public void onReply(String text, String error) {
                mReplyFull = text != null ? text : ("Assistant: " + error);
                mReplyShown = 0;
                if (text != null) Speak.say(HomeActivity.this, text);
            }
        });
    }

    // ---------------------------------------------------------------- MusicEngine.Listener

    @Override public void onMusicUpdate() {
        if (mPage == PAGE_MUSIC) refreshCoverArt();
        if (MusicEngine.current() != null && mNews == null) {
            NewsProvider.fetch(false, new NewsProvider.Callback() {
                @Override public void onNews(List<NewsProvider.Headline> h, String e) {
                    if (h != null) mNews = h;
                }
            });
        }
    }

    private void refreshCoverArt() {
        SongSearch.Track t = MusicEngine.current();
        if (t == null || t.artUrl == null || t.artUrl.isEmpty() || mCoverArt != null) return;
        final String url = t.artUrl;
        new Thread(new Runnable() { @Override public void run() {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                InputStream in = c.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                c.disconnect();
                if (bmp != null) runOnUiThread(new Runnable() { @Override public void run() {
                    mCoverArt = bmp;
                }});
            } catch (Throwable ignored) {}
        }}, "cover").start();
    }

    // ---------------------------------------------------------------- misc

    /** Time/battery/screen tick: re-assert kiosk shade disable (self-healing
     *  against SystemUI restarts that silently clear the flags). */
    private final BroadcastReceiver mTickRecv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_TIME_TICK.equals(a)
                    || Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                Kiosk.disableShadePublic(HomeActivity.this);
                if (AppSettings.kiosk(HomeActivity.this)) applyFullscreen(true);
            }
        }
    };

    private int batteryPct() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return level * 100 / Math.max(1, scale);
        } catch (Throwable t) { return 100; }
    }

    private boolean wifiUp() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled()
                    && wm.getConnectionInfo().getSSID() != null;
        } catch (Throwable t) { return false; }
    }

    private void promptBackgroundImage() {
        new AlertDialog.Builder(this)
                .setTitle("Custom background")
                .setMessage("Pick a photo from your gallery as the background "
                        + "(it will be dimmed).")
                .setPositiveButton("Pick photo", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            Intent gi = new Intent(Intent.ACTION_PICK);
                            gi.setType("image/*");
                            startActivityForResult(gi, 41);
                        } catch (Throwable e) { toast("No gallery app"); }
                    }
                })
                .setNegativeButton("Later", null).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 41 && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                if (bmp != null) mUi.setBgImage(bmp);
            } catch (Throwable e) { toast("Couldn't load image"); }
        }
    }

    /** IMMERSIVE + IME coexistence fix: never re-assert immersive flags while
     *  the keyboard is up — that hide/reshow churn is what made the v2.3
     *  keyboard "just disappear" mid-typing. */
    private void applyFullscreen(boolean on) {
        View d = getWindow().getDecorView();
        if (!on) {
            d.setSystemUiVisibility(0);
            return;
        }
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        try {
            boolean imeActive = imm != null && imm.isActive()
                    && mInput != null && mInput.getVisibility() == View.VISIBLE;
            if (imeActive) return; // let the keyboard live
        } catch (Throwable ignored) {}
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        // back in front: refresh lockdown + bring the keyboard back if the
        // user was mid-typing (the disappearing-keyboard bug workaround)
        Kiosk.disableShadePublic(this);
        if (mInputMode != null && !mInputMode.isEmpty()
                && mInput != null && mInput.getVisibility() == View.VISIBLE) {
            mInput.postDelayed(new Runnable() {
                @Override public void run() { showKeyboard(true); }
            }, 120);
        }
        if (AppSettings.kiosk(this)) applyFullscreen(true);
    }

    private void toast(String s) {
        if (s == null || s.isEmpty()) return;
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static String clip(String s, int w) {
        if (s == null) return "";
        return s.length() <= w ? s : s.substring(0, Math.max(0, w - 3)) + "...";
    }

    private static String fmtMs(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        Kiosk.apply(this);   // self-heal: shade disable + keyboard + rotation
        if (AppSettings.kiosk(this)) applyFullscreen(true);
        mUi.setUiScale(uiScale());
        mUi.setBgMode(AppSettings.bgMode(this));
        WeatherProvider.refresh(this, new WeatherProvider.Callback() {
            @Override public void onWeather(WeatherProvider.WeatherInfo w, String e) {
                if (w != null) mWeather = w;
            }
        });
        NewsProvider.fetch(false, new NewsProvider.Callback() {
            @Override public void onNews(List<NewsProvider.Headline> h, String e) {
                if (h != null) mNews = h;
            }
        });
    }

    @Override
    protected void onDestroy() {
        MusicEngine.unlisten(this);
        VoiceEngine.get().stopListening();
        try { unregisterReceiver(mTickRecv); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // kiosk: home button behavior, never leave the shell
        if (mPage != PAGE_HOME) { switchPage(PAGE_HOME); return; }
        moveTaskToBack(false);
    }
}
