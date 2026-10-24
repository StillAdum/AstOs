package com.astos.setup;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.HashMap;

/**
 * PixelIcon — AstOs 2.4 pixel-art icon set.
 *
 * The user asked for "pixel kinda" instead of pure ASCII: these are 16x16
 * hand-drawn pixel grids rendered as crisp bitmaps (nearest-neighbor —
 * never filtered, never anti-aliased edges) in the active palette.
 *
 * Grid legend:  '.' transparent   'x' fg color   'o' accent   '-' dim
 */
public final class PixelIcon {

    public static final String MIC = "mic";
    public static final String NOTE = "note";
    public static final String CHAT = "chat";
    public static final String SLIDERS = "sliders";
    public static final String SUN = "sun";
    public static final String CLOUD = "cloud";
    public static final String RAIN = "rain";
    public static final String SNOW = "snow";
    public static final String WIFI = "wifi";
    public static final String BATTERY = "battery";
    public static final String HOME = "home";
    public static final String TIMER = "timer";
    public static final String PLAY = "play";
    public static final String PAUSE = "pause";
    public static final String PREV = "prev";
    public static final String NEXT = "next";
    public static final String STOP = "stop";
    public static final String SEARCH = "search";
    public static final String POWER = "power";
    public static final String CHECK = "check";
    public static final String LEFT = "left";
    public static final String RIGHT = "right";
    public static final String VOICE = "voice";
    public static final String PHONE = "phone";
    public static final String DOWN = "down";

    private static final HashMap<String, String[]> GRIDS = new HashMap<String, String[]>();

    private static void g(String key, String... rows) {
        for (String r : rows) {
            if (r.length() != 16) {
                throw new IllegalStateException("icon " + key + " bad row len "
                        + r.length() + ": '" + r + "'");
            }
        }
        if (rows.length != 16) {
            throw new IllegalStateException("icon " + key + " needs 16 rows, got "
                    + rows.length);
        }
        GRIDS.put(key, rows);
    }

