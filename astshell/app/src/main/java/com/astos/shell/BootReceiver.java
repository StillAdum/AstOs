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
        // persistent shade-killer: holds StatusBarManager.disable() alive
        // from a foreground process with a 20s heartbeat (v2.4 fix for the
        // "notification center can still be opened" bug)
        KioskService.ensureRunning(context);

        // v3.1: the PC companion channel (Bluetooth SPP + USB/adb). The
        // tablet listens; the companion connects. Bluetooth is flipped on
        // so "pair from the PC" just works on a kiosk tablet.
        try {
            android.bluetooth.BluetoothAdapter bt =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (bt != null && !bt.isEnabled()) bt.enable();
        } catch (Throwable ignored) {}

        if (!AppSettings.bootLaunch(context)) return;

        boolean setupDone = false;
        try {
            setupDone = Settings.Global.getInt(context.getContentResolver(),
                    "astos_setup_done", 0) == 1;
        } catch (Throwable ignored) {}

        if (setupDone) {
            try { LinkHostShell.ensure(context); } catch (Throwable ignored) {}
        }

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
