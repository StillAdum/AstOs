package com.astos.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Relaunch the AstShell dashboard after boot (kiosk mode). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && AppSettings.bootLaunch(context)) {
            Intent i = new Intent(context, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }
}
