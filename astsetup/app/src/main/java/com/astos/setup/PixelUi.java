package com.astos.setup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
 * PixelUi — the AstOs 2.4 shell canvas.
 *
 * Replaces the old character-cell AsciiView home renderer. The user asked
 * for a UI that is "pixel kinda" (not ASCII), animations that are NOT
 * ASCII art (except the boot animation), and layouts that actually scale
 * to the screen. This view:
 *
 *   - lays everything out in dp (density-aware) from the measured view
 *     size, so nothing is ever out of frame
 *   - draws chunky pixel-art icons (see PixelIcon) — crisp, unfiltered
 *   - animates smoothly with sine math (pulses, slides, spinners,
 *     equalizer bars) — proper graphics, not character art
 *   - keeps the warm no-blue identity from AsciiTheme
 *
 * Rendering model: a Renderer callback is invoked every frame (~30fps);
 * it re-registers hit regions each frame, exactly like the old AsciiView,
 * so tap routing stays trivial.
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
    private float mUiScale = 1f;          // SMALL/LARGE user preference
    private AsciiTheme.Palette mPal = AsciiTheme.PALETTES[0];

    // gesture bookkeeping
    private float mDownX, mDownY;
    private boolean mSwiped;

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRect = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmp = new RectF();

    // background FX
    private String mBgMode = "SOLID";
    private Bitmap mBgImage;
    private final Random mRand = new Random(41);
    private final float[] mStar = new float[64 * 4];    // x, y, phase, size
    private final float[] mDrop = new float[40 * 3];   // x, y, speed

    // icon bitmap cache: name|size|palette-key -> bitmap
    private final HashMap<String, Bitmap> mIconCache = new HashMap<String, Bitmap>();

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

    public void setBgMode(String mode) { this.mBgMode = mode == null ? "SOLID" : mode; }

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
            // one bad frame must never kill the shell: paint the error faintly
            mText.setColor(0x66FF5A5A);
            mText.setTextSize(ts(12));
            c.drawText("ui frame error: " + t2, dp(8), dp(16), mText);
        }
        if (mRunning) postInvalidateOnAnimation();
    }

    // ---------------------------------------------------------------- hits

    /** Register a tap region for this frame. */
    public void hit(RectF r, Runnable run) {
        if (r != null && run != null) mHits.add(new Hit(r, run));
    }

    public void hit(float l, float t, float r, float b, Runnable run) {
        mHits.add(new Hit(new RectF(l, t, r, b), run));
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
                h.run.run();
                performClick();
                return true;
            }
        }
        performClick();
        return true;
    }

    // ---------------------------------------------------------------- shapes

    /** Warm card: rounded rect + pixel-ish double border. */
    public void card(Canvas c, float l, float t, float w, float h, float radius,
                     boolean accentBorder) {
        mTmp.set(l, t, l + w, t + h);
        mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.05f, mPal.bg));
        mRect.setStyle(Paint.Style.FILL);
        c.drawRoundRect(mTmp, radius, radius, mRect);
        mRect.setStyle(Paint.Style.STROKE);
        mRect.setStrokeWidth(Math.max(2f, dp(0.7f)));
        mRect.setColor(accentBorder ? mPal.accent
                : AsciiTheme.dimColor(mPal.fg, 0.22f, mPal.bg));
        c.drawRoundRect(mTmp, radius, radius, mRect);
        mRect.setStyle(Paint.Style.FILL);
    }

    /** Filled rounded button/pill. */
    public void pill(Canvas c, float l, float t, float w, float h,
                     boolean filled, float radius) {
        mTmp.set(l, t, l + w, t + h);
        if (filled) {
            mRect.setColor(mPal.accent);
            mRect.setStyle(Paint.Style.FILL);
            c.drawRoundRect(mTmp, radius, radius, mRect);
        } else {
            mRect.setStyle(Paint.Style.FILL);
            mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.08f, mPal.bg));
            c.drawRoundRect(mTmp, radius, radius, mRect);
            mRect.setStyle(Paint.Style.STROKE);
            mRect.setStrokeWidth(Math.max(2f, dp(0.7f)));
            mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.3f, mPal.bg));
            c.drawRoundRect(mTmp, radius, radius, mRect);
            mRect.setStyle(Paint.Style.FILL);
        }
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

    public float text(Canvas c, String s, float x, float y, float sizeSp,
                      int color, boolean bold, boolean mono) {
        mText.setColor(color);
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(mono ? Typeface.MONOSPACE
                : (bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT));
        c.drawText(s, x, y, mText);
        return mText.measureText(s);
    }

    public float textCentered(Canvas c, String s, float cx, float y, float sizeSp,
                               int color, boolean bold, boolean mono) {
        mText.setColor(color);
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(mono ? Typeface.MONOSPACE
                : (bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT));
        float w = mText.measureText(s);
        c.drawText(s, cx - w / 2f, y, mText);
        return w;
    }

    public float textWidth(String s, float sizeSp, boolean mono) {
        mText.setTextSize(ts(sizeSp));
        mText.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT_BOLD);
        return mText.measureText(s == null ? "" : s);
    }

    /** Wrap to a width given in dp; returns at most {@code maxLines}.
     *  (Callers pass dp; converted to px here so it is density-correct.) */
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

    /** Smooth (non-ASCII) background FX, drawn behind everything each frame. */
    public void drawBackground(Canvas c, long tms) {
        String mode = mBgMode;
        int w = getWidth(), h = getHeight();
        if ("IMAGE".equals(mode) && mBgImage != null) {
            Paint p = mRect;
            p.setFilterBitmap(true);
            float iw = mBgImage.getWidth(), ih = mBgImage.getHeight();
            float scale = Math.max(w / iw, h / ih);
            c.drawBitmap(mBgImage, (w - iw * scale) / 2f, (h - ih * scale) / 2f, p);
            mRect.setColor(Color.argb(150, Color.red(mPal.bg), Color.green(mPal.bg),
                    Color.blue(mPal.bg)));
            c.drawRect(0, 0, w, h, mRect);
            return;
        }
        if ("SOLID".equals(mode)) {
            // gentle vertical warm gradient — barely-there depth
            int top = AsciiTheme.dimColor(mPal.fg, 0.03f, mPal.bg);
            LinearGradient lg = new LinearGradient(0, 0, 0, h, top, mPal.bg,
                    Shader.TileMode.CLAMP);
            mRect.setShader(lg);
            c.drawRect(0, 0, w, h, mRect);
            mRect.setShader(null);
            return;
        }
        // base for all FX modes
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
                if (s > 1.5f) { // little cross sparkle on the big ones
                    mRect.setColor(AsciiTheme.dimColor(mPal.accent, 0.10f * tw, mPal.bg));
                    c.drawRect(x - s, y, x + 2 * s, y + s * 0.35f, mRect);
                    c.drawRect(x, y - s, x + s * 0.35f, y + 2 * s, mRect);
                }
            }
        } else if ("RAIN".equals(mode)) {
            for (int i = 0; i < mDrop.length; i += 3) {
                float speed = mDrop[i + 2];
                float x = mDrop[i] * w;
                float y = (mDrop[i + 1] + tms / 1400f * speed) % 1.2f * h - h * 0.1f;
                mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.08f, mPal.bg));
                c.drawRect(x, y, x + raw(1), y + raw(16) * speed, mRect);
            }
        } else if ("WAVES".equals(mode)) {
            for (int band = 0; band < 3; band++) {
                float baseY = h * (0.55f + band * 0.16f);
                int col = AsciiTheme.dimColor(mPal.fg, 0.045f - band * 0.012f, mPal.bg);
                mRect.setColor(col);
                Path path = new Path();
                path.moveTo(0, h);
                for (int x = 0; x <= w; x += raw(16)) {
                    float y = baseY + (float) Math.sin(x / (170f + band * 60f)
                            + tms / 1600.0 + band * 1.7f) * raw(14);
                    path.lineTo(x, y);
                }
                path.lineTo(w, h);
                path.close();
                c.drawPath(path, mRect);
            }
        } else if ("PLASMA".equals(mode)) {
            for (int blob = 0; blob < 3; blob++) {
                float ph = tms / 2600f + blob * 2.1f;
                float x = w * (0.5f + 0.32f * (float) Math.sin(ph));
                float y = h * (0.5f + 0.34f * (float) Math.cos(ph * 0.8f + blob));
                float r = Math.max(w, h) * 0.5f;
                int edge = AsciiTheme.dimColor(mPal.fg, 0.0f, mPal.bg);
                int core = AsciiTheme.dimColor(mPal.accent, blob == 1 ? 0.10f : 0.06f, mPal.bg);
                RadialGradient rg = new RadialGradient(x, y, r, core, edge,
                        Shader.TileMode.CLAMP);
                mRect.setShader(rg);
                c.drawRect(0, 0, w, h, mRect);
                mRect.setShader(null);
            }
        } else if ("SCANLINES".equals(mode)) {
            float roll = (tms / 40f) % raw(6);
            for (float y = -raw(6) + roll; y < h; y += raw(6)) {
                mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.035f, mPal.bg));
                c.drawRect(0, y, w, y + raw(2), mRect);
            }
            float bandY = (tms / 24f) % (h + 220) - 110;
            LinearGradient lg = new LinearGradient(0, bandY - 100, 0, bandY + 100,
                    AsciiTheme.dimColor(mPal.fg, 0.0f, mPal.bg),
                    AsciiTheme.dimColor(mPal.fg, 0.05f, mPal.bg),
                    Shader.TileMode.CLAMP);
            mRect.setShader(lg);
            c.drawRect(0, bandY - 100, w, bandY + 100, mRect);
            mRect.setShader(null);
        }
    }

    // ---------------------------------------------------------------- widgets

    /** Smooth rounded progress bar. frac in 0..1. */
    public void progressBar(Canvas c, float l, float t, float w, float h, float frac,
                             boolean accent) {
        mTmp.set(l, t, l + w, t + h);
        mRect.setColor(AsciiTheme.dimColor(mPal.fg, 0.10f, mPal.bg));
        c.drawRoundRect(mTmp, h / 2f, h / 2f, mRect);
        float fw = Math.min(1f, Math.max(0f, frac)) * w;
        if (fw > h) {
            mTmp.set(l, t, l + fw, t + h);
            mRect.setColor(accent ? mPal.accent : mPal.fg);
            c.drawRoundRect(mTmp, h / 2f, h / 2f, mRect);
        }
    }

    /** Smooth spinner arc (NOT ascii art). */
    public void spinner(Canvas c, float cx, float cy, float radius, long tms,
                         int color) {
        mRect.setStyle(Paint.Style.STROKE);
        mRect.setStrokeWidth(Math.max(3f, dp(1.4f)));
        mRect.setColor(AsciiTheme.dimColor(color, 0.18f, mPal.bg));
        c.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                0, 360, false, mRect);
        float sweep = 90 + 40 * (float) Math.sin(tms / 300.0);
        float start = (tms / 5.0f) % 360;
        mRect.setColor(color);
        c.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                start, sweep, false, mRect);
        mRect.setStyle(Paint.Style.FILL);
    }

    /** Big mic orb with pulse rings while listening — smooth sine animation. */
    public void micOrb(Canvas c, float cx, float cy, float radius, boolean listening,
                        long tms) {
        if (listening) {
            for (int i = 0; i < 3; i++) {
                float ph = ((tms / 1100.0f) + i / 3f) % 1f;
                float r = radius * (1f + ph * 0.85f);
                int col = AsciiTheme.dimColor(mPal.accent,
                        0.35f * (1f - ph), mPal.bg);
                mRect.setColor(col);
                mRect.setStyle(Paint.Style.STROKE);
                mRect.setStrokeWidth(Math.max(2f, dp(0.8f)));
                c.drawCircle(cx, cy, r, mRect);
                mRect.setStyle(Paint.Style.FILL);
            }
        }
        // orb body: soft radial warm glow + solid accent disc
        RadialGradient rg = new RadialGradient(cx, cy - radius * 0.25f, radius * 1.6f,
                AsciiTheme.dimColor(mPal.accent, 0.5f, mPal.bg),
                AsciiTheme.dimColor(mPal.accent, 0.0f, mPal.bg),
                Shader.TileMode.CLAMP);
        mRect.setShader(rg);
        c.drawCircle(cx, cy, radius * 1.6f, mRect);
        mRect.setShader(null);
        mRect.setColor(mPal.accent);
        c.drawCircle(cx, cy, radius, mRect);
        icon(c, PixelIcon.MIC, cx, cy, radius * 0.66f);
    }

    /** Smooth equalizer bars (music playing). Pure eye candy, not ascii. */
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
            mRect.setColor(i % 3 == 2
                    ? AsciiTheme.dimColor(mPal.accent, 0.8f, mPal.bg) : mPal.accent);
            c.drawRect(l + i * bw + bw * 0.12f, bottom - bh,
                    l + (i + 1) * bw - bw * 0.12f, bottom, mRect);
        }
    }
}
