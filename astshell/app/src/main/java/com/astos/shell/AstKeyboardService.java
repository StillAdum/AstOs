package com.astos.shell;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.inputmethodservice.InputMethodService;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The AstOs keyboard — a chunky terminal-styled landscape IME, drawn in the
 * active AsciiTheme palette. Fully programmatic: big monospace keys, digit
 * row always visible, [DEL] with hold-to-repeat, [HIDE] and [GO] keys.
 */
public class AstKeyboardService extends InputMethodService {

    private static final String[][] LAYER_LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"z", "x", "c", "v", "b", "n", "m"},
    };

    private static final String[][] LAYER_SYMBOLS = {
            {"@", "#", "$", "%", "&", "*", "-", "+", "(", ")"},
            {"\"", "'", ":", ";", "!", "?", "_", "=", "/", "\\"},
            {"<", ">", "[", "]", "{", "}", "|", "~", "`", "^"},
    };

    private boolean mSymbols = false;
    private boolean mShifted = false;

    private final Handler mRepeat = new Handler(Looper.getMainLooper());
    private int mKeyHeight;
    private int mGap;
    private AsciiTheme.Palette mPal;

    @Override
    public View onCreateInputView() {
        Context c = getBaseContext();
        mPal = AsciiTheme.byName(AppSettings.palette(c));

        // 5 rows of ~48dp-equivalent keys fits the landscape IME budget
        mKeyHeight = Math.round(getResources().getDisplayMetrics().density * 46);
        mGap = Math.round(getResources().getDisplayMetrics().density * 4);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(getResources().getDisplayMetrics().density * 8);
        root.setPadding(pad, pad, pad, pad);

        root.addView(row0(c));
        root.addView(letterRow(c, 0));
        root.addView(letterRow(c, 1));
        root.addView(letterRow(c, 2));
        root.addView(bottomRow(c));
        return root;
    }

    // ------------------------------------------------------------- rows

    /** 1..0 + DEL, always visible. */
    private LinearLayout row0(Context c) {
        LinearLayout row = row(c);
        for (char d = '1'; d <= '9'; d++) row.addView(key(c, String.valueOf(d), 0.9f, false));
        row.addView(key(c, "0", 0.9f, false));
        View del = key(c, "DEL", 1.2f, true);
        bindDelete(del);
        row.addView(del);
        return row;
    }

    /** Rows 1-3: letters or symbols depending on the layer toggle. */
    private LinearLayout letterRow(Context c, int rowIdx) {
        LinearLayout row = row(c);
        String[] labels = (mSymbols ? LAYER_SYMBOLS : LAYER_LETTERS)[rowIdx];

        if (rowIdx == 2) {
            TextView shift = key(c, "SHIFT", 1.4f, true);
            if (mShifted) styleAccent(shift);
            shift.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleShift(); }
            });
            row.addView(shift);
        }

        for (String k : labels) row.addView(letterKey(c, k));

        View del = key(c, "DEL", 1.2f, true);
        bindDelete(del);
        row.addView(del);

        if (rowIdx == 1) {
            // natural QWERTY stagger
            View spacerL = new View(c);
            spacerL.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 0.45f));
            row.addView(spacerL, 0);
            View spacerR = new View(c);
            spacerR.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 0.45f));
            row.addView(spacerR);
        }
        return row;
    }

    /** ?123 / ABC + , + space + . + hide + go. */
    private LinearLayout bottomRow(Context c) {
        LinearLayout row = row(c);

        TextView layer = key(c, mSymbols ? "ABC" : "?123", 1.3f, true);
        layer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mSymbols = !mSymbols;
                setInputView(onCreateInputView()); // rebuild with new layer
            }
        });
        row.addView(layer);

        row.addView(letterKey(c, ","));

        TextView space = key(c, "SPACE", 3.0f, false);
        space.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { commit(v, " "); }
        });
        row.addView(space);

        row.addView(letterKey(c, "."));

        TextView hide = key(c, "HIDE", 1.4f, true);
        hide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestHideSelf(0); }
        });
        row.addView(hide);

        TextView go = key(c, "GO", 1.4f, false);
        styleAccent(go);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendEnter(v); }
        });
        row.addView(go);
        return row;
    }

    // ------------------------------------------------------------- keys

    private LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, mKeyHeight);
        lp.topMargin = mGap;
        lp.bottomMargin = mGap;
        row.setLayoutParams(lp);
        return row;
    }

    private TextView key(Context c, String label, float weight, boolean special) {
        TextView k = new TextView(c);
        k.setText(label);
        k.setGravity(Gravity.CENTER);
        k.setTypeface(Typeface.MONOSPACE, special ? Typeface.BOLD : Typeface.NORMAL);
        k.setTextSize(special ? 16f : 20f);
        k.setTextColor(special ? mPal.dim : mPal.fg);
        k.setBackground(makeKeyBg(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(mGap, 0, mGap, 0);
        k.setLayoutParams(lp);
        return k;
    }

    private TextView letterKey(Context c, final String base) {
        final TextView k = key(c, display(base), 1.0f, false);
        k.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { commit(v, base); }
        });
        return k;
    }

    private String display(String base) {
        boolean letter = base.length() == 1 && Character.isLetter(base.charAt(0));
        if (!letter) return base;
        return mShifted ? base.toUpperCase() : base;
    }

    private void styleAccent(TextView k) {
        k.setTextColor(mPal.bg);
        k.setBackground(makeKeyBg(true));
    }

    private GradientDrawable makeKeyBg(boolean accent) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(Math.round(getResources().getDisplayMetrics().density * 10));
        shape.setColor(accent ? mPal.accent
                : AsciiTheme.dimColor(mPal.fg, 0.10f, mPal.bg));
        shape.setStroke(1, AsciiTheme.dimColor(mPal.fg, 0.25f, mPal.bg));
        return shape;
    }

    // ------------------------------------------------------------- actions

    private void toggleShift() {
        mShifted = !mShifted;
        setInputView(onCreateInputView());
    }

    private void commit(View source, String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String out = s;
        if (s.length() == 1 && Character.isLetter(s.charAt(0)) && mShifted) {
            out = s.toUpperCase();
            mShifted = false;
        }
        ic.commitText(out, 1);
        if (!out.equals(s)) setInputView(onCreateInputView());
        tap(source);
    }

    private void sendEnter(View source) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo info = getCurrentInputEditorInfo();
        int action = (info != null) ? (info.imeOptions & EditorInfo.IME_MASK_ACTION)
                : EditorInfo.IME_ACTION_NONE;
        switch (action) {
            case EditorInfo.IME_ACTION_SEARCH:
            case EditorInfo.IME_ACTION_SEND:
            case EditorInfo.IME_ACTION_GO:
            case EditorInfo.IME_ACTION_DONE:
            case EditorInfo.IME_ACTION_NEXT:
                ic.performEditorAction(action);
                break;
            default:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
        tap(source);
    }

    /** Backspace key with press-and-hold auto-repeat. */
    private void bindDelete(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        delete(view);
                        final View btn = view;
                        mRepeat.postDelayed(new Runnable() {
                            @Override public void run() {
                                delete(btn);
                                mRepeat.postDelayed(this, 70);
                            }
                        }, 420);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mRepeat.removeCallbacksAndMessages(null);
                        return true;
                }
                return false;
            }
        });
    }

    private void delete(View source) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.deleteSurroundingText(1, 0);
        tap(source);
    }

    private void tap(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // ------------------------------------------------------------- misc

    /** Never enter ugly fullscreen extract mode — keep the ASCII UI visible. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }
}
