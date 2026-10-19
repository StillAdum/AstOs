package com.astos.shell;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Built-in offline music player. Scans /storage/emulated/0/Music so your
 * 32 GB SD card (adopted as internal storage) becomes the jukebox.
 * "Play music" by voice starts this; the media card controls it.
 */
public class MusicService extends Service {

    public static final String ACTION_START = "com.astos.shell.START";
    public static final String ACTION_TOGGLE = "com.astos.shell.TOGGLE";
    public static final String ACTION_NEXT = "com.astos.shell.NEXT";
    public static final String ACTION_PREV = "com.astos.shell.PREV";
    public static final String ACTION_STOP = "com.astos.shell.STOP";

    private static MusicService sInstance;

    private MediaPlayer mPlayer;
    private MediaSession mSession;
    private final List<File> mTracks = new ArrayList<File>();
    private int mIndex = -1;
    private final Random mRandom = new Random();

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mSession = new MediaSession(this, "AstOsMusic");
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onStop() { stopSelf(); }
        });
        startForeground(1, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            if (isPlaying()) pause(); else if (mIndex >= 0) resume(); else startRandom();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREV.equals(action)) {
            prev();
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else {
            if (!isPlaying() && mIndex < 0) startRandom();
        }
        return START_NOT_STICKY;
    }

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, MusicService.class);
            i.setAction(ACTION_START);
            c.startService(i);
        } catch (Exception ignored) {}
    }

    public static void toggle(Context c) {
        Intent i = new Intent(c, MusicService.class);
        i.setAction(ACTION_TOGGLE);
        try { c.startService(i); } catch (Exception ignored) {}
    }

    public static void next(Context c) {
        Intent i = new Intent(c, MusicService.class); i.setAction(ACTION_NEXT);
        try { c.startService(i); } catch (Exception ignored) {}
    }

    public static void prev(Context c) {
        Intent i = new Intent(c, MusicService.class); i.setAction(ACTION_PREV);
        try { c.startService(i); } catch (Exception ignored) {}
    }

    public static boolean isPlaying() {
        MusicService s = sInstance;
        return s != null && s.mPlayer != null && s.mPlayer.isPlaying();
    }

    public static String currentName() {
        MusicService s = sInstance;
        if (s == null || s.mIndex < 0 || s.mIndex >= s.mTracks.size()) return "";
        String n = s.mTracks.get(s.mIndex).getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ---- internals ----

    private void scan() {
        mTracks.clear();
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        addRecursive(musicDir);
        // also pick up music on the adopted SD card storage root
        try {
            File[] ext = getExternalFilesDirs(null);
            if (ext != null) {
                for (File f : ext) {
                    if (f != null) {
                        File base = f.getParentFile() == null ? null : f.getParentFile().getParentFile();
                        if (base != null && !base.equals(musicDir)) {
                            File sdMusic = new File(base, "Music");
                            if (sdMusic.exists()) addRecursive(sdMusic);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void addRecursive(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) addRecursive(f);
            else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".ogg")
                        || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".aac")) {
                    mTracks.add(f);
                }
            }
        }
    }

    private void startRandom() {
        if (mTracks.isEmpty()) scan();
        if (mTracks.isEmpty()) {
            Speak.say(this, "No music found. Copy songs to the Music folder.");
            stopSelf();
            return;
        }
        mIndex = mRandom.nextInt(mTracks.size());
        playIndex();
    }

    private void playIndex() {
        if (mIndex < 0 || mIndex >= mTracks.size()) return;
        releasePlayer();
        try {
            mPlayer = new MediaPlayer();
            mPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mPlayer.setDataSource(mTracks.get(mIndex).getAbsolutePath());
            mPlayer.prepareAsync();
            mPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) { mp.start(); publishState(); }
            });
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { next(); }
            });
            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) { next(); return true; }
            });
            publishMetadata();
        } catch (Exception e) {
            next();
        }
    }

    private void resume() {
        if (mPlayer != null && !mPlayer.isPlaying()) {
            mPlayer.start();
            publishState();
        }
    }

    private void pause() {
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.pause();
            publishState();
        }
    }

    private void next() {
        if (mTracks.isEmpty()) scan();
        if (mTracks.isEmpty()) return;
        mIndex = (mIndex + 1) % mTracks.size();
        playIndex();
    }

    private void prev() {
        if (mTracks.isEmpty()) scan();
        if (mTracks.isEmpty()) return;
        mIndex = mIndex <= 0 ? mTracks.size() - 1 : mIndex - 1;
        playIndex();
    }

    private void stopPlayback() {
        releasePlayer();
        stopForeground(true);
        stopSelf();
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            try { mPlayer.stop(); } catch (Exception ignored) {}
            try { mPlayer.release(); } catch (Exception ignored) {}
            mPlayer = null;
        }
    }

    private void publishMetadata() {
        mSession.setActive(true);
        MediaMetadata md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentName())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "AstOs")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1)
                .build();
        mSession.setMetadata(md);
    }

    private void publishState() {
        mSession.setActive(true);
        PlaybackState pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 0, 1)
                .build();
        mSession.setPlaybackState(pb);
    }

    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AstOs Music")
                .setContentText("Local music player")
                .setOngoing(true);
        try { b.setStyle(new Notification.MediaStyle().setMediaSession(mSession.getSessionToken())); } catch (Exception ignored) {}
        return b.build();
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        if (mSession != null) { mSession.setActive(false); mSession.release(); }
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @SuppressWarnings("unused")
    private static List<File> empty() { return Collections.emptyList(); }
}
