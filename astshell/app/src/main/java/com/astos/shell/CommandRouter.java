package com.astos.shell;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline voice command parser. Deliberately simple — direct commands,
 * no chatbot personality ("dont make it look full ai").
 */
public final class CommandRouter {

    public interface Actions {
        void openApp(String query);
        void mediaAction(String action, int value); // play|pause|toggle|next|prev|volup|voldown|volset
        void playLocalMusic();
        void spotifyPlay(String query);            // "hey spotify play X"
        void askGroq(String question);             // unmatched -> Groq AI
        boolean groqEnabled();
        void weather();
        void time();
        void date();
        void setTimer(long ms);
        void cancelTimer();
        void timerStatus();
        void homeAssistant(String name, Boolean on); // on == null -> toggle
        void scene(String name);
        void brightness(String action, Integer level); // up|down|set
        void fullscreen(boolean on);
        void openSettings();
        void openAndroidSettings();
        void openNotificationAccess();
        void homeChooser();
        void allApps();
    }

    private CommandRouter() {}

    /** Returns a spoken/visual response, or null when nothing matched. */
    public static String route(String raw, Actions a) {
        String s = normalize(raw);
        if (s.isEmpty()) return null;

        // ---- help / identity ------------------------------------------------
        if (s.equals("help") || s.contains("what can you say") || s.equals("commands")
                || s.contains("what can i say")) {
            return "Say: play plus a song name to start Spotify, ask for the time, "
                    + "the weather, music control, timers, brightness, or Home Assistant devices. "
                    + "Anything else, I answer with Groq AI.";
        }
        if (s.equals("hello") || s.equals("hi") || s.equals("hey astos") || s.equals("hi astos")) {
            return "Hi.";
        }
        if (s.contains("thank")) return "You're welcome.";
        if (s.contains("who are you") || s.contains("what are you")) {
            return "I'm Ast, the voice of AstOs.";
        }
        if (s.contains("what os is this") || s.contains("which os") || s.contains("what system")) {
            return "You're running AstOs one point zero.";
        }

        // ---- wake-word strip: "hey astos, ..." / "astos, ..." ----------------
        s = s.replaceFirst("^hey\\s+ast\\w*\\s*", "").replaceFirst("^ast\\w*\\s+", "").trim();
        if (s.isEmpty()) return "Hi. Say help for what I can do.";

        // ---- Spotify voice playback ----------------------------------------------
        String spotQ = spotifyPlayQuery(s);
        if (spotQ != null) {
            if (spotQ.isEmpty()) return "What should Spotify play?";
            a.spotifyPlay(spotQ);
            return null;
        }

        // ---- timer ---------------------------------------------------------
        if (s.contains("timer")) {
            if (s.startsWith("cancel") || s.startsWith("stop") || s.startsWith("clear")) {
                a.cancelTimer(); return "Timer cancelled.";
            }
            if (s.contains("how much") || s.contains("left") || s.startsWith("check")) {
                a.timerStatus(); return null;
            }
            long ms = parseDuration(s);
            if (ms > 0) { a.setTimer(ms); return "Timer set for " + human(ms) + "."; }
            return "Say: set a timer for five minutes.";
        }

        // ---- music & media ---------------------------------------------------
        if (s.contains("spotify")) {
            if (s.startsWith("open") || s.startsWith("launch") || s.startsWith("start") || s.equals("spotify")) {
                a.openApp("spotify");
                return "Opening Spotify.";
            }
        }
        if (s.equals("play music") || s.equals("play some music") || s.equals("music on")
                || s.equals("play my music") || s.equals("start music") || s.equals("play songs")) {
            a.playLocalMusic();
            return "Playing your music.";
        }
        // "play <song>" / "put on <song>" -> Spotify (named songs mean search)
        if (s.startsWith("play ") || s.startsWith("put on ")) {
            String q = s.startsWith("play ") ? s.substring(5) : s.substring(8);
            q = q.replaceFirst("^(a|an|the|some) ", "").trim();
            if (!q.isEmpty() && !q.equals("music") && !q.equals("song") && !q.equals("songs")) {
                a.spotifyPlay(q);
                return null;
            }
        }
        if (s.equals("play") || s.equals("resume")) { a.mediaAction("play", 0); return null; }
        if (s.equals("pause") || s.equals("stop") || s.equals("hold on")) { a.mediaAction("pause", 0); return "Paused."; }
        if (s.equals("next") || s.equals("skip") || s.contains("next song") || s.contains("skip song")) {
            a.mediaAction("next", 0); return null;
        }
        if (s.equals("previous") || s.equals("go back") || s.contains("last song") || s.contains("previous song")) {
            a.mediaAction("prev", 0); return null;
        }

        // ---- volume ---------------------------------------------------------
        if (s.contains("volume") || s.contains("louder") || s.contains("quieter") || s.contains("mute")) {
            if (s.contains("up") || s.contains("louder") || s.contains("increase")) { a.mediaAction("volup", 0); return "Volume up."; }
            if (s.contains("down") || s.contains("quieter") || s.contains("lower") || s.contains("decrease")) { a.mediaAction("voldown", 0); return "Volume down."; }
            if (s.contains("mute")) { a.mediaAction("volset", 0); return "Muted."; }
            Integer pct = firstNumber(s);
            if (pct != null) { a.mediaAction("volset", pct); return "Volume " + pct + " percent."; }
        }

        // ---- brightness -----------------------------------------------------
        if (s.contains("brightness") || s.contains("brighter") || s.contains("dimmer") || s.contains("dim the")) {
            if (s.contains("up") || s.contains("brighter") || s.contains("increase")) { a.brightness("up", null); return "Brightness up."; }
            if (s.contains("down") || s.contains("dimmer") || s.contains("lower") || s.contains("decrease")) { a.brightness("down", null); return "Brightness down."; }
            Integer lvl = firstNumber(s);
            if (lvl != null) { a.brightness("set", Math.max(1, Math.min(100, lvl))); return "Brightness " + lvl + " percent."; }
        }

        // ---- weather / time / date -------------------------------------------
        if (s.contains("weather") || s.contains("temperature") || s.contains("forecast")) {
            a.weather(); return null;
        }
        if (s.contains("time left") || s.contains("timer left")) { a.timerStatus(); return null; }
        if (s.contains("time") || s.equals("clock")) { a.time(); return null; }
        if (s.contains("date") || s.contains("day is it") || s.contains("today")) { a.date(); return null; }

        // ---- fullscreen / kiosk ------------------------------------------------
        if (s.contains("fullscreen") || s.contains("hide bar")) {
            boolean on = !(s.contains("exit") || s.contains("disable") || s.contains("show bar"));
            a.fullscreen(on);
            return on ? "Fullscreen on." : "Fullscreen off.";
        }

        // ---- settings shortcuts ------------------------------------------------
        if (s.contains("notification access") || s.contains("media control settings")) {
            a.openNotificationAccess(); return "Open notification access and enable AstOs.";
        }
        if (s.contains("android settings") || s.contains("system settings")) {
            a.openAndroidSettings(); return null;
        }
        if (s.contains("settings") || s.contains("setup")) { a.openSettings(); return null; }
        if (s.contains("home screen chooser") || s.contains("default home")) { a.homeChooser(); return null; }
        if (s.equals("apps") || s.contains("all apps") || s.contains("app drawer")) { a.allApps(); return null; }

        // ---- Home Assistant ------------------------------------------------------
        Boolean on = null;
        boolean turnCmd = s.contains("turn on") || s.contains("switch on") || s.contains("lights on")
                || s.startsWith("open ");
        boolean offCmd = s.contains("turn off") || s.contains("switch off") || s.contains("lights off")
                || s.contains("close ");
        if (turnCmd || offCmd) on = turnCmd && !offCmd;

        if (s.contains("scene")) {
            String name = stripPrefix(s, "scene");
            if (s.startsWith("run scene") || s.startsWith("activate scene")) name = stripPrefix(s, "run scene activate");
            a.scene(name);
            return null;
        }
        if (turnCmd || offCmd) {
            String name = s
                    .replace("turn", " ").replace("switch", " ").replace("the", " ")
                    .replace(" on", " |ON| ").replace(" off", " |OFF| ")
                    .replace("open", " ").replace("close", " ").replace("lights", "light");
            // keep only the part naming the device
            String target = name.contains("|ON|") ? name.substring(0, name.indexOf("|ON|"))
                    : name.contains("|OFF|") ? name.substring(0, name.indexOf("|OFF|"))
                    : name;
            target = normalize(target);
            if (target.isEmpty() && s.contains("lights")) target = "light";
            if (!target.isEmpty()) { a.homeAssistant(target, on); return null; }
        }

        // ---- open apps --------------------------------------------------------
        if (s.startsWith("open ") || s.startsWith("launch ") || s.startsWith("start ")
                || s.startsWith("run ")) {
            String app = normalize(s.substring(s.indexOf(' ') + 1));
            if (!app.isEmpty()) { a.openApp(app); return null; }
        }

        // ---- Groq AI fallback: anything else becomes a question ----------------
        if (a.groqEnabled()) { a.askGroq(s); return null; }
        return "I didn't catch that. Say help for a list of commands.";
    }

