package com.astos.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.widget.Toast;

/** Per-player preferred device, not a false system-wide audio switch. */
public final class AudioOutput {
    private AudioOutput() {}
    private static String key(AudioDeviceInfo d) { return d.getType() + ":" + d.getProductName(); }
    public static void choose(Activity a) {
        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        String[] labels = new String[devices.length + 1]; labels[0] = "Automatic (system routing)";
        for (int i = 0; i < devices.length; i++) labels[i + 1] = devices[i].getProductName() + " · " + type(devices[i].getType());
        new com.astos.link.PixelUi.Builder(a).setTitle("Music playback device")
            .setItems(labels, (d, i) -> {
                a.getSharedPreferences("audio", 0).edit().putString("device", i == 0 ? "" : key(devices[i - 1])).apply();
                MusicEngine.refreshOutput();
                Toast.makeText(a, "Applies to AstOS music. Connect/pair speakers before selecting.", Toast.LENGTH_LONG).show();
            }).show();
    }
    public static void apply(Context c, MediaPlayer player) {
        String selected = c.getSharedPreferences("audio", 0).getString("device", "");
        AudioDeviceInfo found = null;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (key(d).equals(selected)) { found = d; break; }
        if (!player.setPreferredDevice(found)) android.util.Log.w("AstAudio", "Preferred output was rejected; using system route");
    }
    private static String type(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth";
            case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Audio jack";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Tablet speaker";
            default: return "Output " + t;
        }
    }
}
