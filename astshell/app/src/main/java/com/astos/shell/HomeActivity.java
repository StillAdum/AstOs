package com.astos.shell;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The AstOs home — pixel-retro dashboard, 99% the approved mock:
 * two corner-tagged cards under a VT323 clock (media left, home right),
 * dock with home/settings nav and the big accent mic, dotted-paper
 * backdrop. All native Views — no WebView anywhere.
 *
 * Services underneath (v2.4 layer): MusicEngine (Discord-bot style
 * songs), MediaControlService (Spotify sessions), offline Vosk voice,
 * Groq AI fallback, Home Assistant, news + weather.
 */
public class HomeActivity extends Activity
        implements CommandRouter.Actions, VoiceEngine.Callback, MusicEngine.Listener,
                   PluginHost.Listener {

    public static volatile Context sAppContext;

    /** Companion/remote refresh hook — cheap, safe to call from any thread. */
    public static void requestRefresh() {
        final Context c = sAppContext;
        if (c == null) return;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                PluginHost ph = PluginHost.get();
                if (ph != null) ph.reloadAll();
                Intent i = new Intent(c, HomeActivity.class);
                // the home is singleTask: this is a no-op bring-to-front that
                // still triggers onResume -> full data refresh
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                try { c.startActivity(i); } catch (Throwable ignored) {}
            }
        });
    }

    // views
    private TextView mClock, mDate, mTimerText;
    private TextView mTemp, mCond, mCity;
    private ImageView mWeatherIcon;
    private TextView mTrack, mArtist, mSrc;
    private TextView mT0, mT1;
    private ImageButton mPlayPause;
    private SegBar mVolBar, mProgBar;
    private TextView mVolText;
    private PixelAlbumArt mArt;
    private EqBars mEq, mWave;
    private LinearLayout mHaGrid, mHomeCols;
    private TextView mHaHint;
    private TextView mVoiceStatus;
    private MicButtonView mMic;
    private TextView mCursor;
    private TextView mHomeTag;
    private PixelBackdrop mBackdrop;
    private Runnable mTypeStep;
    private LinearLayout mPluginsRow;
    private boolean mPluginsBound;

    // state
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<HomeAssistantClient.Entity> mEntities = new ArrayList<HomeAssistantClient.Entity>();
    private List<ResolveInfo> mLaunchables;
    private CountDownTimer mTimer;
    private long mTimerEndsAt;
    private String mRenderedTheme;
    private String mLastUtterance = "";
    private long mLastUtteranceAt;
    private DashboardCards mDashboard;
    private boolean mVoiceReady = false;
    private List<NewsProvider.Headline> mNews;
    private int mNewsIdx = 0;
    private boolean mModelBusy = false;

    private static final int REQ_MIC = 41;
    private static final int REQ_STORAGE = 42;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            updateClock();
            updateMedia();
            mHandler.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        sAppContext = getApplicationContext();

        // first boot: run the AstSetup wizard (phone-driven QR setup)
        if (!setupDone()) {
            try {
                startActivity(new Intent().setClassName("com.astos.setup",
                        "com.astos.setup.SetupActivity"));
                finish();
                return;
            } catch (Throwable ignored) {
                // wizard not installed (dev build) — carry on
            }
        }

        Speak.init(this);
        MusicEngine.listen(this);

        // lockdown + persistent shade-killer (v2.4 layer)
        Kiosk.apply(this);
        KioskService.ensureRunning(this);

        // the companion channel (Bluetooth SPP + USB) — runs alongside the UI
        try { LinkHostShell.ensure(this); } catch (Throwable ignored) {}

        // .astpl mods: load + watch for card changes
        ToolSchedules.restore(this);
        PluginHost.init(this).setListener(this);
        PluginHost.get().reloadAll();

        setContentView(R.layout.activity_home);
        Ui.apply(this, findViewById(android.R.id.content));
        mRenderedTheme = AppSettings.themeKey(this);

        bindViews();
        bindMediaButtons();
        bindMic();
        bindNav();
        mDashboard = new DashboardCards(this, mHomeCols);

        findViewById(R.id.promptBtn).setOnClickListener(v -> com.astos.link.TextEntry.show(this, "Type a prompt", mLastUtterance, false, this::onResult));
        mVoiceStatus.setOnClickListener(v -> {
            if(mFullResponse==null||mFullResponse.isEmpty())return;
            android.app.AlertDialog response=new com.astos.link.PixelUi.Builder(this).setTitle("Ast").setMessage(mFullResponse).setPositiveButton("Close",null).show();
            mHandler.postDelayed(()->response.dismiss(),Math.max(0,mResponseExpiresAt-android.os.SystemClock.elapsedRealtime()));
        });
        PcPlayback.setListener(message -> {typeLine(message);keepLine(15000);});
        bindScenes();

        loadLaunchables();

        if (AppSettings.keepScreenOn(this))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (AppSettings.kiosk(this)) applyImmersive(true);

        registerReceiver(mTickRecv, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(mTickRecv, new IntentFilter(Intent.ACTION_TIME_TICK));
        registerReceiver(mTickRecv, new IntentFilter(Intent.ACTION_SCREEN_ON));

        blinkCursor();
        adjustForOrientation(getResources().getConfiguration().orientation);

        // voice: the model downloads on-device after setup, not bundled
        VoiceEngine.get().prepare(this, new VoiceEngine.ReadyListener() {
            @Override public void onReady(boolean ok, String message) {
                mVoiceReady = ok;
                if (!ok) {
                    if (VoiceEngine.MISSING_MODEL.equals(message)) onModelMissing();
                    else toast("Voice unavailable: " + message);
                }
            }
        });
        VoiceEngine.get().setCallback(this);

        refreshWeather();
        refreshHomeAssistant();
    }

    private boolean setupDone() {
        try {
            return Settings.Global.getInt(getContentResolver(), "astos_setup_done", 0) == 1;
        } catch (Throwable t) { return false; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!setupDone()) return;
        try { LinkHostShell.ensure(this); } catch (Throwable ignored) {}
        sAppContext = getApplicationContext();
        PluginHost.init(this).setListener(this);renderPluginCards();
        Kiosk.apply(this); // self-heal lockdown
        if (mRenderedTheme != null && !mRenderedTheme.equals(AppSettings.themeKey(this))) {
            recreate();
            return;
        }
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
        MediaControlService.refreshSessions();
        refreshWeather();
        refreshHomeAssistant();
        updateVolume();
        if(mDashboard!=null)mDashboard.reflow();
        ((PixelBackdrop)findViewById(R.id.backdrop)).setAnimating(AppSettings.backdropAnim(this));
        applyImmersive(AppSettings.kiosk(this));
        if (AppSettings.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
        VoiceEngine.get().stopListening();
    }

    @Override
    protected void onDestroy() {
        PcPlayback.clearListener();
        if(PluginHost.get()!=null)PluginHost.get().clearListener(this);
        MusicEngine.unlisten(this);
        VoiceEngine.get().stopListening();
        mHandler.removeCallbacks(mTick);
        try { unregisterReceiver(mTickRecv); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        adjustForOrientation(cfg.orientation);
    }

    /** self-heal kiosk: shade must stay dead across SystemUI restarts */
    private final BroadcastReceiver mTickRecv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_TIME_TICK.equals(a)
                    || Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                Kiosk.disableShadePublic(HomeActivity.this);
                if (AppSettings.kiosk(HomeActivity.this)) applyImmersive(true);
                // .astpl mods get their onTick from the minute tick — cheap
                // and perfectly fine for dashboards
                PluginHost ph = PluginHost.get();
                if (ph != null) ph.tick();
            }
        }
    };

    // ------------------------------------------------------------ plugin cards

    /** PluginHost.Listener — cards changed, re-render the row. */
    @Override public void onCardsChanged() {
        mHandler.post(new Runnable() { @Override public void run() { renderPluginCards(); } });
    }

    @Override public void onToast(final String msg) {
        mHandler.post(new Runnable() {
            @Override public void run() { toast(msg); }
        });
    }

    private void renderPluginCards() {
        LinearLayout row = mPluginsRow;
        if (row == null) return;
        row.removeAllViews();
        List<PluginHost.Card> cards = PluginHost.get() == null
                ? java.util.Collections.<PluginHost.Card>emptyList()
                : PluginHost.get().cards();
        for (int i = 0; i < Math.min(4, cards.size()); i++) {
            final PluginHost.Card card = cards.get(i);
            Button b = new Button(this);
            b.setText(card.title + (card.value == null || card.value.isEmpty()
                    ? "" : "\n" + card.value));
            b.setAllCaps(false);
            b.setTextSize(9f);
            b.setTypeface(Ui.monoB(this));
            b.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            b.setBackgroundResource(R.drawable.bg_icon_box);
            b.setTextColor(Ui.col("ink"));
            b.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            row.addView(b, lp);
            Ui.press(b, 2);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    PluginHost ph = PluginHost.get();
                    if (ph != null) ph.cardTap(card.pluginId, card.id);
                }
            });
        }
        row.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** landscape = two columns; portrait = one */
    private void adjustForOrientation(int orientation) {
        if (mHomeCols == null) return;
        if (mDashboard != null) mDashboard.reflow();
    }

    // ------------------------------------------------------------ view binding

    private void bindViews() {
        mClock = (TextView) findViewById(R.id.clockText);
        mDate = (TextView) findViewById(R.id.dateText);
        mTimerText = (TextView) findViewById(R.id.timerText);
        mTemp = (TextView) findViewById(R.id.tempText);
        mCond = (TextView) findViewById(R.id.condText);
        mCity = (TextView) findViewById(R.id.cityText);
        mWeatherIcon = (ImageView) findViewById(R.id.weatherIcon);
        mTrack = (TextView) findViewById(R.id.trackText);
        mArtist = (TextView) findViewById(R.id.artistText);
        mSrc = (TextView) findViewById(R.id.srcText);
        mT0 = (TextView) findViewById(R.id.t0Text);
        mT1 = (TextView) findViewById(R.id.t1Text);
        mPlayPause = (ImageButton) findViewById(R.id.playPauseBtn);
        mProgBar = (SegBar) findViewById(R.id.progBar);
        mVolBar = (SegBar) findViewById(R.id.volBar);
        mVolText = (TextView) findViewById(R.id.volText);
        mArt = (PixelAlbumArt) findViewById(R.id.albumArt);
        mEq = (EqBars) findViewById(R.id.eq);
        mWave = (EqBars) findViewById(R.id.wave);
        mHaGrid = (LinearLayout) findViewById(R.id.haGrid);
        mHomeCols = (LinearLayout) findViewById(R.id.homeCols);
        mHaHint = (TextView) findViewById(R.id.haHint);
        mVoiceStatus = (TextView) findViewById(R.id.voiceStatus);
        mMic = (MicButtonView) findViewById(R.id.micBtn);
        mCursor = (TextView) findViewById(R.id.cursor);
        mPluginsRow = (LinearLayout) findViewById(R.id.pluginsRow);
        renderPluginCards();
        mHomeTag = (TextView) findViewById(R.id.homeTag);
        mBackdrop = (PixelBackdrop) findViewById(R.id.backdrop);

        if (mBackdrop != null) mBackdrop.setAnimating(AppSettings.backdropAnim(this));

        mProgBar.init(36, false, null);
        mVolBar.init(12, true, new SegBar.L() {
            @Override public void onLevel(SegBar bar, int level) {
                int pct = Math.round(level * 100f / 12);
                mediaAction("volset", pct);
                updateVolume();
            }
        });
        Ui.press(findViewById(R.id.appsBtn), 3);
        Ui.press(mPlayPause, 3);
        Ui.press(findViewById(R.id.prevBtn), 3);
        Ui.press(findViewById(R.id.nextBtn), 3);

        // tapping the voiceline opens the pixel input (song search etc.)
        View voiceRow = (View) mVoiceStatus.getParent();
        voiceRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openInput("song", ""); }
        });
    }

    private void bindNav() {
        findViewById(R.id.appsBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { allApps(); }
        });
        findViewById(R.id.navSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.navHome).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* already home */ }
        });
        // nav active state: home is the active tab on this screen
        View hb = findViewById(R.id.navHomeBar);
        View sb = findViewById(R.id.navSettingsBar);
        hb.setBackgroundColor(Ui.cur.accent);
        sb.setVisibility(View.INVISIBLE);
        ViewGroup homeCol = (ViewGroup) findViewById(R.id.navHome);
        ((ImageView) ((ViewGroup) homeCol.getChildAt(0)).getChildAt(0)).setColorFilter(Ui.cur.accent);
        ViewGroup setCol = (ViewGroup) findViewById(R.id.navSettings);
        ((ImageView) ((ViewGroup) setCol.getChildAt(0)).getChildAt(0)).setColorFilter(Ui.cur.soft);
    }

    private void bindScenes() {
        Ui.press(findViewById(R.id.sceneNight), 2);
        Ui.press(findViewById(R.id.sceneMovie), 2);
        Ui.press(findViewById(R.id.sceneOff), 2);
        findViewById(R.id.sceneNight).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sceneRule("night"); }
        });
        findViewById(R.id.sceneMovie).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sceneRule("movie"); }
        });
        findViewById(R.id.sceneOff).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sceneRule("off"); }
        });
    }

    private void blinkCursor() {
        mCursor.post(new Runnable() {
            @Override public void run() {
                mCursor.animate().alpha(0f).setDuration(500).withEndAction(new Runnable() {
                    @Override public void run() {
                        mCursor.animate().alpha(1f).setDuration(500).withEndAction(this).start();
                    }
                }).start();
            }
        });
    }

    // ------------------------------------------------------------ UI updates

    private void updateClock() {
        Date now = new Date();
        SimpleDateFormat tf = new SimpleDateFormat(
                AppSettings.h24(this) ? "HH:mm" : "h:mm", Locale.getDefault());
        mClock.setText(tf.format(now));
        mDate.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        if (mTimerEndsAt > System.currentTimeMillis() && mTimer != null) {
            long left = mTimerEndsAt - System.currentTimeMillis();
            mTimerText.setVisibility(View.VISIBLE);
            mTimerText.setText("TIMER " + formatLeft(left));
        } else if (mTimer == null) {
            long next=Long.MAX_VALUE;
            try{org.json.JSONArray rows=ToolSchedules.list(this);for(int i=0;i<rows.length();i++){org.json.JSONObject row=rows.getJSONObject(i);if("timer".equals(row.optString("kind")))next=Math.min(next,row.optLong("at",Long.MAX_VALUE));}}catch(Exception ignored){}
            if(next!=Long.MAX_VALUE&&next>System.currentTimeMillis()){mTimerText.setVisibility(View.VISIBLE);mTimerText.setText("TIMER "+formatLeft(next-System.currentTimeMillis()));}
            else mTimerText.setVisibility(View.GONE);
        }
    }

    private void updateMedia() {
        org.json.JSONObject pc = PcPlayback.state();
        if (pc != null && (!MusicEngine.isPlaying() || pc.optBoolean("playing", false) || PcPlayback.selected())) {
            mTrack.setText(pc.optString("title", "Spotify on PC"));
            mArtist.setText(pc.optString("artist", "")); mSrc.setText("PC · Spotify");
            setPlaying(pc.optBoolean("playing", false));
            int position = pc.optInt("positionMs",0), duration = pc.optInt("durationMs",0);
            mProgBar.setLevel(duration > 0 ? Math.round(position * 36f / duration) : 0);
            mT0.setText((pc.optBoolean("stale",false)?"~":"")+formatLeft(position));mT1.setText(formatLeft(duration));
            mArt.load(pc.optString("artUrl", ""),pc.optString("title", "Spotify"));
            mEq.useSession(-1);mEq.levels(pc.optJSONArray("bands"));
            return;
        }
        SongSearch.Track t = MusicEngine.current();
        if (t != null) {
            mTrack.setText(t.title == null || t.title.isEmpty() ? "—" : t.title);
            mArtist.setText((t.artist == null || t.artist.isEmpty() ? "unknown" : t.artist)
                    + (t.source.isEmpty() ? "" : " · " + sourceTag(t).toLowerCase(Locale.US)));
            mSrc.setText(sourceTag(t));
            setPlaying(MusicEngine.isPlaying());
            float p = MusicEngine.progress();
            mProgBar.setLevel(Math.round(p * 36f));
            mT0.setText(formatLeft(MusicEngine.positionMs()));
            mT1.setText(formatLeft(t.durationMs > 0 ? (int) t.durationMs : MusicEngine.durationMs()));
            mArt.load(t.artUrl,t.title == null ? "astos" : t.title);
            mEq.useSession(MusicEngine.audioSession());
        } else if (MediaControlService.hasActive()) {
            String tr = MediaControlService.track();
            mTrack.setText(tr == null || tr.isEmpty() ? "—" : tr);
            String a = MediaControlService.artist();
            mArtist.setText(a == null || a.isEmpty() ? "media session" : a);
            mSrc.setText("media session");
            setPlaying(MediaControlService.isPlaying());
            mProgBar.setLevel(0);
            mT0.setText("--:--");
            mT1.setText("--:--");
            mArt.showBitmap(MediaControlService.artwork(),tr==null?"media":tr);
            mEq.useSession(-1);
        } else {
            mEq.useSession(-1);mArt.load("","idle");
            mTrack.setText("Nothing playing");
            mArtist.setText("media shows up here");
            mSrc.setText("idle");
            setPlaying(false);
            mProgBar.setLevel(0);
            mT0.setText("--:--");
            mT1.setText("--:--");
        }
    }

    private String sourceTag(SongSearch.Track t) {
        if (t == null) return "";
        if ("stream".equals(t.source) || "piped".equals(t.source)) return "youtube stream";
        if ("preview".equals(t.source)) return "30s preview";
        if ("local".equals(t.source)) return "local file";
        return "";
    }

    private void setPlaying(boolean on) {
        mPlayPause.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
        mEq.setPlaying(on);
    }

    private void updateVolume() {
        int vol = 0;
        org.json.JSONObject pc = PcPlayback.state();
        try { vol = pc != null && (!MusicEngine.isPlaying() || pc.optBoolean("playing", false) || PcPlayback.selected()) ? pc.optInt("volume",0) : MediaControlService.volumePercent(this); } catch (Throwable ignored) {}
        mVolBar.setLevel(Math.round(vol * 12f / 100f));
        mVolText.setText(vol + "%");
    }

    private void bindMediaButtons() {
        mPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mediaAction("toggle", 0); }
        });
        findViewById(R.id.nextBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mediaAction("next", 0); }
        });
        findViewById(R.id.prevBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mediaAction("prev", 0); }
        });
    }

    private void bindMic() {
        mMic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (VoiceEngine.get().isListening() && VoiceEngine.get().cloudMode()) {
                    VoiceEngine.get().finishCloud();
                    typeLine("transcribing…");
                    return;
                }
                if (VoiceEngine.get().isListening()) {
                    toggleMicOff();
                    return;
                }
                if (!mVoiceReady) {
                    VoiceEngine.get().prepare(HomeActivity.this, new VoiceEngine.ReadyListener() {
                        @Override public void onReady(boolean ok, String message) {
                            mVoiceReady = ok;
                            if (ok) toast("Tap the mic again");
                            else if (VoiceEngine.MISSING_MODEL.equals(message)) onModelMissing();
                            else toast("Voice unavailable: " + message);
                        }
                    });
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    return;
                }
                toggleMic();
            }
        });
    }

    private void toggleMic() {
        VoiceEngine v = VoiceEngine.get();
        try {
            v.startListening();
            setMicActive(true);
            typeLine(v.cloudMode() ? "listening… tap again to send (up to 8 seconds)" : "listening…");
        } catch (Exception e) {
            toast("Mic error: " + e.getMessage());
        }
    }

    private void toggleMicOff() {
        VoiceEngine.get().stopListening();
        setMicActive(false);
    }

    private void setMicActive(boolean active) {
        mMic.setListening(active);
        mWave.setPlaying(active);
    }

    /** char-by-char voiceline, like the mock's typewriter */
    private long mResponseExpiresAt;
    private String mFullResponse="";
    private void typeLine(final String s) {
        if (mTypeStep != null) mVoiceStatus.removeCallbacks(mTypeStep);
        mHandler.removeCallbacks(mHideStatus);
        mResponseExpiresAt=android.os.SystemClock.elapsedRealtime()+15000;
        mFullResponse=s==null?"":s;
        mVoiceStatus.setText(mFullResponse);
        mHandler.postDelayed(mHideStatus,15000);
    }

    private void keepLine(long ms) {
        mHandler.removeCallbacks(mHideStatus);
        mHandler.postDelayed(mHideStatus, 15000);
    }

    private final Runnable mHideStatus = new Runnable() {
        @Override public void run() { typeLine("say something or tap the mic"); }
    };

    private void toast(String s) {
        if (s == null || s.isEmpty()) return;
        typeLine(s);keepLine(15000);
    }

    // ------------------------------------------------------------ voice model

    /** Model not on the device: auto-download on WiFi, else prompt. */
    private void onModelMissing() {
        if (mModelBusy) return;
        if (networkConnected()) {
            downloadModel();
        } else {
            typeLine("voice model: connect wifi, then tap the mic to download");
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
        typeLine("finishing setup: downloading the voice model…");
        ModelDownloader.download(this, new ModelDownloader.Listener() {
            @Override public void onProgress(int pct, String note) {
                if (note != null && !note.isEmpty()) typeLine(note);
            }
            @Override public void onDone() {
                mModelBusy = false;
                VoiceEngine.get().prepare(HomeActivity.this, new VoiceEngine.ReadyListener() {
                    @Override public void onReady(boolean ok, String message) {
                        mVoiceReady = ok;
                        if (ok) typeLine("voice ready — say something");
                        else toast(message);
                    }
                });
            }
            @Override public void onError(String msg) {
                mModelBusy = false;
                typeLine("voice model failed — tap the mic to retry");
            }
        });
    }

    // ------------------------------------------------------------ weather

    private static int weatherIconRes(int code) {
        // codes: 0 clear · 1-3 partly/cloudy · 45/48 fog · 51-67 rain ·
        // 71-77 snow · 80-82 showers · 95-99 storm
        if (code >= 95) return R.drawable.ic_weather_storm;
        if (code >= 71) return R.drawable.ic_weather_snow;
        if (code >= 51) return R.drawable.ic_weather_rain;
        if (code >= 45) return R.drawable.ic_weather_cloud;
        if (code >= 1 && code <= 3) return R.drawable.ic_weather_partly;
        return R.drawable.ic_weather_clear;
    }

    private void refreshWeather() {
        String city = AppSettings.city(this);
        if (city.isEmpty()) {
            mCity.setText("set your city in settings");
            mTemp.setText("--°");
            mCond.setText("weather unset");
            return;
        }
        WeatherProvider.refresh(this, new WeatherProvider.Callback() {
            @Override public void onWeather(WeatherProvider.WeatherInfo w, String error) {
                if (w != null) {
                    mTemp.setText(String.format(Locale.US, "%.0f°", w.temp));
                    mCond.setText(WeatherProvider.describe(w.code)
                            + "  ·  " + String.format(Locale.US, "%.0f° / %.0f°", w.tempMin, w.tempMax));
                    mWeatherIcon.setImageResource(weatherIconRes(w.code));
                    mCity.setText(w.city == null || w.city.isEmpty()
                            ? AppSettings.city(HomeActivity.this) : w.city);
                } else if (error != null) {
                    mCond.setText(error);
                }
            }
        });
    }

    // ------------------------------------------------------------ home assistant

    private void refreshHomeAssistant() {
        if (!AppSettings.haEnabled(this) || AppSettings.haUrl(this).isEmpty()) {
            mHaHint.setVisibility(View.VISIBLE);
            mHaHint.setText("Connect Home Assistant in settings to control your home.");
            return;
        }
        HomeAssistantClient.listEntities(this, new HomeAssistantClient.Callback<List<HomeAssistantClient.Entity>>() {
            @Override public void onResult(final List<HomeAssistantClient.Entity> entities, String error) {
                if (entities != null) {
                    mEntities.clear();
                    mEntities.addAll(entities);
                    renderHa();
                    mHaHint.setVisibility(entities.isEmpty() ? View.VISIBLE : View.GONE);
                } else {
                    mHaHint.setVisibility(View.VISIBLE);
                    mHaHint.setText("Home Assistant: " + error);
                }
            }
        });
    }

    private static int iconFor(HomeAssistantClient.Entity e) {
        String d = e.domain == null ? "" : e.domain;
        if (d.contains("light") || d.contains("scene")) return R.drawable.ic_bulb;
        if (d.contains("media")) return R.drawable.ic_tv;
        if (d.contains("lock")) return R.drawable.ic_lock;
        if (d.contains("fan")) return R.drawable.ic_fan;
        if (d.contains("switch") || d.contains("plug")) return R.drawable.ic_tv;
        return R.drawable.ic_bulb;
    }

    private void renderHa() {
        mHaGrid.removeAllViews();
        List<HomeAssistantClient.Entity> shown = new ArrayList<HomeAssistantClient.Entity>();
        for (HomeAssistantClient.Entity e : mEntities) {
            if (!e.isScene) shown.add(e);
            if (shown.size() >= 9) break;
        }
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(this, 8);
                row.setLayoutParams(lp);
                mHaGrid.addView(row);
            }
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cell.rightMargin = Ui.dp(this, 8);
            row.addView(makeTile(shown.get(i)), cell);
        }
        int on = 0;
        for (HomeAssistantClient.Entity e : mEntities) if (e.on && !e.isScene) on++;
        if (mHomeTag != null) mHomeTag.setText(on > 0 ? "HOME · " + on + " ON" : "HOME");
    }

    private View makeTile(final HomeAssistantClient.Entity e) {
        View v = getLayoutInflater().inflate(R.layout.item_toggle, mHaGrid, false);
        ImageView icon = (ImageView) v.findViewById(R.id.tileIcon);
        TextView name = (TextView) v.findViewById(R.id.tileName);
        TextView state = (TextView) v.findViewById(R.id.tileState);
        name.setText(pretty(e.name));
        icon.setImageResource(iconFor(e));
        syncTile(v, icon, state, e);
        Ui.press(v, 3);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                HomeAssistantClient.toggle(HomeActivity.this, e, new HomeAssistantClient.Callback<Boolean>() {
                    @Override public void onResult(Boolean ok, String error) {
                        if (ok) refreshHomeAssistant();
                        else toast("HA: " + error);
                    }
                });
            }
        });
        return v;
    }

    private void syncTile(View v, ImageView icon, TextView state, HomeAssistantClient.Entity e) {
        boolean lock = e.domain != null && e.domain.contains("lock");
        v.setBackgroundDrawable(Ui.d(this, e.on ? "tileOn" : "tile"));
        icon.setColorFilter(e.on ? Ui.cur.ink : Ui.cur.soft);
        state.setTextColor(e.on ? Ui.cur.ink : Ui.cur.soft);
        state.setText(lock ? (e.on ? "LOCKED" : "OPEN") : (e.on ? "ON" : "OFF"));
        ((TextView) v.findViewById(R.id.tileName)).setTextColor(e.on ? Ui.cur.ink : Ui.cur.soft);
    }

    /** night / movie / off scene rules over known entities */
    private void sceneRule(String rule) {
        for (final HomeAssistantClient.Entity e : mEntities) {
            if (e.isScene) continue;
            boolean lock = e.domain != null && e.domain.contains("lock");
            boolean on;
            if (lock) on = true;                                  // always keep locked
            else if ("night".equals(rule)) on = false;
            else if ("movie".equals(rule)) on = e.domain != null
                    && (e.domain.contains("media") || e.id.toLowerCase(Locale.US).contains("lamp"));
            else on = false;
            HomeAssistantClient.turn(this, e, on, null);
        }
        if ("night".equals(rule) && MusicEngine.isPlaying()) MusicEngine.stop(this);
        toast("\u201c" + rule + "\u201d applied");
    }

    private static String pretty(String s) {
        if (s == null || s.isEmpty()) return "?";
        String[] words = s.split("[_ ]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ app drawer

    @SuppressWarnings("deprecation")
    private void loadLaunchables() {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        mLaunchables = getPackageManager().queryIntentActivities(main, 0);
        Collections.sort(mLaunchables, new java.util.Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return loadLabel(a).compareToIgnoreCase(loadLabel(b));
            }
        });
    }

    private String loadLabel(ResolveInfo ri) {
        try {
            CharSequence c = ri.loadLabel(getPackageManager());
            return c == null ? ri.activityInfo.name : c.toString();
        } catch (Exception e) {
            return ri.activityInfo.name;
        }
    }

    @Override public void allApps() {
        // pixel app drawer: panel card + filter + grid
        final Dialog d = new Dialog(this, R.style.DialogTheme);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 26), Ui.dp(this, 26), Ui.dp(this, 26), Ui.dp(this, 26));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(Ui.d(this, "dialog"));
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        wrap.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setTypeface(Ui.monoB(this));
        title.setTextSize(12);
        title.setLetterSpacing(0.2f);
        title.setTextColor(Ui.cur.bg);
        title.setText("APPS");
        title.setBackgroundDrawable(Ui.d(this, "tag"));
        title.setPadding(Ui.dp(this, 9), Ui.dp(this, 2), Ui.dp(this, 9), Ui.dp(this, 2));
        head.addView(title);

        final EditText filter = new EditText(this);
        filter.setBackgroundDrawable(Ui.d(this, "edit"));
        filter.setHint("filter…");
        filter.setTextColor(Ui.cur.ink);
        filter.setHintTextColor(Ui.cur.soft);
        filter.setSingleLine();
        filter.setTextSize(13);
        filter.setTypeface(Ui.mono(this));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fp.leftMargin = Ui.dp(this, 14);
        head.addView(filter, fp);
        panel.addView(head);

        final GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setVerticalSpacing(Ui.dp(this, 12));
        grid.setHorizontalSpacing(Ui.dp(this, 6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setColumnWidth(Ui.dp(this, 72));
        grid.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        final AppAdapter adapter = new AppAdapter(this, mLaunchables);
        grid.setAdapter(adapter);
        panel.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });

        d.setContentView(wrap);
        d.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        d.show();

        // keyboard inside the dialog window (not behind it)
        final PixelKeyboard kb = PixelKeyboard.attachTo(wrap, filter);
        d.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) { kb.hide(); }
        });
        filter.requestFocus();
    }

    // ------------------------------------------------------------ pixel input

    /** song search / groq key / city / HA URL — pixel dialog + our keyboard */
    private void openInput(String mode, String initial) {
        final Dialog d = new Dialog(this, R.style.DialogTheme);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 200), Ui.dp(this, 80), Ui.dp(this, 200), Ui.dp(this, 80));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(Ui.d(this, "card"));
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 16));
        wrap.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final String m = mode;
        TextView title = new TextView(this);
        title.setTypeface(Ui.monoB(this));
        title.setTextSize(11);
        title.setLetterSpacing(0.2f);
        title.setTextColor(Ui.cur.bg);
        title.setText("song".equals(m) ? "PLAY A SONG" : "groq".equals(m) ? "GROQ AI KEY"
                : "city".equals(m) ? "WEATHER CITY" : "HOME ASSISTANT URL");
        title.setBackgroundDrawable(Ui.d(this, "tag"));
        title.setPadding(Ui.dp(this, 9), Ui.dp(this, 2), Ui.dp(this, 9), Ui.dp(this, 2));
        panel.addView(title);

        final EditText input = new EditText(this);
        input.setBackgroundDrawable(Ui.d(this, "edit"));
        input.setTextColor(Ui.cur.ink);
        input.setHintTextColor(Ui.cur.soft);
        input.setSingleLine();
        input.setTextSize(14);
        input.setTypeface(Ui.mono(this));
        input.setText(initial == null ? "" : initial);
        input.setHint("song".equals(m) ? "type a song name…"
                : "groq".equals(m) ? "paste gsk_ key from console.groq.com"
                : "city".equals(m) ? "city name" : "http://homeassistant.local:8123");
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Ui.dp(this, 12);
        panel.addView(input, ip);

        final PixelKeyboard kb = PixelKeyboard.attachTo(wrap, input);

        d.setContentView(wrap);
        d.show();
        input.requestFocus();

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId,
                                                     android.view.KeyEvent event) {
                String val = input.getText().toString().trim();
                d.dismiss();
                kb.hide();
                if (!val.isEmpty()) submitInput(m, val);
                return true;
            }
        });
    }

    private void submitInput(String mode, String val) {
        if ("song".equals(mode)) {
            typeLine("finding " + val + "…");
            if (AppSettings.speakEnabled(this)) Speak.say(this, "Playing " + val);
            MusicEngine.playQuery(this, val);
        } else if ("groq".equals(mode)) {
            AppSettings.setGroqKey(this, val);
            toast("Groq AI connected");
        } else if ("city".equals(mode)) {
            AppSettings.setCity(this, val);
            WeatherProvider.geocode(this, val, new WeatherProvider.GeoCallback() {
                @Override public void onGeo(String resolved, String error) {
                    if (resolved != null) { toast("Weather set to " + resolved); refreshWeather(); }
                    else toast("Couldn't find that city");
                }
            });
        } else {
            AppSettings.setHaUrl(this, val);
            toast("Home Assistant URL saved");
            refreshHomeAssistant();
        }
    }

    // ------------------------------------------------------------ voice callbacks

    @Override public void onPartial(String text) {
        typeLine("Hearing: " + text);
        ((TextView)findViewById(R.id.heardText)).setText(text);
    }

    @Override public void onResult(String text) {
        if (text == null || text.trim().isEmpty()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (text.equals(mLastUtterance) && now - mLastUtteranceAt < 1800) return;
        mLastUtterance = text; mLastUtteranceAt = now;
        ((TextView)findViewById(R.id.heardText)).setText("You: " + text);
        VoiceEngine.get().stopListening(); setMicActive(false);
        // .astpl mods can observe everything said to the tablet
        PluginHost ph = PluginHost.get();
        if (ph != null) ph.onVoice(text);
        String response = CommandRouter.route(text, this);
        if (response != null) {
            typeLine(response);
            if (AppSettings.speakEnabled(this)) Speak.say(this, response);
            keepLine(4000);
        } else {
            // action spoke for itself (song play etc.)
            keepLine(2500);
        }
        // keep listening in kiosk mode; stop after one command otherwise
        if (!AppSettings.kiosk(this)) toggleMicOff();
        else setMicActive(VoiceEngine.get().isListening());
    }

    @Override public void onError(String message) {
        if ("timeout".equals(message)) { setMicActive(false); return; }
        typeLine("\u2715 " + message);
        setMicActive(false);
        keepLine(2500);
    }

    // ------------------------------------------------------------ CommandRouter.Actions

    @Override public void pcAction(String action, String query, boolean sticky) {
        if(sticky)PcPlayback.preferLocal(this,false);PcPlayback.send(this, action, query, sticky);
    }
    @Override public boolean pcSelected() { return PcPlayback.selected() || (PcPlayback.available() && !PcPlayback.localSelected(this)); }
    @Override public String playbackTarget(boolean pc){if(pc&&!PcPlayback.available())return "PC is not connected";PcPlayback.preferLocal(this,!pc);return pc?"Playback set to PC":"Playback set to tablet";}

    @Override public void openApp(final String query) {
        for (ResolveInfo ri : mLaunchables) {
            String label = loadLabel(ri).toLowerCase(Locale.US);
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.US);
            if (label.contains(query) || pkg.contains(query)
                    || query.contains(label) || query.contains(pkg)) {
                try {
                    Intent i = new Intent();
                    i.setClassName(ri.activityInfo.applicationInfo.packageName, ri.activityInfo.name);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    toast("Opening " + loadLabel(ri));
                } catch (Exception e) { toast("Can't open that"); }
                return;
            }
        }
        toast("No app called " + query);
    }

    @Override public void mediaAction(String action, int value) {
        org.json.JSONObject pc = PcPlayback.state();
        if (pc != null && (!MusicEngine.isPlaying() || pc.optBoolean("playing", false) || PcPlayback.selected())) {
            String remote = "prev".equals(action) ? "previous" : "toggle".equals(action)
                ? (pc.optBoolean("playing", false) ? "pause" : "resume") : "play".equals(action) ? "resume" : action;
            PcPlayback.send(this, remote, String.valueOf(value), false);
            return;
        }
        if ("play".equals(action) || "pause".equals(action) || "toggle".equals(action)) {
            if (MusicEngine.isPlaying() || MusicEngine.current() != null) MusicEngine.toggle(this);
            else MediaControlService.toggle();
        } else if ("next".equals(action)) {
            if (MusicEngine.current() != null) MusicEngine.next(this);
            else MediaControlService.next();
        } else if ("prev".equals(action)) {
            if (MusicEngine.current() != null) MusicEngine.prev(this);
            else MediaControlService.prev();
        } else if ("volup".equals(action)) {
            MediaControlService.volumeUp(this);
        } else if ("voldown".equals(action)) {
            MediaControlService.volumeDown(this);
        } else if ("volset".equals(action)) {
            MediaControlService.volumePercent(this, value);
        }
        updateVolume();
        updateMedia();
    }

    @Override public void playLocalMusic() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("Grant storage, then say play music again");
            return;
        }
        MusicEngine.local(this);
    }

    @Override public void playSong(String query) {
        if(PcPlayback.available() && !PcPlayback.localSelected(this)){PcPlayback.send(this,"play",query,false);return;}
        typeLine("finding " + query + "…");
        MusicEngine.playQuery(this, query);
    }

    @Override public void weather() {
        refreshWeather();
        WeatherProvider.refresh(this, new WeatherProvider.Callback() {
            @Override public void onWeather(WeatherProvider.WeatherInfo w, String error) {
                if (w != null) {
                    String line = Math.round(w.temp) + "\u00B0 " + WeatherProvider.describe(w.code)
                            + " · " + w.city;
                    typeLine(line);
                    if (AppSettings.speakEnabled(HomeActivity.this))
                        Speak.say(HomeActivity.this, "It's " + Math.round(w.temp) + " degrees, "
                                + WeatherProvider.describe(w.code) + ", in " + w.city + ".");
                    keepLine(6000);
                } else {
                    typeLine("weather unavailable: " + error);
                    keepLine(4000);
                }
            }
        });
    }

    @Override public void news() {
        typeLine("fetching the news…");
        NewsProvider.fetch(false, new NewsProvider.Callback() {
            @Override public void onNews(List<NewsProvider.Headline> headlines, String error) {
                if (headlines != null && !headlines.isEmpty()) {
                    mNews = headlines;
                    mNewsIdx = 0;
                    NewsProvider.Headline h = headlines.get(0);
                    typeLine("news: " + h.title);
                    if (AppSettings.speakEnabled(HomeActivity.this))
                        Speak.say(HomeActivity.this, h.title);
                    keepLine(8000);
                } else {
                    typeLine("news unavailable: " + error);
                    keepLine(4000);
                }
            }
        });
    }

    @Override public void time() {
        String t = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        typeLine("it's " + t);
        if (AppSettings.speakEnabled(this)) Speak.say(this, "It's " + t);
        keepLine(4000);
    }

    @Override public void date() {
        String dd = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(new Date());
        typeLine(dd);
        if (AppSettings.speakEnabled(this)) Speak.say(this, dd);
        keepLine(4000);
    }

    @Override public void whatsPlaying() {
        SongSearch.Track t = MusicEngine.current();
        String line;
        if (t != null) {
            line = "playing " + t.title + " by "
                    + (t.artist.isEmpty() ? "unknown" : t.artist);
        } else if (MediaControlService.hasActive()) {
            line = "playing " + (MediaControlService.track() == null
                    ? "a media session" : MediaControlService.track());
        } else {
            line = "nothing is playing";
        }
        typeLine(line);
        if (AppSettings.speakEnabled(this)) Speak.say(this, line);
        keepLine(5000);
    }

    @Override public void setTimer(long ms) {
        cancelTimer();
        mTimerEndsAt = System.currentTimeMillis() + ms;
        mTimer = new CountDownTimer(ms, 1000) {
            @Override public void onTick(long left) {
                mTimerText.setVisibility(View.VISIBLE);
                mTimerText.setText("TIMER " + formatLeft(left));
            }
            @Override public void onFinish() {
                mTimer = null;
                mTimerEndsAt = 0;
                mTimerText.setVisibility(View.GONE);
                typeLine("timer finished");
                if (AppSettings.speakEnabled(HomeActivity.this))
                    Speak.say(HomeActivity.this, "Timer finished");
                beep();
                keepLine(6000);
            }
        }.start();
    }

    @Override public void cancelTimer() {
        if (mTimer != null) { mTimer.cancel(); mTimer = null; }
        mTimerEndsAt = 0;
        mTimerText.setVisibility(View.GONE);
    }

    @Override public void timerStatus() {
        if (mTimerEndsAt > System.currentTimeMillis()) {
            long left = mTimerEndsAt - System.currentTimeMillis();
            String s = "timer: " + formatLeft(left) + " left";
            typeLine(s);
            if (AppSettings.speakEnabled(this)) Speak.say(this, s);
        } else {
            typeLine("no timer running");
            if (AppSettings.speakEnabled(this)) Speak.say(this, "No timer running");
        }
        keepLine(4000);
    }

    private void beep() {
        try { ToneTone.play(this); } catch (Exception ignored) {}
    }

    private static String formatLeft(long ms) {
        long total = (ms + 999) / 1000;
        long m = total / 60, s = total % 60;
        if (m >= 60) {
            long h = m / 60; m = m % 60;
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    @Override public void homeAssistant(String name, Boolean on) {
        HomeAssistantClient.Entity match = HomeAssistantClient.bestMatch(mEntities, name);
        if (match == null) {
            refreshHomeAssistant();
            toast("Nothing in Home Assistant matches '" + name + "'");
            return;
        }
        final String label = pretty(match.name);
        HomeAssistantClient.turn(this, match, on == null ? !match.on : on,
                new HomeAssistantClient.Callback<Boolean>() {
                    @Override public void onResult(Boolean ok, String error) {
                        if (ok) { refreshHomeAssistant(); toast(label + " done"); }
                        else toast("HA: " + error);
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
            float cur = Settings.System.getFloat(
                    getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
            float next = "up".equals(action) ? Math.min(1f, cur + 0.2f)
                    : "down".equals(action) ? Math.max(0.05f, cur - 0.2f)
                    : Math.max(0.02f, Math.min(1f, level / 100f));
            lp.screenBrightness = next;
            wnd.setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    @Override public void fullscreen(boolean on) {
        applyImmersive(on);
        AppSettings.setKiosk(this, on);
    }

    void applyImmersive(boolean on) {
        Ui.setKioskBars(this, on);
    }

    @Override public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override public void openAndroidSettings() {
        startActivity(new Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override public void openNotificationAccess() {
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    @Override public void homeChooser() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        startActivity(Intent.createChooser(i, "Home app"));
    }

    @Override public void askGroq(String question) {
        if (!AiProviders.configured(this)) {
            String hint = "configure an AI provider in settings for AI answers";
            typeLine(hint);
            if (AppSettings.speakEnabled(this)) Speak.say(this, hint);
            keepLine(5000);
            return;
        }
        typeLine("thinking…");
        GroqClient.ask(this, question, new GroqClient.Callback() {
            @Override public void onReply(String text, String error) {
                String r = text != null ? text : "assistant: " + error;
                typeLine(r);
                if (text != null && AppSettings.speakEnabled(HomeActivity.this))
                    Speak.say(HomeActivity.this, text);
                keepLine(9000);
            }
        });
    }

    // ------------------------------------------------------------ MusicEngine.Listener

    @Override public void onMusicUpdate() {
        mHandler.post(new Runnable() { @Override public void run() { updateMedia(); } });
    }

    // ------------------------------------------------------------ misc

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_MIC && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleMic();
        }
    }

    // drawer adapter
    private class AppAdapter extends android.widget.BaseAdapter {
        private final List<ResolveInfo> mAll;
        private final List<ResolveInfo> mShown = new ArrayList<ResolveInfo>();
        private final LayoutInflater mInflater;

        AppAdapter(Context c, List<ResolveInfo> all) {
            mAll = all;
            mInflater = (LayoutInflater) c.getSystemService(LAYOUT_INFLATER_SERVICE);
            mShown.addAll(all);
        }

        void filter(String q) {
            mShown.clear();
            q = q.toLowerCase();
            for (ResolveInfo ri : mAll) {
                if (loadLabel(ri).toLowerCase().contains(q)
                        || ri.activityInfo.packageName.toLowerCase().contains(q)) mShown.add(ri);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return mShown.size(); }
        @Override public Object getItem(int pos) { return mShown.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            if (conv == null) {
                conv = mInflater.inflate(R.layout.item_shortcut, parent, false);
                conv.setLayoutParams(new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final ResolveInfo ri = mShown.get(pos);
            TextView label = (TextView) conv.findViewById(R.id.shortcutLabel);
            label.setText(loadLabel(ri));
            label.setTextColor(Ui.cur.ink);
            ImageView icon = (ImageView) conv.findViewById(R.id.shortcutIcon);
            try { icon.setImageDrawable(ri.loadIcon(getPackageManager())); } catch (Exception ignored) {}
            conv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent i = new Intent();
                        i.setClassName(ri.activityInfo.applicationInfo.packageName, ri.activityInfo.name);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
            return conv;
        }
    }

    // notification tone helper
    private static class ToneTone {
        static void play(Context c) {
            try {
                android.media.ToneGenerator tg =
                        new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90);
                tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 600);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { tg.release(); }
                }, 900);
            } catch (Exception ignored) {}
        }
    }
}
