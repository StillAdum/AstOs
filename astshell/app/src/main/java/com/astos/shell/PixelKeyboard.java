package com.astos.shell;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-shell pixel keyboard.
 *
 * AstOS text fields (settings, setup, app filter) never rely on the
 * system IME — that's what made the keyboard "sometimes disappear" in
 * the wild. This keyboard is ours: always attached, always visible when
 * a registered field is focused, styled like the rest of the OS.
 *
 * Usage:
 *   PixelKeyboard kb = PixelKeyboard.attach(activity, rootView, editText1, ...);
 *   kb.hide(); // when done
 */
public class PixelKeyboard extends ScrollView {

    // -------------------------------------------------- layout data
    private static final String[][] ROWS_LOWER = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"SHIFT", "z", "x", "c", "v", "b", "n", "m", "BKSP"},
            {"?123", "SPACE", "ENTER"}
    };
    private static final String[][] ROWS_NUM = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", ";", "(", ")", "$", "&", "@", "\""},
            {"ABC", ".", ",", "?", "!", "'", "BKSP"},
            {"?123", "SPACE", "ENTER"}
    };

    private EditText mTarget;
    private boolean mShift = false;
    private boolean mNumPage = false;
    private final List<EditText> mFields = new ArrayList<EditText>();
    private LinearLayout mBody;

    public PixelKeyboard(Context c) {
        super(c);
        init();
    }

    /** build keyboard, register fields, hide it, attach as bottom overlay of a view group */
    public static PixelKeyboard attachTo(ViewGroup root, EditText... fields) {
        PixelKeyboard kb = new PixelKeyboard(root.getContext());
        for (EditText f : fields) kb.register(f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(kb, lp);
        kb.hide();
        return kb;
    }

    /** attach to the activity's content frame (behind dialogs! use attachTo inside dialogs) */
    public static PixelKeyboard attach(android.app.Activity a, EditText... fields) {
        FrameLayout root = (FrameLayout) a.getWindow().getDecorView()
                .findViewById(android.R.id.content);
        return attachTo(root, fields);
    }

    private void init() {
        Context c = getContext();
        setVerticalScrollBarEnabled(false);
        setFillViewport(true);
        mBody = new LinearLayout(c);
        mBody.setOrientation(LinearLayout.VERTICAL);
        addView(mBody, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuild();
    }

    /** re-theme + rebuild keys for the current Ui palette */
    public void retheme() {
        rebuild();
    }

    private void rebuild() {
        Context c = getContext();
        mBody.removeAllViews();
        mBody.setBackgroundColor(Ui.cur.bg);
        mBody.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 8));

        String[][] rows = mNumPage ? ROWS_NUM : ROWS_LOWER;
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = Ui.dp(c, 6);
            mBody.addView(r, rp);
            for (final String key : row) {
                r.addView(makeKey(c, key), keyParams(key));
            }
        }
    }

    private LinearLayout.LayoutParams keyParams(String key) {
        float w = 1f;
        int ml = 0;
        if ("SPACE".equals(key)) w = 3.2f;
        else if ("SHIFT".equals(key) || "BKSP".equals(key)) w = 1.5f;
        else if ("?123".equals(key) || "ABC".equals(key)) w = 1.4f;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                Ui.dp(getContext(), 40), w);
        lp.leftMargin = ml;
        lp.rightMargin = Ui.dp(getContext(), 5);
        return lp;
    }

    private TextView makeKey(final Context c, final String key) {
        TextView k = new TextView(c);
        k.setTypeface(Ui.monoB(c));
        k.setTextSize(15);
        k.setGravity(Gravity.CENTER);
        boolean mod = "SHIFT".equals(key) || "BKSP".equals(key)
                || "?123".equals(key) || "ABC".equals(key);
        boolean accent = "ENTER".equals(key);
        k.setBackgroundDrawable(Ui.d(c, accent ? "keyAccent" : (mod ? "keyMod" : "key")));
        if (accent) k.setTextColor(Ui.onAccent());
        else if (mod) k.setTextColor(Ui.cur.ink);
        else k.setTextColor(Ui.cur.ink);
        k.setText(keyLabel(key));
        k.setTag(mod ? "f:monoB" : "f:mono");
        Ui.press(k, 2);
        k.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { onKey(key); }
        });
        return k;
    }

    private String keyLabel(String key) {
        if ("SPACE".equals(key)) return "space";
        if ("BKSP".equals(key)) return "\u232b";
        if ("SHIFT".equals(key)) return mShift ? "\u21e7" : "\u21e7";
        if ("?123".equals(key) || "ABC".equals(key)) return mNumPage ? "abc" : "?123";
        if ("ENTER".equals(key)) return "enter \u23ce";
        if (key.length() == 1 && mShift && !mNumPage) return key.toUpperCase();
        return key;
    }

    private void onKey(String key) {
        if (mTarget == null) return;
        if ("SHIFT".equals(key)) {
            mShift = !mShift;
            rebuild();
            return;
        }
        if ("?123".equals(key) || "ABC".equals(key)) {
            mNumPage = !mNumPage;
            rebuild();
            return;
        }
        if ("BKSP".equals(key)) { sendBackspace(); return; }
        if ("ENTER".equals(key)) { sendEnter(); return; }
        if ("SPACE".equals(key)) { send(" "); return; }
        send(mShift && !mNumPage ? key.toUpperCase() : key);
        if (mShift) { mShift = false; rebuild(); }
    }

    // -------------------------------------------------- text plumbing

    private void send(String s) {
        InputConnection ic = connection();
        if (ic != null) ic.commitText(s, 1);
    }

    private void sendBackspace() {
        InputConnection ic = connection();
        if (ic == null) return;
        CharSequence sel = mTarget.getText();
        int st = mTarget.getSelectionStart(), en = mTarget.getSelectionEnd();
        if (st != en) { ic.commitText("", 1); return; }
        ic.deleteSurroundingText(1, 0);
    }

    private void sendEnter() {
        InputConnection ic = connection();
        if (ic == null) return;
        hide(); // single-line fields: enter dismisses
        ic.performEditorAction(EditorInfo.IME_ACTION_DONE);
    }

    private InputConnection connection() {
        if (mTarget == null) return null;
        EditorInfo ei = new EditorInfo();
        try {
            InputConnection ic = mTarget.onCreateInputConnection(ei);
            return ic;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------- field mgmt

    public void register(final EditText f) {
        mFields.add(f);
        try { f.setShowSoftInputOnFocus(false); } catch (Throwable ignored) { }
        f.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) setTarget(f);
            }
        });
        f.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { setTarget(f); }
        });
    }

    public void setTarget(EditText f) {
        mTarget = f;
        show();
    }

    public EditText getTarget() { return mTarget; }

    public void show() {
        setVisibility(VISIBLE);
        bringToFront();
    }

    public void hide() {
        setVisibility(GONE);
        if (mTarget != null) {
            // let the system know we're done editing this field
        }
    }

    public boolean isShownKb() { return getVisibility() == VISIBLE; }
}
