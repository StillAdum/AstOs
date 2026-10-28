package com.astos.shell;

import android.content.Context;
import com.astos.link.*;
import org.json.*;
import java.util.concurrent.*;

/** Deliberately small tool allowlist. No AI shell, file or arbitrary URL execution. */
public final class AssistantTools {
    private AssistantTools(){}
    private static String required(JSONObject args,String key) throws Exception {
        Object v=args.opt(key);if(!(v instanceof String)||((String)v).trim().isEmpty()||((String)v).length()>512)throw new Exception("Invalid "+key);
        return ((String)v).trim();
    }
    private static long integer(JSONObject args,String key,long min,long max) throws Exception {
        Object v=args.opt(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||((Number)v).doubleValue()!=((Number)v).longValue())throw new Exception("Invalid integer "+key);
        long n=((Number)v).longValue();if(n<min||n>max)throw new Exception(key+" out of range");return n;
    }
    public static JSONObject execute(Context c,String name,JSONObject args) throws Exception {
        switch(name){
            case "search_songs":return pc(c,"search",required(args,"query"));
            case "play_song":return pc(c,"play",required(args,"query"));
            case "get_playback":return pc(c,"state","");
            case "control_playback":{
                String action=required(args,"action"),query="";
                if(action.equals("volume")){action="volset";query=String.valueOf(integer(args,"volume",0,100));}
                else if(!action.equals("pause")&&!action.equals("resume")&&!action.equals("next")&&!action.equals("previous"))throw new Exception("Unsupported playback action");
                return pc(c,action,query);
            }
            case "set_timer":return ToolSchedules.add(c,"timer",required(args,"label"),System.currentTimeMillis()+integer(args,"seconds",1,604800)*1000);
            case "add_reminder":case "set_alarm":{
                long at;
                try{at=java.time.OffsetDateTime.parse(required(args,"at")).toInstant().toEpochMilli();}
                catch(Exception e){throw new Exception("Use a full ISO-8601 datetime with timezone offset");}
                return ToolSchedules.add(c,name.equals("set_alarm")?"alarm":"reminder",required(args,"label"),at);
            }
            case "dismiss_alerts":return new JSONObject().put("ok",true).put("dismissed",c.stopService(new android.content.Intent(c,ToolAlertService.class)));
            case "list_schedules":return new JSONObject().put("ok",true).put("items",ToolSchedules.list(c));
            case "cancel_schedule":return ToolSchedules.cancel(c,required(args,"id"));
            default:throw new Exception("Unknown assistant tool");
        }
    }
    private static JSONObject pc(Context c,String action,String query) throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new Exception("Playback tools must run in background");
        LinkServer link=LinkServer.peek();if(link==null||!link.pcReady())throw new Exception("PC Spotify is disconnected. Start AstOS Companion and Spotify extension.");
        CountDownLatch done=new CountDownLatch(1);JSONObject[] value={null};String[] error={null};
        link.sendPcData(action,query,false,(data,err)->{try{value[0]=data==null?new JSONObject():new JSONObject(LinkJson.write(data));}catch(Exception e){error[0]=e.getMessage();}if(err!=null)error[0]=err;done.countDown();});
        if(!done.await(37,TimeUnit.SECONDS))throw new Exception("PC response timed out; check playback before retrying");
        if(error[0]!=null)throw new Exception(error[0]);if((action.equals("play")||action.equals("resume"))&&MusicEngine.isPlaying())MusicEngine.toggle(c);return value[0].put("ok",true);
    }
}
