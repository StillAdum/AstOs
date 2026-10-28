package com.astos.shell;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * AstOs music engine — Discord-bot-style streaming player.
 * Searches YouTube Music through the on-device extractor, streams the
 * audio, manages a queue, falls back to 30s previews / local files when
 * a stream is blocked, and publishes MediaSession state.
 */
public class MusicEngine extends Service {

    public static final String ACTION_PLAY_QUERY = "com.astos.shell.PLAY_QUERY";
    public static final String ACTION_LOCAL = "com.astos.shell.LOCAL";
    public static final String EXTRA_QUERY = "query";

    private static MusicEngine sInstance;
    public static int audioSession(){try{return sInstance!=null&&sInstance.mPlayer!=null?sInstance.mPlayer.getAudioSessionId():-1;}catch(Exception e){return -1;}}
    public static void refreshOutput() {
        MusicEngine s = sInstance;
        if (s != null && s.mPlayer != null) AudioOutput.apply(s, s.mPlayer);
    }

    /** UI listener — HomeActivity implements this to redraw Now Playing. */
    public interface Listener {
        void onMusicUpdate();
    }
    private static final List<Listener> sListeners = new ArrayList<Listener>();
    public static void listen(Listener l) { synchronized (sListeners) { sListeners.add(l); } }
    public static void unlisten(Listener l) { synchronized (sListeners) { sListeners.remove(l); } }
    private static void fireUpdate() {
        synchronized (sListeners) {
            for (Listener l : sListeners) l.onMusicUpdate();
        }
    }

    public interface Completion {void done(SongSearch.Track track,String error);}
    private static final java.util.concurrent.ConcurrentHashMap<String,Completion> requests=new java.util.concurrent.ConcurrentHashMap<>();
    private Completion pendingCompletion;
    private String requestId="";
    private int searchEpoch;
    private boolean retried;
    private int resumeAt;
    private Runnable prepareTimeout;
    public static String playConfirmed(Context c,String query,Completion cb){
        String id=java.util.UUID.randomUUID().toString();requests.put(id,cb);
        try{c.startService(new Intent(c,MusicEngine.class).setAction(ACTION_PLAY_QUERY).putExtra(EXTRA_QUERY,query).putExtra("requestId",id));}
        catch(Throwable e){requests.remove(id);cb.done(null,"Could not start tablet music: "+e.getMessage());}
        return id;
    }
    public static void cancelRequest(String id){
        requests.remove(id);MusicEngine service=sInstance;if(service==null)return;
        service.mMain.post(()->{if(id.equals(service.requestId)){service.searchEpoch++;service.complete("Playback request timed out and was cancelled");service.releasePlayer();service.mPreparing=false;}});
    }
    private void complete(String error){Completion cb=pendingCompletion;pendingCompletion=null;requestId="";if(cb!=null)cb.done(error==null?mCurrent:null,error);}
    public static void control(Context c,String action){send(c,action.toUpperCase(java.util.Locale.US));}
    public static void controlConfirmed(String action,Completion cb){
        MusicEngine service=sInstance;if(service==null){cb.done(null,"No tablet song is selected");return;}
        service.mMain.post(()->{
            try{
                if("pause".equals(action)){service.searchEpoch++;service.complete("Playback cancelled");service.pause();cb.done(service.mCurrent,null);}
                else if("resume".equals(action)){
                    if(service.mPlayer==null||service.mPreparing)throw new Exception("No prepared tablet song to resume");service.resume();cb.done(service.mCurrent,null);
                }else if("next".equals(action)){
                    if(service.mQueue.isEmpty())throw new Exception("No next song queued; ask to play a song by name");service.complete("Replaced by next song");service.pendingCompletion=cb;service.skipNext();
                }else if("previous".equals(action)){
                    if(service.mCurrent==null)throw new Exception("No tablet song selected");
                    if(service.mPlayer!=null&&positionMs()>3000){service.mPlayer.seekTo(0);cb.done(service.mCurrent,null);}
                    else{service.complete("Restarting song");service.pendingCompletion=cb;service.playCurrent();}
                }else throw new Exception("Unsupported playback action");
            }catch(Exception error){cb.done(null,error.getMessage());}
        });
    }
    private MediaPlayer mPlayer;
    private MediaSession mSession;
    private final Deque<SongSearch.Track> mQueue = new ArrayDeque<SongSearch.Track>();
    private SongSearch.Track mCurrent;
    private boolean mPreparing;
    private String mSearchStatus = "";
    private final Random mRandom = new Random();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    // ---- static API (used by HomeActivity / CommandRouter) --------------------

