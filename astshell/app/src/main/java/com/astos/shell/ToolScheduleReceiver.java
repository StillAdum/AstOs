package com.astos.shell;
import android.content.*;
import org.json.*;
public final class ToolScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())||Intent.ACTION_TIME_CHANGED.equals(i.getAction())){ToolSchedules.restore(c);return;}
        if("com.astos.shell.TOOL_DISMISS".equals(i.getAction())){c.stopService(new Intent(c,ToolAlertService.class));return;}
        if(!"com.astos.shell.TOOL_ALERT".equals(i.getAction()))return;
        try{JSONObject row=ToolSchedules.take(c,i.getStringExtra("id"));if(row!=null)c.startService(new Intent(c,ToolAlertService.class).putExtra("schedule",row.toString()));}
        catch(Exception e){android.util.Log.e("AstSchedules","Alert failed",e);}
    }
}
