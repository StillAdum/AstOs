package com.astos.shell;

import android.content.Context;
import com.astos.link.LinkServer;

/** PC commands only leave the tablet over its authenticated AstLink session. */
public final class PcPlayback {
    private static boolean sticky;
    private PcPlayback() {}
    public static org.json.JSONObject state() {
        LinkServer link = LinkServer.peek();
        if (link == null) return null;
        com.astos.link.LinkJson.Obj state = link.pcPlaybackState();
        if (state == null) return null;
        try { return new org.json.JSONObject(com.astos.link.LinkJson.write(state)); } catch (Exception e) { return null; }
    }
    public static boolean selected() {
        LinkServer link = LinkServer.peek();
        if (link == null || !link.pcReady()) sticky = false;
        return sticky;
    }
    public static void send(Context c, String action, String query, boolean untilDisconnected) {
        LinkServer link = LinkServer.peek();
        if (link == null) {
            android.widget.Toast.makeText(c, "PC companion is disconnected", android.widget.Toast.LENGTH_LONG).show();
            if (AppSettings.speakEnabled(c)) Speak.say(c, "PC companion is disconnected"); return;
        }
        link.sendPc(action, query, untilDisconnected, error -> {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                sticky = error == null && untilDisconnected;
                String message = error == null ? "PC Spotify: " + action : error;
                android.widget.Toast.makeText(c, message, android.widget.Toast.LENGTH_LONG).show();
                if (AppSettings.speakEnabled(c)) Speak.say(c, message);
            });
        });
    }
}
