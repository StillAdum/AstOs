package com.astos.shell;

import android.app.*;
import android.content.*;
import org.json.*;
import java.util.*;

/** Persistent one-shot schedules on the tablet, independent of HomeActivity. */
public final class ToolSchedules {
    private ToolSchedules(){}
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("assistant_schedules",0);}
    public static synchronized JSONArray list(Context c){try{return new JSONArray(prefs(c).getString("items","[]"));}catch(Exception e){return new JSONArray();}}
    private static void save(Context c,JSONArray rows) throws Exception {if(!prefs(c).edit().putString("items",rows.toString()).commit())throw new Exception("Schedule storage failed");}
    private static PendingIntent pending(Context c,String id){
        Intent i=new Intent(c,ToolScheduleReceiver.class).setAction("com.astos.shell.TOOL_ALERT").setData(android.net.Uri.parse("astos-schedule:"+id)).putExtra("id",id);
        return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void arm(Context c,JSONObject row) throws Exception {
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(manager==null)throw new Exception("Alarm service unavailable");
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(System.currentTimeMillis()+500,row.getLong("at")),pending(c,row.getString("id")));
    }
    public static synchronized JSONObject add(Context c,String kind,String label,long at) throws Exception {
        long now=System.currentTimeMillis();if(at<=now||at>now+366L*86400000)throw new Exception("Choose a future time within one year");
        JSONArray rows=list(c);if(rows.length()>=64)throw new Exception("Schedule limit reached; cancel an existing item");
        JSONObject row=new JSONObject().put("id",UUID.randomUUID().toString()).put("kind",kind).put("label",label).put("at",at);
        rows.put(row);save(c,rows);
        try{arm(c,row);}catch(Exception e){cancel(c,row.getString("id"));throw e;}
        return new JSONObject().put("ok",true).put("scheduled",row);
    }
    public static synchronized JSONObject cancel(Context c,String id) throws Exception {
        JSONArray rows=list(c),keep=new JSONArray();boolean found=false;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("id").equals(id))found=true;else keep.put(row);}
        if(!found)throw new Exception("Schedule ID not found");
        ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pending(c,id));save(c,keep);
        return new JSONObject().put("ok",true).put("cancelled",id);
    }
    public static synchronized JSONObject take(Context c,String id) throws Exception {
        JSONArray rows=list(c);JSONObject match=null;
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))match=rows.getJSONObject(i);
        if(match!=null)cancel(c,id);return match;
    }
    public static synchronized void restore(Context c){
        JSONArray rows=list(c);for(int i=0;i<rows.length();i++)try{arm(c,rows.getJSONObject(i));}catch(Exception e){android.util.Log.e("AstSchedules","Restore failed",e);}
    }
}
