package com.astos.shell;

import android.content.Context;
import com.astos.link.LinkServer;

/** PC commands only leave the tablet over its authenticated AstLink session. */
public final class PcPlayback {
    private static boolean sticky;
    private PcPlayback() {}
    public static org.json.JSONObject state() {
        LinkServer link = LinkServer.peek();
        if (link == null) return null;
        com.astos.link.LinkJson.Obj state = link.pcPlaybackState();
        if (state == null) return null;
        try { return new org.json.JSONObject(com.astos.link.LinkJson.write(state)); } catch (Exception e) { return null; }
    }
    public static boolean selected() {
        LinkServer link = LinkServer.peek();
        if (link == null || !link.pcReady()) sticky = false;
        return sticky;
    }
    public interface Listener {void status(String message);}
    private static java.lang.ref.WeakReference<Listener> listener=new java.lang.ref.WeakReference<>(null);
    private static Listener retained;
    public static void setListener(Listener value){retained=value;listener=new java.lang.ref.WeakReference<>(value);}
    public static void clearListener(){retained=null;listener.clear();}
    public static boolean available(){LinkServer s=LinkServer.peek();return s!=null&&s.pcReady();}
    public static boolean localSelected(Context c){return c.getSharedPreferences("playback",0).getBoolean("local",false);}
    public static void preferLocal(Context c,boolean local){c.getSharedPreferences("playback",0).edit().putBoolean("local",local).apply();sticky=!local;}
    private static void status(String message){Listener l=listener.get();if(l!=null)l.status(message);}
    public static void send(Context c,String action,String query,boolean untilDisconnected){
        LinkServer link=LinkServer.peek();if(link==null){status("PC companion is disconnected");return;}
        if(("play".equals(action)||"resume".equals(action))&&MusicEngine.isPlaying())MusicEngine.toggle(c);
        link.sendPc(action,query,untilDisconnected,error->new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
            if(error==null){sticky=untilDisconnected;if("play".equals(action)&&query!=null&&!query.trim().isEmpty())status("Playing "+query+" on your PC");}
            else status(error);
        }));
    }
}
