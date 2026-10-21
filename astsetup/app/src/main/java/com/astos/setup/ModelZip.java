package com.astos.setup;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the offline voice model zip to /sdcard/AstOs/ during the
 * wizard's "Setup is almost finished, just downloading a few more things…"
 * step. AstShell unzips it from there on next start (ModelDownloader checks
 * the SD card location first) — that way the model never ships in the APK
 * and never downloads before setup.
 */
final class ModelZip {

    static final String ZIP_NAME = "vosk-model-small-en-us-0.15.zip";

    private static final String[] URLS = {
            "https://alphacephei.com/vosk/models/" + ZIP_NAME,
            "https://github.com/StillAdum/AstOs/releases/download/vosk-model/" + ZIP_NAME,
            "http://alphacephei.com/vosk/models/" + ZIP_NAME,
    };

    interface Listener {
        void onProgress(int percent, String note); // percent < 0 -> indeterminate
        void onDone();
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ModelZip() {}

    static File target() {
        return new File(new File(Environment.getExternalStorageDirectory(), "AstOs"), ZIP_NAME);
    }

    static boolean present() {
        try {
            return target().isFile() && target().length() > 30_000_000L;
        } catch (Exception e) { return false; }
    }

    static void download(final Context ctx, final Listener l) {
        if (present()) {
            MAIN.post(new Runnable() { @Override public void run() { l.onDone(); }});
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                File out = target();
                File dir = out.getParentFile();
                if (dir != null) dir.mkdirs();
                String lastError = null;
                for (String u : URLS) {
                    try {
                        post(l, -1, "Connecting...");
                        fetch(u, out, l);
                        MAIN.post(new Runnable() { @Override public void run() { l.onDone(); }});
                        return;
                    } catch (final Exception e) {
                        lastError = e.getMessage();
                    }
                }
                final String err = lastError;
                MAIN.post(new Runnable() { @Override public void run() { l.onError(err); }});
            }
        }, "model-zip").start();
    }

    private static void post(final Listener l, final int pct, final String note) {
        MAIN.post(new Runnable() { @Override public void run() { l.onProgress(pct, note); }});
    }

    private static void fetch(String u, File out, final Listener l) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
        byte[] buf = new byte[1 << 16];
        long got = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
            got += n;
            if (total > 0) post(l, (int) (99 * got / total), "Downloading voice model...");
        }
        os.close();
        in.close();
        c.disconnect();
        if (out.length() < 30_000_000L) throw new IOException("incomplete download");
        post(l, 100, "Voice model downloaded.");
    }
}
