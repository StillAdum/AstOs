package com.astos.shell;
import android.content.Context;import android.graphics.*;import android.util.AttributeSet;import android.view.View;
/** Actual captured waveform energy; a flat baseline is shown when audio data is unavailable. */
public class EqBars extends View {
 private final Paint paint=new Paint();private final float[] levels=new float[9];private boolean playing;private int session=-1;private android.media.audiofx.Visualizer capture;
 public EqBars(Context c,AttributeSet a){super(c,a);}public void setMini(boolean b){}
 public void setPlaying(boolean on){playing=on;if(!on)java.util.Arrays.fill(levels,0);invalidate();}
 public void levels(org.json.JSONArray bands){if(bands==null){java.util.Arrays.fill(levels,0);}else for(int i=0;i<9;i++)levels[i]=(float)Math.max(0,Math.min(1,bands.optDouble(i,0)));postInvalidate();}
 public void useSession(int id){if(id==session)return;release();session=id;if(id<=0)return;
  try{capture=new android.media.audiofx.Visualizer(id);capture.setCaptureSize(256);capture.setDataCaptureListener(new android.media.audiofx.Visualizer.OnDataCaptureListener(){
   public void onWaveFormDataCapture(android.media.audiofx.Visualizer v,byte[] data,int rate){if(!playing)return;for(int b=0;b<9;b++){double sum=0;int lo=b*data.length/9,hi=(b+1)*data.length/9;for(int i=lo;i<hi;i++){double value=((data[i]&255)-128)/128.0;sum+=value*value;}levels[b]=(float)Math.min(1,Math.sqrt(sum/Math.max(1,hi-lo))*3);}postInvalidate();}
   public void onFftDataCapture(android.media.audiofx.Visualizer v,byte[] data,int rate){}
  },Math.min(10000,android.media.audiofx.Visualizer.getMaxCaptureRate()),true,false);capture.setEnabled(true);}catch(Exception ignored){release();}
 }
 private void release(){if(capture!=null){try{capture.release();}catch(Exception ignored){}capture=null;}java.util.Arrays.fill(levels,0);}
 @Override protected void onDetachedFromWindow(){release();session=-1;super.onDetachedFromWindow();}
 @Override protected void onDraw(Canvas c){int gap=Ui.dp(getContext(),2),width=Math.max(1,(getWidth()-gap*8)/9);paint.setColor(Ui.cur.accent);for(int i=0;i<9;i++){int h=Math.max(Ui.dp(getContext(),2),Math.round(getHeight()*(playing?levels[i]:0)));c.drawRect(i*(width+gap),getHeight()-h,i*(width+gap)+width,getHeight(),paint);}}
}
