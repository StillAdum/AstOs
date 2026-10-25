package com.astos.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 8x8 mirrored pixel album art, deterministic per track name —
 * the same idea as the mock's drawArt() canvas.
 */
public class PixelAlbumArt extends View {

    private static final int N = 8;
    private final boolean[][] mCells = new boolean[N][N];
    private final int[] mPalette = new int[3];
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random mRnd = new Random();

    public PixelAlbumArt(Context c, AttributeSet a) {
        super(c, a);
        seed("astos");
    }

    public void seed(String name) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) h = 31 * h + name.charAt(i);
        mRnd.setSeed(h);
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N / 2; x++)
                mCells[y][x] = mRnd.nextInt(100) < 46;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        mPalette[0] = Ui.cur.off;
        mPalette[1] = Ui.cur.accent;
        mPalette[2] = Ui.cur.ink;
        long h = 1;
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if (mCells[y][x]) h = h * 31 + y * 7 + x;
        mRnd.setSeed(h ^ 0x5EED);
        int w = getWidth(), ht = getHeight();
        if (w <= 0 || ht <= 0) return;
        float cw = w / (float) N, ch = ht / (float) N;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N / 2; x++) {
                if (!mCells[y][x]) continue;
                int col = mPalette[mRnd.nextInt(100) < 60 ? 0 : (mRnd.nextInt(100) < 60 ? 1 : 2)];
                mP.setColor(col);
                c.drawRect(x * cw, y * ch, x * cw + cw, y * ch + ch, mP);
                int mx = N - 1 - x;
                c.drawRect(mx * cw, y * ch, mx * cw + cw, y * ch + ch, mP);
            }
        }
    }
}
