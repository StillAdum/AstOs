package com.astos.shell;

import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** Pure Java HTTP adapters. Never assumes Anthropic/Gemini are OpenAI-compatible. */
public final class AiProtocol {
    public static final class Failure extends Exception {
        public final int status;
        public Failure(int status,String message){super(message);this.status=status;}
        public boolean mayFallback(){return status==0||status==401||status==402||status==403||status==404||status==408||status==429||status>=500;}
    }
    private AiProtocol(){}
    public static String route(JSONArray providers,boolean fallback,String system,String question) throws Exception {
        StringBuilder errors=new StringBuilder();int attempted=0;
        for(int i=0;i<providers.length();i++){
            JSONObject provider=providers.optJSONObject(i);if(provider==null||!provider.optBoolean("enabled",true))continue;
            if(attempted>0&&!fallback)break;attempted++;
            try{return complete(provider,system,question);}
            catch(Exception e){
                errors.append(e.getMessage()).append("\n");
                if(!(e instanceof Failure)||!((Failure)e).mayFallback())break;
            }
        }
        throw new Failure(0,attempted==0?"No AI provider configured. Add a key and select a model.":errors.toString().trim());
    }
    public static String endpoint(JSONObject config,boolean models) throws Exception {
        String base=config.getString("base").trim().replaceAll("/+$", "");
        URI uri=new URI(base);
        String host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Exception("Use a clean API base URL without credentials or query parameters");
        if(!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme())&&(host.equals("127.0.0.1")||host.equals("localhost")||host.equals("[::1]"))))throw new Exception("Provider URLs require HTTPS (HTTP is allowed only on loopback)");
        // Accept either the API base or the full compatible endpoint.
        base=base.replaceAll("/(chat/completions|messages)$", "");
        if(models)return base+"/models";
        String protocol=config.optString("protocol","openai");
        if("anthropic".equals(protocol))return base+"/messages";
        if("gemini".equals(protocol)){
            String model=config.getString("model").replaceFirst("^models/", "");
            return base+"/models/"+URLEncoder.encode(model,"UTF-8").replace("+","%20")+":generateContent";
        }
        return base+"/chat/completions";
    }
    public static JSONObject request(JSONObject config,String system,String question) throws Exception {
        String protocol=config.optString("protocol","openai"),model=config.optString("model","");
        if(model.isEmpty())throw new Exception("Choose a model using Fetch models");
        JSONObject user=new JSONObject().put("role","user").put("content",question);
        if("anthropic".equals(protocol))return new JSONObject().put("model",model).put("system",system).put("max_tokens",600).put("messages",new JSONArray().put(user));
        if("gemini".equals(protocol))return new JSONObject()
            .put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system))))
            .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",question)))))
            .put("generationConfig",new JSONObject().put("maxOutputTokens",600));
        return new JSONObject().put("model",model)
            .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",system)).put(user))
            .put(config.optString("id").equals("openai")?"max_completion_tokens":"max_tokens",600);
    }
    public static String parse(JSONObject config,JSONObject body) throws Exception {
        String protocol=config.optString("protocol","openai");
        StringBuilder text=new StringBuilder();
        if("anthropic".equals(protocol)){
            JSONArray blocks=body.optJSONArray("content");if(blocks!=null)for(int i=0;i<blocks.length();i++){JSONObject b=blocks.getJSONObject(i);if("text".equals(b.optString("type")))text.append(b.optString("text"));}
        }else if("gemini".equals(protocol)){
            JSONArray candidates=body.optJSONArray("candidates");
            if(candidates!=null&&candidates.length()>0){JSONObject content=candidates.getJSONObject(0).optJSONObject("content");JSONArray parts=content==null?null:content.optJSONArray("parts");if(parts!=null)for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(!part.optBoolean("thought",false))text.append(part.optString("text",""));}}
        }else{
            JSONArray choices=body.optJSONArray("choices");if(choices!=null&&choices.length()>0){JSONObject message=choices.getJSONObject(0).getJSONObject("message");if(!message.isNull("content"))text.append(message.optString("content",""));if(text.length()==0)text.append(message.optString("refusal",""));}
        }
        if(text.toString().trim().isEmpty())throw new Failure(422,"No text response (check model capabilities or provider safety feedback)");
        return text.toString().trim();
    }
    public static String complete(JSONObject config,String system,String question) throws Exception {
        return parse(config,http(config,endpoint(config,false),request(config,system,question)));
    }
    public static List<String> models(JSONObject config) throws Exception {
        JSONObject response=http(config,endpoint(config,true),null);List<String> out=new ArrayList<>();
        boolean gemini=config.optString("protocol").equals("gemini");JSONArray rows=response.optJSONArray(gemini?"models":"data");
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String id=row.optString(gemini?"name":"id","").replaceFirst("^models/","");
            JSONArray methods=row.optJSONArray("supportedGenerationMethods");
            if(gemini&&methods!=null&&!methods.toString().contains("generateContent"))continue;
            if(!id.isEmpty())out.add(id);
        }
        Collections.sort(out);return out;
    }
    private static JSONObject http(JSONObject cfg,String url,JSONObject body) throws Exception {
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(25000);
            String key=cfg.optString("key", ""),protocol=cfg.optString("protocol","openai");
            c.setRequestProperty("Content-Type","application/json");
            if(protocol.equals("anthropic")){c.setRequestProperty("x-api-key",key);c.setRequestProperty("anthropic-version","2023-06-01");}
            else if(protocol.equals("gemini"))c.setRequestProperty("x-goog-api-key",key);
            else if(!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key);
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
            int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();StringBuilder raw=new StringBuilder();
            if(stream!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,"UTF-8"))){String line;while((line=reader.readLine())!=null){raw.append(line);if(raw.length()>524288)throw new Failure(502,"Provider response too large");}}
            JSONObject response;try{response=new JSONObject(raw.toString());}catch(Exception e){response=new JSONObject();}
            if(status<200||status>=300){
                JSONObject error=response.optJSONObject("error");String detail=error==null?response.optString("message",""):error.optString("message",error.optString("code",""));
                if(!key.isEmpty())detail=detail.replace(key,"[redacted]");if(detail.length()>220)detail=detail.substring(0,220);
                String help=status==404?"Model or endpoint unavailable; open AI providers → Fetch models. ":status==401?"API key rejected. ":status==429?"Rate limit or quota exceeded. ":"";
                throw new Failure(status,cfg.optString("label",cfg.optString("id","Provider"))+" HTTP "+status+": "+help+detail);
            }
            return response;
        }catch(IOException e){throw new Failure(0,"Network error: "+e.getClass().getSimpleName());}
        finally{if(c!=null)c.disconnect();}
    }
}
