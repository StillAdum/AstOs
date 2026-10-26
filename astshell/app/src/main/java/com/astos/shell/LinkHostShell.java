package com.astos.shell;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.astos.link.LinkJson;
import com.astos.link.LinkServer;

import java.io.File;
import java.util.List;

/**
 * AstShell's AstLink host: full management after setup — plugins, the
 * whole-OS system grant, provisioning, and the shell's own completions.
 */
public final class LinkHostShell implements LinkServer.Host {

    private static LinkHostShell sHost;

    public static synchronized void ensure(Context ctx) {
        if (sHost == null) sHost = new LinkHostShell();
        LinkServer.get(ctx, sHost).start();
    }

    private LinkHostShell() {}

    @Override public String appName() { return "astshell"; }

    @Override public LinkServer.PluginOps plugins() { return PLUGINS; }

    private final LinkServer.PluginOps PLUGINS = new LinkServer.PluginOps() {
        @Override public LinkJson.Arr list() {
            LinkJson.Arr a = new LinkJson.Arr();
            PluginHost h = PluginHost.get();
            if (h == null) return a;
            for (PluginHost.Plugin p : h.list()) {
                a.add(new LinkJson.Obj()
                        .put("id", p.id)
                        .put("name", p.name())
                        .put("version", p.version())
                        .put("author", p.author())
                        .put("enabled", p.enabled)
                        .put("permissions", strArr(p.permissions()))
                        .put("error", p.error)
                        .put("cards", p.cards.size()));
            }
            return a;
        }

        @Override public boolean setEnabled(String id, boolean on) {
            PluginHost h = PluginHost.get();
            return h != null && h.setEnabled(id, on);
        }

        @Override public boolean remove(String id) {
            PluginHost h = PluginHost.get();
            return h != null && h.remove(id);
        }

        @Override public LinkJson.Obj install(String path) throws Exception {
            PluginHost h = PluginHost.get();
            if (h == null) throw new IllegalStateException("plugin host not running");
            String id = h.install(new File(path));
            PluginHost.Plugin p = h.find(id);
            LinkJson.Obj r = new LinkJson.Obj().put("id", id);
            if (p != null) {
                r.put("name", p.name()).put("version", p.version())
                        .put("enabled", p.enabled)
                        .put("permissions", strArr(p.permissions()))
                        .put("error", p.error);
            }
            return r;
        }

        private LinkJson.Arr strArr(String[] in) {
            LinkJson.Arr a = new LinkJson.Arr();
            for (String s : in) a.add(s);
            return a;
        }
    };

    @Override public boolean systemAllowed() { return true; }

    @Override public boolean completeSetup(ContentResolver cr) {
        try {
            Settings.Global.putInt(cr, Settings.Global.DEVICE_PROVISIONED, 1);
            Settings.Secure.putInt(cr, "user_setup_complete", 1);
            Settings.Global.putInt(cr, "astos_setup_done", 1);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public void onSetupChanged() {
        PluginHost host = PluginHost.get();
        if (host != null && !AppSettings.pluginsEnabled(HomeActivity.sAppContext)) {
            for (PluginHost.Plugin plugin : host.list()) if (plugin.enabled) host.setEnabled(plugin.id, false);
        }
        // the shell reads Settings.Global live on its next frame — nothing
        // to do but wake the home screen for a repaint
        HomeActivity.requestRefresh();
    }

    @Override public void onSetupFinish(LinkJson.Obj wifi) {
        // companion completed setup: honor the wifi join when included
        if (wifi != null && wifi.has("ssid")) {
            // LinkServer already joined via wifi.join in the normal flow;
            // this is the "finish without wifi" case
        }
        HomeActivity.requestRefresh();
    }

    @Override public void onWifiJoined(boolean joined, String ssid, String ip) {
        HomeActivity.requestRefresh();
    }

    @Override public void onPaired(String via) {
        PluginHost.Log.i("companion paired via " + via);
        final String note = "usb".equals(via)
                ? "PC companion connected (USB)"
                : "PC companion connected (Bluetooth)";
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                HomeActivity.requestRefresh();
                android.widget.Toast.makeText(
                        HomeActivity.sAppContext, note, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
}
