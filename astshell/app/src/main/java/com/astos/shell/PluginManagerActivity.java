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
        sub.setText("plugins in /sdcard/AstOs/plugins (*.astpl)");
        sub.setTextSize(10f);
        sub.setTextColor(Ui.col("soft"));
        root.addView(sub);

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
        master.setText("system mods: master switch");
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
        toast(now ? "system mods allowed" : "system mods blocked");
        rebuild();
    }

    private void scanInstall() {
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
                    f.delete();       // keep the inbox clean
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

    private void rebuild() {
        if (mList == null) return;
        mList.removeAllViews();
        PluginHost ph = PluginHost.get();
        List<PluginHost.Plugin> plugins = ph == null
                ? java.util.Collections.<PluginHost.Plugin>emptyList() : ph.list();

        mMasterBtn.setText("system mods: " + (AppSettings.pluginsEnabled(this) ? "ON" : "OFF"));

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
                    if (ph != null) ph.setEnabled(p.id, on);
                    rebuild();
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
        new AlertDialog.Builder(this, R.style.DialogTheme)
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
