package com.astos.shell;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

/** Renders the setup pairing URL as a QR code bitmap (ZXing core). */
public final class Qr {

    private Qr() {}

    /**
     * @param contents  what to encode (the http://<ip>:<port>/ pairing URL)
     * @param sizePx    square edge in pixels
     * @param fg        foreground (ink) argb
     * @param bg        background argb
     */
    public static Bitmap encode(String contents, int sizePx, int fg, int bg) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE,
                    sizePx, sizePx, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    px[off + x] = m.get(x, y) ? fg : bg;
                }
            }
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bm.setPixels(px, 0, w, 0, 0, w, h);
            return bm;
        } catch (Exception e) {
            return fallback(sizePx, fg, bg);
        }
    }

    /** Ugly-but-visible fallback if the encoder chokes. */
    private static Bitmap fallback(int sizePx, int fg, int bg) {
        Bitmap bm = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        int[] px = new int[sizePx * sizePx];
        for (int i = 0; i < sizePx * sizePx; i++) px[i] = bg;
        int pad = sizePx / 8;
        for (int i = pad; i < sizePx - pad; i++) {
            px[i * sizePx + pad] = fg;
            px[i * sizePx + sizePx - pad - 1] = fg;
            px[pad * sizePx + i] = fg;
            px[(sizePx - pad - 1) * sizePx + i] = fg;
        }
        bm.setPixels(px, 0, sizePx, 0, 0, sizePx, sizePx);
        return bm;
    }

    static int contrastInk(boolean dark) {
        return dark ? Color.rgb(0xF2, 0xE7, 0xDA) : Color.rgb(0x2E, 0x2A, 0x26);
    }
}