    public static void playQuery(Context c, String query) {
        Intent i = new Intent(c, MusicEngine.class);
        i.setAction(ACTION_PLAY_QUERY);
        i.putExtra(EXTRA_QUERY, query);
        try { c.startService(i); } catch (Throwable ignored) {}
    }

    public static void local(Context c) { send(c, "LOCAL"); }

    public static void toggle(Context c) { send(c, "TOGGLE"); }
    public static void next(Context c)   { send(c, "NEXT"); }
    public static void prev(Context c)   { send(c, "PREV"); }
    public static void stop(Context c)   { send(c, "STOP"); }

    private static void send(Context c, String action) {
        Intent i = new Intent(c, MusicEngine.class);
        i.setAction("com.astos.shell." + action);
        try { c.startService(i); } catch (Throwable ignored) {}
    }

    private static boolean safePlaying(MediaPlayer p){try{return p.isPlaying();}catch(Exception e){return false;}}

    public static boolean isPlaying() {
        MusicEngine s = sInstance;
        return s != null && s.mPlayer != null && safePlaying(s.mPlayer);
    }

    public static boolean isBusy() {
        MusicEngine s = sInstance;
        return s != null && (s.mPreparing || (s.mPlayer != null && safePlaying(s.mPlayer)));
    }

    public static SongSearch.Track current() {
        MusicEngine s = sInstance;
        return s == null ? null : s.mCurrent;
    }

    public static List<SongSearch.Track> queue() {
        MusicEngine s = sInstance;
        List<SongSearch.Track> out = new ArrayList<SongSearch.Track>();
        if (s != null) out.addAll(s.mQueue);
        return out;
    }

    public static String searchStatus() {
        MusicEngine s = sInstance;
        return s == null ? "" : s.mSearchStatus;
    }

    /** 0..1 position within the current track (0 when unknown). */
    public static float progress() {
        MusicEngine s = sInstance;
        if (s == null || s.mPlayer == null) return 0f;
        try {
            int d = s.mPlayer.getDuration();
            if (d <= 0) return 0f;
            return Math.min(1f, s.mPlayer.getCurrentPosition() / (float) d);
        } catch (Throwable t) { return 0f; }
    }

    public static int positionMs() {
        MusicEngine s = sInstance;
        if (s == null || s.mPlayer == null) return 0;
        try { return s.mPlayer.getCurrentPosition(); } catch (Throwable t) { return 0; }
    }

    public static int durationMs() {
        MusicEngine s = sInstance;
        if (s == null || s.mPlayer == null) return 0;
        try { return s.mPlayer.getDuration(); } catch (Throwable t) { return 0; }
    }

