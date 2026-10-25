package com.astos.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The big accent mic button: ink border, hard shadow, square pixel rings
 * pulsing outward while listening, and a press that slides into its shadow.
 */
public class MicButtonView extends View {

    private boolean mListening = false;
    private boolean mPressed = false;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable mIcon;
    private long mT0 = System.currentTimeMillis();
    private ValueAnimator mPress;

    public MicButtonView(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
    }

    public void setListening(boolean on) {
        mListening = on;
        invalidate();
        if (on) mT0 = System.currentTimeMillis();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (mIcon == null) {
            mIcon = getResources().getDrawable(R.drawable.ic_mic);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPressed = true;
                animate().translationX(Ui.dp(getContext(), 4))
                         .translationY(Ui.dp(getContext(), 4))
                         .setDuration(60).start();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                boolean inside = e.getX() >= 0 && e.getX() <= getWidth()
                        && e.getY() >= 0 && e.getY() <= getHeight();
                mPressed = false;
                animate().translationX(0).translationY(0).setDuration(90).start();
                invalidate();
                if (inside) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mPressed = false;
                animate().translationX(0).translationY(0).setDuration(90).start();
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int s = Ui.dp(getContext(), 4);
        int b = Math.max(2, Ui.dp(getContext(), 2));

        // rings while listening
        if (mListening) {
            long t = (System.currentTimeMillis() - mT0) % 1200;
            for (int k = 0; k < 2; k++) {
                float p = ((t + k * 600) % 1200) / 1200f;
                float inset = -s - p * Ui.dp(getContext(), 22);
                int alpha = Math.round(200 * (1f - p));
                if (alpha <= 0) continue;
                int ir = Math.round(inset);
                mP.setColor(Ui.cur.accent);
                mP.setAlpha(alpha);
                c.drawRect(ir, ir, w - ir, h - ir, mP);
            }
            mP.setAlpha(255);
        }

        // hard shadow
        if (!mPressed) {
            mP.setColor(Ui.cur.shadow);
            c.drawRect(s, s, w, h, mP);
        }
        // body
        mP.setColor(Ui.cur.accent);
        int bw = w - s, bh = h - s;
        c.drawRect(0, 0, bw, bh, mP);
        // ink border
        mP.setColor(Ui.cur.ink);
        c.drawRect(0, 0, bw, b, mP);
        c.drawRect(0, bh - b, bw, bh, mP);
        c.drawRect(0, 0, b, bh, mP);
        c.drawRect(bw - b, 0, bw, bh, mP);
        // icon
        if (mIcon != null) {
            int isz = (int) (Math.min(bw, bh) * 0.42f);
            int ix = (bw - isz) / 2, iy = (bh - isz) / 2;
            mIcon.setBounds(ix, iy, ix + isz, iy + isz);
            if (mIcon instanceof android.graphics.drawable.VectorDrawable) {
                ((android.graphics.drawable.VectorDrawable) mIcon).setTint(Ui.cur.micink);
            } else {
                mIcon.setTintList(android.content.res.ColorStateList.valueOf(Ui.cur.micink));
            }
            mIcon.draw(c);
            mIcon.setTintList(null);
        }
    }
}
