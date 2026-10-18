package com.astos.shell;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * Controls whatever is currently playing — Spotify (free works fine),
 * YouTube Music, the built-in AstOs music player, anything with a media
 * notification.
 *
 * Primary path: MediaSessionManager (allowed because this app is an enabled
 * notification listener — no cloud, no Spotify API keys, no premium needed).
 * Fallback path: media-session tokens found in notifications.
 */
public class MediaControlService extends NotificationListenerService {

    public interface Listener { void onMediaChanged(); }

    private static volatile MediaController sActive;
    private static Listener sListener;
    private static MediaController.Callback sCb;

    public static void setListener(Listener l) { sListener = l; }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        refreshSessions();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handleNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // active media disappearing -> try to reattach to another session
        refreshSessions();
    }

    private void handleNotification(StatusBarNotification sbn) {
        try {
            android.app.Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return;
            if (!n.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) return;
            MediaSession.Token token = n.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION);
            if (token != null) attach(token);
        } catch (Exception ignored) {}
    }

    /** Collect every active media session and attach to the most relevant one. */
    public static void refreshSessions() {
        Context ctx = HomeActivity.sAppContext;
        if (ctx == null) return;
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager)
                            ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            ComponentName me = new ComponentName(ctx, MediaControlService.class);
            List<MediaController> sessions = msm.getActiveSessions(me);
            MediaController best = null;
            long bestStamp = -1;
            for (MediaController c : sessions) {
                PlaybackState pb = c.getPlaybackState();
                if (pb == null) continue;
                if (pb.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (pb.getLastPositionUpdateTime() > bestStamp) {
                    bestStamp = pb.getLastPositionUpdateTime();
                    best = c;
                }
            }
            if (best != null) attachToken(best.getSessionToken());
            notifyChanged();
        } catch (Exception ignored) {
            // No notification access yet, or MEDIA_CONTENT_CONTROL denied on
            // side-loaded installs. The user can still enable notification
            // access in AstOs settings.
        }
    }

    private void attach(MediaSession.Token token) { attachToken(token); }

    private static synchronized void attachToken(MediaSession.Token token) {
        if (sActive != null && sCb != null) {
            try { sActive.unregisterCallback(sCb); } catch (Exception ignored) {}
        }
        sCb = null;
        Context ctx = HomeActivity.sAppContext;
        if (ctx == null || token == null) { sActive = null; return; }
        try {
            sActive = new MediaController(ctx, token);
            sCb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { notifyChanged(); }
                @Override public void onMetadataChanged(android.media.MediaMetadata metadata) { notifyChanged(); }
            };
            sActive.registerCallback(sCb);
        } catch (Exception e) {
            sActive = null;
        }
    }

    private static void notifyChanged() {
        Listener l = sListener;
        if (l != null) l.onMediaChanged();
    }

    // ---- static accessors used by the UI ----

    public static boolean hasActive() {
        if (sActive == null) refreshSessions();
        return sActive != null;
    }

    public static boolean isPlaying() {
        if (sActive == null) return false;
        PlaybackState pb = sActive.getPlaybackState();
        return pb != null && pb.getState() == PlaybackState.STATE_PLAYING;
    }

    public static String track() {
        if (sActive == null) return "";
        android.media.MediaMetadata md = sActive.getMetadata();
        if (md == null) return "";
        return md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE, "");
    }

    public static String artist() {
        if (sActive == null) return "";
        android.media.MediaMetadata md = sActive.getMetadata();
        if (md == null) return "";
        String a = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "");
        if (a == null || a.isEmpty()) {
            a = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "");
        }
        return a == null ? "" : a;
    }

    public static void play()    { if (sActive != null) sActive.getTransportControls().play(); }
    public static void pause()   { if (sActive != null) sActive.getTransportControls().pause(); }
    public static void next()    { if (sActive != null) sActive.getTransportControls().skipToNext(); }
    public static void prev()    { if (sActive != null) sActive.getTransportControls().skipToPrevious(); }

    public static void toggle() {
        if (isPlaying()) pause(); else play();
    }

    /** Volume through the standard audio manager — works for every player. */
    public static void volumeUp(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
    }

    public static void volumeDown(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
    }

    public static void volumePercent(Context c, int percent) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = Math.max(0, Math.min(100, percent)) * max / 100;
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
    }

    public static int volumePercent(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max == 0 ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max;
    }
}
