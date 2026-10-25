package com.astos.shell;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * Power actions via platform-signed reflection:
 * reboot through PowerManager.reboot(reason), shutdown through
 * IPowerManager / the ACTION_REQUEST_SHUTDOWN intent. Several
 * fallbacks — first one that sticks wins.
 */
public final class SysPower {

    private SysPower() {}

    public static void reboot(final Context c) {
        // 1) PowerManager.reboot(String)
        try {
            Object pm = c.getSystemService(Context.POWER_SERVICE);
            Method m = pm.getClass().getMethod("reboot", String.class);
            m.invoke(pm, new Object[]{null});
            return;
        } catch (Throwable ignored) { }
        // 2) REBOOT intent
        try {
            Intent i = new Intent("android.intent.action.REBOOT");
            i.putExtra("nowait", 1);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) { }
        toast(c, "Reboot not permitted — hold power 10s to force");
    }

    public static void shutdown(final Context c) {
        // 1) IPowerManager.shutdown(...) — try known signatures
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "power");
            Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
            Object ipm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            for (Method m : ipm.getClass().getMethods()) {
                String n = m.getName();
                if ("shutdown".equals(n) || "shutdownOrRebootFromPowerModule".equals(n)) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2) { // (boolean confirm, boolean wait)
                        m.invoke(ipm, false, false);
                        return;
                    }
                    if (p.length == 3 && p[2] == String.class) { // (confirm, wait, reason)
                        m.invoke(ipm, false, false, null);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) { }
        // 2) ACTION_REQUEST_SHUTDOWN
        try {
            Intent i = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
            i.putExtra("android.intent.extra.KEY_CONFIRM", false);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) { }
        toast(c, "Shutdown not permitted — hold power 10s to force");
    }

    private static void toast(final Context c, final String s) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                Toast.makeText(c, s, Toast.LENGTH_LONG).show();
            }
        });
    }
}
