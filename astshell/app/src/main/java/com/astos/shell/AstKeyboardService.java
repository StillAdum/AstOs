package com.astos.shell;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The AstOs keyboard — a chunky warm landscape IME, drawn in the active
 * AsciiTheme palette.
 *
 * v2.4 "the keyboard just disappears" fix: the old version rebuilt the whole
 * input view with setInputView(onCreateInputView()) on every shift / layer
 * toggle / auto-unshift. On Android 7.1 that tear-down mid-typing (often
 * while the IME window is still animating in, or racing a focus change)
 * intermittently leaves the IME window hidden — the reported bug. This
 * version builds the view exactly ONCE and updates every key label in
 * place; setInputView() is never called again for the lifetime of the
 * service. onEvaluateInputViewShown() is forced true so a hiccup in the
 * shown-state heuristic can never eat the keyboard either.
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

    /** Every label-bearing key that changes with shift / layer, in place. */
    private final List<TextView> mDynamicKeys = new ArrayList<TextView>();
    /** The base character each dynamic key currently represents (same order). */
    private final List<String> mDynamicBase = new ArrayList<String>();
    private TextView mShiftKey;
    private TextView mLayerKey;

    @Override
    public View onCreateInputView() {
        mDynamicKeys.clear();
        mDynamicBase.clear();
        mShiftKey = null;
        mLayerKey = null;

        Context c = getBaseContext();
        mPal = AsciiTheme.byName(AppSettings.palette(c));

        // 5 rows of ~46dp keys fits the landscape IME budget
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

        if (rowIdx == 2) {
            mShiftKey = key(c, "SHIFT", 1.4f, true);
            mShiftKey.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mShifted = !mShifted;
                    refreshKeys();          // in place — never rebuild
                }
            });
            row.addView(mShiftKey);
        }

        String[] labels = (mSymbols ? LAYER_SYMBOLS : LAYER_LETTERS)[rowIdx];
        for (String k : labels) {
            TextView kv = key(c, display(k), 1.0f, false);
            final String base = k;
            kv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { commit(v, base); }
            });
            mDynamicKeys.add(kv);          // tracked for in-place relabeling
            mDynamicBase.add(k);
            row.addView(kv);
        }

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

        mLayerKey = key(c, mSymbols ? "ABC" : "?123", 1.3f, true);
        mLayerKey.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mSymbols = !mSymbols;
                refreshKeys();              // in place — never rebuild
            }
        });
        row.addView(mLayerKey);

        TextView comma = key(c, ",", 1.0f, false);
        comma.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { commit(v, ","); }
        });
        row.addView(comma);

        TextView space = key(c, "SPACE", 3.0f, false);
        space.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { commit(v, " "); }
        });
        row.addView(space);

        TextView dot = key(c, ".", 1.0f, false);
        dot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { commit(v, "."); }
        });
        row.addView(dot);

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

    private String display(String base) {
        boolean letter = base.length() == 1 && Character.isLetter(base.charAt(0));
        if (!letter) return base;
        return mShifted ? base.toUpperCase() : base;
    }

    /**
     * THE v2.4 fix: relabel + restyle every tracked key in place.
     * No setInputView(), no view rebuild, no window churn — the IME window
     * is never torn down mid-typing again.
     */
    private void refreshKeys() {
        for (int i = 0; i < mDynamicKeys.size(); i++) {
            mDynamicKeys.get(i).setText(display(mDynamicBase.get(i)));
        }
        if (mLayerKey != null) mLayerKey.setText(mSymbols ? "ABC" : "?123");
        if (mShiftKey != null) styleShiftKey();
    }

    private void styleShiftKey() {
        if (mShiftKey == null) return;
        if (mShifted) styleAccent(mShiftKey);
        else {
            mShiftKey.setTextColor(mPal.dim);
            mShiftKey.setBackground(makeKeyBg(false));
        }
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

    private void commit(View source, String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String out = s;
        if (s.length() == 1 && Character.isLetter(s.charAt(0)) && mShifted) {
            out = s.toUpperCase();
            mShifted = false;
        }
        ic.commitText(out, 1);
        if (!out.equals(s)) refreshKeys();   // in place, safe mid-typing
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

    // ------------------------------------------------------------- robustness

    /** Never enter ugly fullscreen extract mode — keep the AstOs UI visible. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /**
     * v2.4: always report the input view as shown-able. The framework's
     * default heuristic (isFullscreenMode || mViewsCreated) can transiently
     * return false right after an app-side focus churn and permanently hide
     * the IME for that field — one more disappearing-keyboard vector closed.
     */
    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    /** Self-heal: if some race left the input view hidden, show it again. */
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        try {
            if (!isInputViewShown()) updateInputViewShown();
        } catch (Throwable ignored) {}
    }
}
