package com.astos.shell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** AstOs settings: AI, music, Home Assistant, weather city, behaviour toggles. */
public class SettingsActivity extends Activity {

    private EditText mHaUrl, mHaToken, mCity, mGroqKey, mGroqModel, mSpotifyId, mSpotifySecret;
    private TextView mHaStatus, mGroqStatus, mSpotifyStatus;
    private Button mHaTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mHaUrl = (EditText) findViewById(R.id.haUrlInput);
        mHaToken = (EditText) findViewById(R.id.haTokenInput);
        mCity = (EditText) findViewById(R.id.cityInput);
        mHaStatus = (TextView) findViewById(R.id.haStatus);
        mHaTest = (Button) findViewById(R.id.haTestBtn);
        mGroqKey = (EditText) findViewById(R.id.groqKeyInput);
        mGroqModel = (EditText) findViewById(R.id.groqModelInput);
        mGroqStatus = (TextView) findViewById(R.id.groqStatus);
        mSpotifyId = (EditText) findViewById(R.id.spotifyIdInput);
        mSpotifySecret = (EditText) findViewById(R.id.spotifySecretInput);
        mSpotifyStatus = (TextView) findViewById(R.id.spotifyStatus);

        mHaUrl.setText(AppSettings.haUrl(this));
        mHaToken.setText(AppSettings.haToken(this));
        mCity.setText(AppSettings.city(this));
        mGroqKey.setText(AppSettings.groqKey(this));
        mGroqModel.setText(AppSettings.groqModel(this));
        mSpotifyId.setText(AppSettings.spotifyId(this));
        mSpotifySecret.setText(AppSettings.spotifySecret(this));
        refreshSpotifyStatus();

        findViewById(R.id.groqSaveBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testGroq(); }
        });
        findViewById(R.id.spotifySaveBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveSpotifyKeys(); }
        });
        findViewById(R.id.spotifyInstallBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { Settings.Secure.putInt(getContentResolver(), "install_non_market_apps", 1); } catch (Exception ignored) {}
                try { Settings.Global.putInt(getContentResolver(), "install_non_market_apps", 1); } catch (Exception ignored) {}
                SpotifyClient.openInstallPage(SettingsActivity.this);
            }
        });

        bindSwitch(R.id.kioskSwitch, AppSettings.kiosk(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setKiosk(SettingsActivity.this, v); }
        });
        bindSwitch(R.id.keepScreenSwitch, AppSettings.keepScreenOn(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setKeepScreenOn(SettingsActivity.this, v); }
        });
        bindSwitch(R.id.speakSwitch, AppSettings.speakEnabled(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setSpeakEnabled(SettingsActivity.this, v); }
        });
        bindSwitch(R.id.bootSwitch, AppSettings.bootLaunch(this), new Set() {
            @Override public void set(boolean v) { AppSettings.setBootLaunch(SettingsActivity.this, v); }
        });

        findViewById(R.id.saveCityBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveCity(); }
        });

        mHaTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testHa(); }
        });

        findViewById(R.id.notifAccessBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        findViewById(R.id.homeChooserBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                startActivity(Intent.createChooser(i, "Home app"));
            }
        });

        findViewById(R.id.androidSettingsBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        TextView about = (TextView) findViewById(R.id.aboutText);
        String ver = "AstOs 1.0 · AstShell 1.0 · Ast voice";
        about.setText(ver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSpotifyStatus();
    }

    private void refreshSpotifyStatus() {
        boolean installed = SpotifyClient.installed(this);
        boolean keys = !AppSettings.spotifyId(this).trim().isEmpty()
                && !AppSettings.spotifySecret(this).trim().isEmpty();
        mSpotifyStatus.setText(installed
                ? ("Spotify installed ✓" + (keys ? " · instant voice play" : " · voice opens search"))
                : "Spotify not installed — tap Install Spotify below");
    }

    private void testGroq() {
        String key = mGroqKey.getText().toString().trim();
        String model = mGroqModel.getText().toString().trim();
        AppSettings.setGroqKey(this, key);
        if (!model.isEmpty()) AppSettings.setGroqModel(this, model);
        if (key.isEmpty()) { mGroqStatus.setText("No key set — get one at console.groq.com/keys"); return; }
        mGroqStatus.setText("Testing…");
        GroqClient.test(this, new GroqClient.Callback() {
            @Override public void onResult(String answer, String error) {
                if (answer != null) mGroqStatus.setText("Ast says: \"" + answer + "\"");
                else mGroqStatus.setText("Failed: " + error);
            }
        });
    }

    private void saveSpotifyKeys() {
        AppSettings.setSpotifyId(this, mSpotifyId.getText().toString().trim());
        AppSettings.setSpotifySecret(this, mSpotifySecret.getText().toString().trim());
        refreshSpotifyStatus();
        toast("Spotify keys saved");
    }

    private interface Set { void set(boolean v); }

    private void bindSwitch(int id, boolean initial, final Set setter) {
        Switch sw = (Switch) findViewById(id);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) { setter.set(checked); }
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
        mHaStatus.setText("Locating " + city + "…");
        WeatherProvider.geocode(this, city, new WeatherProvider.GeoCallback() {
            @Override public void onGeo(String resolved, String error) {
                if (resolved != null) toast("Weather set to " + resolved);
                else toast("Couldn't find " + city + ": " + error);
            }
        });
    }

    private void testHa() {
        String url = mHaUrl.getText().toString().trim();
        String token = mHaToken.getText().toString().trim();
        AppSettings.setHaUrl(this, url);
        AppSettings.setHaToken(this, token);
        if (url.isEmpty()) { mHaStatus.setText("No Home Assistant URL set"); return; }
        mHaStatus.setText("Contacting " + url + " …");
        HomeAssistantClient.listEntities(this, new HomeAssistantClient.Callback<java.util.List<HomeAssistantClient.Entity>>() {
            @Override public void onResult(java.util.List<HomeAssistantClient.Entity> entities, String error) {
                if (entities != null) {
                    int on = 0; for (HomeAssistantClient.Entity e : entities) if (e.on) on++;
                    mHaStatus.setText("Connected — " + entities.size() + " controllable entities ("
                            + on + " on)");
                } else {
                    mHaStatus.setText("Failed: " + error);
                }
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
