package com.astos.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Latest headlines via BBC RSS — free, no key, no account.
 * The Alexa-style "what's the news" answer.
 */
public final class NewsProvider {

    public static class Headline {
        public String title;
        public String source;
        public Headline(String t, String s) { title = t; source = s; }
    }

    public interface Callback { void onNews(List<Headline> headlines, String error); }

    private static List<Headline> sCache;
    private static long sCacheAt;

    private NewsProvider() {}

    private static void async(Runnable r) { new Thread(r, "news").start(); }
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    public static void fetch(final boolean force, final Callback cb) {
        if (!force && sCache != null && System.currentTimeMillis() - sCacheAt < 10 * 60 * 1000L) {
            main().post(new Runnable() { @Override public void run() { cb.onNews(sCache, null); }});
            return;
        }
        async(new Runnable() { @Override public void run() {
            List<Headline> all = new ArrayList<Headline>();
            try {
                List<Headline> w = rss("https://feeds.bbci.co.uk/news/rss.xml", "BBC World");
                if (w != null) all.addAll(w);
            } catch (Throwable ignored) {}
            try {
                List<Headline> t = rss("https://feeds.bbci.co.uk/news/technology/rss.xml", "BBC Tech");
                if (t != null) all.addAll(t);
            } catch (Throwable ignored) {}
            if (all.isEmpty()) {
                final String err = "News unavailable - check wifi";
                main().post(new Runnable() { @Override public void run() { cb.onNews(null, err); }});
                return;
            }
            sCache = all;
            sCacheAt = System.currentTimeMillis();
            final List<Headline> out = all;
            main().post(new Runnable() { @Override public void run() { cb.onNews(out, null); }});
        }});
    }

    private static List<Headline> rss(String url, String src) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "AstOs/2.0 news reader");
            BufferedReader rd = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) sb.append(line);
            rd.close();
            c.disconnect();
            String xml = sb.toString();

            List<Headline> out = new ArrayList<Headline>();
            Pattern item = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
            Pattern title = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
            Matcher m = item.matcher(xml);
            int guard = 0;
            while (m.find() && guard++ < 6) {
                Matcher tm = title.matcher(m.group(1));
                if (tm.find()) {
                    String t = decode(tm.group(1));
                    if (!t.isEmpty()) out.add(new Headline(t, src));
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decode(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("<![CDATA[", "").replace("]]>", "").trim();
    }
}
