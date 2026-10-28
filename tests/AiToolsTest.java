import com.astos.shell.*;
import org.json.*;
import java.util.concurrent.atomic.AtomicInteger;
public class AiToolsTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static JSONObject cfg(String protocol)throws Exception{return new JSONObject().put("protocol",protocol).put("model","fixture-model").put("base","https://example.com/v1");}
 static JSONObject answer(String p)throws Exception{
  if(p.equals("anthropic"))return new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"Done\"}]}");
  if(p.equals("gemini"))return new JSONObject("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Done\"}]}}]}");
  return new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Done\"}}]}");
 }
 static JSONObject call(String p)throws Exception{
  if(p.equals("anthropic"))return new JSONObject("{\"content\":[{\"type\":\"tool_use\",\"id\":\"call1\",\"name\":\"play_song\",\"input\":{\"query\":\"Test\"}}]}");
  if(p.equals("gemini"))return new JSONObject("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"thoughtSignature\":\"retain-exactly\",\"functionCall\":{\"id\":\"call1\",\"name\":\"play_song\",\"args\":{\"query\":\"Test\"}}}]}}]}");
  return new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"play_song\",\"arguments\":\"{\\\"query\\\":\\\"Test\\\"}\"}}]}}]}");
 }
 public static void main(String[] argv)throws Exception{
  for(String p:new String[]{"openai","anthropic","gemini"}){
   AtomicInteger http=new AtomicInteger(),exec=new AtomicInteger();
   String result=AiToolProtocol.route(new JSONArray().put(cfg(p)),false,"system","play a song",(n,a)->{check(n.equals("play_song"),"tool name");exec.incrementAndGet();return new JSONObject().put("ok",true);},(c,b)->{
    check(b.getJSONArray("tools").length()>0,"native definitions");int turn=http.getAndIncrement();
    if(turn==0)return call(p);
    String serialized=b.toString();check(serialized.contains(p.equals("openai")?"tool_call_id":p.equals("anthropic")?"tool_use_id":"functionResponse"),"correlated result");
    if(p.equals("gemini"))check(serialized.contains("retain-exactly"),"thought signature retained");
    return answer(p);
   });check(result.equals("Done")&&exec.get()==1&&http.get()==2,p+" tool cycle");
  }
  AtomicInteger http=new AtomicInteger(),exec=new AtomicInteger();
  try{AiToolProtocol.route(new JSONArray().put(cfg("openai")).put(cfg("anthropic")),true,"sys","play",(n,a)->{exec.incrementAndGet();return new JSONObject().put("ok",true);},(c,b)->{if(http.getAndIncrement()==0)return call("openai");throw new AiProtocol.Failure(503,"offline");});throw new AssertionError("must fail");}catch(Exception e){check(e.getMessage().contains("Tools were attempted"),"uncertain side effect warning");}
  check(exec.get()==1&&http.get()==2,"no fallback replay after action");
  http.set(0);String text=AiToolProtocol.route(new JSONArray().put(cfg("openai")).put(cfg("anthropic")),true,"s","q",(n,a)->new JSONObject(),(c,b)->{if(http.getAndIncrement()==0)throw new AiProtocol.Failure(503,"offline");return answer("anthropic");});check(text.equals("Done")&&http.get()==2,"fallback before tool is allowed");
  exec.set(0);http.set(0);AiToolProtocol.route(new JSONArray().put(cfg("openai")),false,"s","q",(n,a)->{exec.incrementAndGet();return new JSONObject();},(c,b)->http.getAndIncrement()<2?call("openai"):answer("openai"));check(exec.get()==1,"duplicate call ID not replayed");
  check(AiToolProtocol.definitions().length()==10,"all music and scheduling tools registered");
  System.out.println("AI TOOLS PASS: native OpenAI/Claude/Gemini cycles, correlated results, signatures, dedup, safe fallback, complete catalog");
 }
}
