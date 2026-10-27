package com.astos.shell;
import android.app.Activity;import android.widget.*;
/** Small, theme-aware builder; all values persist independently of theme changes. */
final class BackdropSettings {
 static void show(Activity a){android.content.SharedPreferences p=a.getSharedPreferences("backdrop",0);LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(Ui.dp(a,16),Ui.dp(a,8),Ui.dp(a,16),Ui.dp(a,8));
  int[] values={p.getInt("density",22),p.getInt("speed",100),p.getInt("size",3),p.getInt("direction",0),p.getInt("color",0)};
  add(a,body,"Particles",values,0,4,64);add(a,body,"Speed %",values,1,10,300);add(a,body,"Pixel size",values,2,1,8);
  Button direction=new Button(a);direction.setText("Motion: "+new String[]{"Drift","Rain","Horizontal"}[values[3]]);body.addView(direction);direction.setOnClickListener(v->new com.astos.link.PixelUi.Builder(a).setTitle("Motion").setItems(new String[]{"Drift","Rain","Horizontal"},(d,w)->{values[3]=w;direction.setText("Motion: "+new String[]{"Drift","Rain","Horizontal"}[w]);}).show());
  Button color=new Button(a);color.setText("Color: "+new String[]{"Theme mix","Accent","Ink"}[values[4]]);body.addView(color);color.setOnClickListener(v->new com.astos.link.PixelUi.Builder(a).setTitle("Particle color").setItems(new String[]{"Theme mix","Accent","Ink"},(d,w)->{values[4]=w;color.setText("Color: "+new String[]{"Theme mix","Accent","Ink"}[w]);}).show());
  new com.astos.link.PixelUi.Builder(a).setTitle("Custom backdrop").setView(body).setPositiveButton("Save",(d,w)->{p.edit().putInt("density",values[0]).putInt("speed",values[1]).putInt("size",values[2]).putInt("direction",values[3]).putInt("color",values[4]).apply();AppSettings.setBackdropMode(a,5);}).setNegativeButton("Cancel",null).show();
 }
 private static void add(Activity a,LinearLayout root,String label,int[] values,int i,int min,int max){TextView t=new TextView(a);root.addView(t);t.setText(label+": "+values[i]);SeekBar bar=new SeekBar(a);bar.setMax(max-min);bar.setProgress(values[i]-min);root.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(a,40)));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.cur.ink));bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.cur.ink));bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean user){values[i]=v+min;t.setText(label+": "+values[i]);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});}
}
