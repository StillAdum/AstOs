package com.astos.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * The chunky segmented bar (progress / volume / mic sensitivity).
 * Filled segments are accent, empty ones off. Tapping a segment fires
 * onLevel (1-based) — like clicking the HTML .seg spans.
 */
public class SegBar extends View {

    public interface L { void onLevel(SegBar bar, int level); }

    private int mCount = 12;
    private int mLevel = 0;
    private boolean mInteractive = true;
    private L mListener;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SegBar(Context c, AttributeSet a) {
        super(c, a);
        setClickable(mInteractive);
    }

    public void init(int count, boolean interactive, L listener) {
        mCount = count;
        mInteractive = interactive;
        mListener = listener;
        setClickable(interactive);
        invalidate();
    }

    public void setLevel(int level) {
        mLevel = Math.max(0, Math.min(mCount, level));
        invalidate();
    }
    public int getLevel() { return mLevel; }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int gap = Ui.dp(getContext(), 2);
        int bw = (w - gap * (mCount - 1)) / mCount;
        for (int i = 0; i < mCount; i++) {
            mP.setColor(i < mLevel ? Ui.cur.accent : Ui.cur.off);
            int x = i * (bw + gap);
            c.drawRect(x, 0, x + bw, h, mP);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!mInteractive) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                || e.getActionMasked() == MotionEvent.ACTION_UP) {
            float w = getWidth();
            float per = w / mCount;
            int idx = (int) (e.getX() / per);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int lvl = Math.max(1, Math.min(mCount, idx + 1));
                Ui.press(this, 0); // no-op keeps style consistent
                if (mListener != null) mListener.onLevel(this, lvl);
                return true;
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onMeasure(int wms, int hms) {
        int h = Ui.dp(getContext(), 14);
        if (MeasureSpec.getMode(hms) == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(hms);
        else if (MeasureSpec.getMode(hms) == MeasureSpec.AT_MOST) h = Math.min(h, MeasureSpec.getSize(hms));
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), wms), h);
    }
}
