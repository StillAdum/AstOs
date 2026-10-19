package com.astos.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The AstOs first-boot setup wizard — replaces the Lineage/Android setup
 * entirely. Warm, short, voice-forward: Welcome -> Wi-Fi -> Groq AI ->
 * Spotify -> done. Never shows an Android settings screen.
 */
public class SetupActivity extends Activity {

    private static final int REQ_LOCATION = 51;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private android.widget.ViewFlipper mFlipper;
    private TextView mTitle, mSubtitle;
    private LinearLayout mWifiList;
    private TextView mWifiState, mGroqStatus, mSpotifyStatus;
    private EditText mGroqKey;
    private WifiManager mWifi;
    private String mConnectedSsid = null;
    private final List<WifiConfiguration> mSaved = new ArrayList<WifiConfiguration>();

    private static final int STEP_WELCOME = 0, STEP_WIFI = 1,
            STEP_GROQ = 2, STEP_SPOTIFY = 3, STEP_DONE = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mWifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

        mFlipper = (android.widget.ViewFlipper) findViewById(R.id.setupFlipper);
        mTitle = (TextView) findViewById(R.id.setupTitle);
        mSubtitle = (TextView) findViewById(R.id.setupSubtitle);
        mWifiList = (LinearLayout) findViewById(R.id.wifiList);
        mWifiState = (TextView) findViewById(R.id.wifiState);
        mGroqStatus = (TextView) findViewById(R.id.groqStatus);
        mSpotifyStatus = (TextView) findViewById(R.id.spotifyStatus);
        mGroqKey = (EditText) findViewById(R.id.groqKeyInput);
        mGroqKey.setText(AppSettings.groqKey(this));

