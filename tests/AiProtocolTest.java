import com.astos.shell.AiProtocol;
import com.sun.net.httpserver.*;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AiProtocolTest {
    static String base;static AtomicInteger second=new AtomicInteger(),redirected=new AtomicInteger();
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static JSONObject config(String id,String path,String protocol)throws Exception{return new JSONObject().put("id",id).put("label",id).put("base",base+path).put("key","fixture-key").put("model","test-model").put("protocol",protocol);}
    static void send(HttpExchange x,int status,String body)throws Exception{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().add("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();}
    static void expectFailure(JSONObject c,int status)throws Exception{try{AiProtocol.complete(c,"system","question");throw new AssertionError("Expected failure");}catch(AiProtocol.Failure e){check(e.status==status,"Wrong HTTP status");check(!e.getMessage().contains("fixture-key"),"Credential in error");}}
    public static void main(String[] args)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",x->{try{
            String path=x.getRequestURI().getPath();
            if(path.startsWith("/limited")){send(x,429,"{\"error\":{\"message\":\"quota\"}}");return;}
            if(path.startsWith("/missing")){send(x,404,"{\"error\":{\"message\":\"model fixture-key not found\"}}");return;}
            if(path.startsWith("/blocked")){send(x,200,"{\"candidates\":[],\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");return;}
            if(path.startsWith("/redirect")){x.getResponseHeaders().add("Location",base+"/sink");send(x,302,"{}");return;}
            if(path.startsWith("/sink")){redirected.incrementAndGet();send(x,200,"{}");return;}
            if(path.endsWith("/models")){
                send(x,200,path.contains("gemini")?"{\"models\":[{\"name\":\"models/gem-test\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/embedding\",\"supportedGenerationMethods\":[\"embedContent\"]}]}":"{\"data\":[{\"id\":\"chat-test\"}]}");return;
            }
            JSONObject body=new JSONObject(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if(path.startsWith("/anthropic")){
                check("fixture-key".equals(x.getRequestHeaders().getFirst("x-api-key")),"Anthropic auth");check("2023-06-01".equals(x.getRequestHeaders().getFirst("anthropic-version")),"Anthropic version");check(body.has("system")&&body.getJSONArray("messages").length()==1,"Anthropic payload");
                send(x,200,"{\"content\":[{\"type\":\"text\",\"text\":\"anthropic-ok\"}]}");
            }else if(path.startsWith("/gemini")){
                check("fixture-key".equals(x.getRequestHeaders().getFirst("x-goog-api-key")),"Gemini auth");check(body.has("contents")&&body.has("systemInstruction"),"Gemini payload");check(path.endsWith("/models/test-model:generateContent"),"Gemini endpoint");
                send(x,200,"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hidden\",\"thought\":true},{\"text\":\"gemini-ok\"}]}}]}");
            }else{
                check("Bearer fixture-key".equals(x.getRequestHeaders().getFirst("Authorization")),"Compatible auth");check(body.has("messages")&&body.getString("model").equals("test-model"),"Compatible payload");
                if(path.startsWith("/openai"))check(body.has("max_completion_tokens")&&!body.has("max_tokens"),"OpenAI token limit");
                if(path.startsWith("/second"))second.incrementAndGet();send(x,200,"{\"choices\":[{\"message\":{\"content\":\"compatible-ok\"}}]}");
            }
        }catch(Throwable e){try{send(x,500,"{\"error\":{\"message\":\"fixture assertion: "+e.getMessage()+"\"}}");}catch(Exception ignored){}}});
        server.start();
        try{
            for(String id:new String[]{"openai","groq","openrouter","mistral","custom"})check(AiProtocol.complete(config(id,"/"+id,"openai"),"system","question").equals("compatible-ok"),"Compatible "+id);
            check(AiProtocol.complete(config("anthropic","/anthropic","anthropic"),"system","question").equals("anthropic-ok"),"Anthropic response");
            check(AiProtocol.complete(config("google","/gemini","gemini"),"system","question").equals("gemini-ok"),"Gemini response");
            check(AiProtocol.models(config("google","/gemini","gemini")).equals(Arrays.asList("gem-test")),"Gemini discovery filter");
            check(AiProtocol.models(config("groq","/groq","openai")).equals(Arrays.asList("chat-test")),"Compatible discovery");
            JSONObject full=config("custom","/custom/chat/completions","openai");check(AiProtocol.endpoint(full,false).endsWith("/custom/chat/completions"),"Duplicate endpoint suffix");
            expectFailure(config("groq","/missing","openai"),404);
            JSONArray route=new JSONArray().put(config("groq","/limited","openai")).put(config("custom","/second","openai"));
            try{AiProtocol.route(route,false,"system","question");throw new AssertionError("Fallback without consent");}catch(AiProtocol.Failure expected){}
            check(second.get()==0,"Fallback was not disabled");
            check(AiProtocol.route(route,true,"system","question").equals("compatible-ok")&&second.get()==1,"Fallback order");
            JSONArray blocked=new JSONArray().put(config("google","/blocked","gemini")).put(config("custom","/second","openai"));
            try{AiProtocol.route(blocked,true,"system","question");throw new AssertionError("Empty safety response accepted");}catch(AiProtocol.Failure expected){}
            check(second.get()==1,"Safety feedback must not trigger cross-provider fallback");
            expectFailure(config("custom","/redirect","openai"),302);check(redirected.get()==0,"Credential redirect followed");
            JSONObject insecure=config("custom","/custom","openai").put("base","http://example.com/v1");try{AiProtocol.endpoint(insecure,false);throw new AssertionError("Insecure remote key endpoint");}catch(Exception expected){}
            System.out.println("AI CONTRACT PASS: seven provider presets, native payloads/auth, model discovery, 404 redaction, ordered opt-in fallback, safety non-fallback, redirect/HTTPS guards");
        }finally{server.stop(0);}
    }
}
