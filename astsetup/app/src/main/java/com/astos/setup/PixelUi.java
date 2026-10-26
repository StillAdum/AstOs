package com.astos.setup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * PixelUi — the AstSetup canvas, restyled to the approved pixel mock:
 * square corner-tagged cards with 2dp ink borders and hard offset
 * shadows, VT323 display type + IBM Plex Mono body, dotted-paper
 * backdrop with drifting motes, chunky segmented progress bars.
 *
 * Rendering model (unchanged from v2.4): a Renderer callback is invoked
 * every frame; it re-registers hit regions each frame, so tap routing
 * stays trivial. The old rounded-warm look is gone — this is the pixel
 * language of the AstShell 3.0 home.
 */
public class PixelUi extends View {

    /** Per-frame drawing callback. */
    public interface Renderer {
        void onFrame(PixelUi ui, Canvas c, long tms);
    }

    /** Horizontal page swipe (dir -1 = left, +1 = right). */
    public interface SwipeListener {
        void onSwipe(int dir);
    }

    /** One registered tap region. */
    private static class Hit {
        final RectF rect;
        final Runnable run;
        Hit(RectF r, Runnable run) { this.rect = r; this.run = run; }
    }

    private Renderer mRenderer;
    private SwipeListener mSwiper;
    private final List<Hit> mHits = new ArrayList<Hit>();
    private boolean mRunning;
    private long mT0 = SystemClock.elapsedRealtime();
    private float mDensity;
    private float mUiScale = 1f;
    private AsciiTheme.Palette mPal = AsciiTheme.PALETTES[0];

    // gesture bookkeeping
    private float mDownX, mDownY;
    private boolean mSwiped;

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRect = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmp = new RectF();

    // tap-flash feedback: the last hit fired + when (for a 140ms overlay)
    private RectF mFlashRect;
    private long mFlashAt;

    // background FX
    private String mBgMode = "DOTS";
    private Bitmap mBgImage;
    private final Random mRand = new Random(41);
    private final float[] mStar = new float[64 * 4];    // x, y, phase, size
    private final float[] mDrop = new float[40 * 3];   // x, y, speed
    private final float[] mMote = new float[22 * 4];   // x, y, speed, phase

    // icon bitmap cache: name|size|palette-key -> bitmap
    private final HashMap<String, Bitmap> mIconCache = new HashMap<String, Bitmap>();

    // pixel fonts
    private Typeface mVt, mMono, mMonoB;

    public PixelUi(Context ctx) {
        super(ctx);
        mDensity = getResources().getDisplayMetrics().density;
        if (mDensity <= 0) mDensity = 1f;
        for (int i = 0; i < mStar.length; i += 4) {
            mStar[i] = mRand.nextFloat();
            mStar[i + 1] = mRand.nextFloat();
            mStar[i + 2] = mRand.nextFloat() * 6.2831f;
            mStar[i + 3] = 1 + mRand.nextInt(3);
        }
        for (int i = 0; i < mDrop.length; i += 3) {
            mDrop[i] = mRand.nextFloat();
            mDrop[i + 1] = mRand.nextFloat();
            mDrop[i + 2] = 0.55f + mRand.nextFloat() * 0.7f;
        }
        for (int i = 0; i < mMote.length; i += 4) {
            mMote[i] = mRand.nextFloat();
            mMote[i + 1] = mRand.nextFloat();
            mMote[i + 2] = 0.25f + mRand.nextFloat() * 0.8f;
            mMote[i + 3] = mRand.nextFloat() * 9000f;
        }
        try {
            mVt = Typeface.createFromAsset(ctx.getAssets(), "fonts/vt323.ttf");
            mMono = Typeface.createFromAsset(ctx.getAssets(), "fonts/plexmono.ttf");
            mMonoB = Typeface.createFromAsset(ctx.getAssets(), "fonts/plexmono-sb.ttf");
        } catch (Throwable t) {
            mVt = Typeface.MONOSPACE;
            mMono = Typeface.MONOSPACE;
            mMonoB = Typeface.DEFAULT_BOLD;
        }
    }

    // ---------------------------------------------------------------- setup

    public void setRenderer(Renderer r) { mRenderer = r; }

    public void setSwipeListener(SwipeListener l) { mSwiper = l; }

