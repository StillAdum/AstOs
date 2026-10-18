package com.astos.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Fully offline voice recognition built on Vosk.
 * The small English model ships in APK assets and is unpacked on first use.
 */
public class VoiceEngine implements RecognitionListener {

    public interface Callback {
        void onPartial(String text);
        void onResult(String text);
        void onError(String message);
    }

    public interface ReadyListener {
        void onReady(boolean ok, String message);
    }

    private static VoiceEngine sInstance;

    public static synchronized VoiceEngine get() {
        if (sInstance == null) sInstance = new VoiceEngine();
        return sInstance;
    }

    private Model mModel;
    private SpeechService mSpeech;
    private volatile boolean mListening = false;
    private Callback mCallback;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private VoiceEngine() {}

    /** Copies assets/model -> filesDir/model (once) and loads Vosk. */
    public void prepare(final Context ctx, final ReadyListener ready) {
        if (mModel != null) { ready.onReady(true, null); return; }
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(app.getFilesDir(), "model");
                    File marker = new File(app.getFilesDir(), ".model_copied");
                    if (!marker.exists()) {
                        copyAssets(app, "model", dir);
                        marker.createNewFile();
                    }
                    mModel = new Model(dir.getAbsolutePath());
                    mMain.post(new Runnable() { @Override public void run() { ready.onReady(true, null); }});
                } catch (final Exception e) {
                    mMain.post(new Runnable() { @Override public void run() {
                        ready.onReady(false, "Voice model failed to load: " + e.getMessage());
                    }});
                }
            }
        }, "vosk-init").start();
    }

    private static void copyAssets(Context ctx, String path, File out) throws IOException {
        String[] children = ctx.getAssets().list(path);
        if (children == null || children.length == 0) {
            // leaf file
            File f = new File(out, path.substring(path.lastIndexOf('/') + 1));
            f.getParentFile().mkdirs();
            InputStream in = ctx.getAssets().open(path);
            OutputStream os = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close(); in.close();
            return;
        }
        for (String child : children) {
            copyAssets(ctx, path + "/" + child, new File(out, child));
        }
    }

    public void setCallback(Callback cb) { mCallback = cb; }

    public boolean isListening() { return mListening; }

    public synchronized void startListening() throws IOException {
        if (mModel == null || mListening) return;
        mSpeech = new SpeechService(mModel, 16000.0f);
        mSpeech.startListening(this);
        mListening = true;
    }

    public synchronized void stopListening() {
        if (mSpeech != null) {
            try { mSpeech.stop(); } catch (Exception ignored) {}
            try { mSpeech.shutdown(); } catch (Exception ignored) {}
            mSpeech = null;
        }
        mListening = false;
    }

    // ---- RecognitionListener (vosk callbacks arrive on a worker thread) ----

    @Override public void onPartialResults(String s) {
        final String text = safeJson(s, "partial");
        if (mCallback != null && !text.isEmpty()) {
            mMain.post(new Runnable() { @Override public void run() {
                Callback cb = mCallback; if (cb != null) cb.onPartial(text);
            }});
        }
    }

    @Override public void onResult(String s) {
        final String text = safeJson(s, "text");
        if (mCallback != null && !text.isEmpty()) {
            mMain.post(new Runnable() { @Override public void run() {
                Callback cb = mCallback; if (cb != null) cb.onResult(text);
            }});
        }
    }

    @Override public void onFinalResult(String s) {
        final String text = safeJson(s, "text");
        if (mCallback != null && !text.isEmpty()) {
            mMain.post(new Runnable() { @Override public void run() {
                Callback cb = mCallback; if (cb != null) cb.onResult(text);
            }});
        }
    }

    @Override public void onError(Exception e) {
        final String msg = e == null ? "speech error" : e.getMessage();
        mListening = false;
        if (mCallback != null) {
            mMain.post(new Runnable() { @Override public void run() {
                Callback cb = mCallback; if (cb != null) cb.onError(msg);
            }});
        }
    }

    @Override public void onTimeout() {
        mListening = false;
        if (mCallback != null) {
            mMain.post(new Runnable() { @Override public void run() {
                Callback cb = mCallback; if (cb != null) cb.onError("timeout");
            }});
        }
    }

    private static String safeJson(String raw, String key) {
        try {
            JSONObject o = new JSONObject(raw);
            String v = o.optString(key, "");
            return v == null ? "" : v.trim().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