    // ---- service lifecycle ------------------------------------------------------

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
            @Override public void onSkipToNext() { skipNext(); }
            @Override public void onSkipToPrevious() { prevLocal(); }
            @Override public void onStop() { stopAll(); }
        });
        startForeground(1, buildNotification());
        fireUpdate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY_QUERY.equals(action)) {
            String q = intent.getStringExtra(EXTRA_QUERY);
            if(q!=null&&!q.trim().isEmpty()){
                complete("Replaced by a newer song request");requestId=intent.getStringExtra("requestId");
                pendingCompletion=requestId==null?null:requests.remove(requestId);searchAndPlay(q.trim());
            }
        } else if ("com.astos.shell.TOGGLE".equals(action)) {
            if (mPreparing || isPlaying()) pause(); else if (mPlayer != null) resume();
            else if (mCurrent != null) playCurrent(); else startLocalShuffle();
        } else if ("com.astos.shell.PAUSE".equals(action)) {
            searchEpoch++;complete("Playback cancelled");pause();
        } else if ("com.astos.shell.RESUME".equals(action)) {
            resume();
        } else if ("com.astos.shell.NEXT".equals(action)) {
            skipNext();
        } else if ("com.astos.shell.PREV".equals(action)) {
            prevLocal();
        } else if ("com.astos.shell.STOP".equals(action)) {
            stopAll();
        } else if ("com.astos.shell.LOCAL".equals(action)) {
            mQueue.clear();
            startLocalShuffle();
        } else {
            if (!isPlaying() && mCurrent == null) startLocalShuffle();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        searchEpoch++;complete("Tablet music stopped");
        releasePlayer();
        if (mSession != null) { mSession.setActive(false); mSession.release(); }
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- search + queue ------------------------------------------------------------

    private void searchAndPlay(final String query) {
        final int epoch=++searchEpoch;retried=false;resumeAt=0;mPreparing=true;
        mSearchStatus="Searching \""+query+"\"…";fireUpdate();
        SongSearch.resolve(this,query,(track,error)->{
            if(epoch!=searchEpoch)return;
            if(error!=null||track==null||!track.hasAny()){
                mPreparing=false;mSearchStatus=error==null?"No playable audio found":error;complete(mSearchStatus);fireUpdate();return;
            }
            mQueue.clear();mQueue.add(track);skipNext();
        });
    }

    private void skipNext() {
        SongSearch.Track t = mQueue.pollFirst();
        if (t == null) {
            // queue empty: flow into local shuffle if we have local files
            List<File> locals = scanLocal();
            if (!locals.isEmpty()) {
                mCurrent = localTrack(locals.get(mRandom.nextInt(locals.size())));
                playCurrent();
            } else {
                mCurrent = null;
                releasePlayer();
                mSearchStatus = "Queue empty";
                fireUpdate();
            }
            return;
        }
        mCurrent = t;
        playCurrent();
    }

    private void prevLocal() {
        if (mCurrent == null) { skipNext(); return; }
        long now = positionMs();
        if (now > 3000 && mPlayer != null) {
            try { mPlayer.seekTo(0); } catch (Throwable ignored) {}
            return;
        }
        playCurrent();
    }

    private void stopAll() {
        searchEpoch++;complete("Playback stopped");
        releasePlayer();
        mQueue.clear();
        mCurrent = null;
        stopForeground(true);
        stopSelf();
        fireUpdate();
    }

    // ---- playback ------------------------------------------------------------------

    private void playCurrent() {
        if(mCurrent==null)return;releasePlayer();mPreparing=true;
        mSearchStatus="Loading "+mCurrent.title+"…";fireUpdate();
        if(mCurrent.localFile!=null){startLocalFile();return;}
        if(mCurrent.streamUrl!=null&&!mCurrent.streamUrl.isEmpty()){startStream(mCurrent.streamUrl);return;}
        mPreparing=false;mSearchStatus="No audio stream for "+mCurrent.title;complete(mSearchStatus);fireUpdate();
    }
    private void prepared(MediaPlayer player){
        if(player!=mPlayer)return;
        if(prepareTimeout!=null)mMain.removeCallbacks(prepareTimeout);
        try{
            mPreparing=false;if(resumeAt>0){player.seekTo(resumeAt);resumeAt=0;}player.start();
            mSearchStatus="Playing "+mCurrent.title;complete(null);publishAll();
        }catch(Throwable error){onPlayError();}
    }
    private void prepareDeadline(){
        final MediaPlayer player=mPlayer;prepareTimeout=()->{if(player==mPlayer&&mPreparing)onPlayError();};mMain.postDelayed(prepareTimeout,15000);
    }

    private void startStream(String url) {
        try {
            mPlayer = new MediaPlayer();
            AudioOutput.apply(this, mPlayer);
            mPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mPlayer.setDataSource(this,android.net.Uri.parse(url),mCurrent.headers);
            mPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    prepared(mp);
                }
            });
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { if(mp==mPlayer)skipNext(); }
            });
            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    if(mp==mPlayer)onPlayError();
                    return true;
                }
            });
            mPlayer.prepareAsync();
            prepareDeadline();
            publishMetadata();
        } catch (Throwable e) {
            onPlayError();
        }
    }

    private void startLocalFile() {
        SongSearch.Track t = mCurrent;
        if (t == null || t.localFile == null) { mPreparing = false; return; }
        try {
            mPlayer = new MediaPlayer();
            AudioOutput.apply(this, mPlayer);
            mPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mPlayer.setDataSource(t.localFile.getAbsolutePath());
            mPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    prepared(mp);
                }
            });
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { if(mp==mPlayer)skipNext(); }
            });
            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    if(mp==mPlayer)onPlayError();
                    return true;
                }
            });
            mPlayer.prepareAsync();
            prepareDeadline();
            publishMetadata();
        } catch (Throwable e) {
            mPreparing = false;
            onPlayError();
        }
    }

    /** Refresh an expired network stream once; never silently switch songs or previews. */
    private void onPlayError() {
        final SongSearch.Track failed=mCurrent;final int epoch=searchEpoch;
        resumeAt=positionMs();releasePlayer();mPreparing=false;
        if(!retried&&failed!=null&&failed.localFile==null){
            retried=true;mPreparing=true;mSearchStatus="Reconnecting audio…";fireUpdate();
            SongSearch.resolve(this,failed.watchUrl.isEmpty()?failed.query:failed.watchUrl,(track,error)->{
                if(epoch!=searchEpoch||mCurrent!=failed)return;
                if(error==null&&track!=null&&track.hasAny()){mCurrent=track;playCurrent();}
                else playbackFailed(error==null?"Audio source unavailable":error);
            });
        }else playbackFailed("Audio playback failed. Try another source or check Wi-Fi.");
    }
    private void playbackFailed(String error){mPreparing=false;mSearchStatus=error;complete(error);publishState();fireUpdate();}

    private void pause() {
        if(mPreparing){searchEpoch++;mPreparing=false;complete("Playback cancelled");releasePlayer();mSearchStatus="Playback cancelled";fireUpdate();return;}
        try{if(mPlayer!=null&&safePlaying(mPlayer)){mPlayer.pause();publishAll();}}catch(Exception error){playbackFailed("Could not pause tablet audio");}
    }
    private void resume() {
        try{if(mPlayer!=null&&!mPreparing&&!safePlaying(mPlayer)){mPlayer.start();publishAll();}}catch(Exception error){playbackFailed("Could not resume tablet audio");}
    }

    private void releasePlayer() {
        if(prepareTimeout!=null){mMain.removeCallbacks(prepareTimeout);prepareTimeout=null;}
        if (mPlayer != null) {
            try { mPlayer.stop(); } catch (Throwable ignored) {}
            try { mPlayer.release(); } catch (Throwable ignored) {}
            mPlayer = null;
        }
    }

    // ---- local library ---------------------------------------------------------------

    private void startLocalShuffle() {
        List<File> locals = scanLocal();
        if (locals.isEmpty()) {
            mSearchStatus = "No local music found";
            Speak.say(this, "No music found. Try: play a song name.");
            fireUpdate();
            return;
        }
        mCurrent = localTrack(locals.get(mRandom.nextInt(locals.size())));
        playCurrent();
    }

    private SongSearch.Track localTrack(File f) {
        SongSearch.Track t = new SongSearch.Track();
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        t.title = dot > 0 ? n.substring(0, dot) : n;
        t.artist = "";
        t.source = "local";
        t.status = "LOCAL FILE";
        t.localFile = f;
        return t;
    }

    private List<File> scanLocal() {
        List<File> out = new ArrayList<File>();
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        collectFiles(musicDir, out);
        return out;
    }

    private void collectFiles(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectFiles(f, out);
            else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".ogg")
                        || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".aac"))
                    out.add(f);
            }
        }
    }

    // ---- MediaSession + notification ---------------------------------------------------

    private void publishAll() {
        publishMetadata();
        publishState();
        fireUpdate();
    }

    private void publishMetadata() {
        SongSearch.Track t = mCurrent;
        if (t == null) return;
        mSession.setActive(true);
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist.isEmpty() ? "AstOs" : t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION,
                        t.durationMs > 0 ? t.durationMs : durationMs());
        mSession.setMetadata(b.build());
        // fetch cover art in the background for the lock screen
        if (t.artUrl != null && !t.artUrl.isEmpty()) {
            final String url = t.artUrl;
            new Thread(new Runnable() { @Override public void run() {
                Bitmap art = fetchBitmap(url);
                if (art == null) return;
                mMain.post(new Runnable() { @Override public void run() {
                    try {
                        if(mCurrent!=t)return;
                        MediaMetadata.Builder b2 = new MediaMetadata.Builder()
                                .putString(MediaMetadata.METADATA_KEY_TITLE,
                                        mCurrent == null ? "" : mCurrent.title)
                                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                                        mCurrent == null || mCurrent.artist.isEmpty()
                                                ? "AstOs" : mCurrent.artist)
                                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
                        mSession.setMetadata(b2.build());
                    } catch (Throwable ignored) {}
                }});
            }}, "art-fetch").start();
        }
    }

    private void publishState() {
        mSession.setActive(true);
        PlaybackState pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        positionMs(), 1f)
                .build();
        mSession.setPlaybackState(pb);
    }

    private Notification buildNotification() {
        SongSearch.Track t = mCurrent;
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t == null ? "AstOs Music" : t.title)
                .setContentText(t == null ? "Free on-demand music" : t.artist)
                .setOngoing(true);
        try {
            b.setStyle(new Notification.MediaStyle().setMediaSession(mSession.getSessionToken()));
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            android.R.drawable.ic_media_previous), "Prev",
                    android.app.PendingIntent.getService(this, 1,
                            new Intent(this, MusicEngine.class).setAction("com.astos.shell.PREV"),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build());
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            MusicEngine.isPlaying()
                                    ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                    MusicEngine.isPlaying() ? "Pause" : "Play",
                    android.app.PendingIntent.getService(this, 2,
                            new Intent(this, MusicEngine.class).setAction("com.astos.shell.TOGGLE"),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build());
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            android.R.drawable.ic_media_next), "Next",
                    android.app.PendingIntent.getService(this, 3,
                            new Intent(this, MusicEngine.class).setAction("com.astos.shell.NEXT"),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } catch (Throwable ignored) {}
        return b.build();
    }

    private static Bitmap fetchBitmap(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(in);
            in.close();
            c.disconnect();
            return bmp;
        } catch (Throwable t) { return null; }
    }
}
