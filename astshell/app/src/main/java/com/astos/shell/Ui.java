package com.astos.shell;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pixel-retro design engine: themes, fonts, drawable factory,
 * press feedback and the kiosk status-bar lock.
 *
 * XML layout files carry the dune defaults; runtime theming walks the
 * tree once per activity (and on theme change) applying colors by tag:
 *   android:tag="d:card t:soft f:mono i:accent"
 * where d: = background drawable, t: = text color, f: = font, i: = icon tint.
 */
public final class Ui {

    // ------------------------------------------------------------ themes
    public static final class Th {
        public final String key, label;
        public final int bg, panel, ink, soft, accent, on, off, shadow, micink;
        Th(String k, String l, int bg, int p, int i, int s, int a, int o, int f, int sh, int mi) {
            key = k; label = l; this.bg = bg; panel = p; ink = i; soft = s; accent = a;
            this.on = o; this.off = f; shadow = sh; micink = mi;
        }
    }

    public static final Map<String, Th> THEMES = new LinkedHashMap<String, Th>();
    static {
        THEMES.put("dune",   new Th("dune",   "Dune",   0xFFEFE7D8, 0xFFF8F2E4, 0xFF2A241C, 0xFF9A8D78, 0xFFC4742A, 0xFFCFDCA6, 0xFFDFD3BD, 0xFFD9CCB2, 0xFFF8F2E4));
        THEMES.put("forest", new Th("forest", "Forest", 0xFFE6EAD8, 0xFFF2F4E6, 0xFF1F2819, 0xFF7C8C6A, 0xFF8A5A22, 0xFFBCD69A, 0xFFD3DBC0, 0xFFCBD4B4, 0xFFF2F4E6));
        THEMES.put("rust",   new Th("rust",   "Rust",   0xFFF0E2D6, 0xFFF9EEE4, 0xFF2B1C15, 0xFF957462, 0xFFB9481F, 0xFFF0C9A0, 0xFFE2CDBB, 0xFFE0C6B0, 0xFFF9EEE4));
        THEMES.put("mono",   new Th("mono",   "Mono",   0xFFE9E8E3, 0xFFF6F5F0, 0xFF1C1C1C, 0xFF8C8C88, 0xFF2E2E2E, 0xFFC9C9C2, 0xFFDDDCD6, 0xFFD3D2CC, 0xFFF6F5F0));
        THEMES.put("ember",  new Th("ember",  "Ember",  0xFF15110D, 0xFF1E1812, 0xFFEFE2CB, 0xFF8A7B62, 0xFFE39A48, 0xFF4A421F, 0xFF2B241B, 0xFF0D0A07, 0xFF15110D));
        THEMES.put("pine",   new Th("pine",   "Pine",   0xFF101510, 0xFF182118, 0xFFE3EAD6, 0xFF7D8D72, 0xFFD2A94F, 0xFF2F4A28, 0xFF223022, 0xFF0A0E0A, 0xFF101510));
    }

    public static Th cur = THEMES.get("dune");
    private static String sAppliedKey = "dune";
    public static String appliedKey() { return sAppliedKey; }

    // ------------------------------------------------------------ fonts
    private static Typeface sVt, sMono, sMonoB;

    public static Typeface vt(Context c) {
        if (sVt == null) {
            try { sVt = Typeface.createFromAsset(c.getAssets(), "fonts/vt323.ttf"); }
            catch (Exception e) { sVt = Typeface.MONOSPACE; }
        }
        return sVt;
    }
    public static Typeface mono(Context c) {
        if (sMono == null) {
            try { sMono = Typeface.createFromAsset(c.getAssets(), "fonts/plexmono.ttf"); }
            catch (Exception e) { sMono = Typeface.MONOSPACE; }
        }
        return sMono;
    }
    public static Typeface monoB(Context c) {
        if (sMonoB == null) {
            try { sMonoB = Typeface.createFromAsset(c.getAssets(), "fonts/plexmono-sb.ttf"); }
            catch (Exception e) { sMonoB = Typeface.MONOSPACE; }
        }
        return sMonoB;
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ drawables

    private static Drawable solidRect(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        return g;
    }

    private static GradientDrawable borderedRect(int fill, int stroke, int strokeDp, int dashDp, int dashGapDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        if (dashDp > 0) g.setStroke(dp(c, strokeDp), stroke, dp(c, dashDp), dp(c, dashGapDp));
        else g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    /** panel/ink bordered rect with a hard shadow peeking bottom-right */
    private static LayerDrawable makeShadowRect(Context c, int fill, int stroke, int shadowDp) {
        int s = dp(c, shadowDp);
        Drawable shadow = solidRect(cur.shadow);
        Drawable main = borderedRect(fill, stroke, 2, 0, 0, c);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{shadow, main});
        ld.setLayerInset(0, s, s, 0, 0);   // shadow: shifted down-right
        ld.setLayerInset(1, 0, 0, s, s);   // main: pulled in at bottom-right
        return ld;
    }

    private static Drawable pressedVariant(Drawable normal, Drawable flat) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, flat);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    /** build a themed drawable by name — the pixel design language */
    public static Drawable d(Context c, String name) {
        if (name == null) return null;
        switch (name) {
            case "card":     return makeShadowRect(c, cur.panel, cur.ink, 6);
            case "cardFlat": return borderedRect(cur.panel, cur.ink, 2, 0, 0, c);
            case "dialog":   return makeShadowRect(c, cur.bg, cur.ink, 8);
            case "sunken":   return borderedRect(cur.bg, cur.soft, 2, 4, 3, c);
            case "btn":      return pressedVariant(makeShadowRect(c, cur.panel, cur.ink, 3),
                                                   borderedRect(cur.panel, cur.ink, 2, 0, 0, c));
            case "btnMain":  return pressedVariant(makeShadowRect(c, cur.accent, cur.ink, 3),
                                                   borderedRect(cur.accent, cur.ink, 2, 0, 0, c));
            case "btnFlat":  return pressedVariant(borderedRect(cur.panel, cur.soft, 2, 0, 0, c),
                                                   borderedRect(cur.on, cur.ink, 2, 0, 0, c));
            case "btnInk":   return pressedVariant(makeShadowRect(c, cur.ink, cur.micink, 3),
                                                   borderedRect(cur.ink, cur.accent, 2, 0, 0, c));
            case "tile":     return borderedRect(0x00000000, cur.soft, 2, 4, 3, c);
            case "tileOn":   return pressedVariant(makeShadowRect(c, cur.on, cur.ink, 3),
                                                   borderedRect(cur.on, cur.ink, 2, 0, 0, c));
            case "tag":
            case "pill":     return solidRect(cur.ink);
            case "edit":     return borderedRect(cur.bg, cur.ink, 2, 0, 0, c);
            case "opt":      return borderedRect(0x00000000, cur.soft, 2, 0, 0, c);
            case "optOn":    return borderedRect(cur.on, cur.ink, 2, 0, 0, c);
            case "iconBox":  return borderedRect(cur.panel, cur.soft, 2, 0, 0, c);
            case "key":      return pressedVariant(makeShadowRect(c, cur.panel, cur.ink, 2),
                                                   borderedRect(cur.on, cur.ink, 2, 0, 0, c));
            case "keyAccent":return pressedVariant(makeShadowRect(c, cur.accent, cur.ink, 2),
                                                   borderedRect(cur.accent, cur.ink, 2, 0, 0, c));
            case "keyMod":   return pressedVariant(makeShadowRect(c, cur.off, cur.ink, 2),
                                                   borderedRect(cur.off, cur.ink, 2, 0, 0, c));
            default:         return null;
        }
    }

