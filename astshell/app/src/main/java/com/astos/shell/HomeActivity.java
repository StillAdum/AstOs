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
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * AstShell 2.0 — the ASCII home of AstOs.
 *
 * Everything is drawn as monospace character art through AsciiView:
 * a figlet clock, ASCII weather cards, an animated Now Playing screen
 * with album art, a matrix-rain background, and very nice animations.
 * Four pages: HOME, MUSIC (Discord-bot-style on-demand songs), ASSIST
 * (weather/news/date/AI), SETUP (theme, background, keys).
 */
public class HomeActivity extends Activity
        implements CommandRouter.Actions, VoiceEngine.Callback, MusicEngine.Listener {

    public static volatile Context sAppContext;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_MUSIC = 1;
    private static final int PAGE_ASSIST = 2;
    private static final int PAGE_SETUP = 3;
    private static final String[] TABS = {"HOME", "MUSIC", "ASSIST", "SETUP"};

    private AsciiView mAscii;
    private BackgroundFX mBgFx;
    private AsciiTheme.Palette mPal;
    private EditText mInput;
    private ImageView mArt;
    private FrameLayout mRoot;

    private int mPage = PAGE_HOME;
    private String mInputMode = ""; // "", "song", "groq", "city"

    // state
    private WeatherProvider.WeatherInfo mWeather;
    private List<NewsProvider.Headline> mNews;
    private String mReply = "";              // assistant reply (typewriter)
    private String mReplyFull = "";
    private int mReplyShown = 0;
    private String mPartial = "";            // live voice partial
    private String mTicker = "AstOs 2.0 - your custom OS";
    private Bitmap mCoverArt;
    private boolean mCoverArtIsAsciiPending;
    private CountDownTimer mTimer;
    private long mTimerEnds;
    private String mTransition;
    private int mTransFrame = 0;
    private int mEqSeed = 0;

    // equalizer bars smooth state
    private final int[] mEq = new int[10];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // first boot: run the AstOs setup wizard before anything else
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

        buildUi();

        if (AppSettings.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (AppSettings.kiosk(this)) applyFullscreen(true);

        registerReceiver(mBatRecv, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(mBatRecv, new IntentFilter(Intent.ACTION_TIME_TICK));

        // voice model loads in background — mic works when ready
        VoiceEngine.get().prepare(this, new VoiceEngine.ReadyListener() {
            @Override public void onReady(boolean ok, String message) {
                if (!ok) toast(message);
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
            return Settings.Global.getInt(c.getContentResolver(), "astos_setup_done", 1) == 1;
        } catch (Throwable t) { return true; }
    }

    // ---------------------------------------------------------------- UI construction

    private void buildUi() {
        mPal = AsciiTheme.byName(AppSettings.palette(this));

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(mPal.bg);

        mAscii = new AsciiView(this);
        mAscii.setBgColor(mPal.bg);
        mAscii.setFontPx(AppSettings.fontPx(this));
        mAscii.setFps(AppSettings.animations(this) ? 24 : 4);
        mRoot.addView(mAscii, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // album-art overlay (real bitmap mode) — hidden unless MUSIC page
        mArt = new ImageView(this);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setVisibility(View.GONE);
        mRoot.addView(mArt);

        // terminal-style input line overlay
        mInput = new EditText(this);
        mInput.setTypeface(Typeface.MONOSPACE);
        mInput.setTextColor(mPal.fg);
        mInput.setBackgroundColor(Color.argb(200,
                Color.red(mPal.bg), Color.green(mPal.bg), Color.blue(mPal.bg)));
        mInput.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
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

        mBgFx = new BackgroundFX(AppSettings.bgMode(this));
        mAscii.setBackgroundFx(mBgFx);
        mAscii.setRenderer(new AsciiView.Renderer() {
            @Override public void onFrame(AsciiView v, long frame) {
                drawFrame(v, frame);
            }
        });
        mAscii.start();

        // swipe left/right to change pages
        mAscii.setOnTouchListener(new View.OnTouchListener() {
            float x0; boolean used;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN) { x0 = ev.getX(); used = false; }
                if (ev.getAction() == MotionEvent.ACTION_MOVE && !used
                        && Math.abs(ev.getX() - x0) > 90) {
                    used = true;
                    switchPage(ev.getX() < x0 ? (mPage + 1) % 4 : (mPage + 3) % 4);
                }
                if (ev.getAction() == MotionEvent.ACTION_UP && !used) {
                    // let AsciiView handle taps
                    mAscii.dispatchTap(ev);
                }
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- frame render

    private void drawFrame(AsciiView v, long frame) {
        mPal = AsciiTheme.byName(AppSettings.palette(this));
        v.setBgColor(mPal.bg);
        mRoot.setBackgroundColor(mPal.bg);

        v.wipe();
        mBgFx.render(v, frame, mPal);

        int cols = v.cols(), rows = v.rows();
        drawTopBar(v, cols, frame);
        drawInputLine(v, cols, rows);
        drawHint(v, cols, rows);

        switch (mPage) {
            case PAGE_HOME:  drawHome(v, cols, rows, frame); break;
            case PAGE_MUSIC: drawMusic(v, cols, rows, frame); break;
            case PAGE_ASSIST: drawAssist(v, cols, rows, frame); break;
            case PAGE_SETUP: drawSetup(v, cols, rows, frame); break;
        }

        if (mTransition != null) drawTransition(v, cols, rows, frame);
    }

    private void drawTopBar(AsciiView v, int cols, long frame) {
        // shimmer logo
        String logo = " AstOs ";
        for (int i = 0; i < logo.length(); i++) {
            int ph = (int) ((frame / 2 + i) % 12);
            int col = ph < 3 ? mPal.accent : mPal.fg;
            v.setCellChar(1 + i, 0, logo.charAt(i), col);
        }
        // tabs, centered
        int total = 0;
        for (String t : TABS) total += t.length() + 4;
        int x = (cols - total) / 2;
        for (int i = 0; i < TABS.length; i++) {
            String label = (i == mPage ? "[ " + TABS[i] + " ]" : "  " + TABS[i] + "  ");
            v.put(x, 0, label, i == mPage ? mPal.accent : mPal.dim);
            v.hit(x, 0, label.length(), 1, tabTap(i));
            x += label.length();
        }
        // right: battery + wifi
        String stat = " " + batteryPct() + "%" + (wifiUp() ? " [~] " : " [x] ");
        v.put(cols - stat.length(), 0, stat, mPal.dim);
    }

    private Runnable tabTap(final int i) {
        return new Runnable() { @Override public void run() { switchPage(i); }};
    }

    private void drawInputLine(AsciiView v, int cols, int rows) {
        if (mInputMode.isEmpty()) return;
        String hint = "song".equals(mInputMode) ? "SONG NAME"
                : "groq".equals(mInputMode) ? "GROQ API KEY (gsk_...)"
                : "city".equals(mInputMode) ? "CITY NAME" : "";
        v.put(cols - hint.length() - 2, rows - 3, hint, mPal.dim);
    }

    private void drawHint(AsciiView v, int cols, int rows) {
        String h = hintFor();
        v.putC(cols / 2, rows - 1, h, mPal.dim);
    }

    private String hintFor() {
        switch (mPage) {
            case PAGE_HOME:
                return "tap MIC to talk - say \"play despacito\" or \"what's the news\" - swipe for pages";
            case PAGE_MUSIC:
                return "say \"play <song>\" or type a song name - free, no account, Discord-bot style";
            case PAGE_ASSIST:
                return "ask me anything - weather, news, date, or any question (Groq AI)";
            default:
                return "make it yours: theme, background, keys - all ASCII, all yours";
        }
    }

    // ---------------------------------------------------------------- HOME

    private void drawHome(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        Date now = new Date();

        // big figlet clock with blinking colon
        String hm = new SimpleDateFormat("HH:mm", Locale.US).format(now);
        if (!AppSettings.animations(this)) {
            Figlet.draw(v, cx, 2, hm, mPal.fg);
        } else {
            boolean blink = (frame / 12) % 2 == 0;
            String left = hm.substring(0, 2), right = hm.substring(3);
            String[] gl = Figlet.render(left), gr = Figlet.render(right), gc = Figlet.render(":");
            int lw = gl[0].length(), gw = gr[0].length();
            int startX = cx - (lw + 2 + gw) / 2;
            for (int r = 0; r < 5; r++) {
                v.put(startX, 2 + r, gl[r], mPal.fg);
                v.put(startX + lw + 2, 2 + r, gc[r], blink ? mPal.accent : mPal.dim);
                v.put(startX + lw + 4, 2 + r, gr[r], mPal.fg);
            }
        }

        v.putC(cx, 8, new SimpleDateFormat("EEEE - MMMM d yyyy", Locale.US).format(now), mPal.dim);

        // weather mini-card
        if (mWeather != null) {
            String line = " " + Math.round(mWeather.temp) + "C  "
                    + WeatherProvider.describe(mWeather.code).toUpperCase()
                    + "  " + mWeather.city.toUpperCase() + " ";
            String icon = asciiWeatherIcon(mWeather.code);
            String box = "[" + icon + "] " + line;
            v.putC(cx, 10, "+-- weather " + repeat('-', Math.max(2, box.length() - 12)) + "+", mPal.dim);
            v.putC(cx, 11, box, mPal.accent);
            v.putC(cx, 12, "+" + repeat('-', box.length()) + "+", mPal.dim);
        }

        // now playing strip + equalizer
        SongSearch.Track t = MusicEngine.current();
        int y = 14;
        if (t != null || MusicEngine.isBusy()) {
            drawEqualizer(v, cx - 26, y + 1, frame);
            String status = MusicEngine.isBusy() && !MusicEngine.isPlaying()
                    ? MusicEngine.searchStatus() : t.title + "  -  " + (t.artist.isEmpty() ? "?" : t.artist);
            v.put(cx - 14, y, "~ " + clip(status, 40), mPal.accent);
            v.put(cx - 14, y + 1, "  " + (MusicEngine.isPlaying() ? "[ PLAYING " + sourceTag(t) + " ]" : "[ PAUSED ]"), mPal.dim);
        } else {
            v.putC(cx, y, "say \"play <song>\" for free on-demand music", mPal.dim);
        }

        // news ticker (marquee)
        if (mNews != null && !mNews.isEmpty()) {
            String t1 = mNews.get(0).title;
            int shift = (int) (frame / 2) % Math.max(1, t1.length());
            String shown = ("NEWS >> " + t1 + "   ***   ").substring(shift);
            shown = clip(shown, cols - 16);
            v.putC(cx, y + 4, shown, mPal.dim);
        } else {
            v.putC(cx, y + 4, "tap ASSIST then NEWS for the latest headlines", mPal.dim);
        }

        // action buttons
        int bx = cx - 24;
        String[] labels = {"[ MIC ]", "[ APPS ]", "[ MUSIC ]", "[ SETUP ]"};
        for (int i = 0; i < labels.length; i++) {
            int w = labels[i].length();
            v.put(bx, y + 6, labels[i], mPal.fg);
            v.hit(bx, y + 6, w, 1, btnTap(i));
            bx += w + 4;
        }

        // live voice partial
        if (!mPartial.isEmpty()) {
            boolean cursor = (frame / 8) % 2 == 0;
            v.put(2, rows - 4, "> " + clip(mPartial, cols - 6) + (cursor ? "_" : " "), mPal.accent);
        }

        // timer line
        if (mTimer != null) {
            long left = Math.max(0, mTimerEnds - System.currentTimeMillis());
            v.put(cols - 18, rows - 4, "timer " + fmtMs(left), mPal.accent);
        }
    }

    private String sourceTag(SongSearch.Track t) {
        if (t == null) return "";
        if ("stream".equals(t.source)) return "FULL YOUTUBE STREAM";
        if ("preview".equals(t.source)) return "30s PREVIEW";
        if ("local".equals(t.source)) return "LOCAL FILE";
        return "";
    }

    private void drawEqualizer(AsciiView v, int x, int y, long frame) {
        boolean playing = MusicEngine.isPlaying();
        for (int i = 0; i < 10; i++) {
            int target = playing ? 1 + (int) ((i * 7 + (frame / 3)) % 5) : 1;
            mEq[i] += (target - mEq[i]) / 2;
            for (int h = 0; h < 5; h++) {
                char c = h < mEq[i] ? (h % 2 == 0 ? '#' : '=') : '.';
                v.setCellChar(x + i * 2, y - h, c, h < mEq[i] ? mPal.accent : mPal.dim);
            }
        }
    }

    private Runnable btnTap(final int i) {
        final HomeActivity self = this;
        return new Runnable() { @Override public void run() {
            if (i == 0) startVoice();
            else if (i == 1) allApps();
            else if (i == 2) switchPage(PAGE_MUSIC);
            else switchPage(PAGE_SETUP);
        }};
    }

    // ---------------------------------------------------------------- MUSIC

    private void drawMusic(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;
        SongSearch.Track t = MusicEngine.current();

        if (t == null && !MusicEngine.isBusy()) {
            Figlet.draw(v, cx, 4, "NOW PLAYING", mPal.fg);
            v.putC(cx, 11, "nothing playing", mPal.dim);
            v.putC(cx, 13, "say \"play <song name>\"  -  or tap below to type", mPal.dim);
            String search = "[ + SEARCH A SONG ]";
            v.putC(cx, 16, search, mPal.accent);
            v.hit(cx - search.length() / 2, 16, search.length(), 1, new Runnable() {
                @Override public void run() { openInput("song", ""); }
            });
            drawLocalHint(v, cx, 19);
            return;
        }

        String status = MusicEngine.searchStatus();
        if (t == null) {
            // still searching
            String dots = repeat('.', (int) (frame / 6 % 6));
            v.putC(cx, 8, clip(status, cols - 8) + dots, mPal.accent);
            v.putC(cx, 10, "searching YouTube Music on-device (like a Discord bot)", mPal.dim);
            return;
        }

        // ---- album art (ASCII-art cover or bitmap) ----
        int artX = Math.max(4, cx - 34), artY = 4, artS = 16;
        if (AppSettings.asciiCover(this)) {
            mArt.setVisibility(View.GONE);
            drawAsciiCover(v, artX, artY, artS, frame);
        } else if (mCoverArt != null) {
            // bitmap art: ASCII frame around a real cover image
            v.box(artX - 1, artY - 1, artS + 2, artS + 2, mPal.dim, "now");
            placeArtOverlay(artX, artY, artS);
        } else {
            v.box(artX, artY, artS, artS, mPal.dim, "ART");
            v.putC(artX + artS / 2, artY + artS / 2, "*", mPal.accent);
        }

        // ---- track info ----
        int infoX = artX + artS + 4;
        int infoW = Math.max(20, cols - infoX - 4);
        String[] titleLines = wrap(t.title, infoW);
        for (int i = 0; i < titleLines.length && i < 3; i++)
            v.put(infoX, 4 + i, titleLines[i], mPal.fg);
        v.put(infoX, 4 + Math.min(3, titleLines.length) + 1, "by " + (t.artist.isEmpty() ? "unknown" : t.artist), mPal.dim);
        v.put(infoX, 4 + Math.min(3, titleLines.length) + 2, "[" + sourceTag(t) + "]", mPal.accent);

        // progress bar
        int py = Math.min(12, artY + artS + 2);
        float p = MusicEngine.progress();
        int barW = Math.min(40, cols - 10);
        int barX = cx - barW / 2;
        StringBuilder bar = new StringBuilder();
        int filled = (int) (barW * p);
        for (int i = 0; i < barW; i++) bar.append(i < filled ? '=' : '-');
        v.put(barX, py, "|" + bar + "|", mPal.accent);
        String times = fmtMs(MusicEngine.positionMs()) + " / " + fmtMs(t.durationMs > 0 ? (int) t.durationMs : MusicEngine.durationMs());
        v.putC(cx, py + 1, times, mPal.dim);

        // controls
        int cy = py + 3;
        String[][] btns = {{"|<", "PREV"}, {"[ " + (MusicEngine.isPlaying() ? "PAUSE" : "PLAY") + " ]", "TOGGLE"}, {">|", "NEXT"}, {"[ STOP ]", "STOP"}, {"[ + SONG ]", "SEARCH"}};
        int bw = 0;
        for (String[] bb : btns) bw += bb[0].length() + 3;
        int bxx = cx - bw / 2;
        for (int i = 0; i < btns.length; i++) {
            v.put(bxx, cy, btns[i][0], i == 4 ? mPal.dim : mPal.fg);
            v.hit(bxx, cy, btns[i][0].length(), 1, musicBtn(btns[i][1]));
            bxx += btns[i][0].length() + 3;
        }

        // equalizer + status line
        drawEqualizer(v, cx - 26, cy + 3, frame);
        if (!status.isEmpty()) v.putC(cx, cy + 2, clip(status, cols - 8), mPal.accent);

        // queue
        List<SongSearch.Track> q = MusicEngine.queue();
        if (!q.isEmpty()) {
            int qy = cy + 4;
            v.put(4, qy, "+-- up next ", mPal.dim);
            for (int i = 0; i < q.size() && i < 3; i++) {
                SongSearch.Track qt = q.get(i);
                v.put(4, qy + 1 + i, "  " + (i + 1) + ". " + clip(qt.title + " - " + qt.artist, cols - 10), mPal.dim);
            }
        }
    }

    private Runnable musicBtn(final String tag) {
        return new Runnable() { @Override public void run() {
            if ("PREV".equals(tag)) MusicEngine.prev(HomeActivity.this);
            else if ("TOGGLE".equals(tag)) MusicEngine.toggle(HomeActivity.this);
            else if ("NEXT".equals(tag)) MusicEngine.next(HomeActivity.this);
            else if ("STOP".equals(tag)) MusicEngine.stop(HomeActivity.this);
            else openInput("song", "");
        }};
    }

    private void drawLocalHint(AsciiView v, int cx, int y) {
        v.putC(cx, y, "[ PLAY MY LOCAL MUSIC ]", mPal.dim);
        v.hit(cx - 11, y, 22, 1, new Runnable() { @Override public void run() { playLocalMusic(); }});
    }

    /** ASCII-art render of the cover bitmap, or a placeholder frame. */
    private void drawAsciiCover(AsciiView v, int x, int y, int s, long frame) {
        if (mCoverArt == null) {
            v.box(x, y, s, s, mPal.dim, "ART");
            if (MusicEngine.isPlaying()) {
                // tiny vinyl animation placeholder
                int spin = (int) (frame / 3 % 3);
                char disc = "oO@".charAt(spin);
                v.putC(x + s / 2, y + s / 2, String.valueOf(disc), mPal.accent);
            }
            return;
        }
        String shade = " .:-=+*#%@";
        int w = Math.min(s, mCoverArt.getWidth());
        int h = Math.min(s, mCoverArt.getHeight());
        Bitmap bmp = Bitmap.createScaledBitmap(mCoverArt, w, h, true);
        for (int r = 0; r < s; r++) {
            for (int c = 0; c < s; c++) {
                int px = c * w / s, py = r * h / s;
                int colr = bmp.getPixel(Math.min(w - 1, px), Math.min(h - 1, py));
                double lum = (0.299 * Color.red(colr) + 0.587 * Color.green(colr)
                        + 0.114 * Color.blue(colr)) / 255.0;
                int idx = (int) (lum * (shade.length() - 1));
                v.setCellChar(x + c, y + r, shade.charAt(idx),
                        AsciiTheme.dimColor(mPal.fg, 0.35f + 0.6f * (float) lum, mPal.bg));
            }
        }
        v.box(x - 1, y - 1, s + 2, s + 2, mPal.dim, null);
    }

    private void placeArtOverlay(int x, int y, int s) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                s * mAscii.cellW(), s * mAscii.cellH());
        lp.leftMargin = (int) mAscii.offsetX() + x * mAscii.cellW();
        lp.topMargin = (int) mAscii.offsetY() + y * mAscii.cellH();
        mArt.setLayoutParams(lp);
        mArt.setImageBitmap(mCoverArt);
        mArt.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- ASSIST

    private void drawAssist(AsciiView v, int cols, int rows, long frame) {
        int cx = cols / 2;

        // mic button with live waveform
        String mic = VoiceEngine.get().isListening() ? "( LISTENING... )" : "(  TAP TO SPEAK  )";
        v.putC(cx, 2, "   ___   ", mPal.accent);
        v.putC(cx, 3, "  /   \\  ", mPal.fg);
        v.putC(cx, 4, "  \\___/  ", mPal.fg);
        v.putC(cx, 5, mic, VoiceEngine.get().isListening() ? mPal.accent : mPal.fg);
        v.hit(cx - 12, 2, 24, 4, new Runnable() { @Override public void run() { startVoice(); }});

        if (!mPartial.isEmpty()) {
            v.putC(cx, 7, "> " + clip(mPartial, cols - 10), mPal.accent);
        }

        // quick chips
        int y = 9;
        String[][] chips = {{"[ WEATHER ]", "weather"}, {"[ NEWS ]", "news"},
                {"[ DATE ]", "date"}, {"[ TIME ]", "time"}, {"[ MUSIC ]", "music"}};
        int cw = 0;
        for (String[] c : chips) cw += c[0].length() + 2;
        int cxx = cx - cw / 2;
        for (String[] c : chips) {
            v.put(cxx, y, c[0], mPal.dim);
            v.hit(cxx, y, c[0].length(), 1, chipTap(c[1]));
            cxx += c[0].length() + 2;
        }

        // reply bubble with typewriter
        int by = y + 2;
        if (mReplyShown == 0 && mReplyFull != null && !mReplyFull.isEmpty()) {
            mReplyShown = 1; mReply = "";
        }
        if (mReplyShown > 0 && mReplyFull != null && !mReplyFull.isEmpty()) {
            int reveal = Math.min(mReplyFull.length(), mReplyShown);
            if (reveal < mReplyFull.length() && frame % 2 == 0) mReplyShown += 1;
            mReply = mReplyFull.substring(0, reveal);
            String[] lines = wrap(mReply + (reveal < mReplyFull.length() ? "_" : ""), cols - 14);
            int bw = cols - 10;
            v.box(5, by, bw, lines.length + 2, mPal.accent, "ast");
            for (int i = 0; i < lines.length; i++) v.put(7, by + 1 + i, lines[i], mPal.fg);
            by += lines.length + 3;
        }

        // weather card
        if (mWeather != null) {
            v.put(5, by, "weather: " + Math.round(mWeather.temp) + "C  "
                    + WeatherProvider.describe(mWeather.code) + "  ("
                    + Math.round(mWeather.tempMin) + " to " + Math.round(mWeather.tempMax) + ")  "
                    + mWeather.city, mPal.fg);
            by += 1;
        }
        // news card
        if (mNews != null && !mNews.isEmpty()) {
            v.put(5, by, "latest news:", mPal.fg);
            for (int i = 0; i < Math.min(5, mNews.size()) && by + 1 + i < rows - 4; i++) {
                NewsProvider.Headline h = mNews.get(i);
                v.put(5, by + 1 + i, " " + clip("[" + h.source + "] " + h.title, cols - 10), mPal.dim);
            }
        }
    }

    private Runnable chipTap(final String tag) {
        return new Runnable() { @Override public void run() {
            if ("weather".equals(tag)) weather();
            else if ("news".equals(tag)) news();
            else if ("date".equals(tag)) date();
            else if ("time".equals(tag)) time();
            else switchPage(PAGE_MUSIC);
        }};
    }

    // ---------------------------------------------------------------- SETUP

    private void drawSetup(AsciiView v, int cols, int rows, long frame) {
        int x = 4, y = 2;
        int w = Math.min(cols - 8, 96);

        v.put(x, y, "BACKGROUND:", mPal.fg);
        String mode = AppSettings.bgMode(this);
        String bgLine = "< " + mode + " >";
        v.put(x + 12, y, bgLine, mPal.accent);
        v.hit(x + 11, y, 1, 1, bgShift(-1));
        v.hit(x + 12 + bgLine.length(), y, 1, 1, bgShift(1));
        v.put(x + 12 + bgLine.length() + 2, y,
                " " + joinNames(AsciiTheme.BG_MODES, mode), mPal.dim);
        y += 2;

        v.put(x, y, "THEME:", mPal.fg);
        String pal = AppSettings.palette(this);
        String palLine = "< " + pal + " >";
        v.put(x + 12, y, palLine, mPal.accent);
        v.hit(x + 11, y, 1, 1, palShift(-1));
        v.hit(x + 12 + palLine.length(), y, 1, 1, palShift(1));
        y += 2;

        v.put(x, y, "FONT:", mPal.fg);
        String[] fonts = {"SMALL", "MED", "LARGE"};
        int curF = AppSettings.fontPx(this) >= 22 ? 2 : (AppSettings.fontPx(this) <= 14 ? 0 : 1);
        String fontLine = "< " + fonts[curF] + " >";
        v.put(x + 12, y, fontLine, mPal.accent);
        v.hit(x + 11, y, 1, 1, fontShift(-1));
        v.hit(x + 12 + fontLine.length(), y, 1, 1, fontShift(1));
        y += 2;

        // toggle rows
        y = drawToggle(v, x, y, "ASCII ALBUM COVERS", AppSettings.asciiCover(this), "ascii_cover");
        y = drawToggle(v, x, y, "ANIMATIONS", AppSettings.animations(this), "animations");
        y = drawToggle(v, x, y, "SPOKEN REPLIES", AppSettings.speakEnabled(this), "speak");
        y = drawToggle(v, x, y, "KIOSK MODE", AppSettings.kiosk(this), "kiosk");
        y = drawToggle(v, x, y, "LAUNCH ON BOOT", AppSettings.bootLaunch(this), "boot");
        y = drawToggle(v, x, y, "KEEP SCREEN ON", AppSettings.keepScreenOn(this), "screen");
        y += 1;

        v.put(x, y, "GROQ AI:", mPal.fg);
        String key = AppSettings.groqKey(this);
        String keyTxt = key.isEmpty() ? "[ ADD FREE API KEY ]" : "[ gsk_...*** - CHANGE ]";
        v.put(x + 12, y, keyTxt, mPal.accent);
        v.hit(x + 12, y, keyTxt.length(), 1, new Runnable() { @Override public void run() {
            openInput("groq", AppSettings.groqKey(HomeActivity.this));
        }});
        v.put(x + 12 + keyTxt.length() + 2, y, "console.groq.com - free", mPal.dim);
        y += 2;

        v.put(x, y, "WEATHER CITY:", mPal.fg);
        String city = AppSettings.resolvedCity(this);
        String cityTxt = city.isEmpty() ? "[ SET CITY ]" : "[ " + city + " - CHANGE ]";
        v.put(x + 15, y, cityTxt, mPal.accent);
        v.hit(x + 15, y, cityTxt.length(), 1, new Runnable() { @Override public void run() {
            openInput("city", AppSettings.city(HomeActivity.this));
        }});
        y += 2;

        v.put(x, y, "HOME ASSISTANT:", mPal.fg);
        String haTxt = AppSettings.haUrl(this).isEmpty() ? "[ CONNECT ]" : "[ CHANGE SERVER ]";
        v.put(x + 18, y, haTxt, mPal.accent);
        v.hit(x + 18, y, haTxt.length(), 1, new Runnable() { @Override public void run() {
            openInput("ha", AppSettings.haUrl(HomeActivity.this));
        }});
        y += 3;

        // system actions
        String[][] acts = {
                {"[ RUN SETUP WIZARD ]", "wizard"},
                {"[ ANDROID SETTINGS ]", "android"},
                {"[ NOTIFICATION ACCESS ]", "notif"},
                {"[ ALL APPS ]", "apps"}};
        int ax = x;
        for (String[] act : acts) {
            v.put(ax, y, act[0], mPal.dim);
            v.hit(ax, y, act[0].length(), 1, sysTap(act[1]));
            ax += act[0].length() + 2;
        }
        y += 3;

        v.put(x, y, "AstOs 2.0 - AstShell 2.0 - a real custom OS (LineageOS 14.1 core)", mPal.dim);
    }

    private int drawToggle(AsciiView v, int x, int y, String label, boolean on, final String key) {
        String t = "[" + (on ? "x" : " ") + "]";
        v.put(x, y, t, on ? mPal.accent : mPal.dim);
        v.put(x + 4, y, label, on ? mPal.fg : mPal.dim);
        v.hit(x, y, 4 + label.length(), 1, new Runnable() { @Override public void run() {
            boolean cur;
            if ("ascii_cover".equals(key)) { cur = AppSettings.asciiCover(HomeActivity.this);
                AppSettings.setAsciiCover(HomeActivity.this, !cur); }
            else if ("animations".equals(key)) { cur = AppSettings.animations(HomeActivity.this);
                AppSettings.setAnimations(HomeActivity.this, !cur);
                mAscii.setFps(!cur ? 24 : 4); }
            else if ("speak".equals(key)) { cur = AppSettings.speakEnabled(HomeActivity.this);
                AppSettings.setSpeakEnabled(HomeActivity.this, !cur); }
            else if ("kiosk".equals(key)) { cur = AppSettings.kiosk(HomeActivity.this);
                AppSettings.setKiosk(HomeActivity.this, !cur); }
            else if ("boot".equals(key)) { cur = AppSettings.bootLaunch(HomeActivity.this);
                AppSettings.setBootLaunch(HomeActivity.this, !cur); }
            else { cur = AppSettings.keepScreenOn(HomeActivity.this);
                AppSettings.setKeepScreenOn(HomeActivity.this, !cur); }
        }});
        return y + 1;
    }

    private Runnable bgShift(final int d) {
        return new Runnable() { @Override public void run() {
            List<String> modes = new ArrayList<String>();
            for (String m : AsciiTheme.BG_MODES) modes.add(m);
            int i = Math.max(0, modes.indexOf(AppSettings.bgMode(HomeActivity.this)));
            String next = modes.get((i + d + modes.size()) % modes.size());
            AppSettings.setBgMode(HomeActivity.this, next);
            mBgFx = new BackgroundFX(next);
            mAscii.setBackgroundFx(mBgFx);
            if ("IMAGE".equals(next)) promptBackgroundImage();
        }};
    }

    private Runnable palShift(final int d) {
        return new Runnable() { @Override public void run() {
            List<String> names = new ArrayList<String>();
            for (AsciiTheme.Palette p : AsciiTheme.PALETTES) names.add(p.name);
            int i = Math.max(0, names.indexOf(AppSettings.palette(HomeActivity.this)));
            AppSettings.setPalette(HomeActivity.this,
                    names.get((i + d + names.size()) % names.size()));
        }};
    }

    private Runnable fontShift(final int d) {
        return new Runnable() { @Override public void run() {
            int cur = AppSettings.fontPx(HomeActivity.this);
            int[] sizes = {14, 18, 22};
            int idx = cur >= 22 ? 2 : (cur <= 14 ? 0 : 1);
            int next = sizes[(idx + d + 3) % 3];
            AppSettings.setFontPx(HomeActivity.this, next);
            mAscii.setFontPx(next);
        }};
    }

    private Runnable sysTap(final String tag) {
        return new Runnable() { @Override public void run() {
            if ("wizard".equals(tag)) {
                try {
                    startActivity(new Intent().setClassName("com.astos.setup",
                            "com.astos.setup.SetupActivity"));
                } catch (Throwable e) { toast("Setup wizard not installed"); }
            } else if ("android".equals(tag)) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } else if ("notif".equals(tag)) {
                openNotificationAccess();
            } else if ("apps".equals(tag)) {
                allApps();
            }
        }};
    }

    // ---------------------------------------------------------------- transitions

    private void switchPage(int p) {
        if (p == mPage) return;
        mPage = p;
        if (mInputMode != null && !mInputMode.isEmpty()) closeInput();
        if (mArt != null && mPage != PAGE_MUSIC) mArt.setVisibility(View.GONE);
        if (AppSettings.animations(this)) {
            mTransition = "wipe";
            mTransFrame = 0;
        }
        if (mPage == PAGE_MUSIC) refreshCoverArt();
    }

    private void drawTransition(AsciiView v, int cols, int rows, long frame) {
        int t = mTransFrame++;
        if (t > 5) { mTransition = null; return; }
        String glitch = "#$%&@*+=~";
        int w = (cols * (t + 1)) / 6;
        for (int y = 1; y < rows - 2; y++)
            for (int x = 0; x < w; x++)
                v.setCellChar(x, y, glitch.charAt((x + y + t * 3) % glitch.length()), mPal.dim);
    }

    // ---------------------------------------------------------------- input

    private void openInput(String mode, String initial) {
        mInputMode = mode;
        mInput.setText(initial == null ? "" : initial);
        mInput.setHint("song".equals(mode) ? "> type a song name..."
                : "groq".equals(mode) ? "> paste gsk_ key from console.groq.com"
                : "city".equals(mode) ? "> city name (e.g. Mumbai)"
                : "> home assistant URL (http://IP:8123)");
        mInput.setVisibility(View.VISIBLE);
        mInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mInput, 0);
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
                            }});
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
        mReplyShown = 0;
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
                    + "(setup page -> GROQ AI). Meanwhile: weather, news, music, timers all work.";
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

    private final BroadcastReceiver mBatRecv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // battery/time ticks redraw on next frame automatically
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
                .setMessage("Pick a photo from your gallery as the ASCII background "
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
                if (bmp != null) mAscii.setBgImage(bmp);
            } catch (Throwable e) { toast("Couldn't load image"); }
        }
    }

    private void applyFullscreen(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        } else {
            d.setSystemUiVisibility(0);
        }
    }

    private void toast(String s) {
        if (s == null || s.isEmpty()) return;
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String asciiWeatherIcon(int code) {
        if (code == 0) return "\\|/";
        if (code <= 2) return "(~)";
        if (code == 3) return "((~))";
        if (code <= 48) return "===";
        if (code <= 57) return "./.";
        if (code <= 67) return "/!/";
        if (code <= 77) return "*snow*";
        if (code <= 82) return "//!\\\\";
        return " ** ";
    }

    private String joinNames(String[] arr, String active) {
        StringBuilder sb = new StringBuilder();
        for (String a : arr) {
            if (!a.equals(active)) sb.append(a.toLowerCase()).append(" ");
        }
        return sb.toString().trim();
    }

    private static String repeat(char c, int n) {
        if (n < 0) n = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static String clip(String s, int w) {
        if (s == null) return "";
        return s.length() <= w ? s : s.substring(0, Math.max(0, w - 3)) + "...";
    }

    private static String[] wrap(String s, int w) {
        List<String> lines = new ArrayList<String>();
        if (s == null) return new String[]{""};
        for (String para : s.split("\n")) {
            String cur = para;
            while (cur.length() > w) {
                int cut = cur.lastIndexOf(' ', w);
                if (cut <= 0) cut = w;
                lines.add(cur.substring(0, cut).trim());
                cur = cur.substring(cut).trim();
            }
            if (!cur.isEmpty()) lines.add(cur);
        }
        return lines.toArray(new String[0]);
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
        try { unregisterReceiver(mBatRecv); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // kiosk: home button behavior, never leave the shell
        if (mPage != PAGE_HOME) { switchPage(PAGE_HOME); return; }
        moveTaskToBack(false);
    }
}
