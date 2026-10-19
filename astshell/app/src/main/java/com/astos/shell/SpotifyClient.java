package com.astos.shell;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Spotify voice playback for the free tier.
 *
 * "Hey Spotify, play <song>" ->
 *   1. with Spotify developer keys (free app, client-credentials): Web API
 *      search -> spotify:track:<id> deep link -> the song starts instantly;
 *   2. without keys: spotify:search:<song> deep link -> results open with
 *      the song at the top, one tap plays;
 *   3. Spotify not installed: guided install (APKPure in the built-in
 *      browser — the ROM has no Play Store by design).
 *
 * No premium unlock, no cracking — plain free tier, deep-linked by voice.
 */
public final class SpotifyClient {

    public interface Callback {
        void onResult(boolean played, String message);
    }

    private SpotifyClient() {}

    public static final String SPOTIFY_PKG = "com.spotify.music";
    private static final String APKPURE_URL = "https://apkpure.com/spotify-app/com.spotify.music";

    // client-credentials token cache (an hour of validity, refreshed lazily)
    private static String sToken;
    private static long sTokenExpiresAt;

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SPOTIFY_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Open the install page in the built-in browser (Jelly). */
    public static void openInstallPage(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(APKPURE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    /** Open the Spotify app itself (sign-in / use). */
    public static void openApp(Context c) {
        try {
            Intent i = c.getPackageManager()
                    .getLaunchIntentForPackage(SPOTIFY_PKG);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i); }
        } catch (Exception ignored) {}
    }

    private static boolean keysPresent(Context c) {
        return !AppSettings.spotifyId(c).trim().isEmpty()
                && !AppSettings.spotifySecret(c).trim().isEmpty();
    }

    /** Voice entry point: play a song by name. */
    public static void play(final Context c, final String query, final Callback cb) {
        if (!installed(c)) {
            main().post(new Runnable() { @Override public void run() {
                cb.onResult(false, "Spotify isn't installed yet — open AstOs Settings to install it");
            }});
            return;
        }
        if (!keysPresent(c)) {
            // graceful free path: deep-link the search, top result one tap away
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("spotify:search:" + Uri.encode(query)));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception e) {
                main().post(new Runnable() { @Override public void run() {
                    cb.onResult(false, "Couldn't open Spotify search");
                }});
                return;
            }
            main().post(new Runnable() { @Override public void run() {
                cb.onResult(true, "Spotify is showing " + query + " — tap to play");
            }});
            return;
        }

        new Thread(new Runnable() { @Override public void run() {
            try {
                String token = token(c);
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://api.spotify.com/v1/search?type=track&limit=1&q="
                                + Uri.encode(query)).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                int code = conn.getResponseCode();
                BufferedReader rd = new BufferedReader(new InputStreamReader(
                        code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) sb.append(line);
                rd.close();
                conn.disconnect();
                if (code != 200) throw new RuntimeException("Spotify HTTP " + code);

                JSONObject tracks = new JSONObject(sb.toString())
                        .getJSONObject("tracks");
                int total = tracks.optInt("total", 0);
                if (total == 0) {
                    final String msg = "Spotify couldn't find " + query;
                    main().post(new Runnable() { @Override public void run() {
                        cb.onResult(false, msg);
                    }});
                    return;
                }
                JSONObject track = tracks.getJSONArray("items").getJSONObject(0);
                final String uri = track.optString("uri", "");
                String name = track.optString("name", query);
                String artist = "";
                try { artist = track.getJSONArray("artists")
                        .getJSONObject(0).optString("name", ""); } catch (Exception ignored) {}

                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);

                final String msg = (artist.isEmpty() ? name : name + " by " + artist)
                        + (track.optBoolean("is_local", false) ? "" : "");
                main().post(new Runnable() { @Override public void run() {
                    cb.onResult(true, "Playing " + msg);
                }});
            } catch (final Exception e) {
                final String err = e.getMessage() == null ? "Spotify error" : e.getMessage();
                main().post(new Runnable() { @Override public void run() {
                    cb.onResult(false, err);
                }});
            }
        }}, "spotify-play").start();
    }

    private static synchronized String token(Context c) throws Exception {
        long now = System.currentTimeMillis();
        if (sToken != null && now < sTokenExpiresAt - 60000) return sToken;

        String auth = android.util.Base64.encodeToString(
                (AppSettings.spotifyId(c).trim() + ":" + AppSettings.spotifySecret(c).trim())
                        .getBytes("UTF-8"), android.util.Base64.NO_WRAP);

        HttpURLConnection conn = (HttpURLConnection)
                new URL("https://accounts.spotify.com/api/token").openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Basic " + auth);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write("grant_type=client_credentials".getBytes("UTF-8"));
        os.close();

        int code = conn.getResponseCode();
        BufferedReader rd = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) sb.append(line);
        rd.close();
        conn.disconnect();
        if (code != 200) throw new RuntimeException("Spotify token HTTP " + code);

        JSONObject o = new JSONObject(sb.toString());
        sToken = o.optString("access_token", "");
        long expiresIn = o.optLong("expires_in", 3600) * 1000L;
        sTokenExpiresAt = now + expiresIn;
        if (sToken.isEmpty()) throw new RuntimeException("Spotify token empty");
        return sToken;
    }

    private static Handler main() { return new Handler(Looper.getMainLooper()); }
}
