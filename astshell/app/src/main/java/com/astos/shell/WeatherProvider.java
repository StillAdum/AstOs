package com.astos.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Weather via Open-Meteo — free, no API key, no account.
 * Geocoding also via Open-Meteo.
 */
public final class WeatherProvider {

    public static class WeatherInfo {
        public float temp, tempMin, tempMax;
        public int code;
        public String city;
    }

    public interface Callback { void onWeather(WeatherInfo w, String error); }
    public interface GeoCallback { void onGeo(String resolvedName, String error); }

    private WeatherProvider() {}

    private static void async(Runnable r) { new Thread(r, "weather").start(); }
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        BufferedReader rd = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) sb.append(line);
        rd.close();
        c.disconnect();
        return sb.toString();
    }

    /** Resolve a city name to lat/lon and save it. */
    public static void geocode(final Context ctx, final String city, final GeoCallback cb) {
        async(new Runnable() { @Override public void run() {
            try {
                String q = URLEncoder.encode(city, "UTF-8");
                String json = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + q);
                JSONObject root = new JSONObject(json);
                final String name;
                if (root.getJSONArray("results").length() > 0) {
                    JSONObject r = root.getJSONArray("results").getJSONObject(0);
                    final float lat = (float) r.getDouble("latitude");
                    final float lon = (float) r.getDouble("longitude");
                    StringBuilder nm = new StringBuilder(r.optString("name", city));
                    String admin = r.optString("admin1", "");
                    if (!admin.isEmpty()) nm.append(", ").append(admin);
                    name = nm.toString();
                    AppSettings.setGeo(ctx, lat, lon);
                    AppSettings.setResolvedCity(ctx, name);
                } else {
                    main().post(new Runnable() { @Override public void run() {
                        cb.onGeo(null, "City not found: " + city);
                    }});
                    return;
                }
                main().post(new Runnable() { @Override public void run() { cb.onGeo(name, null); }});
            } catch (final Exception e) {
                final String msg = e.getMessage();
                main().post(new Runnable() { @Override public void run() { cb.onGeo(null, msg); }});
            }
        }});
    }

    public static void refresh(final Context ctx, final Callback cb) {
        final float lat = AppSettings.lat(ctx), lon = AppSettings.lon(ctx);
        if (Float.isNaN(lat) || Float.isNaN(lon)) { cb.onWeather(null, "Set your city in AstOs settings"); return; }
        async(new Runnable() { @Override public void run() {
            try {
                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
                        + "&timezone=auto&forecast_days=1";
                JSONObject root = new JSONObject(get(url));
                WeatherInfo w = new WeatherInfo();
                JSONObject cur = root.getJSONObject("current");
                w.temp = (float) cur.getDouble("temperature_2m");
                w.code = cur.optInt("weather_code", 0);
                JSONObject day = root.getJSONObject("daily");
                w.tempMax = (float) day.getJSONArray("temperature_2m_max").getDouble(0);
                w.tempMin = (float) day.getJSONArray("temperature_2m_min").getDouble(0);
                w.city = AppSettings.resolvedCity(ctx);
                final WeatherInfo wi = w;
                main().post(new Runnable() { @Override public void run() { cb.onWeather(wi, null); }});
            } catch (final Exception e) {
                final String msg = e.getMessage();
                main().post(new Runnable() { @Override public void run() { cb.onWeather(null, msg); }});
            }
        }});
    }

    /** Human description for a WMO weather code. */
    public static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code <= 48) return "Fog";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        if (code <= 86) return "Snow showers";
        return "Storm";
    }

    public static int iconRes(int code) {
        if (code == 0) return R.drawable.ic_weather_clear;
        if (code <= 2) return R.drawable.ic_weather_partly;
        if (code == 3) return R.drawable.ic_weather_cloud;
        if (code <= 48) return R.drawable.ic_weather_cloud;
        if (code <= 67) return R.drawable.ic_weather_rain;
        if (code <= 77) return R.drawable.ic_weather_snow;
        if (code <= 82) return R.drawable.ic_weather_rain;
        return R.drawable.ic_weather_storm;
    }
}
