package com.astos.setup;

import android.content.ContentResolver;
import android.provider.Settings;

import com.astos.link.LinkJson;
import com.astos.link.LinkServer;

/**
 * AstSetup's AstLink host: the wizard only exposes setup + wifi + info.
 * Plugins and whole-OS commands wait until the shell owns the device.
 */
public final class LinkHostSetup implements LinkServer.Host {

    public static final LinkHostSetup INSTANCE = new LinkHostSetup();

    private LinkHostSetup() {}

    @Override public String appName() { return "astsetup"; }

    @Override public LinkServer.PluginOps plugins() { return null; }

    @Override public boolean systemAllowed() { return false; }

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
        SetupActivity.notifyCompanionChanged();
    }

    @Override public void onSetupFinish(LinkJson.Obj wifi) {
        String ssid = wifi == null ? "" : wifi.str("ssid", "");
        String pass = wifi == null ? "" : wifi.str("pass", "");
        SetupActivity.notifyCompanionSetup(ssid, pass);
    }

    @Override public void onWifiJoined(boolean joined, String ssid, String ip) {
        SetupActivity.notifyCompanionWifi(joined, ssid, ip);
    }

    @Override public void onPaired(String via) {
        SetupActivity.notifyCompanionChanged();
    }
}