    /** Extracts the song from a Spotify play command; null = not one. */
    static String spotifyPlayQuery(String s) {
        String q = null;
        if (s.startsWith("hey spotify") || s.startsWith("okay spotify") || s.startsWith("ok spotify")) {
            String rest = s.replaceFirst("^(hey|okay|ok)\\s+spotify\\s*", "").trim();
            rest = rest.replaceFirst("^(can you |please )?play(ing)? ", "").trim();
            q = rest;
        } else if (s.startsWith("spotify play") || s.startsWith("spotify, play")
                || s.startsWith("spotify can you") || s.startsWith("spotify please")) {
            q = s.replaceFirst("^spotify[, ]+(can you |please )?play ", "").trim();
        } else if (s.startsWith("play ") && (s.endsWith(" on spotify") || s.endsWith(" on the spotify"))) {
            q = s.substring(5, s.lastIndexOf(" on "));
        } else if ((s.startsWith("ask spotify to play ") || s.startsWith("tell spotify to play "))) {
            q = s.replaceFirst("^(ask|tell) spotify to play ", "").trim();
        }
        if (q == null) return null;
        // "song X"/"track X" are just filler the model sometimes inserts
        q = q.replaceFirst("^(the )?(song|track|some|music) ", "").trim();
        return q;
    }

