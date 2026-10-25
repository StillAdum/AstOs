package com.astos.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The dotted-paper backdrop with slow drifting pixel motes —
 * the canvas layer of the pixel design. Reads colors from Ui.cur so
 * it re-themes on the next frame automatically.
 */
public class PixelBackdrop extends View {

    private static final int GRID = 20;   // dp between paper dots
    private static final int MOTES = 22;

    private static class Mote {
        float x, y, k, ph;
        int layer; // 0 soft, 1 accent
    }

    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMote = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Mote> mMotes = new ArrayList<Mote>();
    private final Random mRnd = new Random();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mStart = 0;
    private boolean mAnimating = true;
    private int mLastW = -1, mLastH = -1;

    private final Runnable mFrame = new Runnable() {
        @Override public void run() {
            if (mAnimating && isAttachedToWindowCompat()) {
                long t = System.currentTimeMillis() - mStart;
                for (Mote m : mMotes) {
                    m.y += m.k * (t % 64) / 64f * 0.35f;
                    m.x += Math.sin((t + m.ph) / 4200f) * 0.22f;
                    if (m.y > getHeight() + 8) { m.y = -8; m.x = mRnd.nextFloat() * getWidth(); }
                    if (m.x < -8) m.x = getWidth() + 8;
                    if (m.x > getWidth() + 8) m.x = -8;
                }
                mStart = System.currentTimeMillis();
                invalidate();
                mHandler.postDelayed(this, 64);
            }
        }
    };

    public PixelBackdrop(Context c, AttributeSet a) {
        super(c, a);
        seed();
    }

    public void setAnimating(boolean on) {
        mAnimating = on;
        if (on) start();
        else mHandler.removeCallbacks(mFrame);
        invalidate();
    }

    private void seed() {
        mMotes.clear();
        for (int i = 0; i < MOTES; i++) {
            Mote m = new Mote();
            m.x = mRnd.nextFloat();
            m.y = mRnd.nextFloat();
            m.k = 0.25f + mRnd.nextFloat() * 0.9f;
            m.ph = mRnd.nextFloat() * 9000f;
            m.layer = mRnd.nextInt(3) == 0 ? 1 : 0;
            mMotes.add(m);
        }
    }

    private boolean isAttachedToWindowCompat() {
        return getWindowToken() != null;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStart = System.currentTimeMillis();
        if (mAnimating) start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mFrame);
    }

    private void start() {
        mHandler.removeCallbacks(mFrame);
        if (isAttachedToWindowCompat()) mHandler.postDelayed(mFrame, 32);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w != mLastW || h != mLastH) {
            mLastW = w; mLastH = h;
            for (Mote m : mMotes) {
                if (m.x <= 1f) m.x *= w;   // first layout: normalize 0..1 -> px
                if (m.y <= 1f) m.y *= h;
            }
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // paper dots
        mDot.setColor(Ui.cur.shadow);
        int g = Ui.dp(getContext(), GRID);
        for (int y = g / 2; y < h; y += g)
            for (int x = g / 2; x < w; x += g)
                c.drawRect(x - 1, y - 1, x + 1, y + 1, mDot);

        // motes
        int s = Ui.dp(getContext(), 3);
        int big = s * 2;
        for (Mote m : mMotes) {
            mMote.setColor(m.layer == 1 ? Ui.cur.accent : Ui.cur.soft);
            int sz = (m.ph % 7) < 1 ? big : s;
            c.drawRect(m.x, m.y, m.x + sz, m.y + sz, mMote);
        }
    }
}
