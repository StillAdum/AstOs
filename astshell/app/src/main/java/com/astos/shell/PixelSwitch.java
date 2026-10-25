package com.astos.shell;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The pixel toggle: ink-bordered track, square knob that slides with a
 * stepped feel. ON = sage fill, OFF = warm off-white fill.
 */
public class PixelSwitch extends View {

    public interface On { void changed(PixelSwitch sw, boolean on); }

    private boolean mOn = false;
    private float mKnob = 0f; // 0 = left, 1 = right
    private On mListener;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator mAnim;

    public PixelSwitch(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
    }

    public void setOn(boolean on) {
        mOn = on;
        animateKnob(on ? 1f : 0f);
    }
    public boolean isOn() { return mOn; }
    public void setListener(On l) { mListener = l; }

    private void animateKnob(float to) {
        if (mAnim != null) mAnim.cancel();
        mAnim = ValueAnimator.ofFloat(mKnob, to);
        mAnim.setDuration(140);
        mAnim.setInterpolator(new LinearInterpolator());
        mAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                mKnob = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        mAnim.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP
                && isEnabled()
                && e.getX() >= 0 && e.getX() <= getWidth()
                && e.getY() >= 0 && e.getY() <= getHeight()) {
            mOn = !mOn;
            animateKnob(mOn ? 1f : 0f);
            performClick();
            if (mListener != null) mListener.changed(this, mOn);
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    @Override protected void onMeasure(int wms, int hms) {
        int w = Ui.dp(getContext(), 46);
        int h = Ui.dp(getContext(), 24);
        if (MeasureSpec.getMode(wms) == MeasureSpec.EXACTLY) w = MeasureSpec.getSize(wms);
        if (MeasureSpec.getMode(hms) == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(hms);
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int b = Math.max(2, Ui.dp(getContext(), 2));
        // track
        mP.setColor(mOn ? Ui.cur.on : Ui.cur.off);
        c.drawRect(0, 0, w, h, mP);
        mP.setColor(Ui.cur.ink);
        c.drawRect(0, 0, w, b, mP);
        c.drawRect(0, h - b, w, h, mP);
        c.drawRect(0, 0, b, h, mP);
        c.drawRect(w - b, 0, w, h, mP);
        // knob (square)
        int inset = b + Ui.dp(getContext(), 2);
        int kw = h - inset * 2;
        int kh = kw;
        int travel = w - inset * 2 - kw;
        int kx = (int) (inset + travel * mKnob);
        mP.setColor(Ui.cur.ink);
        c.drawRect(kx, inset, kx + kw, inset + kh, mP);
    }
}
