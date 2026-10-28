package com.astos.link;
import android.app.AlertDialog;import android.bluetooth.*;import android.content.*;import android.os.*;import android.view.WindowManager;
/** Pairing consent stays explicit: only expose a confirmation while the user opened pairing. */
public final class PairingPrompts implements java.io.Closeable {
 public interface Allowed {boolean get();}
 private final Context context;private final Allowed allowed;private boolean registered;private AlertDialog dialog;private final Handler main=new Handler(Looper.getMainLooper());
 public PairingPrompts(Context c,Allowed a){context=c;allowed=a;}
 private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(!allowed.get()||!android.provider.Settings.canDrawOverlays(context))return;
  final BluetoothDevice device=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(device==null)return;
  int variant=i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,-1),key=i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY,-1);
  // Leave PIN-entry and unsupported variants to the platform; never guess a PIN or accept unseen comparisons.
  if(variant!=BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION||key<0)return;
  abortBroadcast();main.post(()->{if(!allowed.get())return;if(dialog!=null)dialog.dismiss();
   dialog=new PixelUi.Builder(context).setTitle("Pair new PC?").setMessage("Confirm this same number is shown on your PC: "+String.format(java.util.Locale.US,"%06d",key)+"\nDevice: "+device.getName())
    .setPositiveButton("Numbers match",(d,w)->confirm(device,true)).setNegativeButton("Reject",(d,w)->confirm(device,false)).create();
   dialog.setOnCancelListener(d->confirm(device,false));dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);try{dialog.show();PixelUi.decorate(dialog);}catch(Exception e){confirm(device,false);android.util.Log.w("AstPairing","Could not show pairing confirmation",e);}
  });
 }};
 private void confirm(BluetoothDevice d,boolean yes){try{d.getClass().getMethod("setPairingConfirmation",boolean.class).invoke(d,yes);}catch(Exception ignored){}}
 public void start(){if(registered)return;IntentFilter f=new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);f.setPriority(1000);context.registerReceiver(receiver,f);registered=true;}
 public static void discoverable(Context c){new Thread(()->{try{BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();if(a==null)return;if(!a.isEnabled()&&!a.enable())throw new Exception("Bluetooth cannot turn on");long until=android.os.SystemClock.elapsedRealtime()+12000;while(!a.isEnabled()){if(android.os.SystemClock.elapsedRealtime()>until)throw new Exception("Bluetooth startup timed out");Thread.sleep(200);}Object result=a.getClass().getMethod("setScanMode",int.class,int.class).invoke(a,BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,300);if(Boolean.FALSE.equals(result))throw new Exception("Discovery consent required");}catch(Exception e){new Handler(Looper.getMainLooper()).post(()->{try{Intent i=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);}catch(Exception failure){android.widget.Toast.makeText(c,"Bluetooth discoverability: "+failure.getMessage(),android.widget.Toast.LENGTH_LONG).show();}});}},"ast-pair-discoverable").start();}
 public void close(){if(registered){try{context.unregisterReceiver(receiver);}catch(Exception ignored){}registered=false;}main.post(()->{if(dialog!=null){dialog.dismiss();dialog=null;}});}
}
