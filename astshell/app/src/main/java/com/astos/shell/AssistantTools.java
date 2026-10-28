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
        LinkServer link=LinkServer.peek();
        if(link==null||!link.pcReady()||PcPlayback.localSelected(c))return tablet(c,action,query);
        CountDownLatch done=new CountDownLatch(1);JSONObject[] value={null};String[] error={null};
        link.sendPcData(action,query,false,(data,err)->{try{value[0]=data==null?new JSONObject():new JSONObject(LinkJson.write(data));}catch(Exception e){error[0]=e.getMessage();}if(err!=null)error[0]=err;done.countDown();});
        if(!done.await(37,TimeUnit.SECONDS))throw new Exception("PC response timed out; check playback before retrying");
        if(error[0]!=null)throw new Exception(error[0]);if((action.equals("play")||action.equals("resume"))&&MusicEngine.isPlaying())MusicEngine.toggle(c);return value[0].put("ok",true);
    }
    private static JSONObject tablet(Context c,String action,String query)throws Exception{
        if("search".equals(action)){
            JSONArray tracks=new JSONArray();for(SongSearch.Track track:SongSearch.search(query))tracks.put(trackJson(track));
            return new JSONObject().put("ok",true).put("tracks",tracks).put("source","tablet");
        }
        if("state".equals(action))return tabletState();
        if("volset".equals(action)||"volup".equals(action)||"voldown".equals(action)){
            if("volset".equals(action))MediaControlService.volumePercent(c,Integer.parseInt(query));
            else if("volup".equals(action))MediaControlService.volumeUp(c);else MediaControlService.volumeDown(c);
            return tabletState().put("volume",MediaControlService.volumePercent(c));
        }
        CountDownLatch done=new CountDownLatch(1);String[] error={null};SongSearch.Track[] played={null};
        MusicEngine.Completion cb=(track,failure)->{played[0]=track;error[0]=failure;done.countDown();};
        String request="";
        if("play".equals(action))request=MusicEngine.playConfirmed(c,query,cb);
        else MusicEngine.controlConfirmed(action,cb);
        if(!done.await(60,TimeUnit.SECONDS)){if(!request.isEmpty())MusicEngine.cancelRequest(request);throw new Exception("Tablet audio timed out; playback request cancelled");}
        if(error[0]!=null)throw new Exception(error[0]);
        return tabletState().put("accepted",true);
    }
    private static JSONObject trackJson(SongSearch.Track track)throws Exception{
        return new JSONObject().put("uri",track.watchUrl).put("title",track.title).put("artist",track.artist).put("durationMs",track.durationMs).put("artUrl",track.artUrl);
    }
    private static JSONObject tabletState()throws Exception{
        SongSearch.Track track=MusicEngine.current();JSONObject value=track==null?new JSONObject():trackJson(track);
        return value.put("ok",true).put("available",track!=null).put("source","tablet").put("playing",MusicEngine.isPlaying()).put("positionMs",MusicEngine.positionMs()).put("status",MusicEngine.searchStatus());
    }

}
