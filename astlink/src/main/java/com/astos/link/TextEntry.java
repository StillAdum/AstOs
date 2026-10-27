package com.astos.link;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;

/** In-dialog keyboard: remains visible even when the system IME is unavailable. */
public final class TextEntry {
    public interface Result { void entered(String value); }
    private TextEntry() {}
    public static void show(Activity a, String title, String initial, boolean secret, Result result) {
        LinearLayout body = new LinearLayout(a); body.setOrientation(1); body.setPadding(PixelUi.dp(a,12),PixelUi.dp(a,8),PixelUi.dp(a,12),PixelUi.dp(a,8));
        EditText field = new EditText(a); field.setSingleLine(true); field.setText(initial == null ? "" : initial);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setShowSoftInputOnFocus(false); body.addView(field, new LinearLayout.LayoutParams(-1,PixelUi.dp(a,48)));
        LinearLayout keys = new LinearLayout(a); keys.setOrientation(1); body.addView(keys);
        boolean[] shift = {false}, symbols = {false};
        Runnable[] draw = new Runnable[1];
        java.util.function.Consumer<String> insert = text -> {
            int start = Math.max(0, field.getSelectionStart()), end = Math.max(0,field.getSelectionEnd());
            field.getText().replace(Math.min(start,end),Math.max(start,end),text);
        };
        draw[0] = () -> {
            keys.removeAllViews();
            String[] rows = symbols[0] ? new String[]{"1234567890", "!@#$%^&*()", "-_+=[]{}\\|", ";:'\",.<>/?`~"} : new String[]{"1234567890","qwertyuiop","asdfghjkl","zxcvbnm"};
            for (String row : rows) {
                LinearLayout line = new LinearLayout(a); keys.addView(line);
                for (char ch : row.toCharArray()) {
                    String text = shift[0] ? String.valueOf(ch).toUpperCase(java.util.Locale.US) : String.valueOf(ch);
                    TextView key = key(a,text); line.addView(key,new LinearLayout.LayoutParams(0,PixelUi.dp(a,44),1));
                    key.setOnClickListener(v -> insert.accept(text));
                }
            }
            LinearLayout last = new LinearLayout(a); keys.addView(last);
            for (String label : new String[]{"Shift","Symbols","Space","Delete","Paste"}) {
                TextView key=key(a,label);last.addView(key,new LinearLayout.LayoutParams(0,PixelUi.dp(a,44),1));
                key.setOnClickListener(v -> {
                    if (label.equals("Shift")) {shift[0]=!shift[0];draw[0].run();}
                    else if(label.equals("Symbols")){symbols[0]=!symbols[0];draw[0].run();}
                    else if(label.equals("Space"))insert.accept(" ");
                    else if(label.equals("Delete")){
                        int p=Math.max(0,field.getSelectionStart()),q=Math.max(0,field.getSelectionEnd());
                        if(p!=q)insert.accept(""); else if(p>0)field.getText().delete(p-1,p);
                    } else {
                        ClipboardManager clip=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                        if(clip.hasPrimaryClip() && clip.getPrimaryClip().getItemCount()>0) insert.accept(clip.getPrimaryClip().getItemAt(0).coerceToText(a).toString());
                    }
                });
            }
        };
        draw[0].run();
        AlertDialog dialog = new com.astos.link.PixelUi.Builder(a).setTitle(title).setView(body)
            .setPositiveButton("Use text",(d,w)->result.entered(field.getText().toString())).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->{PixelUi.decorate(dialog);field.requestFocus();field.setSelection(field.length());dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);});
        dialog.show();
        dialog.getWindow().setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels-PixelUi.dp(a,24),PixelUi.dp(a,820)),ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static TextView key(Activity a,String text){
        TextView v=new TextView(a);v.setText(text);v.setTextSize(15);v.setGravity(Gravity.CENTER);new PixelUi(a).control(v);v.setPadding(0,0,0,0);v.setFocusable(false);return v;
    }
}
