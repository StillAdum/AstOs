package com.astos.setup;

import java.util.Random;

/**
 * Animated ASCII background effects — the "customizable background" layer.
 * Renders dim background characters into the AsciiView grid before page
 * content is drawn each frame.
 */
public class BackgroundFX {

    public static final String[] MODES = {"SOLID", "STARS", "RAIN", "WAVES", "PLASMA", "SCANLINES", "IMAGE"};

    private static final String RAIN_CHARS = "01<>[]{}#$%&@*+=~:;^/\\|abcdefghijklmnopqrstuvwxyz";
    private static final String SHADE = " .:-=+*#%@";

    private final String mMode;
    private final Random mRnd = new Random(31337);
    private int[] mRainDrops;
    private int[] mStarX, mStarY;
    private int mStarsInited = 0;
    private float mHueDrift = 0f;

    public BackgroundFX(String mode) { mMode = mode; }
    public String mode() { return mMode; }

    /** Draw the background layer for this frame (fills empty-looking cells). */
    public void render(AsciiView v, long frame, AsciiTheme.Palette p) {
        if ("STARS".equals(mMode)) stars(v, frame, p);
        else if ("RAIN".equals(mMode)) rain(v, frame, p);
        else if ("WAVES".equals(mMode)) waves(v, frame, p);
        else if ("PLASMA".equals(mMode)) plasma(v, frame, p);
        else if ("SCANLINES".equals(mMode)) scanlines(v, frame, p);
        // SOLID / IMAGE: nothing to draw in the char layer
    }

    // ---- twinkling starfield ----------------------------------------------------

    private void stars(AsciiView v, long frame, AsciiTheme.Palette p) {
        int cols = v.cols(), rows = v.rows();
        if (mStarX == null || mStarsInited != cols * rows) {
            int n = Math.max(24, (cols * rows) / 90);
            mStarX = new int[n]; mStarY = new int[n];
            for (int i = 0; i < n; i++) {
                mStarX[i] = mRnd.nextInt(cols);
                mStarY[i] = mRnd.nextInt(rows);
            }
            mStarsInited = cols * rows;
        }
        int base = AsciiTheme.dimColor(p.accent, 0.22f, p.bg);
        int bright = AsciiTheme.dimColor(p.accent, 0.75f, p.bg);
        for (int i = 0; i < mStarX.length; i++) {
            // each star twinkles on its own clock
            long phase = (frame + i * 7) % 40;
            char ch = phase < 3 ? '+' : (phase < 6 ? '.' : '.');
            int col = (phase < 3 || phase > 36) ? bright : base;
            v.setCellChar(mStarX[i], mStarY[i], ch, col);
        }
    }

    // ---- matrix rain --------------------------------------------------------------

    private void rain(AsciiView v, long frame, AsciiTheme.Palette p) {
        int cols = v.cols(), rows = v.rows();
        if (mRainDrops == null || mRainDrops.length != cols) {
            mRainDrops = new int[cols];
            for (int i = 0; i < cols; i++) mRainDrops[i] = -mRnd.nextInt(rows * 2);
        }
        int tailDim = AsciiTheme.dimColor(p.fg, 0.10f, p.bg);
        int midDim = AsciiTheme.dimColor(p.fg, 0.18f, p.bg);
        int headDim = AsciiTheme.dimColor(p.accent, 0.34f, p.bg);
        for (int x = 0; x < cols; x++) {
            // advance drops every other frame (throttled speed)
            if (frame % 2 == 0) mRainDrops[x] += (x % 3 == 0 ? 1 : (x % 7 == 0 ? 0 : 1));
            int head = mRainDrops[x];
            if (head < 0) continue;
            for (int t = 0; t < 7; t++) {
                int y = head - t;
                if (y < 0 || y >= rows) continue;
                int col = t == 0 ? headDim : (t < 3 ? midDim : tailDim);
                char ch = RAIN_CHARS.charAt(
                        Math.abs((x * 31 + y * 17 + (int) (frame / 3)) * 7) % RAIN_CHARS.length());
                v.setCellChar(x, y, ch, col);
            }
            if (head >= rows + 8) mRainDrops[x] = -mRnd.nextInt(rows);
        }
    }

    // ---- rolling waves --------------------------------------------------------------

    private void waves(AsciiView v, long frame, AsciiTheme.Palette p) {
        int cols = v.cols(), rows = v.rows();
        float t = frame * 0.045f;
        int dim1 = AsciiTheme.dimColor(p.accent, 0.13f, p.bg);
        int dim2 = AsciiTheme.dimColor(p.fg, 0.07f, p.bg);
        for (int x = 0; x < cols; x++) {
            int y1 = (int) (rows * 0.5f + Math.sin(x * 0.22f + t) * (rows * 0.22f));
            int y2 = (int) (rows * 0.5f + Math.sin(x * 0.13f - t * 0.7f + 2f) * (rows * 0.30f));
            if (y1 >= 0 && y1 < rows) v.setCellChar(x, y1, '~', dim1);
            if (y2 >= 0 && y2 < rows) v.setCellChar(x, y2, '"', dim2);
        }
    }

    // ---- ASCII plasma ---------------------------------------------------------------

    private void plasma(AsciiView v, long frame, AsciiTheme.Palette p) {
        int cols = v.cols(), rows = v.rows();
        float t = frame * 0.05f;
        for (int y = 0; y < rows; y += 2) {
            for (int x = 0; x < cols; x += 2) {
                double v1 = Math.sin(x * 0.18f + t);
                double v2 = Math.sin(y * 0.23f - t * 0.6f);
                double v3 = Math.sin((x + y) * 0.11f + t * 0.4f);
                double val = (v1 + v2 + v3 + 3.0) / 6.0;  // 0..1
                if (val < 0.55) continue;                    // keep most of the screen calm
                int idx = (int) (Math.min(1.0, (val - 0.55) / 0.45) * (SHADE.length() - 1));
                char ch = SHADE.charAt(idx);
                int col = AsciiTheme.dimColor(p.accent, 0.06f + 0.16f * (float) val, p.bg);
                v.setCellChar(x, y, ch, col);
                v.setCellChar(x + 1, y, ch, col);
            }
        }
    }

    // ---- CRT scanlines ----------------------------------------------------------------

    private void scanlines(AsciiView v, long frame, AsciiTheme.Palette p) {
        int rows = v.rows(), cols = v.cols();
        // slow drift: a brighter band rolls down the screen
        int bandRow = (int) ((frame * 0.35f) % (rows + 8)) - 4;
        int dim = AsciiTheme.dimColor(p.fg, 0.05f, p.bg);
        int bandDim = AsciiTheme.dimColor(p.accent, 0.13f, p.bg);
        for (int y = 0; y < rows; y += 2) {
            char ch = (y == bandRow || y == bandRow + 2) ? '-' : '.';
            int col = (y == bandRow) ? bandDim : dim;
            for (int x = 0; x < cols; x += 3) v.setCellChar(x, y, ch, col);
        }
    }
}