    static {
        g(MIC,
                "................",
                ".....xxxxx......",
                ".....xxxxx......",
                ".....xxxxx......",
                ".....xxxxx......",
                ".....xxxxx......",
                "....xxxxxxxx....",
                "..xxxxxxxxxxxx..",
                ".xxxxxxxxxxxxxx.",
                ".oooooooooooooo.",
                ".xxxxxxxxxxxxxx.",
                ".......xx.......",
                ".......xx.......",
                ".......xx.......",
                "...xxxxxxxxxx...",
                "..oooooooooooo..");
        g(NOTE,
                "................",
                "................",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "....xx....xx....",
                "....xx....xx....",
                "....xx....xx....",
                "....xx....xx....",
                "....xx....xx....",
                "....xx....xx....",
                "...xxx...xxx....",
                "..xxxxx..oooo...",
                ".oooooo..ooooo..",
                ".oooooo..ooooo..",
                ".oooooo..ooooo..",
                "................");
        g(CHAT,
                "................",
                "................",
                "..xxxxxxxxxxxx..",
                ".xxxxxxxxxxxxxx.",
                ".xxxxx....xxxxx.",
                ".xxxxx....xxxxx.",
                ".xxxxxxxxxxxxxx.",
                ".xxxxxxxxxxxxxx.",
                ".xxxxxxxxxxxxxx.",
                "..xxxxxxxxxxxx..",
                "......xxxx......",
                ".....xx.........",
                "....xx..........",
                "................",
                "................",
                "................");
        g(SLIDERS,
                "................",
                "................",
                ".xxxxxxxxxxxx...",
                ".xxxxxxooooox...",
                "................",
                "................",
                ".oooooxxxxxxx...",
                ".oooooxxxxxxx...",
                "................",
                "................",
                ".xxxxxxxxoooo...",
                ".xxxxxxxxoooo...",
                "................",
                "................",
                "................",
                "................");
        g(SUN,
                "................",
                ".......xx.......",
                "..x....xx....x..",
                "...x..xxxx..x...",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "...xxxxxxxxxx...",
                "...xxxxxxxxxx...",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "...x..xxxx..x...",
                "..x....xx....x..",
                ".......xx.......",
                "................",
                "................",
                "................");
        g(CLOUD,
                "................",
                ".......xxx......",
                "......xxxxx.....",
                ".....xxxxxxx....",
                "....xxxxxxxxx...",
                "..xxxxxxxxxxxx..",
                ".xxxxxxxxxxxxxx.",
                ".xxxxxxxxxxxxxx.",
                ".xxxxxxxxxxxxxx.",
                "..xxxxxxxxxxxx..",
                "................",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(RAIN,
                "................",
                "................",
                ".......xxx......",
                "......xxxxx.....",
                ".....xxxxxxx....",
                "....xxxxxxxxx...",
                "..xxxxxxxxxxxx..",
                ".xxxxxxxxxxxxxx.",
                ".xxxxxxxxxxxxxx.",
                "..xxxxxxxxxxxx..",
                "...o...o...o....",
                "....o...o...o...",
                "...o...o...o....",
                "....o...o...o...",
                "...o...o...o....",
                "................");
        g(SNOW,
                "................",
                "................",
                ".......xxx......",
                "......xxxxx.....",
                ".....xxxxxxx....",
                "....xxxxxxxxx...",
                "..xxxxxxxxxxxx..",
                ".xxxxxxxxxxxxxx.",
                ".xxxxxxxxxxxxxx.",
                "..xxxxxxxxxxxx..",
                "...o...o...o....",
                "..o.o.o.o.o.o...",
                "...o...o...o....",
                "..o.o.o.o.o.o...",
                "...o...o...o....",
                "................");
        g(WIFI,
                "................",
                "....xxxxxxxx....",
                "...xx......xx...",
                "...x........x...",
                "................",
                "......xxxx......",
                ".....xx..xx.....",
                ".....x....x.....",
                "................",
                "......oooo......",
                "......oooo......",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(BATTERY,
                "................",
                "......xxxxx.....",
                ".....xxxxxxx....",
                ".....xooooox....",
                ".....xooooox....",
                ".....xooooox....",
                ".....xooooox....",
                ".....xooooox....",
                ".....xooooox....",
                ".....xooooox....",
                ".....xooooox....",
                ".....xxxxxxx....",
                "......xxxxx.....",
                "................",
                "................",
                "................");
        g(HOME,
                "................",
                ".......oo.......",
                "......oooo......",
                ".....oooooo.....",
                "....oooooooo....",
                "...oooooooooo...",
                "..xxxxxxxxxxxx..",
                "..xxx..xx..xxx..",
                "..xxx..xx..xxx..",
                "..xxx..xx..xxx..",
                "..xxx......xxx..",
                "..xxx......xxx..",
                "..xxxxxxxxxxxx..",
                "................",
                "................",
                "................");
        g(TIMER,
                "....xxxxxxxx....",
                "...xxxxxxxxxx...",
                "..xxx......xxx..",
                ".xxx........xxx.",
                ".xx..........xx.",
                ".xx....xx....xx.",
                ".xx....xx....xx.",
                ".xx..........xx.",
                ".xxx........xxx.",
                "..xxx......xxx..",
                "...xxxxxxxxxx...",
                "....xxxxxxxx....",
                "................",
                "................",
                "................",
                "................");
        g(PLAY,
                "................",
                "................",
                "................",
                "......xx........",
                "......xxxx......",
                "......xxxxxx....",
                "......xxxxxxxx..",
                "......xxxxxxxx..",
                "......xxxxxx....",
                "......xxxx......",
                "......xx........",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(PAUSE,
                "................",
                "................",
                "................",
                "....xxx..xxx....",
                "....xxx..xxx....",
                "....xxx..xxx....",
                "....xxx..xxx....",
                "....xxx..xxx....",
                "....xxx..xxx....",
                "....xxx..xxx....",
                "....xxx..xxx....",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(PREV,
                "................",
                "................",
                "................",
                "..........xx....",
                "........xxxx....",
                "......xxxxxx....",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "......xxxxxx....",
                "........xxxx....",
                "..........xx....",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(NEXT,
                "................",
                "................",
                "................",
                "......xx........",
                "......xxxx......",
                "......xxxxxx....",
                "......xxxxxxxx..",
                "......xxxxxxxx..",
                "......xxxxxx....",
                "......xxxx......",
                "......xx........",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(STOP,
                "................",
                "................",
                "................",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "....xxxxxxxx....",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(SEARCH,
                "................",
                "................",
                "................",
                "....xxxx........",
                "...xxxxxx.......",
                "...xxxxxx.......",
                "...xxxxxx.......",
                "....xxxx........",
                ".........xxx....",
                "..........xxx...",
                "...........xxx..",
                "............xx..",
                "................",
                "................",
                "................",
                "................");
        g(POWER,
                "................",
                ".......oo.......",
                ".......oo.......",
                "...xx..oo..xx...",
                "..xxx......xxx..",
                ".xx..........xx.",
                ".xx..........xx.",
                ".xx..........xx.",
                "..xxx......xxx..",
                "...xx......xx...",
                "................",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(CHECK,
                "................",
                "................",
                "................",
                "................",
                ".........ooo....",
                "........ooo.....",
                ".......ooo......",
                "..ooo.ooo.......",
                "...ooooo........",
                "....ooo.........",
                ".....o..........",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(LEFT,
                "................",
                "................",
                "................",
                "....xx..........",
                "...xx...........",
                "..xx............",
                ".xx.............",
                ".xx.............",
                "..xx............",
                "...xx...........",
                "....xx..........",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(RIGHT,
                "................",
                "................",
                "................",
                "..........xx....",
                "...........xx...",
                "............xx..",
                ".............xx.",
                ".............xx.",
                "............xx..",
                "...........xx...",
                "..........xx....",
                "................",
                "................",
                "................",
                "................",
                "................");
        g(VOICE,
                "................",
                "................",
                ".......oo.......",
                ".......oo.......",
                ".......oo.......",
                "...xx..oo..xx...",
                "...xx..oo..xx...",
                "...xx..oo..xx...",
                "...xx..oo..xx...",
                "...xx..oo..xx...",
                ".......oo.......",
                ".......oo.......",
                ".......oo.......",
                "................",
                "................",
                "................");
        g(PHONE,
                "................",
                "................",
                "................",
                "...xxxxxxxxx....",
                "....oooooooo....",
                "....oooooooo....",
                "....oooooooo....",
                "....oooooooo....",
                "....oooooooo....",
                "....oooooooo....",
                "....oooooooo....",
                "....oooooooo....",
                "...xxxxxxxxx....",
                "................",
                "................",
                "................");
        g(DOWN,
                "................",
                "................",
                ".......oo.......",
                ".......oo.......",
                ".......oo.......",
                ".......oo.......",
                "......oooo......",
                ".....oooooo.....",
                "....oooooooo....",
                "...oooooooooo...",
                "................",
                "................",
                "................",
                "................",
                "................",
                "................");
    }

    private PixelIcon() {}

    /** Render an icon as a crisp square bitmap of side {@code sizePx}. */
    public static Bitmap render(String name, int sizePx,
                                int fg, int accent, int dim) {
        String[] rows = GRIDS.get(name);
        if (rows == null) {
            rows = GRIDS.get(CHAT);
        }
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        float cell = sizePx / 16f;
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setAntiAlias(false);
        for (int r = 0; r < 16; r++) {
            String row = rows[r];
            for (int q = 0; q < 16; q++) {
                char ch = q < row.length() ? row.charAt(q) : '.';
                if (ch == '.') continue;
                p.setColor(ch == 'o' ? accent : (ch == '-' ? dim : fg));
                c.drawRect(q * cell, r * cell, q * cell + cell + 0.5f,
                        r * cell + cell + 0.5f, p);
            }
        }
        return bmp;
    }

    /** 4x7 blocky pixel digits for the big clock (and nothing else). */
    static final String[] DIGITS = {
            "xxxx,x..x,x..x,x..x,x..x,x..x,xxxx", // 0 — rows joined by ,
            "..x.,..x.,..x.,..x.,..x.,..x.,..x.", // 1
            "xxx.,...x,..x.,.x..,.x..,x...,xxxx", // 2
            "xxx.,...x,..x.,.xx.,...x,...x,xxx.", // 3
            "x..x,x..x,x..x,xxxx,...x,...x,...x", // 4
            "xxxx,x...,xxx.,...x,...x,...x,xxx.", // 5
            "xxx.,x...,xxx.,x..x,x..x,x..x,xxx.", // 6
            "xxxx,...x,...x,..x.,.x..,.x..,.x..", // 7
            "xxx.,x..x,x..x,xxx.,x..x,x..x,xxx.", // 8
            "xxx.,x..x,x..x,xxxx,...x,...x,xxx.", // 9
    };

    /**
     * Draw a string of digits (plus ':') with the blocky pixel font.
     * Returns the total width drawn.
     */
    public static float drawPixelNumber(Canvas c, String text, float x, float y,
                                        float block, int color, Paint p) {
        float cx = x;
        int prev = p.getColor();
        p.setColor(color);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ':') {
                // colon: two blocks
                c.drawRect(cx, y + block, cx + block, y + 2 * block, p);
                c.drawRect(cx, y + 4 * block, cx + block, y + 5 * block, p);
                cx += block * 2.4f;
            } else if (ch >= '0' && ch <= '9') {
                String spec = DIGITS[ch - '0'];
                String[] rows = spec.split(",");
                for (int r = 0; r < 7; r++) {
                    String row = rows[r];
                    for (int q = 0; q < row.length(); q++) {
                        if (row.charAt(q) != '.') {
                            c.drawRect(cx + q * block, y + r * block,
                                    cx + (q + 1) * block, y + (r + 1) * block, p);
                        }
                    }
                }
                cx += block * 5.6f;
            } else if (ch != ' ') {
                c.drawRect(cx, y + block, cx + block * 2, y + block * 3, p);
                cx += block * 3.4f;
            } else {
                cx += block * 2.4f;
            }
        }
        p.setColor(prev);
        return cx - x;
    }

    public static float measurePixelNumber(String text, float block) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ':' || ch == ' ') w += block * 2.4f;
            else if (ch >= '0' && ch <= '9') w += block * 5.6f;
            else w += block * 3.4f;
        }
        return w;
    }

    // ---------------------------------------------------------------- blocky letters

    /**
     * The same 5-row blocky letter grid the boot animation uses — the AstOs
     * brand font. Pixel blocks on canvas, never ASCII characters.
     */
    private static final java.util.HashMap<String, String[]> LETTERS =
            new java.util.HashMap<String, String[]>();

    private static void L(String k, String... rows) { LETTERS.put(k, rows); }

    static {
        L("A", "  #  ", " # # ", "#####", "#   #", "#   #");
        L("B", "#### ", "#   #", "#### ", "#   #", "#### ");
        L("C", " ####", "#    ", "#    ", "#    ", " ####");
        L("D", "#### ", "#   #", "#   #", "#   #", "#### ");
        L("E", "#####", "#    ", "#### ", "#    ", "#####");
        L("F", "#####", "#    ", "#### ", "#    ", "#    ");
        L("G", " ####", "#    ", "#  ##", "#   #", " ####");
        L("H", "#   #", "#   #", "#####", "#   #", "#   #");
        L("I", "#####", "  #  ", "  #  ", "  #  ", "#####");
        L("J", "#####", "   # ", "   # ", "#  # ", " ##  ");
        L("K", "#   #", "#  # ", "###  ", "#  # ", "#   #");
        L("M", "#   #", "## ##", "# # #", "#   #", "#   #");
        L("N", "#   #", "##  #", "# # #", "#  ##", "#   #");
        L("O", " ### ", "#   #", "#   #", "#   #", " ### ");
        L("P", "#### ", "#   #", "#### ", "#    ", "#    ");
        L("Q", " ### ", "#   #", "#   #", " ### ", "    #");
        L("R", "#### ", "#   #", "#### ", "#  # ", "#   #");
        L("S", " ####", "#    ", " ### ", "    #", "#### ");
        L("T", "#####", "  #  ", "  #  ", "  #  ", "  #  ");
        L("U", "#   #", "#   #", "#   #", "#   #", " ### ");
        L("V", "#   #", "#   #", "#   #", " # # ", "  #  ");
        L("W", "#   #", "#   #", "# # #", "## ##", "#   #");
        L("X", "#   #", " # # ", "  #  ", " # # ", "#   #");
        L("Y", "#   #", " # # ", "  #  ", "  #  ", "  #  ");
        L("Z", "#####", "   # ", "  #  ", " #   ", "#####");
        L("s", " ####", "#    ", " ### ", "    #", "#### ");
        L("t", "  #  ", "  #  ", "#####", "  #  ", "  #  ");
        L("o", "     ", " ### ", "#   #", "#   #", " ### ");
    }

    /** Draw text with the blocky brand font; 'shown' reveals N blocks (typing). */
    public static float drawBlockText(android.graphics.Canvas c, String text, float x,
                                      float y, float block, int color,
                                      android.graphics.Paint p, int shownBlocks) {
        float cx = x;
        int count = 0;
        int prev = p.getColor();
        p.setColor(color);
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            String[] g = LETTERS.get(ch);
            if (g == null) { cx += block * 2.4f; continue; }
            for (int r = 0; r < 5; r++) {
                for (int q = 0; q < 5; q++) {
                    if (g[r].charAt(q) != '#') continue;
                    count++;
                    if (count > shownBlocks) {
                        p.setColor(prev);
                        return cx - x;
                    }
                    c.drawRect(cx + q * block, y + r * block,
                            cx + (q + 1) * block, y + (r + 1) * block, p);
                }
            }
            cx += block * 6.4f;
        }
        p.setColor(prev);
        return cx - x;
    }

    public static int countBlocks(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            String[] g = LETTERS.get(text.substring(i, i + 1));
            if (g == null) continue;
            for (String row : g) {
                for (int q = 0; q < 5; q++) {
                    if (row.charAt(q) == '#') n++;
                }
            }
        }
        return n;
    }

    public static float measureBlockText(String text, float block) {
        return text.length() * block * 6.4f;
    }
}
