package com.astos.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Relaunch AstShell after boot (kiosk mode). On very first boot it hands
 * the session to the AstOs setup wizard instead — a clean, custom welcome,
 * not the CyanogenMod/Android one.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!AppSettings.bootLaunch(context)) return;

        boolean setupDone = true;
        try {
            setupDone = Settings.Global.getInt(context.getContentResolver(),
                    "astos_setup_done", 1) == 1;
        } catch (Throwable ignored) {}

        if (!setupDone) {
            try {
                Intent w = new Intent().setClassName("com.astos.setup",
                        "com.astos.setup.SetupActivity");
                w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(w);
                return;
            } catch (Throwable ignored) {
                // wizard missing — fall through to the shell
            }
        }

        if (AppSettings.bootLaunch(context)) {
            Intent i = new Intent(context, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }
}
