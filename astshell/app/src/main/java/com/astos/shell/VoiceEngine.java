package com.astos.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.IOException;

/**
 * Fully offline voice recognition built on Vosk.
 * The small English model is downloaded ON DEVICE after the first-boot
 * setup (see ModelDownloader) and lives in filesDir/model.
 */
public class VoiceEngine implements RecognitionListener {

    /** ReadyListener message when the model has not been downloaded yet. */
    public static final String MISSING_MODEL = "model_missing";

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

    /** Loads Vosk from filesDir/model (downloaded by ModelDownloader). */
    public void prepare(final Context ctx, final ReadyListener ready) {
        if (mModel != null) { ready.onReady(true, null); return; }
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ModelDownloader.present(app)) {
                        throw new MissingModelException();
                    }
                    File dir = new File(app.getFilesDir(), "model");
                    mModel = new Model(dir.getAbsolutePath());
                    mMain.post(new Runnable() { @Override public void run() { ready.onReady(true, null); }});
                } catch (MissingModelException me) {
                    mMain.post(new Runnable() { @Override public void run() {
                        ready.onReady(false, MISSING_MODEL);
                    }});
                } catch (final Exception e) {
                    mMain.post(new Runnable() { @Override public void run() {
                        ready.onReady(false, "Voice model failed to load: " + e.getMessage());
                    }});
                }
            }
        }, "vosk-init").start();
    }

    private static class MissingModelException extends Exception {}

    /** Convenience: is the model downloaded and ready to load? */
    public static boolean modelReady(Context c) {
        return ModelDownloader.present(c);
    }

    public void setCallback(Callback cb) { mCallback = cb; }

    public boolean isListening() { return mListening; }

    public synchronized void startListening() throws IOException {
        if (mModel == null || mListening) return;
        Recognizer rec = new Recognizer(mModel, 16000.0f);
        mSpeech = new SpeechService(rec, 16000.0f);
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

    @Override public void onPartialResult(String s) {
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
