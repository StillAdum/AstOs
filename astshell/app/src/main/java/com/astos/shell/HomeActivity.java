package com.astos.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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
 * The AstOs home dashboard: clock, weather, media control, Home Assistant
 * toggles, app shortcuts and the voice button. Warm, calm, card-based.
 */
public class HomeActivity extends Activity
        implements CommandRouter.Actions, VoiceEngine.Callback, MediaControlService.Listener {

    public static volatile Context sAppContext;

    // views
    private TextView mClock, mDate, mTimerText;
    private TextView mTemp, mCond, mCity;
    private ImageView mWeatherIcon;
    private TextView mTrack, mArtist;
    private ImageButton mPlayPause;
    private LinearLayout mHaGrid;
    private TextView mHaHint;
    private LinearLayout mShortcutRow;
    private TextView mVoiceStatus;
    private ImageButton mMic;

    // state
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<HomeAssistantClient.Entity> mEntities = new ArrayList<HomeAssistantClient.Entity>();
    private List<ResolveInfo> mLaunchables;
    private CountDownTimer mTimer;
    private long mTimerEndsAt;
    private boolean mVoiceReady = false;

    private static final int REQ_MIC = 41;
    private static final int REQ_STORAGE = 42;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            updateClock();
            updateMedia();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sAppContext = getApplicationContext();
        setContentView(R.layout.activity_home);

        mClock = (TextView) findViewById(R.id.clockText);
        mDate = (TextView) findViewById(R.id.dateText);
        mTimerText = (TextView) findViewById(R.id.timerText);
        mTemp = (TextView) findViewById(R.id.tempText);
        mCond = (TextView) findViewById(R.id.condText);
        mCity = (TextView) findViewById(R.id.cityText);
        mWeatherIcon = (ImageView) findViewById(R.id.weatherIcon);
        mTrack = (TextView) findViewById(R.id.trackText);
        mArtist = (TextView) findViewById(R.id.artistText);
        mPlayPause = (ImageButton) findViewById(R.id.playPauseBtn);
        mHaGrid = (LinearLayout) findViewById(R.id.haGrid);
        mHaHint = (TextView) findViewById(R.id.haHint);
        mShortcutRow = (LinearLayout) findViewById(R.id.shortcutRow);
        mVoiceStatus = (TextView) findViewById(R.id.voiceStatus);
        mMic = (ImageButton) findViewById(R.id.micBtn);

        bindMediaButtons();
        bindMic();

        findViewById(R.id.settingsBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.allAppsBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { allApps(); }
        });

        loadLaunchables();
        renderShortcuts();
        registerBatteryTick();

        Speak.init(this);

        // voice engine warm-up in background
        VoiceEngine.get().prepare(this, new VoiceEngine.ReadyListener() {
            @Override public void onReady(boolean ok, String message) {
                mVoiceReady = ok;
                if (!ok) toast("Voice unavailable: " + message);
            }
        });
        VoiceEngine.get().setCallback(this);

        if (AppSettings.kiosk(this)) applyImmersive(true);
        if (AppSettings.keepScreenOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sAppContext = getApplicationContext();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
        MediaControlService.setListener(this);
        MediaControlService.refreshSessions();
        refreshWeather();
        refreshHomeAssistant();
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
        MediaControlService.setListener(null);
    }

    @Override
    protected void onDestroy() {
        VoiceEngine.get().stopListening();
        mHandler.removeCallbacks(mTick);
        super.onDestroy();
    }

    // ------------------------------------------------------------ UI updates

    private void updateClock() {
        Date now = new Date();
        mClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        mDate.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        if (mTimerEndsAt > System.currentTimeMillis() && mTimer != null) {
            long left = mTimerEndsAt - System.currentTimeMillis();
            mTimerText.setVisibility(View.VISIBLE);
            mTimerText.setText("Timer · " + formatLeft(left));
        } else if (mTimer == null) {
            mTimerText.setVisibility(View.GONE);
        }
    }

    private void updateMedia() {
        if (MediaControlService.hasActive()) {
            mTrack.setText(empty(MediaControlService.track()));
            mArtist.setText(empty(MediaControlService.artist()));
            mPlayPause.setImageResource(MediaControlService.isPlaying()
                    ? R.drawable.ic_pause : R.drawable.ic_play);
        } else {
            String name = MusicService.currentName();
            if (!name.isEmpty() && MusicService.isPlaying()) {
                mTrack.setText(name);
                mArtist.setText("AstOs music");
                mPlayPause.setImageResource(R.drawable.ic_pause);
            } else {
                mTrack.setText("Nothing playing");
                mArtist.setText("Media shows up here");
                mPlayPause.setImageResource(R.drawable.ic_play);
            }
        }
    }

    private static String empty(String s) { return s == null || s.isEmpty() ? "—" : s; }

    private void bindMediaButtons() {
        mPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (MediaControlService.hasActive()) MediaControlService.toggle();
                else MusicService.toggle(HomeActivity.this);
                updateMedia();
            }
        });
        findViewById(R.id.nextBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (MediaControlService.hasActive()) MediaControlService.next();
                else MusicService.next(HomeActivity.this);
            }
        });
        findViewById(R.id.prevBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (MediaControlService.hasActive()) MediaControlService.prev();
                else MusicService.prev(HomeActivity.this);
            }
        });
        findViewById(R.id.volDownBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { MediaControlService.volumeDown(HomeActivity.this); }
        });
        findViewById(R.id.volUpBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { MediaControlService.volumeUp(HomeActivity.this); }
        });
    }

    private void bindMic() {
        mMic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!mVoiceReady) {
                    VoiceEngine.get().prepare(HomeActivity.this, new VoiceEngine.ReadyListener() {
                        @Override public void onReady(boolean ok, String message) {
                            mVoiceReady = ok;
                            if (ok) toast("Tap the mic again");
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
        if (v.isListening()) {
            v.stopListening();
            setMicActive(false);
            mVoiceStatus.setVisibility(View.GONE);
        } else {
            try {
                v.startListening();
                setMicActive(true);
                showVoiceStatus("Listening…");
            } catch (Exception e) {
                toast("Mic error: " + e.getMessage());
            }
        }
    }

    private void setMicActive(boolean active) {
        mMic.setAlpha(active ? 1.0f : 0.85f);
        mMic.setBackgroundResource(active ? R.drawable.bg_mic_on : R.drawable.bg_mic);
        mMic.setImageResource(active ? R.drawable.ic_mic_on : R.drawable.ic_mic);
    }

    private void showVoiceStatus(String s) {
        mVoiceStatus.setVisibility(View.VISIBLE);
        mVoiceStatus.setText(s);
    }

    private final Runnable mHideStatus = new Runnable() {
        @Override public void run() { mVoiceStatus.setVisibility(View.GONE); }
    };

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ weather

    private void refreshWeather() {
        String city = AppSettings.city(this);
        if (city.isEmpty()) {
            mCity.setText("Set your city in settings");
            mTemp.setText("--");
            mCond.setText("");
            return;
        }
        WeatherProvider.refresh(this, new WeatherProvider.Callback() {
            @Override public void onWeather(WeatherProvider.WeatherInfo w, String error) {
                if (w != null) {
                    mTemp.setText(String.format(Locale.US, "%.0f°", w.temp));
                    mCond.setText(WeatherProvider.describe(w.code)
                            + "  ·  " + String.format(Locale.US, "%.0f° / %.0f°", w.tempMin, w.tempMax));
                    mWeatherIcon.setImageResource(WeatherProvider.iconRes(w.code));
                    mCity.setText(w.city == null || w.city.isEmpty() ? AppSettings.city(HomeActivity.this) : w.city);
                } else if (error != null) {
                    mCond.setText(error);
                }
            }
        });
    }

    private final Runnable mWeatherRefresher = new Runnable() {
        @Override public void run() {
            refreshWeather();
            mHandler.postDelayed(this, 30 * 60 * 1000);
        }
    };

    // ------------------------------------------------------------ home assistant

    private void refreshHomeAssistant() {
        if (AppSettings.haUrl(this).isEmpty()) {
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

    private void renderHa() {
        mHaGrid.removeAllViews();
        List<HomeAssistantClient.Entity> shown = new ArrayList<HomeAssistantClient.Entity>();
        for (HomeAssistantClient.Entity e : mEntities) {
            if (!e.isScene) shown.add(e);
            if (shown.size() >= 9) break;
        }
        if (shown.isEmpty() && !mEntities.isEmpty()) {
            // only scenes: show first scenes
            for (HomeAssistantClient.Entity e : mEntities) {
                if (e.isScene) { shown.add(e); if (shown.size() >= 6) break; }
            }
        }
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                row.setLayoutParams(lp);
                mHaGrid.addView(row);
            }
            row.addView(makeHaChip(shown.get(i)), makeChipParams());
        }
    }

    private LinearLayout.LayoutParams makeChipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, 12, 12);
        return lp;
    }

    private View makeHaChip(final HomeAssistantClient.Entity e) {
        View v = getLayoutInflater().inflate(R.layout.item_toggle, mHaGrid, false);
        TextView name = (TextView) v.findViewById(R.id.toggleName);
        TextView state = (TextView) v.findViewById(R.id.toggleState);
        name.setText(pretty(e.name));
        state.setText(e.isScene ? "scene" : (e.on ? "on" : "off"));
        v.setBackgroundResource(e.on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
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

    // ------------------------------------------------------------ shortcuts

    @SuppressWarnings("deprecation")
    private void loadLaunchables() {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        mLaunchables = getPackageManager().queryIntentActivities(main, 0);
        // sort: system-ish useful apps first, alphabetical
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

    private void renderShortcuts() {
        mShortcutRow.removeAllViews();
        int n = 0;
        for (ResolveInfo ri : mLaunchables) {
            String pkg = ri.activityInfo.applicationInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (n >= 5) break;
            mShortcutRow.addView(makeShortcut(ri));
            n++;
        }
    }

    private View makeShortcut(final ResolveInfo ri) {
        View v = getLayoutInflater().inflate(R.layout.item_shortcut, mShortcutRow, false);
        TextView label = (TextView) v.findViewById(R.id.shortcutLabel);
        label.setText(loadLabel(ri));
        ImageView icon = (ImageView) v.findViewById(R.id.shortcutIcon);
        try { icon.setImageDrawable(ri.loadIcon(getPackageManager())); } catch (Exception ignored) {}
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent();
                    i.setClassName(ri.activityInfo.applicationInfo.packageName, ri.activityInfo.name);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) { toast("Can't open " + loadLabel(ri)); }
            }
        });
        return v;
    }

    private void allApps() {
        // small in-house app drawer with filter box
        final AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DialogTheme);
        b.setTitle("Apps");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        final EditText filter = new EditText(this);
        filter.setHint("Filter…");
        filter.setSingleLine();
        final GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(16);
        grid.setHorizontalSpacing(16);
        grid.setPadding(24, 24, 24, 24);
        final AppAdapter adapter = new AppAdapter(this, mLaunchables);
        grid.setAdapter(adapter);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });
        wrap.addView(filter);
        wrap.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setView(wrap);
        b.setNegativeButton("Close", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { d.dismiss(); }
        });
        b.show();
    }

    // ------------------------------------------------------------ battery

    private void registerBatteryTick() {
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                // kept for future dashboard widgets
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    // ------------------------------------------------------------ voice callbacks

    @Override public void onPartial(String text) {
        showVoiceStatus("… " + text);
    }

    @Override public void onResult(String text) {
        mHandler.removeCallbacks(mHideStatus);
        String response = CommandRouter.route(text, this);
        if (response != null) {
            showVoiceStatus(response);
            Speak.say(this, response);
            mHandler.postDelayed(mHideStatus, 4000);
        } else {
            showVoiceStatus(text);
            mHandler.postDelayed(mHideStatus, 2500);
        }
        // keep listening in kiosk mode; stop after one command otherwise
        if (!AppSettings.kiosk(this)) toggleMic();
        else setMicActive(VoiceEngine.get().isListening());
    }

    @Override public void onError(String message) {
        showVoiceStatus("✕ " + message);
        setMicActive(false);
        mHandler.postDelayed(mHideStatus, 2500);
    }

    // ------------------------------------------------------------ CommandRouter.Actions

    @Override public void openApp(String query) {
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
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if ("play".equals(action)) {
            if (MediaControlService.hasActive()) MediaControlService.play();
            else MusicService.toggle(this);
        } else if ("pause".equals(action)) {
            if (MediaControlService.hasActive()) MediaControlService.pause();
            else if (MusicService.isPlaying()) MusicService.toggle(this);
        } else if ("next".equals(action)) {
            if (MediaControlService.hasActive()) MediaControlService.next();
            else MusicService.next(this);
        } else if ("prev".equals(action)) {
            if (MediaControlService.hasActive()) MediaControlService.prev();
            else MusicService.prev(this);
        } else if ("volup".equals(action)) {
            MediaControlService.volumeUp(this);
        } else if ("voldown".equals(action)) {
            MediaControlService.volumeDown(this);
        } else if ("volset".equals(action)) {
            MediaControlService.volumePercent(this, value);
        }
        updateMedia();
    }

    @Override public void playLocalMusic() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("Grant storage, then say play music again");
            return;
        }
        MusicService.start(this);
    }

    @Override public void weather() { refreshWeather(); }

    @Override public void time() {
        String t = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        showVoiceStatus("It's " + t);
        Speak.say(this, "It's " + t);
    }

    @Override public void date() {
        String d = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(new Date());
        showVoiceStatus(d);
        Speak.say(this, d);
    }

    @Override public void setTimer(long ms) {
        cancelTimer();
        mTimerEndsAt = System.currentTimeMillis() + ms;
        mTimer = new CountDownTimer(ms, 1000) {
            @Override public void onTick(long left) {
                mTimerText.setVisibility(View.VISIBLE);
                mTimerText.setText("Timer · " + formatLeft(left));
            }
            @Override public void onFinish() {
                mTimer = null;
                mTimerEndsAt = 0;
                mTimerText.setVisibility(View.GONE);
                showVoiceStatus("Timer finished");
                Speak.say(HomeActivity.this, "Timer finished");
                beep();
                mHandler.postDelayed(mHideStatus, 6000);
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
            String s = "Timer: " + formatLeft(left) + " left";
            showVoiceStatus(s);
            Speak.say(this, s);
        } else {
            showVoiceStatus("No timer running");
            Speak.say(this, "No timer running");
        }
    }

    private void beep() {
        try {
            final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int t = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            if (t == 0) t = 2;
            ToneTone.play(this);
        } catch (Exception ignored) {}
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

    @Override public void scene(String name) { homeAssistant(name, Boolean.TRUE); }

    @Override public void brightness(String action, Integer level) {
        try {
            if ("up".equals(action)) adjustBrightness(+40);
            else if ("down".equals(action)) adjustBrightness(-40);
            else if (level != null) setBrightness(level);
        } catch (Exception e) {
            toast("Brightness needs WRITE_SETTINGS — enable it in Android settings for AstOs");
        }
    }

    private void adjustBrightness(int delta) {
        int cur = getWindow().getAttributes().screenBrightness;
        if (cur < 0) {
            try { cur = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS); }
            catch (Exception e) { cur = 128; }
        }
        setBrightness(Math.round((cur / 255f) * 100f) + (int) Math.round(delta / 255f * 100f));
    }

    private void setBrightness(int percent) {
        int p = Math.max(1, Math.min(100, percent));
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = p / 100f;
        getWindow().setAttributes(lp);
        try {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, Math.round(255 * p / 100f));
        } catch (Exception ignored) {}
        toast("Brightness " + p + "%");
    }

    @Override public void fullscreen(boolean on) {
        applyImmersive(on);
        AppSettings.setKiosk(this, on);
    }

    void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
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
        i.setPackage(getPackageName()); // ask system to (re)confirm default
        startActivity(i);
        // also fire chooser for all home apps
        Intent chooser = Intent.createChooser(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "Home app");
        startActivity(chooser);
    }

    // ------------------------------------------------------------ misc

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_MIC && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleMic();
        }
    }

    @Override
    public void onMediaChanged() {
        mHandler.post(new Runnable() { @Override public void run() { updateMedia(); } });
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
