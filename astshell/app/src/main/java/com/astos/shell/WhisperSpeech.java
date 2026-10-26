package com.astos.shell;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Opt-in short recordings to Groq Whisper; Vosk remains the private/offline default. */
final class WhisperSpeech {
    interface Result { void done(String text, String error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File file;
    private Result callback;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private final Runnable finish = () -> finish();
    WhisperSpeech(Context c) { context = c.getApplicationContext(); }
    synchronized void start(Result result) throws Exception {
        callback = result; cancelled = false;
        file = File.createTempFile("ast-speech-", ".m4a", context.getCacheDir());
        recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000); recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(48000); recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare(); recorder.start(); main.postDelayed(finish, 8000);
        } catch (Exception e) { cancel(); throw e; }
    }
    synchronized void finish() {
        if (recorder == null) return;
        main.removeCallbacks(finish);
        try { recorder.stop(); }
        catch (RuntimeException e) { recorder.release(); recorder = null; file.delete(); deliver(null, "Recording too short; try again"); return; }
        recorder.release(); recorder = null;
        final File audio = file;
        new Thread(() -> upload(audio), "groq-whisper").start();
    }
    synchronized void cancel() {
        cancelled = true; main.removeCallbacks(finish);
        if (connection != null) connection.disconnect();
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} recorder.release(); recorder = null; }
        if (file != null) file.delete();
    }
    private void upload(File audio) {
        HttpURLConnection c = null;
        try {
            String boundary = "AstSpeech" + java.util.UUID.randomUUID().toString();
            c = (HttpURLConnection) new URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection();
            connection = c;
            if (cancelled) return;
            c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + AppSettings.groqKey(context));
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = c.getOutputStream()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes("UTF-8"));
                try (FileInputStream in = new FileInputStream(audio)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
            }
            int status = c.getResponseCode();
            if (status != 200) throw new Exception(status == 401 ? "Check your Groq key" : status == 429 ? "Groq speech rate limit; try offline voice" : "Groq speech HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line; while ((line = r.readLine()) != null) body.append(line);
            }
            String text = new JSONObject(body.toString()).optString("text", "").trim();
            deliver(text, text.isEmpty() ? "No speech heard" : null);
        } catch (Exception e) { deliver(null, "Speech recognition: " + e.getMessage()); }
        finally { if (c != null) c.disconnect(); connection = null; audio.delete(); }
    }
    private void deliver(String text, String error) {
        main.post(() -> { synchronized (WhisperSpeech.this) { if (!cancelled && callback != null) callback.done(text, error); } });
    }
}
