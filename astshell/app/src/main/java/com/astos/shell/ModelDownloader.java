package com.astos.shell;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the offline Vosk voice model ON THE DEVICE — never before setup.
 * The first-boot wizard's "Setup is almost finished, just downloading a few
 * more things…" step drives this; the model is no longer bundled in the APK.
 *
 * Source order:
 *   0. a zip the setup wizard already saved to /sdcard/AstOs/ (no network)
 *   1. alphacephei.com over HTTPS
 *   2. our GitHub release mirror (self-hosted by scripts/60-release.sh)
 *   3. alphacephei.com over plain HTTP (LAN-first ROM, cleartext allowed)
 */
public final class ModelDownloader {

    public static final String ZIP_NAME = "vosk-model-small-en-us-0.15.zip";

    static final String[] URLS = {
            "https://alphacephei.com/vosk/models/" + ZIP_NAME,
            "https://github.com/StillAdum/AstOs/releases/download/vosk-model/" + ZIP_NAME,
            "http://alphacephei.com/vosk/models/" + ZIP_NAME,
    };

    public interface Listener {
        void onProgress(int percent, String note); // percent < 0 -> indeterminate
        void onDone();
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ModelDownloader() {}

    /** True when the unpacked model is on disk and ready to load. */
    public static boolean present(Context c) {
        return new File(c.getFilesDir(), "model/conf/model.conf").exists();
    }

    public static void download(final Context ctx, final Listener l) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                File zip = new File(app.getCacheDir(), ZIP_NAME);

                // 0) wizard may have parked the zip on the SD card already
                try {
                    File pre = preDownloaded();
                    if (pre != null) {
                        postProgress(l, -1, "Unpacking…");
                        unzip(app, pre);
                        if (!zip.delete()) zip.deleteOnExit();
                        if (!present(app)) throw new IOException("model.conf missing after unpack");
                        MAIN.post(new Runnable() { @Override public void run() { l.onDone(); }});
                        return;
                    }
                } catch (Exception ignored) {}

                String lastError = null;
                for (int i = 0; i < URLS.length; i++) {
                    try {
                        postProgress(l, -1, "Connecting…");
                        fetch(URLS[i], zip, l);
                        postProgress(l, -1, "Unpacking…");
                        unzip(app, zip);
                        if (!zip.delete()) zip.deleteOnExit();
                        if (!present(app)) throw new IOException("model.conf missing after unpack");
                        MAIN.post(new Runnable() { @Override public void run() { l.onDone(); }});
                        return;
                    } catch (final Exception e) {
                        lastError = e.getMessage();
                    }
                }
                final String err = lastError;
                MAIN.post(new Runnable() { @Override public void run() { l.onError(err); }});
            }
        }, "model-download").start();
    }

    /** Zip saved by the setup wizard (must look complete). */
    static File preDownloaded() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "AstOs");
            File f = new File(dir, ZIP_NAME);
            if (f.isFile() && f.length() > 30_000_000L) return f;
        } catch (Exception ignored) {}
        return null;
    }

    private static void postProgress(final Listener l, final int pct, final String note) {
        MAIN.post(new Runnable() { @Override public void run() { l.onProgress(pct, note); }});
    }

    /** Stream one URL to the target zip file with progress. */
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
            if (total > 0) {
                final int pct = (int) (99 * got / total);
                postProgress(l, pct, "Downloading voice model…");
            }
        }
        os.close();
        in.close();
        c.disconnect();
        if (out.length() < 30_000_000L) throw new IOException("incomplete download");
        postProgress(l, 100, "Downloaded — unpacking…");
    }

    /** Unzip, stripping the model's top-level folder into files/model/. */
    private static void unzip(Context app, File zip) throws IOException {
        File staging = new File(app.getFilesDir(), "model_dl");
        delete(staging);
        staging.mkdirs();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        String firstSegment = null;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            int slash = name.indexOf('/');
            if (slash <= 0) continue;
            if (firstSegment == null) firstSegment = name.substring(0, slash);
            if (!name.startsWith(firstSegment + "/")) continue;
            String rel = name.substring(firstSegment.length() + 1);
            if (rel.isEmpty()) continue;
            File f = new File(staging, rel);
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(staging.getCanonicalPath() + File.separator)) {
                throw new IOException("bad zip entry: " + name);
            }
            if (e.isDirectory()) {
                f.mkdirs();
                continue;
            }
            f.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
        }
        zin.close();

        File finalDir = new File(app.getFilesDir(), "model");
        delete(finalDir);
        if (!staging.renameTo(finalDir)) throw new IOException("could not finalize model dir");
    }

    static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
