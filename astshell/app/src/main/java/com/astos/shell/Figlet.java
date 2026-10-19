package com.astos.shell;

import java.util.HashMap;
import java.util.Map;

/**
 * Tiny embedded 5-line ASCII font — the "big text" engine of the AstOs
 * ASCII interface. Pure ASCII blocks, rendered in monospace.
 */
public final class Figlet {

    private static final Map<Character, String[]> F = build();

    private static Map<Character, String[]> build() {
        Map<Character, String[]> m = new HashMap<Character, String[]>();
        String[][] g = {
            {"A", "  #  ", " # # ", "#####", "#   #", "#   #"},
            {"B", "#### ", "#   #", "#### ", "#   #", "#### "},
            {"C", " ####", "#    ", "#    ", "#    ", " ####"},
            {"D", "#### ", "#   #", "#   #", "#   #", "#### "},
            {"E", "#####", "#    ", "#### ", "#    ", "#####"},
            {"F", "#####", "#    ", "#### ", "#    ", "#    "},
            {"G", " ####", "#    ", "#  ##", "#   #", " ####"},
            {"H", "#   #", "#   #", "#####", "#   #", "#   #"},
            {"I", "#####", "  #  ", "  #  ", "  #  ", "#####"},
            {"J", "#####", "   # ", "   # ", "#  # ", " ##  "},
            {"K", "#   #", "#  # ", "###  ", "#  # ", "#   #"},
            {"L", "#    ", "#    ", "#    ", "#    ", "#####"},
            {"M", "#   #", "## ##", "# # #", "#   #", "#   #"},
            {"N", "#   #", "##  #", "# # #", "#  ##", "#   #"},
            {"O", " ### ", "#   #", "#   #", "#   #", " ### "},
            {"P", "#### ", "#   #", "#### ", "#    ", "#    "},
            {"Q", " ### ", "#   #", "#   #", " ### ", "    #"},
            {"R", "#### ", "#   #", "#### ", "#  # ", "#   #"},
            {"S", " ####", "#    ", " ### ", "    #", "#### "},
            {"T", "#####", "  #  ", "  #  ", "  #  ", "  #  "},
            {"U", "#   #", "#   #", "#   #", "#   #", " ### "},
            {"V", "#   #", "#   #", "#   #", " # # ", "  #  "},
            {"W", "#   #", "#   #", "# # #", "## ##", "#   #"},
            {"X", "#   #", " # # ", "  #  ", " # # ", "#   #"},
            {"Y", "#   #", " # # ", "  #  ", "  #  ", "  #  "},
            {"Z", "#####", "   # ", "  #  ", " #   ", "#####"},
            {"0", " ### ", "#   #", "#  ##", "##  #", " ### "},
            {"1", "  #  ", " ##  ", "  #  ", "  #  ", "#####"},
            {"2", " ### ", "#   #", "  ## ", " #   ", "#####"},
            {"3", "#### ", "    #", " ### ", "    #", "#### "},
            {"4", "#   #", "#   #", "#####", "    #", "    #"},
            {"5", "#####", "#    ", "#### ", "    #", "#### "},
            {"6", " ### ", "#    ", "#### ", "#   #", " ### "},
            {"7", "#####", "    #", "   # ", "  #  ", "  #  "},
            {"8", " ### ", "#   #", " ### ", "#   #", " ### "},
            {"9", " ### ", "#   #", " ####", "    #", " ### "},
            {":", "     ", "  #  ", "     ", "  #  ", "     "},
            {".", "     ", "     ", "     ", "     ", "  #  "},
            {",", "     ", "     ", "     ", "  #  ", " #   "},
            {"-", "     ", "     ", "#####", "     ", "     "},
            {"+", "     ", "  #  ", "#####", "  #  ", "     "},
            {"_", "     ", "     ", "     ", "     ", "#####"},
            {"/", "    #", "   # ", "  #  ", " #   ", "#    "},
            {"!", "  #  ", "  #  ", "  #  ", "     ", "  #  "},
            {"?", " ### ", "#   #", "  ## ", "     ", "  #  "},
            {"'", "  #  ", "  #  ", "     ", "     ", "     "},
            {"(", "   # ", "  #  ", "  #  ", "  #  ", "   # "},
            {")", " #   ", "  #  ", "  #  ", "  #  ", " #   "},
            {"[", "#### ", "  #  ", "  #  ", "  #  ", "#### "},
            {"]", " ####", "   # ", "   # ", "   # ", " ####"},
            {"<", "   # ", "  #  ", " #   ", "  #  ", "   # "},
            {">", " #   ", "  #  ", "   # ", "  #  ", " #   "},
            {"#", " # # ", "#####", " # # ", "#####", " # # "},
            {"*", "     ", " # # ", "  #  ", " # # ", "     "},
            {"=", "     ", "#####", "     ", "#####", "     "},
            {" ", "     ", "     ", "     ", "     ", "     "},
        };
        for (String[] e : g) {
            String[] rows = new String[5];
            for (int i = 0; i < 5; i++) rows[i] = e[i + 1];
            m.put(e[0].charAt(0), rows);
        }
        return m;
    }

    private Figlet() {}

    /** Render text into multi-line ASCII art. Unknown chars become spaces. */
    public static String[] render(String text) {
        String[] out = new String[5];
        for (int i = 0; i < 5; i++) out[i] = "";
        if (text == null) text = "";
        String s = text.toUpperCase();
        for (int c = 0; c < s.length(); c++) {
            String[] g = F.get(s.charAt(c));
            if (g == null) g = F.get(' ');
            for (int r = 0; r < 5; r++) out[r] += g[r] + " ";
        }
        // trim common trailing spaces of the widest row for tight layout
        return out;
    }

    /** Render centered into an AsciiView row block starting at (x, y). */
    public static void draw(AsciiView v, int cx, int y, String text, int color) {
        String[] g = render(text);
        int w = g.length > 0 ? g[0].length() : 0;
        int x = cx - w / 2;
        for (int r = 0; r < 5; r++) v.put(x, y + r, g[r], color);
    }
}