    // ------------------------------------------------------------ apply / walk

    /** apply current theme to an activity's window + root view tree */
    public static void apply(final Activity a, View root) {
        cur = THEMES.get(AppSettings.themeKey(a));
        if (cur == null) cur = THEMES.get("dune");
        sAppliedKey = cur.key;
        Window w = a.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(cur.bg));
        if (root != null) walk(a, root);
    }

    private static void walk(Context c, View v) {
        Object tag = v.getTag();
        if (tag instanceof String) applyTokens(c, v, (String) tag);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(c, g.getChildAt(i));
        }
    }

    private static void applyTokens(Context c, View v, String tag) {
        for (String tok : tag.split("\\s+")) {
            if (tok.startsWith("d:")) {
                Drawable d = d(c, tok.substring(2));
                if (d != null) v.setBackgroundDrawable(d);
            } else if (tok.startsWith("t:") && v instanceof TextView) {
                ((TextView) v).setTextColor(col(tok.substring(2)));
            } else if (tok.startsWith("f:") && v instanceof TextView) {
                ((TextView) v).setTypeface(font(c, tok.substring(2)));
            } else if (tok.startsWith("i:") && v instanceof ImageView) {
                ((ImageView) v).setColorFilter(col(tok.substring(2)));
            }
        }
    }

    public static int col(String name) {
        switch (name) {
            case "bg": return cur.bg;
            case "panel": return cur.panel;
            case "ink": return cur.ink;
            case "soft": return cur.soft;
            case "accent": return cur.accent;
            case "on": return cur.on;
            case "off": return cur.off;
            case "shadow": return cur.shadow;
            case "mic": return cur.micink;
            default: return cur.ink;
        }
    }

    public static Typeface font(Context c, String name) {
        switch (name) {
            case "vt": return vt(c);
            case "monoB": return monoB(c);
            default: return mono(c);
        }
    }

    /** set + tint an ImageView icon */
    public static void icon(ImageView iv, int res, int color) {
        iv.setImageResource(res);
        if (color != 0) iv.setColorFilter(color);
        else iv.clearColorFilter();
    }

    // ------------------------------------------------------------ press FX
    /** steps() style press: element slides into its own shadow */
    public static void press(final View v, final int offsetDp) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                final int d = dp(view.getContext(), offsetDp);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().translationX(d).translationY(d).setDuration(60).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().translationX(0).translationY(0).setDuration(90).start();
                        break;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------ kiosk
    /**
     * Immersive + lock the status bar so the notification shade can not
     * be pulled down (platform-signed reflection; harmless if it fails).
     */
    public static void setKioskBars(Activity a, boolean on) {
        View decor = a.getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        try {
            Object sb = a.getSystemService("statusbar");
            java.lang.reflect.Method m = sb.getClass().getMethod("disable", int.class);
            // DISABLE_EXPAND = 0x00010000 — shade can't be opened while kiosk is on
            m.invoke(sb, on ? (0x00010000 | 0x00020000 | 0x00080000 | 0x00100000) : 0);
            sb.getClass().getMethod("disable2", int.class).invoke(sb, on ? 1 : 0);
        } catch (Throwable ignored) { }
    }

    /** all TextViews under a root */
    public static List<TextView> textViews(View root) {
        List<TextView> out = new ArrayList<TextView>();
        collect(root, out);
        return out;
    }
    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }
}
