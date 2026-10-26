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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The AstOs assistant brain — Groq's ultra-fast cloud LLMs
 * (https://console.groq.com — free tier API key, paste it in AstOs Setup).
 * 1 GB of RAM can't run local models, so the thinking happens in the cloud
 * while the voice and the OS stay fully local.
 */
public final class GroqClient {

    public static final String MODEL = "llama-3.1-8b-instant";

    public interface Callback { void onReply(String text, String error); }

    private GroqClient() {}

    private static void async(Runnable r) { new Thread(r, "groq").start(); }
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    /** Ask Groq. Context (weather/now playing/date) is injected automatically. */
    public static void ask(final Context ctx, final String question, final Callback cb) {
        final String key = AppSettings.groqKey(ctx);
        if (key.isEmpty()) {
            main().post(new Runnable() { @Override public void run() {
                cb.onReply(null, "no-key");
            }});
            return;
        }
        async(new Runnable() { @Override public void run() {
            try {
                String sys = "You are AstOs, the built-in assistant of AstOs, a custom "
                        + "voice-first home-assistant operating system on a small tablet. "
                        + "Answer briefly (max 60 words), plainly and helpfully. "
                        + "Today is " + new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
                                .format(new Date()) + "."
                        + contextLine(ctx);
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", sys));
                messages.put(new JSONObject().put("role", "user").put("content", question));

                JSONObject body = new JSONObject();
                JSONObject mediaParameters = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                        .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray(new String[]{"play", "pause", "resume", "next", "previous"})))
                        .put("query", new JSONObject().put("type", "string"))
                        .put("until_disconnected", new JSONObject().put("type", "boolean")))
                    .put("required", new JSONArray(new String[]{"action"}));
                body.put("tools", new JSONArray().put(new JSONObject().put("type", "function")
                    .put("function", new JSONObject().put("name", "pc_spotify")
                        .put("description", "Control Spotify on the paired PC ONLY when the user explicitly requests PC/computer playback. Never use for tablet playback.")
                        .put("parameters", mediaParameters))));
                body.put("model", MODEL);
                body.put("messages", messages);
                body.put("max_tokens", 200);
                body.put("temperature", 0.5);

                HttpURLConnection c = (HttpURLConnection)
                        new URL("https://api.groq.com/openai/v1/chat/completions").openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();

                int code = c.getResponseCode();
                BufferedReader rd = new BufferedReader(new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) sb.append(line);
                rd.close();
                c.disconnect();

                if (code == 401) {
                    post(cb, null, "Groq rejected the API key - check it in Setup");
                    return;
                }
                if (code == 429) {
                    post(cb, null, "Groq rate limit - try again in a moment");
                    return;
                }
                if (code != 200) {
                    post(cb, null, "Groq error " + code);
                    return;
                }
                JSONObject resp = new JSONObject(sb.toString());
                JSONArray choices = resp.getJSONArray("choices");
                JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0) {
                    JSONObject fn = calls.getJSONObject(0).getJSONObject("function");
                    if (!"pc_spotify".equals(fn.optString("name"))) throw new Exception("Unknown assistant action");
                    JSONObject args = new JSONObject(fn.getString("arguments"));
                    String action = args.optString("action");
                    if (!java.util.Arrays.asList("play", "pause", "resume", "next", "previous").contains(action))
                        throw new Exception("Unsupported playback action");
                    main().post(() -> PcPlayback.send(ctx, action, args.optString("query", ""), args.optBoolean("until_disconnected", false)));
                    post(cb, "Sent your playback request to the PC.", null);
                    return;
                }
                String text = message.optString("content", "").trim();
                post(cb, text, null);
            } catch (final Throwable e) {
                post(cb, null, "Groq failed: " + e.getMessage());
            }
        }});
    }

    private static String contextLine(Context ctx) {
        try {
            List<String> bits = new ArrayList<String>();
            String city = AppSettings.resolvedCity(ctx);
            if (city != null && !city.isEmpty()) bits.add("The user lives near " + city + ".");
            SongSearch.Track t = MusicEngine.current();
            if (t != null && t.title != null && !t.title.isEmpty())
                bits.add("Currently playing: " + t.title + " by "
                        + (t.artist == null ? "" : t.artist) + ".");
            StringBuilder sb = new StringBuilder(" ");
            for (String b : bits) sb.append(b).append(" ");
            return sb.toString();
        } catch (Throwable t) { return " "; }
    }

    private static void post(final Callback cb, final String text, final String err) {
        main().post(new Runnable() { @Override public void run() { cb.onReply(text, err); }});
    }
}
