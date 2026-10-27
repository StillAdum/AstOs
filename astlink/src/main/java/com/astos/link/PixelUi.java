package com.astos.link;
import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.view.*;import android.widget.*;
/** Shared square-edged AstOS surfaces for shell and setup, including native dialog children. */
public final class PixelUi {
 public final int bg,panel,ink,soft,accent,shadow;
 public PixelUi(Context c){String key=android.provider.Settings.Global.getString(c.getContentResolver(),"astos_theme");int[] p;
  if("forest".equals(key))p=new int[]{0xffe6ead8,0xfff2f4e6,0xff1f2819,0xff536146,0xff8a5a22,0xffcbd4b4};
  else if("rust".equals(key))p=new int[]{0xfff0e2d6,0xfff9eee4,0xff2b1c15,0xff775443,0xffb9481f,0xffe0c6b0};
  else if("mono".equals(key))p=new int[]{0xffe9e8e3,0xfff6f5f0,0xff1c1c1c,0xff62625e,0xff2e2e2e,0xffd3d2cc};
  else if("ember".equals(key))p=new int[]{0xff15110d,0xff1e1812,0xffefe2cb,0xffc0ae91,0xffe39a48,0xff0d0a07};
  else if("pine".equals(key))p=new int[]{0xff101510,0xff182118,0xffe3ead6,0xffafbea3,0xffd2a94f,0xff0a0e0a};
  else p=new int[]{0xffefe7d8,0xfff8f2e4,0xff2a241c,0xff70614e,0xffc4742a,0xffd9ccb2};
  bg=p[0];panel=p[1];ink=p[2];soft=p[3];accent=p[4];shadow=p[5];
 }
 public static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 public Drawable box(Context c,int fill){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setStroke(dp(c,2),ink);d.setCornerRadius(0);return d;}
 public void control(TextView t){Context c=t.getContext();t.setTextColor(ink);t.setBackground(box(c,panel));t.setPadding(dp(c,10),dp(c,8),dp(c,10),dp(c,8));t.setAllCaps(false);try{t.setTypeface(Typeface.createFromAsset(c.getAssets(),"fonts/plexmono-sb.ttf"));}catch(Exception ignored){}t.setElevation(0);t.setStateListAnimator(null);}
 public void walk(View v){Context c=v.getContext();if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(ink);t.setHintTextColor(soft);try{t.setTypeface(Typeface.createFromAsset(c.getAssets(),"fonts/plexmono.ttf"));}catch(Exception ignored){}if(v instanceof Button && !(v instanceof CompoundButton))control(t);if(v instanceof EditText){t.setBackground(box(c,panel));t.setPadding(dp(c,10),dp(c,6),dp(c,10),dp(c,6));}if(v instanceof CompoundButton)((CompoundButton)v).setButtonTintList(android.content.res.ColorStateList.valueOf(ink));}
  if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i));}
 }
 public static void decorate(AlertDialog d){if(d.getWindow()==null)return;PixelUi p=new PixelUi(d.getContext());d.getWindow().setWindowAnimations(0);d.getWindow().setBackgroundDrawable(p.box(d.getContext(),p.panel));View root=d.getWindow().getDecorView();root.setBackground(p.box(d.getContext(),p.panel));p.walk(root);
  ListView list=d.getListView();if(list!=null){list.setBackgroundColor(p.panel);list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener(){public void onChildViewAdded(View parent,View child){p.walk(child);}public void onChildViewRemoved(View parent,View child){}});}
 }
 public static class Builder extends AlertDialog.Builder {
  public Builder(Context c){super(c);} public Builder(Context c,int theme){super(c,theme);}
  @Override public AlertDialog create(){AlertDialog d=super.create();d.setOnShowListener(x->decorate(d));return d;}
  @Override public AlertDialog show(){AlertDialog d=create();d.show();decorate(d);return d;}
 }
}
