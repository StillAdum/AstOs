package com.astos.shell;
import android.app.*;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
import android.view.View;
import android.view.ViewGroup;
import org.json.*;
import java.util.*;

/** API credentials stay in app-private preferences; never shown in companion snapshots. */
public final class ProviderSettingsActivity extends Activity {
    private JSONArray providers; private LinearLayout body; private TextView status;
    @Override public void onCreate(Bundle b){super.onCreate(b);providers=AiProviders.load(this);showList();}
    private void page(String title){
        ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(20,12,20,20);scroll.addView(body);setContentView(scroll);Ui.apply(this,body);
        TextView heading=new TextView(this);heading.setText(title);heading.setTextColor(Ui.cur.ink);heading.setTextSize(22);body.addView(heading);
        status=new TextView(this);status.setTextColor(Ui.cur.ink);body.addView(status);
    }
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);body.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());return b;}
    private void showList(){
        page("AI providers — primary first");button("‹ Back to AstOS",this::finish);
        TextView note=new TextView(this);note.setText("API keys and API billing are separate from ChatGPT/Claude subscriptions. Prompts are sent to the selected provider. Speech recognition is configured separately.");note.setTextColor(Ui.cur.ink);body.addView(note);
        CheckBox fallback=new CheckBox(this);fallback.setText("Allow fallback: send failed prompts to the next enabled provider (may incur charges)");fallback.setChecked(AiProviders.fallback(this));body.addView(fallback);fallback.setOnCheckedChangeListener((v,on)->AiProviders.fallback(this,on));
        for(int i=0;i<providers.length();i++){
            final int index=i;JSONObject p=providers.optJSONObject(i);if(p==null)continue;
            button((i==0?"PRIMARY · ":"FALLBACK "+i+" · ")+p.optString("label")+" · "+p.optString("model","choose model")+(p.optBoolean("enabled",true)?"":" [disabled]"),()->edit(index));
        }
        button("+ Add provider",()->{
            String[] names={"Groq","OpenAI / ChatGPT API","Anthropic / Claude","Google Gemini","OpenRouter","Mistral AI","Custom (OpenAI compatible)"};
            String[] ids={"groq","openai","anthropic","google","openrouter","mistral","custom"};
            new AlertDialog.Builder(this).setTitle("Provider type").setItems(names,(d,w)->{try{providers.put(AiProviders.preset(ids[w]));edit(providers.length()-1);}catch(Exception e){status.setText(e.getMessage());}}).show();
        });
    }
    private void edit(int index){
        JSONObject source=providers.optJSONObject(index);if(source==null)return;
        final JSONObject cfg;try{cfg=new JSONObject(source.toString());}catch(Exception e){return;}
        page("Configure "+cfg.optString("label"));button("‹ Provider list",this::showList);
        field(cfg,"label","Display name",false);field(cfg,"base","API base URL",false);field(cfg,"key","API key",true);field(cfg,"model","Model ID",false);
        if(cfg.optString("id").equals("custom"))button("Protocol: "+cfg.optString("protocol"),()->new AlertDialog.Builder(this).setTitle("Custom API protocol").setItems(new String[]{"OpenAI-compatible","Anthropic messages","Gemini generateContent"},(d,w)->{try{cfg.put("protocol",new String[]{"openai","anthropic","gemini"}[w]);status.setText("Protocol: "+cfg.optString("protocol"));}catch(Exception ignored){}}).show());
        CheckBox enabled=new CheckBox(this);enabled.setText("Enabled in primary/fallback order");enabled.setChecked(cfg.optBoolean("enabled",true));body.addView(enabled);
        button("Fetch models from this provider",()->{
            status.setText("Fetching models…");new Thread(()->{
                try{List<String> ids=AiProtocol.models(cfg);runOnUiThread(()->{
                    if(ids.isEmpty()){status.setText("No models returned. Enter a supported model ID from your provider account.");return;}
                    new AlertDialog.Builder(this).setTitle("Choose an available model").setItems(ids.toArray(new String[0]),(d,w)->{try{cfg.put("model",ids.get(w));status.setText("Model selected: "+ids.get(w)+". Save below.");}catch(Exception ignored){}}).show();
                });}catch(Exception e){runOnUiThread(()->status.setText(e.getMessage()));}
            },"ai-model-list").start();
        });
        button("Test provider (makes an API request)",()->{
            status.setText("Testing…");new Thread(()->{
                try{String reply=AiProtocol.complete(cfg,"Answer briefly.","Reply only OK.");runOnUiThread(()->status.setText("Connected: "+reply));}
                catch(Exception e){runOnUiThread(()->status.setText(e.getMessage()));}
            },"ai-provider-test").start();
        });
        button("Save provider",()->{
            try{
                if(cfg.optString("model").trim().isEmpty())throw new Exception("Select a model first");
                AiProtocol.endpoint(cfg,false);cfg.put("enabled",enabled.isChecked());providers.put(index,cfg);AiProviders.save(this,providers);showList();
            }catch(Exception e){status.setText(e.getMessage());}
        });
        if(index>0)button("Move up in fallback order",()->{try{Object before=providers.get(index-1);providers.put(index-1,source);providers.put(index,before);AiProviders.save(this,providers);showList();}catch(Exception ignored){}});
        if(index+1<providers.length())button("Move down in fallback order",()->{try{Object after=providers.get(index+1);providers.put(index+1,source);providers.put(index,after);AiProviders.save(this,providers);showList();}catch(Exception ignored){}});
        button("Remove provider",()->new AlertDialog.Builder(this).setMessage("Remove this provider and its saved key?").setPositiveButton("Remove",(d,w)->{providers.remove(index);AiProviders.save(this,providers);showList();}).setNegativeButton("Cancel",null).show());
    }
    private void field(JSONObject cfg,String key,String title,boolean secret){
        Button button=button(title+": "+(secret?(cfg.optString(key).isEmpty()?"not set":"saved (hidden)"):cfg.optString(key)),()->{});
        button.setOnClickListener(v->com.astos.link.TextEntry.show(this,title,cfg.optString(key),secret,value->{try{cfg.put(key,value.trim());button.setText(title+": "+(secret?(value.isEmpty()?"not set":"saved (hidden)"):value));}catch(Exception ignored){}}));
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)Ui.setKioskBars(this,true);}
}
