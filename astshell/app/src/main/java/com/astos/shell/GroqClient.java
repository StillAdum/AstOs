package com.astos.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Groq AI chat client (api.groq.com, OpenAI-compatible endpoint).
 * Key comes free at console.groq.com/keys — set in AstOs setup or settings.
 */
public final class GroqClient {

    public interface Callback {
        void onResult(String answer, String error);
    }

    private GroqClient() {}

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are Ast, the warm, calm voice assistant of AstOs, a family tablet OS. "
            + "Answer briefly and helpfully — usually one to three short sentences. "
            + "No emojis, no markdown, plain spoken style. If asked who you are, say "
            + "you are Ast running on AstOs.";

    /** One-shot question -> answer. Runs on a worker thread, callback on main. */
    public static void chat(final Context c, final String question, final Callback cb) {
        final String key = AppSettings.groqKey(c).trim();
        if (key.isEmpty()) { main().post(new Runnable() { @Override public void run() {
            cb.onResult(null, "No Groq API key set");
        }}); return; }

        new Thread(new Runnable() { @Override public void run() {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("model", AppSettings.groqModel(c).trim());
                body.put("temperature", 0.4);
                body.put("max_tokens", 220);
                JSONArray messages = new JSONArray();
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", SYSTEM_PROMPT);
                messages.put(sys);
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", question);
                messages.put(user);
                body.put("messages", messages);

                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(20000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                BufferedReader rd = new BufferedReader(new InputStreamReader(
                        code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) sb.append(line);
                rd.close();

                if (code != 200) {
                    String detail = sb.length() > 240 ? sb.substring(0, 240) : sb.toString();
                    throw new RuntimeException("Groq HTTP " + code + ": " + brief(detail));
                }

                JSONObject resp = new JSONObject(sb.toString());
                JSONArray choices = resp.optJSONArray("choices");
                if (choices == null || choices.length() == 0)
                    throw new RuntimeException("Groq returned no answer");
                String answer = choices.getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                if (answer.isEmpty()) throw new RuntimeException("Empty answer from Groq");

                final String out = answer;
                main().post(new Runnable() { @Override public void run() {
                    cb.onResult(out, null);
                }});
            } catch (final Exception e) {
                final String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main().post(new Runnable() { @Override public void run() {
                    cb.onResult(null, err);
                }});
            } finally {
                if (conn != null) conn.disconnect();
            }
        }}, "groq-chat").start();
    }

    /** Quick connectivity/key test used by setup + settings. */
    public static void test(final Context c, final Callback cb) {
        chat(c, "Say hello in exactly five words.", cb);
    }

    private static String brief(String s) {
        try {
            JSONObject o = new JSONObject(s);
            JSONObject err = o.optJSONObject("error");
            if (err != null) return err.optString("message", s);
        } catch (Exception ignored) {}
        return s;
    }

    private static Handler main() { return new Handler(Looper.getMainLooper()); }
}
