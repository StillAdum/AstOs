package com.astos.shell;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.Engine;
import android.widget.Toast;

import java.util.Locale;

/**
 * Text-to-speech with graceful degradation: if the ROM has no TTS engine
 * installed (stock AstOs is GApps-free), AstOs simply answers visually.
 * Install eSpeak NG from F-Droid to enable spoken replies.
 */
public final class Speak {

    private static TextToSpeech sTts;
    private static boolean sReady = false;
    private static boolean sTried = false;
    private static boolean sWarnedNoEngine = false;

    private Speak() {}

    public static synchronized void init(final Context ctx) {
        if (sTried) return;
        sTried = true;
        sTts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int r = sTts.setLanguage(Locale.getDefault());
                    if (r == Engine.LANG_MISSING_DATA || r == Engine.LANG_NOT_SUPPORTED) {
                        sTts.setLanguage(Locale.US);
                    }
                    sReady = true;
                } else {
                    sTts = null;
                }
            }
        });
    }

    public static synchronized boolean hasEngine() {
        return sReady;
    }

    public static void say(Context ctx, String text) {
        Speak.init(ctx);
        if (ctx instanceof android.app.Activity) {
            Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        }
        if (!AppSettings.speakEnabled(ctx)) return;
        synchronized (Speak.class) {
            if (sTts == null) {
                if (!sWarnedNoEngine) {
                    sWarnedNoEngine = true;
                }
                return;
            }
            if (sReady) {
                sTts.speak(text, TextToSpeech.QUEUE_ADD, null, "astos_" + System.currentTimeMillis());
            }
        }
    }

    public static synchronized void shutdown() {
        if (sTts != null) { sTts.shutdown(); sTts = null; }
        sReady = false;
        sTried = false;
    }
}
