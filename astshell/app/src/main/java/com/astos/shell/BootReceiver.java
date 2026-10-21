package com.astos.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Boot kiosk: re-apply the system lockdown (no shade / nav bar / lock
 * screen, our keyboard as default IME) and open the dashboard — or the
 * first-boot setup wizard when the device is fresh (astos_setup_done
 * defaults to 0, so the wizard always runs on a new flash).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        Kiosk.apply(context);

        if (!AppSettings.bootLaunch(context)) return;

        boolean setupDone = false;
        try {
            setupDone = Settings.Global.getInt(context.getContentResolver(),
                    "astos_setup_done", 0) == 1;
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

        Intent i = new Intent(context, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
