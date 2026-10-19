package com.astos.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discord-bot-style song resolution for AstOs:
 *
 *   1. iTunes Search  -> which song you meant: title, artist, cover art,
 *                        duration, 30s preview (the bot's autocomplete step)
 *   2. NewPipeExtractor on-device -> YouTube music search + full audio-only
 *      stream URL (this is literally the yt-dlp of Android — same engine the
 *      Piped instances run, but running locally on the tablet's home IP)
 *   3. Piped REST instances -> fallback stream resolution if the local
 *      extractor is blocked
 *   4. iTunes 30s preview -> last-resort full-quality snippet
 *   5. local Music/ folder -> fuzzy title match
 *
 * No Spotify Premium. No account. Free on-demand playback.
 */
public final class SongSearch {

    /** A resolved (or resolving) track. */
    public static class Track {
        public String query;        // what the user asked for
        public String title = "";   // display title
        public String artist = "";  // display artist
        public String artUrl = "";  // 600x600 cover art
        public String previewUrl = "";// 30s m4a snippet (always playable)
        public String watchUrl = "";// youtube watch URL
        public String streamUrl = "";// full audio-only stream URL
        public String source = "";  // "stream" | "preview" | "local" | "piped"
        public long durationMs;     // track length
        public File localFile;      // local playback, if source == local
        public String status = "";  // human status for the UI

        public boolean hasAny() {
            return (streamUrl != null && !streamUrl.isEmpty())
                    || (previewUrl != null && !previewUrl.isEmpty())
                    || localFile != null;
        }
    }

    public interface Callback { void onResult(Track t, String error); }

    private static boolean sNpInited = false;

    private SongSearch() {}

    private static void async(Runnable r) { new Thread(r, "song-search").start(); }
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    // ------------------------------------------------------------------ NewPipe init

    private static synchronized void initNp() {
        if (sNpInited) return;
        try {
            NewPipe.init(new HttpDownloader());
            sNpInited = true;
        } catch (Throwable ignored) {}
    }

    /** Plain HttpURLConnection downloader for NewPipeExtractor. */
    private static class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request req) throws java.io.IOException, ReCaptchaException {
            String method = req.httpMethod();
            String url = req.url();
            Map<String, List<String>> headers = req.headers();
            byte[] body = req.dataToSend();

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(method);
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                for (String val : e.getValue()) c.addRequestProperty(e.getKey(), val);
            }
            if (body != null && body.length > 0) {
                c.setDoOutput(true);
                c.getOutputStream().write(body);
            }
            int code = c.getResponseCode();
            if (code == 429) {
                c.disconnect();
                throw new ReCaptchaException("reCaptcha / rate-limit required", url);
            }
            BufferedReader rd = new BufferedReader(
                    new InputStreamReader(code >= 400 ? c.getErrorStream() : c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) sb.append(line);
            rd.close();
            Response resp = new Response(code, c.getResponseMessage(),
                    c.getHeaderFields(), sb.toString(), url);
            c.disconnect();
            return resp;
        }
    }

    // ------------------------------------------------------------------ main entry

    /** Resolve a free-text query into a playable Track (async, main thread cb). */
    public static void resolve(final Context ctx, final String rawQuery, final Callback cb) {
        async(new Runnable() { @Override public void run() {
            final Track t = new Track();
            t.query = rawQuery == null ? "" : rawQuery.trim();
            if (t.query.isEmpty()) {
                post(cb, t, "What song?");
                return;
            }

            // ---- step 1: iTunes catalog (metadata + art + preview) ---------------
            try {
                String q = URLEncoder.encode(t.query, "UTF-8");
                JSONObject root = new JSONObject(get(
                        "https://itunes.apple.com/search?term=" + q
                                + "&media=music&entity=song&limit=5"));
                JSONArray results = root.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject r = pickBest(results, t.query);
                    t.title = r.optString("trackName", "");
                    t.artist = r.optString("artistName", "");
                    t.artUrl = r.optString("artworkUrl100", "")
                            .replace("100x100bb", "600x600bb");
                    t.durationMs = r.optLong("trackTimeMillis", 0);
                    t.previewUrl = r.optString("previewUrl", "");
                }
            } catch (Exception ignored) {}

            String yq = (t.title.isEmpty() || t.artist.isEmpty())
                    ? t.query : (t.title + " " + t.artist);

            // ---- step 2: on-device NewPipeExtractor (full stream) ----------------
            String fullUrl = null;
            try {
                initNp();
                fullUrl = npResolve(yq);
            } catch (Throwable ignored) {}

            // ---- step 3: Piped REST fallback --------------------------------------
            if (fullUrl == null) {
                try { fullUrl = pipedResolve(yq); } catch (Throwable ignored) {}
            }

            // ---- decide what we got ------------------------------------------------
            if (fullUrl != null) {
                t.streamUrl = fullUrl;
                t.source = t.title.isEmpty() ? "stream" : "stream";
                t.status = "FULL SONG - YOUTUBE AUDIO";
                if (t.durationMs == 0 && t.watchUrl.isEmpty()) {
                    // try to enrich duration from the watch page metadata cheaply
                    try {
                        JSONObject o = new JSONObject(get(
                                "https://www.youtube.com/oembed?url="
                                        + URLEncoder.encode(t.watchUrl, "UTF-8") + "&format=json"));
                        if (t.title.isEmpty()) t.title = o.optString("title", "");
                    } catch (Throwable ignored) {}
                }
                post(cb, t, null);
            } else if (!t.previewUrl.isEmpty()) {
                t.source = "preview";
                t.status = "30s PREVIEW (full stream blocked - trying again next song)";
                post(cb, t, null);
            } else {
                // ---- step 5: local fuzzy match -------------------------------------
                File local = localMatch(ctx, t.query);
                if (local != null) {
                    t.localFile = local;
                    t.source = "local";
                    t.status = "LOCAL FILE";
                    if (t.title.isEmpty()) t.title = stripExt(local.getName());
                    post(cb, t, null);
                } else {
                    post(cb, t, "Couldn't find \"" + t.query + "\" (check wifi)");
                }
            }
        }});
    }

    private static void post(final Callback cb, final Track t, final String err) {
        main().post(new Runnable() { @Override public void run() { cb.onResult(t, err); }});
    }

    // ------------------------------------------------------------------ NewPipe path

    private static String npResolve(String query) throws Exception {
        StreamingService yt = ServiceList.YouTube;
        // music_songs = YouTube Music songs filter (exact like a music bot)
        SearchQueryHandler h = yt.getSearchQHFactory().fromQuery(query,
                Collections.singletonList(
                        org.schabi.newpipe.extractor.services.youtube.linkHandler
                                .YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "");
        SearchInfo si = SearchInfo.getInfo(yt, h);
        String watchUrl = null;
        for (org.schabi.newpipe.extractor.InfoItem item : si.getRelatedItems()) {
            if (item instanceof StreamInfoItem) {
                StreamInfoItem s = (StreamInfoItem) item;
                watchUrl = s.getUrl();
                if (s.getDuration() > 30) break;    // skip very short results
            }
        }
        if (watchUrl == null) return null;

        StreamInfo info = StreamInfo.getInfo(watchUrl);
        AudioStream best = null;
        int bestScore = -1;
        for (AudioStream a : info.getAudioStreams()) {
            if (a == null || a.getUrl() == null) continue;
            boolean progressive = a.isUrl()
                    && a.getDeliveryMethod() == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
            boolean m4a = a.getFormat() != null
                    && a.getFormat() == org.schabi.newpipe.extractor.MediaFormat.M4A;
            int br = a.getAverageBitrate();
            if (!progressive) continue;              // MediaPlayer can't do DASH
            int score = (m4a ? 1000 : 0) - Math.abs(128000 - br);
            if (score > bestScore) { bestScore = score; best = a; }
        }
        if (best == null) return null;
        return best.getUrl();
    }

    // ------------------------------------------------------------------ Piped REST path

    private static final String[] PIPED_INSTANCES = {
            "https://api.piped.private.coffee",
            "https://pipedapi.ducks.party",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
    };

    private static String pipedResolve(String query) throws Exception {
        String id = null;
        for (String inst : PIPED_INSTANCES) {
            try {
                JSONObject r = new JSONObject(get(inst + "/search?q="
                        + URLEncoder.encode(query, "UTF-8") + "&filter=music_songs"));
                JSONArray items = r.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    String u = items.getJSONObject(0).optString("url", "");
                    int eq = u.indexOf("v=");
                    if (eq > 0) { id = u.substring(eq + 2); break; }
                }
            } catch (Throwable ignored) {}
        }
        if (id == null) return null;
        for (String inst : PIPED_INSTANCES) {
            try {
                JSONObject r = new JSONObject(get(inst + "/streams/" + id));
                JSONArray auds = r.optJSONArray("audioStreams");
                if (auds == null) continue;
                String best = null; int bestBr = 0;
                for (int i = 0; i < auds.length(); i++) {
                    JSONObject a = auds.getJSONObject(i);
                    String mime = a.optString("mimeType", "");
                    if (!mime.contains("mp4")) continue;   // m4a only for MediaPlayer
                    int br = a.optInt("bitrate", 0);
                    if (br > bestBr && br <= 192000) { bestBr = br; best = a.optString("url", ""); }
                }
                if (best != null && !best.isEmpty()) return best;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ------------------------------------------------------------------ local path

    private static File localMatch(Context ctx, String query) {
        try {
            java.io.File dir = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC);
            List<File> all = new ArrayList<File>();
            collect(dir, all);
            String q = query.toLowerCase();
            File best = null; int bestScore = 0;
            for (File f : all) {
                String n = stripExt(f.getName()).toLowerCase();
                int score = 0;
                for (String tok : q.split("\\s+")) {
                    if (tok.length() > 2 && n.contains(tok)) score += tok.length();
                }
                if (score > bestScore) { bestScore = score; best = f; }
            }
            return bestScore >= 4 ? best : null;
        } catch (Throwable ignored) { return null; }
    }

    private static void collect(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(f, out);
            else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".ogg")
                        || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".aac"))
                    out.add(f);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    /** Prefer the result whose title+artist best matches the query. */
    private static JSONObject pickBest(JSONArray results, String query) {
        String q = query.toLowerCase();
        JSONObject best = results.optJSONObject(0);
        int bestScore = -1;
        for (int i = 0; i < results.length() && i < 5; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String hay = (r.optString("trackName", "") + " " + r.optString("artistName", ""))
                    .toLowerCase();
            int score = 0;
            for (String tok : q.split("\\s+")) {
                if (tok.length() > 2 && hay.contains(tok)) score += tok.length();
            }
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return best;
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 7.1; AstOs) AppleWebKit/537.36");
        BufferedReader rd = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) sb.append(line);
        rd.close();
        c.disconnect();
        return sb.toString();
    }

    /** Search suggestions for the on-screen keyboard-less flow (like the bot's autocomplete). */
    public static List<String> suggest(String partial) {
        List<String> out = new ArrayList<String>();
        if (partial == null || partial.trim().length() < 2) return out;
        try {
            JSONObject root = new JSONObject(get("https://itunes.apple.com/search?term="
                    + URLEncoder.encode(partial.trim(), "UTF-8")
                    + "&media=music&entity=song&limit=6"));
            JSONArray rs = root.optJSONArray("results");
            for (int i = 0; rs != null && i < rs.length() && out.size() < 5; i++) {
                JSONObject r = rs.getJSONObject(i);
                String s = r.optString("trackName", "") + " - " + r.optString("artistName", "");
                if (!s.trim().equals("-")) out.add(s);
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
