package com.astos.shell;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline-first voice command parser. Direct commands run locally with no
 * network; anything unrecognized can be answered by the Groq AI assistant
 * (cloud) when the user has configured a key.
 *
 * Music works Discord-bot style:
 *   "play never gonna give you up"  -> searches YouTube Music, streams it
 *   "hey spotify play <song>"        -> same thing (spotify = the music intent)
 */
public final class CommandRouter {

    public interface Actions {
        void openApp(String query);
        void mediaAction(String action, int value); // play|pause|toggle|next|prev|volup|voldown|volset
        void playLocalMusic();
        void playSong(String query);                // on-demand song search + stream
        void weather();
        void news();
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
        void askGroq(String question);              // assistant fallback
        void whatsPlaying();
        default void pcAction(String action, String query, boolean sticky) {}
        default String playbackTarget(boolean pc){return pc?"Playback set to PC":"Playback set to tablet";}
        default boolean pcSelected() { return false; }
    }

    private CommandRouter() {}

    /** Returns a spoken/visual response, or null when the action speaks for itself. */
    public static String route(String raw, Actions a) {
        String s = normalize(raw);
        if (s.isEmpty()) return null;

        s = s.replaceFirst("^(hey |hi )?(ast os|astos|ast)\\b[ ,]*", "").trim();
        s=s.replaceFirst("^(?:(?:could you|can you|would you|please)\\s+)+", "");
        if(s.matches(".*(set|switch|change).*(playback|audio|output).*(pc|computer|desktop).*"))return a.playbackTarget(true);
        if(s.matches(".*(set|switch|change).*(playback|audio|output).*(tablet|local|speaker).*"))return a.playbackTarget(false);
        if(s.matches(".*(on|through) (my |the )?tablet.*")){a.playbackTarget(false);s=s.replaceAll("(on|through) (my |the )?tablet", "").trim();}
        boolean explicitPc = s.matches(".*\\b(pc|computer|desktop)\\b.*");
        boolean stickyPc = s.contains("until") && s.contains("disconnect");
        if ((explicitPc || a.pcSelected()) && !s.contains("timer")) {
            String action = null;
            if (s.startsWith("next") || s.startsWith("skip") || s.startsWith("play next")) action = "next";
            else if (s.startsWith("previous") || s.startsWith("last song") || s.startsWith("play previous")) action = "previous";
            else if (s.startsWith("pause") || s.startsWith("stop")) action = "pause";
            else if (s.startsWith("resume")) action = "resume";
            else if (s.startsWith("play") || s.startsWith("put on") || s.startsWith("i want to hear")) action = "play";
            if (action != null) {
                String query = s.replaceFirst("^(play|put on|i want to hear)\\s*", "")
                    .replaceAll("\\b(on|in|through|thru|thrugh) (my |the )?(pc|computer|desktop)\\b", "")
                    .replaceAll("\\b(on |in )?spotify\\b", "")
                    .replaceAll("until.*disconnect.*", "").trim();
                query=query.replaceFirst("^(the )?(song|track)\\s+", "");
                if (query.matches("(some |my )?(music|songs|song)?")) query = "";
                a.pcAction(action, query, stickyPc || a.pcSelected());
                return null;
            }
        }

        // ---- help / identity ------------------------------------------------
        if (s.equals("help") || s.contains("what can you say") || s.equals("commands")
                || s.contains("what can i say")) {
            return "You can say: play any song by name, like play despacito. "
                    + "Or hey spotify play something. Ask for the weather, the news, "
                    + "the time, or the date. Timers, brightness, and volume work too. "
                    + "Anything else, just ask me.";
        }
        if (s.equals("hello") || s.equals("hi") || s.equals("hey astos") || s.equals("hi astos")
                || s.equals("hey spotify")) {
            return "Hi. What do you need?";
        }
        if (s.contains("thank")) return "You're welcome.";
        if (s.contains("who are you") || s.contains("what are you") || s.equals("who made you")
                || s.contains("what os is this") || s.contains("which os")) {
            return "I'm AstOs — your voice-first home assistant.";
        }

        // ---- on-demand music (Discord bot style) -------------------------------
        String songQuery = extractSongQuery(s);
        if (songQuery != null) {
            boolean justQueue = s.startsWith("queue ") || s.startsWith("add ");
            a.playSong(justQueue ? songQuery : songQuery);
            return null; // MusicEngine reports status
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

        // ---- music & media control ---------------------------------------------
        if (s.equals("play music") || s.equals("play some music") || s.equals("music on")
                || s.equals("play my music") || s.equals("start music")
                || s.equals("play my songs") || s.equals("shuffle my music")
                || s.equals("play local music")) {
            a.playLocalMusic();
            return "Playing your music.";
        }
        if (s.equals("play") || s.equals("resume")) { a.mediaAction("play", 0); return null; }
        if (s.equals("pause") || s.equals("stop music") || s.equals("hold on")) {
            a.mediaAction("pause", 0); return "Paused.";
        }
        if (s.equals("stop")) { a.mediaAction("pause", 0); return "Stopped."; }
        if (s.equals("next") || s.equals("skip") || s.contains("next song") || s.contains("skip song")
                || s.contains("skip this")) {
            a.mediaAction("next", 0); return null;
        }
        if (s.equals("previous") || s.equals("go back") || s.contains("last song") || s.contains("previous song")) {
            a.mediaAction("prev", 0); return null;
        }
        if (s.contains("what song") || s.contains("whats playing") || s.contains("what is playing")
                || s.contains("name of this song") || s.contains("whats this song")) {
            a.whatsPlaying(); return null;
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

        // ---- weather / news / time / date -------------------------------------------
        if (s.contains("news") || s.contains("headline") || s.contains("whats happening")
                || s.contains("what is happening") || s.contains("read me the news")) {
            a.news(); return null;
        }
        if (s.contains("weather") || s.contains("temperature") || s.contains("forecast")) {
            a.weather(); return null;
        }
        if (s.contains("time left") || s.contains("timer left")) { a.timerStatus(); return null; }
        if (s.contains("what day") || s.contains("day is it") || s.contains("today")) {
            a.date(); return null;
        }
        if (s.contains("date") || s.contains("whats today") || s.contains("what is today")) {
            a.date(); return null;
        }
        if (s.contains("time") || s.equals("clock")) { a.time(); return null; }

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
            a.openSettings(); return null;
        }
        if (s.contains("settings") || s.contains("setup")) { a.openSettings(); return null; }
        if (s.contains("home screen chooser") || s.contains("default home")) { a.openSettings(); return null; }
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

        // ---- assistant fallback: ask Groq (Alexa-style general questions) -------
        a.askGroq(raw);
        return null;
    }

    // ---------------------------------------------------------------- song extraction

    /**
     * Pull a song query out of phrases like:
     *   play X | play the song X | play X by Y | hey spotify play X | spotify X
     *   queue X | find X | search X | put on X
     * Returns null when this is not a song request.
     */
    private static String extractSongQuery(String s) {
        String q = s;
        // strip wake words / prefixes
        for (String pre : new String[]{
                "hey astos play", "hey spotify play", "hey astos put on",
                "spotify play", "play the song", "play song", "search and play",
                "can you play", "please play", "i want to hear", "put on",
                "listen to", "queue ", "add ", "play some ", "find song",
                "search song", "play "}) {
            if (q.startsWith(pre)) {
                String rest = q.substring(pre.length()).trim();
                if (q.startsWith("queue ") || q.startsWith("add ")) return rest;
                if (rest.matches("(some |my )?(music|songs)|local music|next song|previous song")) return null;
                return rest.isEmpty() ? null : rest;
            }
        }
        if (!q.startsWith("play ") && !q.startsWith("search ") && !q.startsWith("find ")) return null;
        String rest = q.substring(q.indexOf(' ') + 1).trim();
        // filter obvious media-control phrases
        if (rest.isEmpty()) return null;
        if (rest.equals("music") || rest.equals("my music") || rest.equals("some music")
                || rest.equals("songs") || rest.equals("local music") || rest.equals("it")
                || rest.equals("that") || rest.equals("this") || rest.equals("the song")) {
            return null;
        }
        if (rest.startsWith("something by ")) rest = rest.substring("something by ".length());
        return rest;
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
