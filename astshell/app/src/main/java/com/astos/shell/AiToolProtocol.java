package com.astos.shell;

import org.json.*;
import java.util.*;

/** Native provider tool calls. Never executes JSON embedded in ordinary model text. */
public final class AiToolProtocol {
    public interface Executor { JSONObject execute(String name,JSONObject args) throws Exception; }
    public interface Transport { JSONObject send(JSONObject cfg,JSONObject body) throws Exception; }
    private AiToolProtocol(){}
    private static JSONObject field(String type,String description) throws Exception {return new JSONObject().put("type",type).put("description",description);}
    private static JSONObject def(String name,String description,JSONObject props,String... required) throws Exception {
        return new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",props).put("required",new JSONArray(Arrays.asList(required))).put("additionalProperties",false));
    }
    public static JSONArray definitions() throws Exception {
        JSONArray a=new JSONArray();
        a.put(def("search_songs","Search YouTube/SoundCloud on the selected player: connected PC or standalone tablet. Returns up to five song links, titles and artists; does not start playback. Use a returned URI for an exact match.",new JSONObject().put("query",field("string","Song title and optional artist")),"query"));
        a.put(def("play_song","Play a full song by name or YouTube/SoundCloud HTTPS link on the selected player. With no PC, play directly on the tablet. PC song-name playback needs only Companion, not Spotify. Explicit Spotify links need the Spotify extension. Never say playback succeeded if this tool returns an error.",new JSONObject().put("query",field("string","Song title, optional artist, or an exact YouTube/SoundCloud link; Spotify links need its extension")),"query"));
        a.put(def("control_playback","Control the selected PC or standalone tablet player. Use pause, resume, next, previous or volume. No automatic pause on transient tablet disconnect.",new JSONObject().put("action",field("string","Playback operation").put("enum",new JSONArray("[\"pause\",\"resume\",\"next\",\"previous\",\"volume\"]"))).put("volume",field("integer","Only for volume: 0–100")),"action"));
        a.put(def("get_playback","Read current PC song, elapsed time, duration and playing state. Treat stale state as estimated, not a confirmed live reading.",new JSONObject()));
        a.put(def("add_reminder","Create a persistent tablet reminder. at must be an ISO-8601 datetime with timezone offset. Ask the user if the time is ambiguous. Survives reboot; cannot ring while powered off.",new JSONObject().put("label",field("string","Reminder text")).put("at",field("string","ISO-8601 timestamp including explicit timezone offset")),"label","at"));
        a.put(def("set_alarm","Create a one-shot persistent tablet alarm at an ISO-8601 datetime including timezone offset. Choose the next occurrence only if the user's intent is unambiguous; otherwise ask. Survives reboot, not power-off.",new JSONObject().put("label",field("string","Alarm label")).put("at",field("string","ISO-8601 timestamp including timezone offset")),"label","at"));
        a.put(def("set_timer","Create a persistent tablet countdown timer, 1 second to 7 days. Runs independently of the assistant screen and survives reboot.",new JSONObject().put("label",field("string","Timer label")).put("seconds",field("integer","Countdown duration: 1–604800 seconds")),"label","seconds"));
        a.put(def("list_schedules","List pending reminders, alarms and timers with IDs and trigger times.",new JSONObject()));
        a.put(def("cancel_schedule","Cancel a pending reminder, alarm or timer by its exact ID from list_schedules. Never guess an ID.",new JSONObject().put("id",field("string","Exact scheduled item ID")),"id"));
        a.put(def("dismiss_alerts","Stop currently ringing tablet alarms, reminders or timers. Does not delete future scheduled items.",new JSONObject()));
        return a;
    }
    public static String route(JSONArray providers,boolean fallback,String system,String question,Executor executor) throws Exception {
        return route(providers,fallback,system,question,executor,(cfg,body)->AiProtocol.http(cfg,AiProtocol.endpoint(cfg,false),body));
    }
    public static String route(JSONArray providers,boolean fallback,String system,String question,Executor executor,Transport http) throws Exception {
        Exception last=null;int attempted=0;
        for(int i=0;i<providers.length();i++){
            JSONObject cfg=providers.optJSONObject(i);if(cfg==null||!cfg.optBoolean("enabled",true))continue;
            if(attempted++>0&&!fallback)break;
            boolean executed=false;Map<String,JSONObject> results=new HashMap<>();
            try{
                String protocol=cfg.optString("protocol","openai");JSONObject body=AiProtocol.request(cfg,system,question);
                JSONArray defs=definitions(),tools=new JSONArray();
                for(int d=0;d<defs.length();d++){
                    JSONObject f=defs.getJSONObject(d);
                    if(protocol.equals("anthropic"))tools.put(new JSONObject().put("name",f.getString("name")).put("description",f.getString("description")).put("input_schema",f.getJSONObject("parameters")));
                    else if(protocol.equals("gemini")){JSONObject g=new JSONObject(f.toString());g.getJSONObject("parameters").remove("additionalProperties");tools.put(g);}
                    else tools.put(new JSONObject().put("type","function").put("function",f));
                }
                body.put("tools",protocol.equals("gemini")?new JSONArray().put(new JSONObject().put("functionDeclarations",tools)):tools);
                if(!protocol.equals("gemini"))body.put("tool_choice",protocol.equals("anthropic")?new JSONObject().put("type","auto"):"auto");
                int callsMade=0;
                for(int round=0;round<6;round++){
                    JSONObject response=http.send(cfg,body);JSONArray calls=new JSONArray(),history=body.getJSONArray(protocol.equals("gemini")?"contents":"messages");
                    if(protocol.equals("anthropic")){
                        JSONArray blocks=response.optJSONArray("content");if(blocks!=null)for(int j=0;j<blocks.length();j++){JSONObject b=blocks.getJSONObject(j);if(b.optString("type").equals("tool_use"))calls.put(new JSONObject().put("id",b.getString("id")).put("name",b.getString("name")).put("args",b.getJSONObject("input")));}
                        if(calls.length()>0)history.put(new JSONObject().put("role","assistant").put("content",blocks));
                    }else if(protocol.equals("gemini")){
                        JSONArray candidates=response.optJSONArray("candidates");JSONObject content=candidates==null||candidates.length()==0?null:candidates.getJSONObject(0).optJSONObject("content");
                        JSONArray parts=content==null?null:content.optJSONArray("parts");
                        if(parts!=null)for(int j=0;j<parts.length();j++){JSONObject f=parts.getJSONObject(j).optJSONObject("functionCall");if(f!=null)calls.put(new JSONObject().put("id",f.optString("id",round+":"+j)).put("name",f.getString("name")).put("args",f.optJSONObject("args")==null?new JSONObject():f.getJSONObject("args")).put("providerId",f.optString("id","")));}
                        // Preserve thought signatures and complete model content byte-for-byte as JSON.
                        if(calls.length()>0)history.put(content);
                    }else{
                        JSONArray choices=response.optJSONArray("choices");JSONObject msg=choices==null||choices.length()==0?null:choices.getJSONObject(0).optJSONObject("message");JSONArray raw=msg==null?null:msg.optJSONArray("tool_calls");
                        if(raw!=null)for(int j=0;j<raw.length();j++){JSONObject call=raw.getJSONObject(j),f=call.getJSONObject("function");calls.put(new JSONObject().put("id",call.getString("id")).put("name",f.getString("name")).put("args",new JSONObject(f.getString("arguments"))));}
                        if(calls.length()>0)history.put(msg);
                    }
                    if(calls.length()==0)return AiProtocol.parse(cfg,response);
                    JSONArray outputs=new JSONArray();
                    for(int j=0;j<calls.length();j++){
                        JSONObject call=calls.getJSONObject(j);String id=call.getString("id"),name=call.getString("name");JSONObject output=results.get(id);
                        if(output==null){
                            if(++callsMade>12)throw new Exception("Assistant tool limit reached; stopped safely");
                            executed=true;boolean known=false;for(int d=0;d<defs.length();d++)if(defs.getJSONObject(d).getString("name").equals(name))known=true;
                            try{if(!known)throw new Exception("Unknown tool: "+name);output=executor.execute(name,call.getJSONObject("args"));}
                            catch(Exception e){output=new JSONObject().put("ok",false).put("error",String.valueOf(e.getMessage()));}
                            results.put(id,output);
                        }
                        if(protocol.equals("anthropic"))outputs.put(new JSONObject().put("type","tool_result").put("tool_use_id",id).put("content",output.toString()).put("is_error",!output.optBoolean("ok",true)));
                        else if(protocol.equals("gemini")){
                            JSONObject f=new JSONObject().put("name",name).put("response",output);if(!call.optString("providerId").isEmpty())f.put("id",call.getString("providerId"));outputs.put(new JSONObject().put("functionResponse",f));
                        }else history.put(new JSONObject().put("role","tool").put("tool_call_id",id).put("content",output.toString()));
                    }
                    if(protocol.equals("anthropic"))history.put(new JSONObject().put("role","user").put("content",outputs));
                    else if(protocol.equals("gemini"))history.put(new JSONObject().put("role","user").put("parts",outputs));
                }
                throw new Exception("Assistant tool round limit reached; stopped safely");
            }catch(Exception e){
                // Never repeat successful/uncertain side effects through a fallback provider.
                if(executed)throw new Exception("Tools were attempted; provider follow-up failed. Check playback/schedules before retrying. "+e.getMessage());
                last=e;if(!(e instanceof AiProtocol.Failure)||!((AiProtocol.Failure)e).mayFallback())throw e;
            }
        }
        throw last==null?new Exception("No AI provider configured"):last;
    }
}
