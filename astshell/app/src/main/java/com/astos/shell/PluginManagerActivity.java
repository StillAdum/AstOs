package com.astos.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * The .astpl mod manager: list installed plugins with permission chips and
 * error lines, flip them on/off, remove them, install new ones from
 * /sdcard/AstOs/plugins/*.astpl (drop them there via USB or the PC
 * companion), and control the master switch for system-grade mods.
 */
public class PluginManagerActivity extends Activity implements PluginHost.Listener {

    private LinearLayout mList;
    private TextView mEmpty;
    private final Handler mH = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PluginHost.init(this).setListener(this);

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("AstOs mods");
        title.setTypeface(Ui.monoB(this));
        title.setTextSize(24f);
        title.setTextColor(Ui.col("ink"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Install trusted .astpl packages from PC, internal storage or SD card.");
        sub.setTextSize(10f);
        sub.setTextColor(Ui.col("soft"));
        root.addView(sub);

        Button back=new Button(this);back.setText("Back");back.setOnClickListener(v->finish());root.addView(back,lp(8));
        Button pick=new Button(this);pick.setText("Choose .astpl file (including SD card)");pick.setOnClickListener(v->{android.content.Intent i=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(android.content.Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,43);}catch(Exception e){toast("No file picker available: "+e.getMessage());}});root.addView(pick,lp(8));
        Button scan = new Button(this);
        scan.setText("scan + install from sdcard");
        scan.setAllCaps(false);
        scan.setTypeface(Ui.monoB(this));
        Ui.press(scan, 2);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanInstall(); }
        });
        root.addView(scan, lp(10));

        Button master = new Button(this);
        master.setText("trusted Java plugins: master switch");
        master.setAllCaps(false);
        master.setTypeface(Ui.monoB(this));
        Ui.press(master, 2);
        master.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMaster(); }
        });
        root.addView(master, lp(8));
        mMasterBtn = master;

        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mList, lp(12));

        mEmpty = new TextView(this);
        mEmpty.setText("no plugins installed yet.\n\nput .astpl files in\n/sdcard/AstOs/plugins and tap\nscan — or install from the\nPC companion app.");
        mEmpty.setTextSize(11f);
        mEmpty.setTextColor(Ui.col("soft"));
        mEmpty.setVisibility(View.GONE);
        root.addView(mEmpty, lp(14));

        setContentView(sc);
        Ui.apply(this, sc);
        rebuild();
    }

    private Button mMasterBtn;

    private static LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    @Override protected void onResume() {
        super.onResume();
        PluginHost ph = PluginHost.get();
        if (ph != null) ph.reloadAll();
        rebuild();
    }

    @Override protected void onDestroy() {
        PluginHost ph = PluginHost.get();
        if (ph != null && ph instanceof PluginHost) {
            // keep the host running; only detach our listener if we're it
            try { ph.setListener(this); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onCardsChanged() { mH.post(new Runnable() {
        @Override public void run() { rebuild(); }
    }); }

    @Override public void onToast(final String msg) {
        mH.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(PluginManagerActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void toggleMaster() {
        boolean now = !AppSettings.pluginsEnabled(this);
        AppSettings.setPluginsEnabled(this, now);
        if (!now && PluginHost.get() != null) {
            for (PluginHost.Plugin plugin : PluginHost.get().list())
                if (plugin.enabled) PluginHost.get().setEnabled(plugin.id, false);
        }
        toast(now ? "Trusted plugins can access AstShell data, Java and system permissions" : "Plugins blocked");
        rebuild();
    }

    private void scanInstall() {
        if(checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},44);return;}
        File dir = new File(Environment.getExternalStorageDirectory(), "AstOs/plugins");
        if (!dir.isDirectory()) {
            toast("no /sdcard/AstOs/plugins folder yet");
            return;
        }
        File[] zips = dir.listFiles();
        int installed = 0, failed = 0;
        if (zips != null) {
            for (File f : zips) {
                String n = f.getName().toLowerCase();
                if (!n.endsWith(".astpl") || !f.isFile()) continue;
                try {
                    PluginHost.get().install(f);
                    // Preserve user-owned SD files; imports are copied, never moved.
                    installed++;
                } catch (Exception e) {
                    failed++;
                    PluginHost.Log.e(f.getName() + ": " + e);
                }
            }
        }
        toast(installed + " installed" + (failed > 0 ? ", " + failed + " failed" : ""));
        PluginHost.get().reloadAll();
        rebuild();
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==44&&results.length>0&&results[0]==0)scanInstall();}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request!=43||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();new Thread(()->{File temp=null;try{temp=File.createTempFile("import-",".astpl",getCacheDir());try(java.io.InputStream in=getContentResolver().openInputStream(uri);java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){if(in==null)throw new java.io.IOException("Cannot open selected file");byte[] buf=new byte[8192];long total=0;int n;while((n=in.read(buf))!=-1){total+=n;if(total>256L*1024*1024)throw new java.io.IOException("Package exceeds 256 MB");out.write(buf,0,n);}}PluginHost.get().install(temp);mH.post(()->{toast("Mod installed; review trust before enabling");rebuild();});}catch(Exception e){mH.post(()->toast("Import failed: "+e.getMessage()));}finally{if(temp!=null)temp.delete();}},"ast-mod-import").start();
    }
    private void rebuild() {
        if (mList == null) return;
        mList.removeAllViews();
        PluginHost ph = PluginHost.get();
        List<PluginHost.Plugin> plugins = ph == null
                ? java.util.Collections.<PluginHost.Plugin>emptyList() : ph.list();

        mMasterBtn.setText("trusted Java plugins: " + (AppSettings.pluginsEnabled(this) ? "ON" : "OFF"));

        mEmpty.setVisibility(plugins.isEmpty() ? View.VISIBLE : View.GONE);
        for (final PluginHost.Plugin p : plugins) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(p.name() + "  " + p.version() + "  ·  " + (p.enabled ? "on" : "off"));
            name.setTypeface(Ui.monoB(this));
            name.setTextSize(13f);
            name.setTextColor(Ui.col(p.enabled ? "ink" : "soft"));
            txt.addView(name);
            TextView meta = new TextView(this);
            StringBuilder sb = new StringBuilder();
            for (String perm : p.permissions()) sb.append(perm).append(' ');
            if (p.author() != null && !p.author().isEmpty()) sb.append("· by ").append(p.author());
            meta.setText(sb.toString().trim());
            meta.setTextSize(9f);
            meta.setTextColor(Ui.col("accent"));
            txt.addView(meta);
            if (p.error != null && !p.error.isEmpty()) {
                TextView err = new TextView(this);
                err.setText(p.error);
                err.setTextSize(9f);
                err.setTextColor(0xFFB84E3C);
                txt.addView(err);
            }
            row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button del = new Button(this);
            del.setText("x");
            del.setTypeface(Ui.monoB(this));
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirmRemove(p);
                }
            });
            row.addView(del, new LinearLayout.LayoutParams(
                    Ui.dp(this, 44), Ui.dp(this, 44)));
            Ui.press(del, 2);

            PixelSwitch sw = new PixelSwitch(this, null);
            sw.setOn(p.enabled);
            sw.setListener(new PixelSwitch.On() {
                @Override public void changed(PixelSwitch s, boolean on) {
                    PluginHost ph = PluginHost.get();
                    if (ph != null && on) {
                        new com.astos.link.PixelUi.Builder(PluginManagerActivity.this)
                            .setTitle("Trust Java plugin?")
                            .setMessage("This plugin can access AstShell data, Java classes and all system permissions granted to AstShell. Only enable code you trust.")
                            .setPositiveButton("Trust and enable", (d, w) -> { ph.setEnabled(p.id, true); rebuild(); })
                            .setNegativeButton("Cancel", (d, w) -> rebuild()).show();
                    } else {
                        if (ph != null) ph.setEnabled(p.id, false);
                        rebuild();
                    }
                }
            });
            row.addView(sw, new LinearLayout.LayoutParams(
                    Ui.dp(this, 56), Ui.dp(this, 30)));

            mList.addView(row);

            // separator dot line
            View sep = new View(this);
            sep.setBackgroundColor(Ui.col("off"));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            mList.addView(sep, sl);
        }
    }

    private void confirmRemove(final PluginHost.Plugin p) {
        new com.astos.link.PixelUi.Builder(this, R.style.DialogTheme)
                .setTitle("Remove " + p.name() + "?")
                .setMessage("The mod is deleted from the tablet.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        PluginHost ph = PluginHost.get();
                        if (ph != null) ph.remove(p.id);
                        rebuild();
                    }
                })
                .setNegativeButton("Keep", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
