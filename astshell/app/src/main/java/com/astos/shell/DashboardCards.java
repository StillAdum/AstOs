package com.astos.shell;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;

/** Reflows visible cards; keeps hidden views attached so refresh callbacks remain safe. */
final class DashboardCards {
    private final Activity activity; private final LinearLayout root;
    private final LinkedHashMap<String,View> cards = new LinkedHashMap<>();
    private final SharedPreferences prefs;
    private final String[] keys={"clock","weather","media","home","plugins"};
    private final String[] labels={"Clock and timer","Weather","Now playing","Home controls","Plugin cards"};
    DashboardCards(Activity a,LinearLayout root){
        activity=a;this.root=root;prefs=a.getSharedPreferences("dashboard",0);
        cards.put("media",a.findViewById(R.id.mediaCard));cards.put("home",a.findViewById(R.id.controlCard));
        View plugins=a.findViewById(R.id.pluginsRow);((ViewGroup)plugins.getParent()).removeView(plugins);
        LinearLayout pluginCard=new LinearLayout(a);pluginCard.setOrientation(1);pluginCard.setPadding(12,12,12,12);pluginCard.setBackgroundDrawable(Ui.d(a,"card"));
        TextView title=new TextView(a);title.setText("Plugin cards");title.setTextColor(Ui.cur.ink);pluginCard.addView(title);pluginCard.addView(plugins);
        cards.put("plugins",pluginCard);reflow();
    }
    void edit(){
        boolean[] selected=new boolean[keys.length];for(int i=0;i<keys.length;i++)selected[i]=prefs.getBoolean(keys[i],true);
        new AlertDialog.Builder(activity).setTitle("Dashboard cards")
            .setMultiChoiceItems(labels,selected,(d,w,on)->selected[w]=on)
            .setPositiveButton("Apply",(d,w)->{SharedPreferences.Editor e=prefs.edit();for(int i=0;i<keys.length;i++)e.putBoolean(keys[i],selected[i]);e.apply();reflow();})
            .setNegativeButton("Cancel",null).show();
    }
    void reflow(){
        activity.findViewById(R.id.clockCard).setVisibility(prefs.getBoolean("clock",true)?View.VISIBLE:View.GONE);
        View weather=activity.findViewById(R.id.weatherCard);
        weather.setVisibility(prefs.getBoolean("weather",true)?View.VISIBLE:View.GONE);
        LinearLayout.LayoutParams weatherParams=(LinearLayout.LayoutParams)weather.getLayoutParams();
        weatherParams.width=prefs.getBoolean("clock",true)?-2:0;weatherParams.weight=prefs.getBoolean("clock",true)?0:1;
        weather.setLayoutParams(weatherParams);
        for(View card:cards.values())if(card.getParent()!=null)((ViewGroup)card.getParent()).removeView(card);
        root.removeAllViews();root.setOrientation(LinearLayout.VERTICAL);
        int dp=(int)(activity.getResources().getDisplayMetrics().widthPixels/activity.getResources().getDisplayMetrics().density);
        int columns=dp>=720?2:1;int count=0;LinearLayout row=null;
        LinearLayout hidden=new LinearLayout(activity);hidden.setVisibility(View.GONE);
        for(Map.Entry<String,View> entry:cards.entrySet()){
            View card=entry.getValue();
            if(!prefs.getBoolean(entry.getKey(),true)){card.setVisibility(View.GONE);hidden.addView(card);continue;}
            card.setVisibility(View.VISIBLE);
            if(count%columns==0){row=new LinearLayout(activity);root.addView(row,new LinearLayout.LayoutParams(-1,-2));}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);lp.setMargins(0,0,Ui.dp(activity,8),Ui.dp(activity,8));row.addView(card,lp);count++;
        }
        root.addView(hidden);
    }
}
