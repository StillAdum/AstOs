package com.astos.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.schabi.newpipe.extractor.*;
import org.schabi.newpipe.extractor.downloader.*;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** On-device YouTube -> SoundCloud search and full audio. No PC or Spotify needed.
 * The desktop equivalent uses yt-dlp. Android uses the existing NewPipe library,
 * which runs on this ROM without a Python/desktop binary. No public proxy API,
 * mandatory catalog rewrite, or silent replacement with a 30-second preview.
 */
public final class SongSearch {
    public static class Track {
        public String query="",title="",artist="",artUrl="",previewUrl="",watchUrl="",streamUrl="",source="",status="";
        public long durationMs;public File localFile;
        public Map<String,String> headers=new HashMap<>();
        public boolean hasAny(){return !streamUrl.isEmpty()||localFile!=null;}
    }
    public interface Callback{void onResult(Track t,String error);}
    private static boolean initialized;
    private SongSearch(){}
    private static synchronized void init(){if(!initialized){NewPipe.init(new HttpDownloader());initialized=true;}}
    private static final class HttpDownloader extends Downloader{
        @Override public Response execute(Request request)throws IOException,ReCaptchaException{
            HttpURLConnection c=(HttpURLConnection)new URL(request.url()).openConnection();
            c.setConnectTimeout(7000);c.setReadTimeout(10000);c.setRequestMethod(request.httpMethod());
            try{
                for(Map.Entry<String,List<String>> e:request.headers().entrySet())for(String v:e.getValue())c.addRequestProperty(e.getKey(),v);
                byte[] body=request.dataToSend();
                if(body!=null&&body.length>0){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body);}}
                int code=c.getResponseCode();if(code==429)throw new ReCaptchaException("Source rate-limited; try again later",request.url());
                InputStream input=code>=400?c.getErrorStream():c.getInputStream();
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                if(input!=null)try(InputStream in=input){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>12*1024*1024)throw new IOException("Music metadata is too large");out.write(b,0,n);}}
                return new Response(code,c.getResponseMessage(),c.getHeaderFields(),out.toString("UTF-8"),c.getURL().toString());
            }finally{c.disconnect();}
        }
    }
    public static void resolve(Context context,String query,Callback cb){
        new Thread(()->{
            Track track=null;String error=null;
            try{track=resolveSync(context,query);}catch(Throwable e){error="Couldn't play: "+message(e);}
            final Track result=track;final String failure=error;
            new Handler(Looper.getMainLooper()).post(()->cb.onResult(result,failure));
        },"song-search").start();
    }
    private static String checked(String query)throws Exception{
        String q=query==null?"":query.trim();if(q.isEmpty()||q.length()>512)throw new Exception("Enter a song title and optionally its artist");return q;
    }
    private static StreamingService service(String query)throws Exception{
        if(!query.matches("(?i)^https?://.*"))return null;
        URI uri=new URI(query);String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new Exception("Use a YouTube or SoundCloud HTTPS link");
        host=host.toLowerCase(Locale.US);
        if(host.equals("youtu.be")||host.equals("youtube.com")||host.endsWith(".youtube.com"))return ServiceList.YouTube;
        if(host.equals("soundcloud.com")||host.endsWith(".soundcloud.com"))return ServiceList.SoundCloud;
        throw new Exception("Use a song name, YouTube link or SoundCloud link");
    }
    private static List<StreamInfoItem> items(StreamingService source,String query)throws Exception{
        SearchInfo info=SearchInfo.getInfo(source,source.getSearchQHFactory().fromQuery(query,Collections.emptyList(),""));
        List<StreamInfoItem> out=new ArrayList<>();
        for(InfoItem item:info.getRelatedItems())if(item instanceof StreamInfoItem){StreamInfoItem s=(StreamInfoItem)item;if(s.getDuration()!=0)out.add(s);if(out.size()>=5)break;}
        return out;
    }
    public static List<Track> search(String raw)throws Exception{
        String query=checked(raw);init();StreamingService direct=service(query);List<Track> out=new ArrayList<>();
        if(direct!=null){StreamInfo info=StreamInfo.getInfo(direct,query);out.add(metadata(info,query));return out;}
        List<String> errors=new ArrayList<>();
        for(StreamingService source:new StreamingService[]{ServiceList.YouTube,ServiceList.SoundCloud}){
            try{for(StreamInfoItem item:items(source,query)){
                Track t=new Track();t.query=query;t.title=item.getName();t.artist=item.getUploaderName();t.watchUrl=item.getUrl();t.durationMs=Math.max(0,item.getDuration())*1000;
                if(!item.getThumbnails().isEmpty())t.artUrl=item.getThumbnails().get(0).getUrl();out.add(t);
            }if(!out.isEmpty())return out;}catch(Throwable e){errors.add(message(e));}
        }
        throw new Exception("No song results. "+String.join("; ",errors));
    }
    public static Track resolveSync(Context context,String raw)throws Exception{
        String query=checked(raw);init();StreamingService direct=service(query);List<String> errors=new ArrayList<>();
        for(StreamingService source:direct==null?new StreamingService[]{ServiceList.YouTube,ServiceList.SoundCloud}:new StreamingService[]{direct}){
            try{
                String url=direct!=null?query:null;
                if(url==null){List<StreamInfoItem> found=items(source,query);if(found.isEmpty())throw new Exception("No matches");url=found.get(0).getUrl();}
                StreamInfo info=StreamInfo.getInfo(source,url);Track t=metadata(info,query);AudioStream best=null;int score=Integer.MIN_VALUE;
                for(AudioStream audio:info.getAudioStreams()){
                    if(audio==null||!audio.isUrl())continue;
                    int rank=AudioStreamChoice.score(audio.getDeliveryMethod()==DeliveryMethod.PROGRESSIVE_HTTP,audio.getDeliveryMethod()==DeliveryMethod.HLS,audio.getFormat()==MediaFormat.M4A,audio.getAverageBitrate());
                    if(rank>score){best=audio;score=rank;}
                }
                if(best==null)throw new Exception("No compatible AAC/HLS or progressive audio stream");
                t.streamUrl=best.getContent();t.source="stream";t.status=source==ServiceList.YouTube?"YOUTUBE AUDIO":"SOUNDCLOUD AUDIO";
                t.headers.put("User-Agent","Mozilla/5.0");return t;
            }catch(Throwable e){errors.add(message(e));}
        }
        File local=localMatch(context,query);if(local!=null){Track t=new Track();t.query=query;t.title=stripExt(local.getName());t.source="local";t.status="LOCAL FILE";t.localFile=local;return t;}
        throw new Exception(String.join("; ",errors));
    }
    private static Track metadata(StreamInfo info,String query){
        Track t=new Track();t.query=query;t.title=info.getName();t.artist=info.getUploaderName();t.watchUrl=info.getUrl();t.durationMs=Math.max(0,info.getDuration())*1000;
        if(!info.getThumbnails().isEmpty())t.artUrl=info.getThumbnails().get(0).getUrl();return t;
    }
    private static String message(Throwable error){String m=error.getMessage();if(m==null||m.isEmpty())m=error.getClass().getSimpleName();return m.replaceAll("https?://\\S+","[source]").substring(0,Math.min(250,m.replaceAll("https?://\\S+","[source]").length()));}
    private static File localMatch(Context c,String query){
        List<File> files=new ArrayList<>();collect(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),files,0);
        File best=null;int score=0;for(File f:files){String name=stripExt(f.getName()).toLowerCase(Locale.US);int rank=0;for(String token:query.toLowerCase(Locale.US).split("\\s+"))if(token.length()>2&&name.contains(token))rank+=token.length();if(rank>score){score=rank;best=f;}}
        return score>=4?best:null;
    }
    private static void collect(File dir,List<File> out,int depth){
        if(dir==null||!dir.isDirectory()||depth>12||out.size()>10000)return;File[] files=dir.listFiles();if(files==null)return;
        for(File f:files){if(f.isDirectory())collect(f,out,depth+1);else if(f.getName().toLowerCase(Locale.US).matches(".*\\.(mp3|m4a|ogg|wav|flac|aac)$"))out.add(f);}
    }
    private static String stripExt(String name){int i=name.lastIndexOf('.');return i>0?name.substring(0,i):name;}
    public static List<String> suggest(String query){List<String> out=new ArrayList<>();try{for(Track t:search(query))out.add(t.title+" — "+t.artist);}catch(Exception ignored){}return out;}
}
