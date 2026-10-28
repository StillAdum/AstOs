package com.astos.shell;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.provider.Settings;

import java.util.Map;

/** AstOs settings — pixel cards: display, sound, system + HA + weather + AI. */
public class SettingsActivity extends Activity {

    private EditText mHaUrl, mHaToken, mCity, mGroq;
    private TextView mHaStatus, mCityStatus, mGroqStatus;
    private Button mHaTest;
    private SegBar mVolRow;
    private PixelKeyboard mKb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.apply(this, findViewById(android.R.id.content));

        mHaUrl = (EditText) findViewById(R.id.haUrlInput);
        mHaToken = (EditText) findViewById(R.id.haTokenInput);
        mCity = (EditText) findViewById(R.id.cityInput);
        mGroq = (EditText) findViewById(R.id.groqInput);
        mHaStatus = (TextView) findViewById(R.id.haStatus);
        mCityStatus = (TextView) findViewById(R.id.cityStatus);
        mGroqStatus = (TextView) findViewById(R.id.groqStatus);
        mHaTest = (Button) findViewById(R.id.haTestBtn);
        mVolRow = (SegBar) findViewById(R.id.volRow);

        mHaUrl.setText(AppSettings.haUrl(this));
        mHaToken.setText(AppSettings.haToken(this));
        mCity.setText(AppSettings.city(this));
        mGroq.setText(AppSettings.groqKey(this));
        String resolved = AppSettings.resolvedCity(this);
        mCityStatus.setText(resolved.isEmpty() ? "geocoding on save" : "resolved: " + resolved);

        // our own keyboard — never relies on the (vanishing) system IME
        mKb = PixelKeyboard.attach(this, mHaUrl, mHaToken, mCity, mGroq);