    public void setPalette(AsciiTheme.Palette p) {
        mPal = p;
        mIconCache.clear();
    }

    public AsciiTheme.Palette pal() { return mPal; }

    public void setUiScale(float s) { mUiScale = s; }

    public void setBgMode(String mode) { this.mBgMode = mode == null ? "DOTS" : mode; }

    public void setBgImage(Bitmap b) { mBgImage = b; }

    public void start() {
        mRunning = true;
        mT0 = SystemClock.elapsedRealtime();
        postInvalidateOnAnimation();
    }

    public void stop() { mRunning = false; }

    // ---------------------------------------------------------------- units

    /** dp -> px (with the user's UI scale applied). */
    public float dp(float v) { return v * mDensity * mUiScale; }

    /** raw dp -> px, no scale (for hit slop etc). */
    public float raw(float v) { return v * mDensity; }

    /** text size in sp -> px (with UI scale). */
    public float ts(float sp) { return sp * mDensity * mUiScale; }

    public float widthDp() { return getWidth() / mDensity; }

    public float heightDp() { return getHeight() / mDensity; }

    // ---------------------------------------------------------------- frame

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (mRenderer == null) return;
        long t = SystemClock.elapsedRealtime() - mT0;
        mHits.clear();
        c.drawColor(mPal.bg);
        try {
            mRenderer.onFrame(this, c, t);
        } catch (Throwable t2) {
            // one bad frame must never kill the wizard: paint the error faintly
            mText.setColor(0x66FF5A5A);
            mText.setTextSize(ts(12));
            mText.setTypeface(mMono);
            c.drawText("ui frame error: " + t2, dp(8), dp(16), mText);
        }
        // tap-flash overlay: proof the tap registered
        if (mFlashRect != null
                && SystemClock.elapsedRealtime() - mFlashAt < 140) {
            mRect.setColor(0x33F2B896);
            mRect.setStyle(Paint.Style.FILL);
            c.drawRoundRect(mFlashRect, dp(8), dp(8), mRect);
        } else {
            mFlashRect = null;
        }
        if (mRunning) postInvalidateOnAnimation();
    }

    // ---------------------------------------------------------------- hits

    /** Register a tap region for this frame.
     *
     *  v3.1 FIX: the arguments are (left, top, WIDTH, HEIGHT) — this is what
     *  every call site passes. The old (l, t, r, b) interpretation made
     *  every button after the welcome screen an inverted/dead rectangle:
     *  the "no phone? set up wifi on the tablet" pill, the local wifi list
     *  rows, RESCAN, SKIP WIFI, RETRY, CONTINUE and ENTER ASTOS all refused
     *  to fire ("i cant tap it, if i do nothing happens").
     */
    public void hit(float l, float t, float w, float h, Runnable run) {
        mHits.add(new Hit(new RectF(l, t, l + w, t + h), run));
    }

    public void hit(RectF r, Runnable run) {
        if (r != null && run != null) mHits.add(new Hit(r, run));
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int act = ev.getAction();
        if (act == MotionEvent.ACTION_DOWN) {
            mDownX = ev.getX();
            mDownY = ev.getY();
            mSwiped = false;
            return true;                       // consume, wait for UP
        }
        if (act == MotionEvent.ACTION_MOVE && !mSwiped) {
            float dx = ev.getX() - mDownX;
            float dy = ev.getY() - mDownY;
            if (Math.abs(dx) > raw(70) && Math.abs(dx) > 2.2f * Math.abs(dy)) {
                mSwiped = true;
                if (mSwiper != null) mSwiper.onSwipe(dx < 0 ? -1 : 1);
            }
            return true;
        }
        if (act != MotionEvent.ACTION_UP) return true;
        if (mSwiped) { performClick(); return true; }
        float x = ev.getX(), y = ev.getY();
        float slop = raw(14);
        // iterate backward so later (more specific) hits win
        for (int i = mHits.size() - 1; i >= 0; i--) {
            Hit h = mHits.get(i);
            if (x >= h.rect.left - slop && x <= h.rect.right + slop
                    && y >= h.rect.top - slop && y <= h.rect.bottom + slop) {
                mFlashRect = new RectF(h.rect);   // visual ack — see onDraw
                mFlashAt = SystemClock.elapsedRealtime();
                h.run.run();
                performClick();
                return true;
            }
        }
        performClick();
        return true;
    }

    // ---------------------------------------------------------------- shapes

    /**
     * Pixel card: panel fill, 2dp ink border, hard shadow offset
     * bottom-right. The radius/accentBorder params are kept for API
     * compatibility and interpreted in the pixel language.
     */
    public void card(Canvas c, float l, float t, float w, float h, float radius,
                     boolean accentBorder) {
        float sh = Math.min(dp(6), h * 0.3f);
        float b = Math.max(2f, dp(1f));
        // hard shadow
        mRect.setStyle(Paint.Style.FILL);
        mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.16f, mPal.bg));
        c.drawRect(l + sh, t + sh, l + w, t + h, mRect);
        // panel
        mRect.setColor(mPal.panel);
        c.drawRect(l, t, l + w - sh, t + h - sh, mRect);
        // ink border (accent when highlighted)
        mRect.setStyle(Paint.Style.STROKE);
        mRect.setStrokeWidth(b);
        mRect.setColor(accentBorder ? mPal.accent : mPal.fg);
        c.drawRect(l + b / 2f, t + b / 2f, l + w - sh - b / 2f, t + h - sh - b / 2f, mRect);
        mRect.setStyle(Paint.Style.FILL);
    }

    /**
     * Pixel pill (button): filled = accent with ink border + shadow,
     * hollow = panel with soft border. Radius ignored (square corners).
     */
    public void pill(Canvas c, float l, float t, float w, float h,
                     boolean filled, float radius) {
        float b = Math.max(2f, dp(1f));
        float sh = filled ? dp(3) : 0f;
        if (filled) {
            mRect.setStyle(Paint.Style.FILL);
            mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.16f, mPal.bg));
            c.drawRect(l + sh, t + sh, l + w, t + h, mRect);
        }
        mRect.setColor(filled ? mPal.accent : mPal.panel);
        c.drawRect(l, t, l + w - sh, t + h - sh, mRect);
        mRect.setStyle(Paint.Style.STROKE);
        mRect.setStrokeWidth(b);
        mRect.setColor(filled ? mPal.fg : AsciiTheme.dimColor(mPal.fg, 0.35f, mPal.bg));
        c.drawRect(l + b / 2f, t + b / 2f, l + w - sh - b / 2f, t + h - sh - b / 2f, mRect);
        mRect.setStyle(Paint.Style.FILL);
    }

    // ---------------------------------------------------------------- icons

    /** Draw an arbitrary bitmap (e.g. the pairing QR code) in dp units. */
    public void bitmap(Canvas c, Bitmap b, float left, float top, float sizeDp,
                       boolean crisp) {
        if (b == null || b.isRecycled()) return;
        float px = dp(sizeDp);
        mRect.setFilterBitmap(!crisp);
        c.drawBitmap(b, null, new RectF(left, top, left + px, top + px), mRect);
        mRect.setFilterBitmap(true);
    }

    public Bitmap icon(String name, float sizeDp) {
        int px = Math.max(8, (int) dp(sizeDp));
        String key = name + "|" + px + "|" + Integer.toHexString(mPal.fg)
                + Integer.toHexString(mPal.accent);
        Bitmap b = mIconCache.get(key);
        if (b == null || b.isRecycled()) {
            b = PixelIcon.render(name, px, mPal.fg, mPal.accent, mPal.dim);
            mIconCache.put(key, b);
        }
        return b;
    }

    /** Draw an icon centered in the given box. */
    public void icon(Canvas c, String name, float cx, float cy, float sizeDp) {
        Bitmap b = icon(name, sizeDp);
        float half = dp(sizeDp) / 2f;
        Paint p = mRect;
        p.setFilterBitmap(false);
        c.drawBitmap(b, cx - half, cy - half, p);
        p.setFilterBitmap(true);
    }

    public void iconAt(Canvas c, String name, float left, float top, float sizeDp) {
        Bitmap b = icon(name, sizeDp);
        mRect.setFilterBitmap(false);
        c.drawBitmap(b, left, top, mRect);
        mRect.setFilterBitmap(true);
    }

    // ---------------------------------------------------------------- text

    /** VT323 display text (big numbers, wordmarks — the mock's display face). */
    public float textVt(Canvas c, String s, float x, float y, float sizeSp, int color) {
        mText.setColor(color);
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(mVt);
        c.drawText(s, x, y, mText);
        return mText.measureText(s);
    }

    public float textCenteredVt(Canvas c, String s, float cx, float y, float sizeSp, int color) {
        mText.setColor(color);
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(mVt);
        float w = mText.measureText(s);
        c.drawText(s, cx - w / 2f, y, mText);
        return w;
    }

    /** Body text — IBM Plex Mono / SemiBold, tiny tracking when bold. */
    public float text(Canvas c, String s, float x, float y, float sizeSp,
                      int color, boolean bold, boolean mono) {
        mText.setColor(color);
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(bold ? mMonoB : mMono);
        c.drawText(s, x, y, mText);
        return mText.measureText(s);
    }

    public float textCentered(Canvas c, String s, float cx, float y, float sizeSp,
                               int color, boolean bold, boolean mono) {
        mText.setColor(color);
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(bold ? mMonoB : mMono);
        float w = mText.measureText(s);
        c.drawText(s, cx - w / 2f, y, mText);
        return w;
    }

    public float textWidth(String s, float sizeSp, boolean mono) {
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(mMonoB);
        return mText.measureText(s == null ? "" : s);
    }

    /** Wrap to a width given in dp; returns at most {@code maxLines}. */
    public List<String> wrap(String s, float sizeSp, float maxWidthDp, int maxLines) {
        float maxWidthPx = dp(maxWidthDp);
        List<String> out = new ArrayList<String>();
        if (s == null || s.isEmpty()) return out;
        for (String para : s.split("\n")) {
            String cur = para;
            while (!cur.isEmpty()) {
                int cut = 0;
                while (cut < cur.length()) {
                    float w = textWidth(cur.substring(0, cut + 1), sizeSp, false);
                    if (w > maxWidthPx) break;
                    cut++;
                }
                if (cut == 0) cut = 1;
                int lastSpace = cur.lastIndexOf(' ', cut);
                if (lastSpace > 0) cut = lastSpace;
                out.add(cur.substring(0, cut).trim());
                if (out.size() >= maxLines) return out;
                cur = cur.substring(cut).trim();
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- bg FX

    /** Background FX; the pixel default is the dotted paper + motes. */
    public void drawBackground(Canvas c, long tms) {
        String mode = mBgMode;
        int w = getWidth(), h = getHeight();
        if ("IMAGE".equals(mode) && mBgImage != null) {
            Paint p = mRect;
            p.setFilterBitmap(true);
            float iw = mBgImage.getWidth(), ih = mBgImage.getHeight();
            float scale = Math.max(w / iw, h / ih);
            c.drawBitmap(mBgImage, (w - iw * scale) / 2f, (h - ih * scale) / 2f, p);
            mRect.setColor((150 << 24) | (mPal.bg & 0xFFFFFF));
            c.drawRect(0, 0, w, h, mRect);
            return;
        }
        if ("DOTS".equals(mode)) {
            c.drawColor(mPal.bg);
            // paper dots
            float g = raw(20);
            mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.20f, mPal.bg));
            for (float y = g / 2f; y < h; y += g)
                for (float x = g / 2f; x < w; x += g)
                    c.drawRect(x - 1, y - 1, x + 1, y + 1, mRect);
            // drifting motes
            for (int i = 0; i < mMote.length; i += 4) {
                float speed = mMote[i + 2];
                float ph = mMote[i + 3];
                float x = (float) ((mMote[i] + Math.sin((tms + ph) / 4200f) * 0.02) * w);
                float y = (float) ((((mMote[i + 1] + tms / 30000f * speed) % 1.1f) * h) - h * 0.05f);
                boolean big = (ph % 7) < 1;
                int s = big ? (int) raw(6) : (int) raw(3);
                mRect.setColor((ph % 3) < 1
                        ? AsciiTheme.dimColor(mPal.accent, 0.45f, mPal.bg)
                        : AsciiTheme.dimColor(mPal.fg, 0.25f, mPal.bg));
                c.drawRect(x, y, x + s, y + s, mRect);
            }
            return;
        }
        if ("SOLID".equals(mode)) {
            int top = AsciiTheme.dimColor(mPal.fg, 0.03f, mPal.bg);
            LinearGradient lg = new LinearGradient(0, 0, 0, h, top, mPal.bg,
                    Shader.TileMode.CLAMP);
            mRect.setShader(lg);
            c.drawRect(0, 0, w, h, mRect);
            mRect.setShader(null);
            return;
        }
        // base for all legacy FX modes
        c.drawColor(mPal.bg);
        if ("STARS".equals(mode)) {
            for (int i = 0; i < mStar.length; i += 4) {
                float tw = 0.45f + 0.55f * (float) Math.sin(tms / 900.0
                        + mStar[i + 2]);
                if (tw <= 0.25f) continue;
                float x = mStar[i] * w;
                float y = mStar[i + 1] * h;
                float s = mStar[i + 3] * raw(1);
                int col = AsciiTheme.dimColor(mPal.fg, 0.16f * tw, mPal.bg);
                mRect.setColor(col);
                c.drawRect(x, y, x + s, y + s, mRect);
            }
        } else if ("RAIN".equals(mode)) {
            for (int i = 0; i < mDrop.length; i += 3) {
                float speed = mDrop[i + 2];
                float x = mDrop[i] * w;
                float y = (mDrop[i + 1] + tms / 1400f * speed) % 1.2f * h - h * 0.1f;
                mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.08f, mPal.bg));
                c.drawRect(x, y, x + raw(1), y + raw(16) * speed, mRect);
            }
        } else if ("SCANLINES".equals(mode)) {
            float roll = (tms / 40f) % raw(6);
            for (float y = -raw(6) + roll; y < h; y += raw(6)) {
                mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.035f, mPal.bg));
                c.drawRect(0, y, w, y + raw(2), mRect);
            }
        }
        // WAVES / PLASMA legacy modes render as DOTS-style calm paper now
    }

    // ---------------------------------------------------------------- widgets

    /** Chunky segmented progress bar (the mock's .seg). frac in 0..1. */
    public void progressBar(Canvas c, float l, float t, float w, float h, float frac,
                             boolean accent) {
        int n = 24;
        float gap = Math.max(2f, raw(2));
        float bw = (w - gap * (n - 1)) / n;
        int fill = Math.round(Math.max(0f, Math.min(1f, frac)) * n);
        for (int i = 0; i < n; i++) {
            mRect.setColor(i < fill
                    ? (accent ? mPal.accent : mPal.fg)
                    : AsciiTheme.dimColor(mPal.fg, 0.10f, mPal.bg));
            float x = l + i * (bw + gap);
            c.drawRect(x, t, x + bw, t + h, mRect);
        }
    }

    /** Spinner (rotating square outline — pixel take on a spinner). */
    public void spinner(Canvas c, float cx, float cy, float radius, long tms,
                         int color) {
        int steps = 8;
        for (int i = 0; i < steps; i++) {
            float ang = (tms / 90f) + i * (6.2832f / steps);
            float x = cx + (float) Math.cos(ang) * radius;
            float y = cy + (float) Math.sin(ang) * radius;
            int s = (int) raw(3);
            mRect.setColor(i == 0 ? color
                    : AsciiTheme.dimColor(color, 0.15f + 0.06f * (steps - i), mPal.bg));
            c.drawRect(x - s, y - s, x + s, y + s, mRect);
        }
    }

    /** Equalizer bars (music playing). */
    public void equalizer(Canvas c, float l, float bottom, float w, float h,
                           boolean playing, long tms, int[] state) {
        int n = state.length;
        float bw = w / n;
        for (int i = 0; i < n; i++) {
            float target = playing
                    ? 0.25f + 0.6f * (0.5f + 0.5f * (float) Math.sin(tms / 130.0 + i * 0.9))
                    : 0.12f;
            float cur = state[i] / 100f;
            cur += (target - cur) * 0.25f;
            state[i] = Math.round(cur * 100);
            float bh = Math.max(raw(2), cur * h);
            mRect.setColor(mPal.accent);
            c.drawRect(l + i * bw + bw * 0.12f, bottom - bh,
                    l + (i + 1) * bw - bw * 0.12f, bottom, mRect);
        }
    }
}
