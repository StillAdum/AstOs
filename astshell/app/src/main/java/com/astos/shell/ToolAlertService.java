package com.astos.shell;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import org.json.*;

/** Target API25: alarm audio and a foreground dismiss notification. */
public final class ToolAlertService extends Service {
    private MediaPlayer sound;private final Handler main=new Handler(Looper.getMainLooper());
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int startId){
        try{
            JSONObject row=new JSONObject(i.getStringExtra("schedule"));String kind=row.getString("kind"),label=row.getString("label");
            Intent dismiss=new Intent(this,ToolScheduleReceiver.class).setAction("com.astos.shell.TOOL_DISMISS");
            PendingIntent stop=PendingIntent.getBroadcast(this,1,dismiss,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Intent show=new Intent(this,ToolAlertActivity.class).putExtra("label",label).putExtra("kind",kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent view=PendingIntent.getActivity(this,2,show,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this).setSmallIcon(R.drawable.ic_mic).setContentTitle(kind.toUpperCase(java.util.Locale.US)).setContentText(label).setContentIntent(view).setOngoing(true).addAction(0,"Dismiss",stop).build();
            startForeground(714,n);
            if(sound!=null){sound.release();sound=null;}
            android.net.Uri uri=RingtoneManager.getDefaultUri(kind.equals("reminder")?RingtoneManager.TYPE_NOTIFICATION:RingtoneManager.TYPE_ALARM);
            if(uri!=null){sound=new MediaPlayer();sound.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());sound.setDataSource(this,uri);sound.setLooping(!kind.equals("reminder"));sound.prepare();sound.start();}
            startActivity(show);main.removeCallbacksAndMessages(null);main.postDelayed(()->stopSelf(),120000);
        }catch(Exception e){android.util.Log.e("AstSchedules","Alert audio/UI failed",e);main.postDelayed(()->stopSelf(),120000);}
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);if(sound!=null){sound.release();sound=null;}stopForeground(true);super.onDestroy();}
}