        findViewById(R.id.editCardsBtn).setOnClickListener(v->DashboardCards.choose(this,null));
        findViewById(R.id.animationBtn).setOnClickListener(v->new com.astos.link.PixelUi.Builder(this).setTitle("Backdrop animation").setSingleChoiceItems(new String[]{"Off","Slow drift","Drift","Fast drift","Rain","Custom…"},AppSettings.backdropMode(this),(d,w)->{d.dismiss();if(w==5)BackdropSettings.show(this);else AppSettings.setBackdropMode(this,w);}).show());
        findViewById(R.id.playbackTargetBtn).setOnClickListener(v->new com.astos.link.PixelUi.Builder(this).setTitle("Default playback").setItems(new String[]{"PC when connected (automatic)","Tablet / selected speaker"},(d,w)->{PcPlayback.preferLocal(this,w==1);}).show());
        buildThemeRow();
        bindSwitches();
        bindSystemButtons();
        bindWeather();
        bindHa();
        bindGroq();
        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.manageProvidersBtn).setOnClickListener(v -> startActivity(new Intent(this, ProviderSettingsActivity.class)));

        mVolRow.init(12, true, new SegBar.L() {
            @Override public void onLevel(SegBar bar, int level) {
                MediaControlService.volumePercent(SettingsActivity.this,
                        Math.round(level * 100f / 12));
                syncVolume();
            }
        });
        syncVolume();
        updateWifiLine();

        ((TextView) findViewById(R.id.aboutText)).setText(
                "AstOS " + com.astos.link.LinkServer.osVersion() + " · AstShell");

        // companion link status + the pairing code the PC app asks for
        try {
            com.astos.link.LinkServer lk = com.astos.link.LinkServer.peek();
            String code = lk != null ? lk.displayCode() : "------";
            String bt = "off";
            try {
                android.bluetooth.BluetoothAdapter ad =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                bt = (ad != null && ad.isEnabled()) ? "on" : "off";
            } catch (Throwable ignored) {}
            ((TextView) findViewById(R.id.companionText)).setText(
                    "PC companion · bluetooth " + bt + " · Wi-Fi · new-PC code " + code);
        } catch (Throwable ignored) {}

        TextView companion=(TextView)findViewById(R.id.companionText);
        if(companion.getParent() instanceof LinearLayout){LinearLayout parent=(LinearLayout)companion.getParent();Button pair=new Button(this);pair.setText("Connect / pair new PC");Ui.press(pair,2);parent.addView(pair,parent.indexOfChild(companion)+1);new com.astos.link.PixelUi(this).control(pair);pair.setOnClickListener(v->{
            com.astos.link.LinkServer link=com.astos.link.LinkServer.peek();if(link==null){LinkHostShell.ensure(this);link=com.astos.link.LinkServer.peek();}if(link==null){Toast.makeText(this,"Connection service is starting; retry",Toast.LENGTH_LONG).show();return;}
            String code=link.beginPairing();com.astos.link.PairingPrompts.discoverable(this);companion.setText("New-PC pairing code: "+code+" · valid for 5 minutes");
            new com.astos.link.PixelUi.Builder(this).setTitle("Pair new PC").setMessage("Open AstOS Companion on the same Wi-Fi, or choose this tablet in its Bluetooth picker.\nWi-Fi pairing code: "+code+"\nPreviously trusted PCs reconnect without a code. Confirm matching Bluetooth numbers on both devices.").setPositiveButton("Done",null).show();
        });}
        pressAll();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) Ui.setKioskBars(this, AppSettings.kiosk(this));
    }

    private void pressAll() {
        Ui.press(findViewById(R.id.haTestBtn), 3);
        Ui.press(findViewById(R.id.groqSaveBtn), 3);
        Ui.press(findViewById(R.id.saveCityBtn), 3);
        Ui.press(findViewById(R.id.powerBtn), 3);
        Ui.press(findViewById(R.id.pluginsBtn), 3);
        Ui.press(findViewById(R.id.wizardBtn), 3);
        Ui.press(findViewById(R.id.notifAccessBtn), 2);
    }

    // ------------------------------------------------------------ theme

    private void buildThemeRow() {
        LinearLayout row = (LinearLayout) findViewById(R.id.themeRow);
        row.removeAllViews();
        LinearLayout cur = null;
        int i = 0;
        for (Map.Entry<String, Ui.Th> e : Ui.THEMES.entrySet()) {
            if (i % 3 == 0) {
                cur = new LinearLayout(this);
                cur.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(this, 6);
                row.addView(cur, lp);
            }
            cur.addView(makeSwatch(e.getKey(), e.getValue()),
                    new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            i++;
        }
    }

    private View makeSwatch(final String key, final Ui.Th t) {
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.VERTICAL);
        boolean active = key.equals(AppSettings.themeKey(this));
        sw.setBackgroundDrawable(Ui.d(this, active ? "tileOn" : "opt"));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int ch = Ui.dp(this, 10);
        int[] cols = {t.bg, t.on, t.accent, t.ink};
        for (int c : cols) {
            View v = new View(this);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ch, ch, 1f);
            cp.rightMargin = 1;
            v.setBackgroundColor(c);
            chips.addView(v, cp);
        }
        chips.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        sw.addView(chips);

        TextView name = new TextView(this);
        name.setTypeface(Ui.monoB(this));
        name.setTextSize(9);
        name.setLetterSpacing(0.1f);
        name.setText(t.label.toUpperCase(java.util.Locale.US));
        name.setTextColor(Ui.cur.ink);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 5));
        sw.addView(name);

        Ui.press(sw, 2);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AppSettings.setThemeKey(SettingsActivity.this, key);
                recreate(); // re-theme everything
            }
        });
        return sw;
    }

    // ------------------------------------------------------------ switches

    private void bindSwitches() {
        bindSw(R.id.h24Switch, AppSettings.h24(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setH24(SettingsActivity.this, v); }
        });
        bindSw(R.id.animSwitch, AppSettings.backdropAnim(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setBackdropAnim(SettingsActivity.this, v); }
        });
        bindSw(R.id.speakSwitch, AppSettings.speakEnabled(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setSpeakEnabled(SettingsActivity.this, v); }
        });
        bindSw(R.id.bootSwitch, AppSettings.bootLaunch(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setBootLaunch(SettingsActivity.this, v); }
        });
        bindSw(R.id.keepScreenSwitch, AppSettings.keepScreenOn(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setKeepScreenOn(SettingsActivity.this, v); }
        });
        bindSw(R.id.kioskSwitch, AppSettings.kiosk(this), new Set() {
            @Override public void set(boolean v) {
                AppSettings.setKiosk(SettingsActivity.this, v);
                Ui.setKioskBars(SettingsActivity.this, v);
            }
        });
    }

    private interface Set { void set(boolean v); }

    private void bindSw(int id, boolean initial, final Set setter) {
        PixelSwitch sw = (PixelSwitch) findViewById(id);
        sw.setOn(initial);
        sw.setListener(new PixelSwitch.On() {
            @Override public void changed(PixelSwitch s, boolean on) { setter.set(on); }
        });
    }

    // ------------------------------------------------------------ system

    private void bindSystemButtons() {
        findViewById(R.id.powerBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, PowerActivity.class));
            }
        });
        // the .astpl mod manager
        findViewById(R.id.pluginsBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, PluginManagerActivity.class));
            }
        });
        // re-run the phone-driven setup wizard (AstSetup app)
        findViewById(R.id.wizardBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    com.astos.link.LinkServer link = com.astos.link.LinkServer.peek();
                    if (link != null) link.stop();
                    Settings.Global.putInt(getContentResolver(), "astos_setup_done", 0);
                    startActivity(new Intent().setClassName("com.astos.setup",
                            "com.astos.setup.SetupActivity"));
                    finish();
                } catch (Throwable t) { toast("Setup wizard not installed"); }
            }
        });
        findViewById(R.id.notifAccessBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        findViewById(R.id.audioOutputBtn).setOnClickListener(v -> AudioOutput.choose(this));
        findViewById(R.id.voiceModelBtn).setOnClickListener(v -> {
            String[] options = {"Offline Vosk (continuous)", "Whisper large v3 — accuracy (sends audio to Groq)", "Whisper turbo — faster (sends audio to Groq)"};
            new com.astos.link.PixelUi.Builder(this).setTitle("Speech recognition")
                .setItems(options, (d, which) -> {
                    if (which > 0 && AiProviders.speechKey(this).isEmpty()) {
                        Toast.makeText(this, "Save a Groq key first", Toast.LENGTH_LONG).show(); return;
                    }
                    VoiceEngine.get().stopListening();
                    getSharedPreferences("voice", 0).edit().putBoolean("whisper", which > 0).putString("whisper_model",which==2?"whisper-large-v3-turbo":"whisper-large-v3").apply();
                    VoiceEngine.get().prepare(this, (ok, message) -> {
                        if (!ok) Toast.makeText(this, "Download the offline voice model from the dashboard", Toast.LENGTH_LONG).show();
                    });
                }).show();
        });
    }

    private void updateWifiLine() {
        String line = "wifi · ";
        try {
            WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String ssid = wi != null && wi.getSSID() != null ? wi.getSSID().replace("\"", "") : "";
            line += (ssid.isEmpty() || "0x".equals(ssid) || "<unknown ssid>".equals(ssid))
                    ? "not connected" : ssid + " · connected";
        } catch (Throwable t) {
            line += "unknown";
        }
        ((TextView) findViewById(R.id.kvWifi)).setText(line);
    }

    private void syncVolume() {
        int vol = 0;
        try { vol = MediaControlService.volumePercent(this); } catch (Throwable ignored) {}
        mVolRow.setLevel(Math.round(vol * 12f / 100f));
    }

    // ------------------------------------------------------------ weather

    private void bindWeather() {
        findViewById(R.id.saveCityBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveCity(); }
        });
    }

    private void saveCity() {
        final String city = mCity.getText().toString().trim();
        if (city.isEmpty()) {
            AppSettings.setCity(this, "");
            toast("City cleared");
            return;
        }
        AppSettings.setCity(this, city);
        mCityStatus.setText("locating " + city + "…");
        WeatherProvider.geocode(this, city, new WeatherProvider.GeoCallback() {
            @Override public void onGeo(String resolved, String error) {
                if (resolved != null) {
                    mCityStatus.setText("resolved: " + resolved);
                    toast("Weather set to " + resolved);
                } else {
                    mCityStatus.setText("couldn't find " + city + ": " + error);
                }
            }
        });
    }

    // ------------------------------------------------------------ home assistant

    private void bindHa() {
        mHaTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testHa(); }
        });
    }

    private void testHa() {
        String url = mHaUrl.getText().toString().trim();
        String token = mHaToken.getText().toString().trim();
        AppSettings.setHaUrl(this, url);
        AppSettings.setHaToken(this, token);
        if (url.isEmpty()) { mHaStatus.setText("No Home Assistant URL set"); return; }
        mHaStatus.setText("contacting " + url + "…");
        HomeAssistantClient.listEntities(this, new HomeAssistantClient.Callback<java.util.List<HomeAssistantClient.Entity>>() {
            @Override public void onResult(java.util.List<HomeAssistantClient.Entity> entities, String error) {
                if (entities != null) {
                    int on = 0;
                    for (HomeAssistantClient.Entity e : entities) if (e.on) on++;
                    mHaStatus.setText("Connected — " + entities.size() + " entities (" + on + " on)");
                } else {
                    mHaStatus.setText("Failed: " + error);
                }
            }
        });
    }

    // ------------------------------------------------------------ groq

    private void bindGroq() {
        findViewById(R.id.groqSaveBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AppSettings.setGroqKey(SettingsActivity.this,
                        mGroq.getText().toString().trim());
                mGroqStatus.setText("saved · ask the mic anything");
                toast("Groq AI key saved");
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
