package com.astos.shell;
import android.content.Context;
/** Legacy class name retained for existing callers; routing is provider-neutral. */
public final class GroqClient {
    public interface Callback {void onReply(String text,String error);}
    private GroqClient(){}
    public static void ask(Context context,String question,Callback callback){AiProviders.ask(context,question,callback::onReply);}
}