    // ---------------------------------------------------------------- utils

    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.US);
        s = s.replace("'", "").replace("?", "").replace(".", "")
             .replace(",", "").replace("!", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static final Map<String, Integer> WORDS = buildWords();

    private static Map<String, Integer> buildWords() {
        Map<String, Integer> m = new HashMap<String, Integer>();
        String[] names = {"zero","one","two","three","four","five","six","seven","eight","nine",
                "ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen",
                "eighteen","nineteen","twenty","thirty","forty","fifty","sixty","seventy",
                "eighty","ninety","hundred"};
        int[] vals = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,30,40,50,60,70,80,90,100};
        for (int i = 0; i < names.length; i++) m.put(names[i], vals[i]);
        return m;
    }

    private static long parseDuration(String s) {
        int num = 0; boolean numSeen = false;
        String unit = "minute";
        for (String tok : s.split(" ")) {
            if (tok.matches("\\d+")) { num = Integer.parseInt(tok); numSeen = true; }
            else if (WORDS.containsKey(tok)) { num = WORDS.get(tok); numSeen = true; }
            else if (tok.contains("second") || tok.equals("sec")) unit = "second";
            else if (tok.contains("minute") || tok.equals("min")) unit = "minute";
            else if (tok.contains("hour")) unit = "hour";
        }
        if (!numSeen || num <= 0) return 0;
        if ("second".equals(unit)) return num * 1000L;
        if ("hour".equals(unit)) return num * 3600000L;
        return num * 60000L;
    }

    private static String human(long ms) {
        if (ms % 3600000L == 0 && ms >= 3600000L) return (ms / 3600000L) + " hours";
        if (ms % 60000L == 0 && ms >= 60000L) return (ms / 60000L) + " minutes";
        return (ms / 1000L) + " seconds";
    }

    private static Integer firstNumber(String s) {
        Matcher m = Pattern.compile("\\b(\\d{1,3})\\b").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        for (String tok : s.split(" ")) {
            if (WORDS.containsKey(tok)) return WORDS.get(tok);
        }
        return null;
    }

    private static String stripPrefix(String s, String prefixes) {
        String out = s;
        for (String p : prefixes.split(" ")) {
            out = out.replace(p + " ", " ");
        }
        return normalize(out.replace(prefixes, " "));
    }
}
