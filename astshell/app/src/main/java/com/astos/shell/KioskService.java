package com.astos.shell;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * KioskService — the persistent shade-killer (v2.4).
 *
 * Why this exists: StatusBarManager.disable(DISABLE_EXPAND | ...) is bound
 * to the CALLING process's binder token. SystemUI silently clears the flags
 * when that binder dies (low-RAM kill, process restart) and when SystemUI
 * itself restarts — which is exactly why the v2.3 notification shade could
 * still be pulled down sometimes, with its black border showing.
 *
 * This foreground service keeps AstShell's process alive 24/7 and
 * re-asserts the disable on:
 *   - a 20 s heartbeat (catches SystemUI restarts within seconds)
 *   - ACTION_SCREEN_ON / USER_PRESENT (catches every wake-up)
 *   - ACTION_TIME_TICK (once a minute, in addition to the heartbeat)
 *
 * START_STICKY: if the system ever does kill us, we come straight back.
 */
public class KioskService extends Service {

    private EdgeNavigation navigation;
    private static final long HEARTBEAT_MS = 20_000L;

    private final Handler mH = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver mRecv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            reassert();
        }
    };

    private final Runnable mBeat = new Runnable() {
        @Override public void run() {
            reassert();
            mH.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private void reassert() {
        try {
            Kiosk.disableShadePublic(this);
            Kiosk.hideSystemBarsPublic(this);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Notification n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle("AstOs")
                    .setContentText("system lockdown active")
                    .setPriority(Notification.PRIORITY_MIN)
                    .setOngoing(true)
                    .build();
            startForeground(0x414F53 /* "AOS" */, n);
        } catch (Throwable ignored) {
            // foreground promotion is best-effort; the heartbeat still runs
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_TIME_TICK);
        try { registerReceiver(mRecv, f); } catch (Throwable ignored) {}
        navigation=new EdgeNavigation(this);navigation.start();
        reassert();
        mH.postDelayed(mBeat, HEARTBEAT_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        reassert();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if(navigation!=null)navigation.stop();
        mH.removeCallbacksAndMessages(null);
        try { unregisterReceiver(mRecv); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Idempotent start from anywhere (BootReceiver, HomeActivity, Setup). */
    public static void ensureRunning(Context c) {
        try {
            c.startService(new Intent(c, KioskService.class));
        } catch (Throwable ignored) {
            // side-loaded case: no foreground service powers — heartbeat in
            // HomeActivity's receivers still covers the common paths
        }
    }
}
