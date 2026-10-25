package com.astos.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * The music EQ bars — 9 chunky accent bars with staggered steps()
 * animation while playing, squashed flat when paused.
 */
public class EqBars extends View {

    private static final int N = 9;
    private final float[] mPhase = new float[N];
    private final float[] mSpeed = new float[N];
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Random mRnd = new Random();
    private boolean mPlaying = false;
    private boolean mMini = false;
    private long mT0 = System.currentTimeMillis();

    private final Runnable mFrame = new Runnable() {
        @Override public void run() {
            if (mPlaying && isShown()) {
                invalidate();
                mHandler.postDelayed(this, 80);
            }
        }
    };

    public EqBars(Context c, AttributeSet a) {
        super(c, a);
        for (int i = 0; i < N; i++) {
            mPhase[i] = mRnd.nextFloat() * 10f;
            mSpeed[i] = 0.9f + mRnd.nextFloat() * 1.3f;
        }
    }

    public void setMini(boolean mini) { mMini = mini; }

    public void setPlaying(boolean on) {
        mPlaying = on;
        mHandler.removeCallbacks(mFrame);
        if (on) mHandler.postDelayed(mFrame, 40);
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mFrame);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int gap = Ui.dp(getContext(), 2);
        int bw = (w - gap * (N - 1)) / N;
        float t = (System.currentTimeMillis() - mT0) / 250f;
        mP.setColor(Ui.cur.accent);
        for (int i = 0; i < N; i++) {
            float v;
            if (mPlaying) {
                v = 0.15f + 0.85f * (Math.round(4 * (0.5f + 0.5f * Math.sin(t * mSpeed[i] + mPhase[i]))) / 4f);
            } else {
                v = 0.15f;
            }
            int bh = Math.max(Ui.dp(getContext(), 2), Math.round(h * v));
            int x = i * (bw + gap);
            c.drawRect(x, h - bh, x + bw, h, mP);
        }
    }
}
