package com.astos.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The ASCII screen: a monospace character grid with per-cell colors, a
 * ~24 fps frame ticker for animations, optional bitmap background layer,
 * and cell-coordinate touch areas (ASCII buttons).
 *
 * Everything on the AstOs UI is drawn through this class.
 */
public class AsciiView extends View {

    /** Per-frame painter — draws background FX + page content each tick. */
    public interface Renderer {
        void onFrame(AsciiView v, long frame);
    }

    public static class Hit {
        public final int x, y, w, h;
        public final Runnable tap;
        public Hit(int x, int y, int w, int h, Runnable tap) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.tap = tap;
        }
    }

    private char[] mCells;
    private int[] mColors;
    private int mCols = 1, mRows = 1;
    private int mCellW = 10, mCellH = 18, mFontPx = 18;
    private float mOffX, mOffY;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBgPaint = new Paint();
    private int mBgColor = Color.BLACK;

    private final List<Hit> mHits = new ArrayList<Hit>();

    private Renderer mRenderer;
    private BackgroundFX mBgFx;
    private long mFrame = 0;
    private int mFps = 24;
    private boolean mRunning = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            mFrame++;
            if (mRenderer != null) {
                try { mRenderer.onFrame(AsciiView.this, mFrame); } catch (Exception ignored) {}
            }
            invalidate();
            if (mRunning) mHandler.postDelayed(this, 1000 / mFps);
        }
    };

    private Bitmap mBgImage;      // IMAGE background mode
    private float mBgImageDim = 0.55f;

    public AsciiView(Context c) {
        super(c);
        mPaint.setTypeface(Typeface.MONOSPACE);
        mBgPaint.setStyle(Paint.Style.FILL);
        setFontPx(18);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ---- config -------------------------------------------------------------

    public void setRenderer(Renderer r) { mRenderer = r; }
    public void setBackgroundFx(BackgroundFX fx) { mBgFx = fx; }
    public void setFps(int fps) { mFps = Math.max(8, Math.min(30, fps)); }
    public void setBgColor(int color) { mBgColor = color; }
    public void setBgImage(Bitmap b) { mBgImage = b; }

    public void start() {
        if (!mRunning) { mRunning = true; mHandler.post(mTick); }
    }

    public void stop() { mRunning = false; mHandler.removeCallbacks(mTick); }

    public int cols() { return mCols; }
    public int rows() { return mRows; }
    public long frame() { return mFrame; }
    public int cellW() { return mCellW; }
    public int cellH() { return mCellH; }
    public float offsetX() { return mOffX; }
    public float offsetY() { return mOffY; }

    public void setFontPx(int px) {
        mFontPx = px;
        mPaint.setTextSize(px);
        mCellW = (int) Math.ceil(mPaint.measureText("M"));
        mCellH = (int) Math.ceil(px * 1.22f);
        remeasure();
    }
    public int fontPx() { return mFontPx; }

    private void remeasure() {
        if (getWidth() > 0 && getHeight() > 0) {
            int cols = Math.max(1, getWidth() / mCellW);
            int rows = Math.max(1, getHeight() / mCellH);
            if (cols != mCols || rows != mRows) {
                mCols = cols; mRows = rows;
                mCells = new char[cols * rows];
                mColors = new int[cols * rows];
                clear();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        remeasure();
        // center the grid with half-cell margins for a balanced look
        mOffX = (w - mCols * mCellW) / 2f;
        mOffY = (h - mRows * mCellH) / 2f;
    }

    // ---- cell write API -------------------------------------------------------

    public void clear() {
        if (mCells == null) return;
        java.util.Arrays.fill(mCells, ' ');
        java.util.Arrays.fill(mColors, 0);
    }

    public void clearHits() { mHits.clear(); }

    public void hit(int x, int y, int w, int h, Runnable tap) {
        mHits.add(new Hit(x, y, w, h, tap));
    }

    private void setCell(int x, int y, char ch, int color) {
        if (x < 0 || y < 0 || x >= mCols || y >= mRows) return;
        int i = y * mCols + x;
        mCells[i] = ch;
        mColors[i] = color == 0 ? 0xFF808080 : color;
    }

    /** Write a string; returns the end x. */
    public int put(int x, int y, String s, int color) {
        if (s == null) return x;
        for (int i = 0; i < s.length(); i++) setCell(x + i, y, s.charAt(i), color);
        return x + s.length();
    }

    /** Write a string centered on cx. */
    public void putC(int cx, int y, String s, int color) {
        if (s == null) return;
        put(cx - s.length() / 2, y, s, color);
    }

    public void putC(int cx, int y, String[] lines, int color) {
        for (int i = 0; i < lines.length; i++) putC(cx, y + i, lines[i], color);
    }

    public void hline(int x, int y, int w, char ch, int color) {
        for (int i = 0; i < w; i++) setCell(x + i, y, ch, color);
    }

    public void vline(int x, int y, int h, char ch, int color) {
        for (int i = 0; i < h; i++) setCell(x, y + i, ch, color);
    }

    /** ASCII box with optional title. Corners use +, edges - and |. */
    public void box(int x, int y, int w, int h, int color, String title) {
        hline(x + 1, y, w - 2, '-', color);
        hline(x + 1, y + h - 1, w - 2, '-', color);
        vline(x, y + 1, h - 2, '|', color);
        vline(x + w - 1, y + 1, h - 2, '|', color);
        setCell(x, y, '+', color);
        setCell(x + w - 1, y, '+', color);
        setCell(x, y + h - 1, '+', color);
        setCell(x + w - 1, y + h - 1, '+', color);
        if (title != null && title.length() + 4 < w) {
            put(x + 2, y, "[ " + title + " ]", color);
        }
    }

    public void fillBox(int x, int y, int w, int h, int color, char ch) {
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++)
                setCell(x + c, y + r, ch, color);
    }

    public void clearBox(int x, int y, int w, int h) {
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++)
                setCell(x + c, y + r, ' ', 0xFF808080);
    }

    public char cell(int x, int y) {
        if (x < 0 || y < 0 || x >= mCols || y >= mRows) return ' ';
        return mCells[y * mCols + x];
    }

    public void setCellChar(int x, int y, char ch, int color) { setCell(x, y, ch, color); }

    /** Blank out everything to the background color (full repaint base). */
    public void wipe() {
        clear();
        clearHits();
    }

    // ---- input ----------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            return dispatchTap(ev);
        }
        return false;
    }

    /** Convert a tap's pixel coords to a cell and fire the matching hit area. */
    public boolean dispatchTap(MotionEvent ev) {
        int cx = (int) ((ev.getX() - mOffX) / mCellW);
        int cy = (int) ((ev.getY() - mOffY) / mCellH);
        for (Hit h : mHits) {
            if (cx >= h.x && cx < h.x + h.w && cy >= h.y && cy < h.y + h.h) {
                if (h.tap != null) h.tap.run();
                performClick();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    // ---- draw ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(mBgColor);
        if (mBgImage != null && mBgFx != null && "IMAGE".equals(mBgFx.mode())) {
            drawCoverImage(canvas);
        }
        if (mCells == null) return;

        float ox = mOffX;
        StringBuilder run = new StringBuilder();
        int runColor = 0, runStart = 0;

        for (int y = 0; y < mRows; y++) {
            run.setLength(0);
            runColor = 0;
            runStart = 0;
            float yy = mOffY + y * mCellH + mFontPx;
            for (int x = 0; x < mCols; x++) {
                int i = y * mCols + x;
                int col = mColors[i];
                char ch = mCells[i];
                if (col != runColor) {
                    if (run.length() > 0) {
                        mPaint.setColor(runColor == 0 ? 0xFF808080 : runColor);
                        canvas.drawText(run, 0, run.length(),
                                ox + runStart * mCellW, yy, mPaint);
                        run.setLength(0);
                    }
                    runColor = col;
                    runStart = x;
                }
                run.append(ch);
            }
            if (run.length() > 0) {
                mPaint.setColor(runColor == 0 ? 0xFF808080 : runColor);
                canvas.drawText(run, 0, run.length(), ox + runStart * mCellW, yy, mPaint);
            }
        }
    }

    private void drawCoverImage(Canvas canvas) {
        Bitmap b = mBgImage;
        if (b == null) return;
        float scale = Math.max((float) getWidth() / b.getWidth(),
                (float) getHeight() / b.getHeight());
        int w = (int) (b.getWidth() * scale);
        int h = (int) (b.getHeight() * scale);
        android.graphics.Rect dst = new android.graphics.Rect(
                (getWidth() - w) / 2, (getHeight() - h) / 2,
                (getWidth() + w) / 2, (getHeight() + h) / 2);
        mBgPaint.setAlpha((int) (255 * mBgImageDim));
        canvas.drawBitmap(b, null, dst, mBgPaint);
        mBgPaint.setAlpha(255);
    }
}
