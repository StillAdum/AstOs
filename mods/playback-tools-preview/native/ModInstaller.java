package com.astos.preview;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compiled to DEX: Rhino JavaAdapter cannot generate Dalvik receiver subclasses. */
public final class ModInstaller {
    private Context context;
    private BroadcastReceiver receiver;
    private final AtomicBoolean busy=new AtomicBoolean();
    private String action;
    public ModInstaller(){}
    private void say(final String text){new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){Toast.makeText(context,text,Toast.LENGTH_LONG).show();}});}
    public synchronized void attach(Context c){
        if(receiver!=null)return;
        context=c.getApplicationContext();action=context.getPackageName()+".PREVIEW_INSTALL_"+UUID.randomUUID().toString();
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            int status=i.getIntExtra(PackageInstaller.EXTRA_STATUS,-999);
            if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
                Intent consent=i.getParcelableExtra(Intent.EXTRA_INTENT);
                if(consent!=null){consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(consent);}
            }else{
                busy.set(false);
                say(status==PackageInstaller.STATUS_SUCCESS?"AstShell update installed; reopen Home":"Update not installed: "+i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }};
        context.registerReceiver(receiver,new IntentFilter(action));
    }
    public synchronized void dispose(){if(receiver!=null){try{context.unregisterReceiver(receiver);}catch(Exception ignored){}receiver=null;}}
    public void install(Context c,final String path){
        attach(c);if(!busy.compareAndSet(false,true)){say("An update is already being prepared");return;}
        say("Preparing AstShell update — confirm installation when asked");
        new Thread(new Runnable(){public void run(){
            PackageInstaller.Session session=null;
            try{
                File apk=new File(path);PackageManager pm=context.getPackageManager();
                PackageInfo old=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);
                PackageInfo incoming=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNATURES);
                if(incoming==null||!context.getPackageName().equals(incoming.packageName))throw new Exception("Invalid AstShell package");
                if(old.signatures==null||incoming.signatures==null||!Arrays.equals(old.signatures,incoming.signatures))throw new Exception("ROM signing key mismatch; stopped safely without deleting data");
                PackageInstaller installer=pm.getPackageInstaller();
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());params.setSize(apk.length());
                session=installer.openSession(installer.createSession(params));
                try(InputStream input=new FileInputStream(apk);OutputStream output=session.openWrite("base.apk",0,apk.length())){
                    byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);session.fsync(output);
                }
                Intent intent=new Intent(action).setPackage(context.getPackageName());
                // Mutable result extras are required by PackageInstaller on API25.
                PendingIntent result=PendingIntent.getBroadcast(context,772,intent,PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(result.getIntentSender());
            }catch(Exception e){if(session!=null)try{session.abandon();}catch(Exception ignored){}busy.set(false);say(String.valueOf(e.getMessage()));}
            finally{if(session!=null)try{session.close();}catch(Exception ignored){}}
        }},"astos-preview-installer").start();
    }
}