        findViewById(R.id.btnStart).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoStep(STEP_WIFI); }
        });
        findViewById(R.id.btnWifiSkip).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoStep(STEP_GROQ); }
        });
        findViewById(R.id.btnWifiNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoStep(STEP_GROQ); }
        });
        findViewById(R.id.btnWifiRescan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanWifi(); }
        });
        findViewById(R.id.btnWifiManual).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { manualJoin(); }
        });

        findViewById(R.id.btnGroqTest).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testGroq(); }
        });
        findViewById(R.id.btnGroqSkip).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoStep(STEP_SPOTIFY); }
        });
        findViewById(R.id.btnGroqNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String k = mGroqKey.getText().toString().trim();
                if (!k.isEmpty()) AppSettings.setGroqKey(SetupActivity.this, k);
                gotoStep(STEP_SPOTIFY);
            }
        });

        findViewById(R.id.btnSpotifyInstall).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { installSpotify(); }
        });
        findViewById(R.id.btnSpotifyOpen).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SpotifyClient.openApp(SetupActivity.this);
                toast("Sign in inside Spotify, then come back");
            }
        });
        findViewById(R.id.btnSpotifyNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoStep(STEP_DONE); }
        });

        findViewById(R.id.btnFinish).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishSetup(); }
        });

        applyImmersive();
    }

    private void gotoStep(int step) {
        switch (step) {
            case STEP_WIFI:
                mTitle.setText("Wi-Fi");
                mSubtitle.setText("Pick your home network");
                startWifiStep();
                break;
            case STEP_GROQ:
                mTitle.setText("Ast's brain — Groq AI");
                mSubtitle.setText("Free key, answers everything else");
                break;
            case STEP_SPOTIFY:
                mTitle.setText("Music");
                mSubtitle.setText("Spotify free, voice-played");
                refreshSpotifyStatus();
                break;
            case STEP_DONE:
                mTitle.setText("AstOs is ready");
                mSubtitle.setText("Welcome home");
                break;
        }
        mFlipper.setDisplayedChild(step);
        applyImmersive();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        int i = mFlipper.getDisplayedChild();
        if (i > STEP_WELCOME) gotoStep(i - 1);
        // on step 0 the setup stays — this is a kiosk device's first boot
    }

    // ------------------------------------------------------------ Wi-Fi

    private void startWifiStep() {
        if (mWifi == null) { mWifiState.setText("Wi-Fi unavailable on this device"); return; }
        if (!mWifi.isWifiEnabled()) {
            mWifiState.setText("Turning Wi-Fi on…");
            try { mWifi.setWifiEnabled(true); } catch (Exception ignored) {}
        } else {
            mWifiState.setText("Scanning…");
        }
        mHandler.postDelayed(new Runnable() { @Override public void run() { scanWifi(); } }, 1200);
    }

    private void scanWifi() {
        mWifiState.setText("Scanning…");
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return; // result callback re-runs the scan
        }
        try { mWifi.startScan(); } catch (Exception ignored) {}
        mHandler.postDelayed(new Runnable() { @Override public void run() { renderScan(); } }, 2200);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_LOCATION && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            scanWifi();
        } else if (code == REQ_LOCATION) {
            mWifiState.setText("Scanning needs location permission — or join manually below");
        }
    }

    private void renderScan() {
        mWifiList.removeAllViews();

        // saved networks first (no location permission needed; fresh ROMs have none)
        mSaved.clear();
        try { mSaved.addAll(mWifi.getConfiguredNetworks()); } catch (Exception ignored) {}
        int savedShown = 0;
        for (final WifiConfiguration wc : mSaved) {
            if (wc.SSID == null || wc.SSID.replace("\"", "").isEmpty()) continue;
            if (savedShown == 0) addSectionRow("Saved networks");
            addWifiRow(wc.SSID.replace("\"", ""), "Saved — tap to connect",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try {
                                mWifi.enableNetwork(wc.networkId, true);
                                mWifiState.setText("Connecting to " + wc.SSID.replace("\"", "") + "…");
                                pollConnected(wc.SSID.replace("\"", ""), 8);
                            } catch (Exception e) { toast("Couldn't connect"); }
                        }
                    });
            savedShown++;
        }

        List<ScanResult> scans = null;
        try { scans = mWifi.getScanResults(); } catch (Exception ignored) {}
        if (scans == null || scans.isEmpty()) {
            addSectionRow("No networks found yet");
            mWifiState.setText("Nothing showed up — scan again, or join manually");
            return;
        }

        Map<String, ScanResult> best = new HashMap<String, ScanResult>();
        for (ScanResult s : scans) {
            if (s.SSID == null || s.SSID.isEmpty()) continue; // hidden
            ScanResult cur = best.get(s.SSID);
            if (cur == null || s.level > cur.level) best.put(s.SSID, s);
        }
        List<String> names = new ArrayList<String>(best.keySet());
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Integer.compare(best.get(b).level, best.get(a).level);
            }
        });
        if (!names.isEmpty()) addSectionRow("Nearby networks");
        for (String name : names) {
            final ScanResult sr = best.get(name);
            boolean open = !sr.capabilities.contains("WPA") && !sr.capabilities.contains("WEP")
                    && !sr.capabilities.contains("PSK");
            addWifiRow(name,
                    (open ? "Open network" : "Secured") + "  ·  " + signalWord(sr.level),
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { connectTo(sr); }
                    });
        }
        mWifiState.setText(mConnectedSsid != null
                ? "Connected to " + mConnectedSsid + " ✓"
                : "Tap a network to join");
    }

    private static String signalWord(int level) {
        if (level > -55) return "strong signal";
        if (level > -70) return "good signal";
        return "weak signal";
    }

    private void connectTo(final ScanResult sr) {
        boolean open = !sr.capabilities.contains("WPA") && !sr.capabilities.contains("WEP")
                && !sr.capabilities.contains("PSK");
        if (open) {
            WifiConfiguration wc = new WifiConfiguration();
            wc.SSID = "\"" + sr.SSID + "\"";
            wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            enable(wc);
            return;
        }
        final EditText pass = new EditText(this);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("Wi-Fi password");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(48, 24, 48, 8);
        wrap.addView(pass);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(sr.SSID)
                .setView(wrap)
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        WifiConfiguration wc = new WifiConfiguration();
                        wc.SSID = "\"" + sr.SSID + "\"";
                        wc.preSharedKey = "\"" + pass.getText().toString() + "\"";
                        wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                        enable(wc);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void enable(WifiConfiguration wc) {
        try {
            int id = mWifi.addNetwork(wc);
            if (id == -1) {
                // already known? find and enable it
                for (WifiConfiguration c : mWifi.getConfiguredNetworks()) {
                    if (c.SSID.equals(wc.SSID)) { id = c.networkId; break; }
                }
            }
            mWifi.enableNetwork(id, true);
            mWifiState.setText("Connecting to " + wc.SSID.replace("\"", "") + "…");
            toast("Connecting…");
            pollConnected(wc.SSID.replace("\"", ""), 8);
        } catch (Exception e) {
            toast("Couldn't join: " + e.getMessage());
        }
    }

    private void pollConnected(final String ssid, final int tries) {
        mHandler.postDelayed(new Runnable() { @Override public void run() {
            try {
                WifiInfo info = mWifi.getConnectionInfo();
                String cur = info == null ? null : info.getSSID().replace("\"", "");
                boolean up = info != null && info.getBSSID() != null && cur != null && cur.equals(ssid);
                if (up) {
                    mConnectedSsid = ssid;
                    mWifiState.setText("Connected to " + ssid + " ✓");
                    return;
                }
            } catch (Exception ignored) {}
            if (tries > 1) pollConnected(ssid, tries - 1);
            else mWifiState.setText("Still trying — check the password and tap the network again");
        }}, 1500);
    }

    private void manualJoin() {
        final EditText ssid = new EditText(this);
        ssid.setHint("Network name");
        final EditText pass = new EditText(this);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("Password");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(48, 24, 48, 8);
        wrap.addView(ssid);
        wrap.addView(pass);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Join network")
                .setView(wrap)
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = ssid.getText().toString().trim();
                        if (name.isEmpty()) { toast("Enter the network name"); return; }
                        WifiConfiguration wc = new WifiConfiguration();
                        wc.SSID = "\"" + name + "\"";
                        String p = pass.getText().toString();
                        if (!p.isEmpty()) {
                            wc.preSharedKey = "\"" + p + "\"";
                            wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                        }
                        enable(wc);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ rows

    private void addSectionRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setLetterSpacing(0.18f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(getColor(R.color.sage));
        tv.setPadding(6, 28, 6, 6);
        mWifiList.addView(tv);
    }

    private void addWifiRow(String name, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(40, 30, 40, 30);
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 12);
        row.setLayoutParams(lp);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(16);
        n.setTextColor(getColor(R.color.ink));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(getColor(R.color.ink_faint));
        row.addView(n);
        row.addView(s);
        row.setOnClickListener(click);
        mWifiList.addView(row);
    }

    // ------------------------------------------------------------ Groq

    private void testGroq() {
        final String key = mGroqKey.getText().toString().trim();
        if (key.isEmpty()) { mGroqStatus.setText("Paste a key from console.groq.com/keys"); return; }
        AppSettings.setGroqKey(this, key);
        mGroqStatus.setText("Testing…");
        GroqClient.test(this, new GroqClient.Callback() {
            @Override public void onResult(String answer, String error) {
                if (answer != null) {
                    mGroqStatus.setText("Ast says: \"" + answer + "\"");
                    toast("Groq AI connected");
                } else {
                    mGroqStatus.setText("Key failed: " + error);
                }
            }
        });
    }

    // ------------------------------------------------------------ Spotify

    private void refreshSpotifyStatus() {
        boolean installed = SpotifyClient.installed(this);
        if (installed) {
            mSpotifyStatus.setText("Spotify installed ✓");
            Button b = (Button) findViewById(R.id.btnSpotifyInstall);
            b.setVisibility(View.GONE);
        } else {
            mSpotifyStatus.setText("Spotify not installed yet");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (mFlipper != null && mFlipper.getDisplayedChild() == STEP_SPOTIFY) refreshSpotifyStatus();
    }

    private void installSpotify() {
        // platform-signed: pre-enable unknown sources so the user never
        // sees that Android settings screen
        try { Settings.Secure.putInt(getContentResolver(), "install_non_market_apps", 1); } catch (Exception ignored) {}
        try { Settings.Global.putInt(getContentResolver(), "install_non_market_apps", 1); } catch (Exception ignored) {}
        SpotifyClient.openInstallPage(this);
        toast("Tap Download APK on the page, then open the downloaded file");
    }

    // ------------------------------------------------------------ finish

    private void finishSetup() {
        AppSettings.setSetupDone(this, true);

        // what a setup wizard must hand the system — the platform-signed way
        try { Settings.Global.putInt(getContentResolver(), "device_provisioned", 1); } catch (Exception ignored) {}
        try { Settings.Secure.putInt(getContentResolver(), "user_setup_complete", 1); } catch (Exception ignored) {}

        // silently enable our notification listener so play/pause/next work
        // over Spotify from the first minute
        enableNotificationListener();

        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    private void enableNotificationListener() {
        try {
            ComponentName me = new ComponentName(this, MediaControlService.class);
            String flat = me.flattenToString();
            String cur = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (cur == null) cur = "";
            Set<String> parts = new HashSet<String>();
            for (String s : cur.split(":")) if (!s.isEmpty()) parts.add(s);
            if (!parts.contains(flat)) {
                parts.add(flat);
                StringBuilder sb = new StringBuilder();
                for (String s : parts) sb.append(s).append(':');
                String out = sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "";
                Settings.Secure.putString(getContentResolver(),
                        "enabled_notification_listeners", out);
            }
        } catch (Exception e) {
            // graceful fallback: the user can still enable it in AstOs Settings
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
