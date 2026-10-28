// Trusted native app-update mod. No ROM partition, settings reset or auto-install.
var root = new java.io.File(context.getFilesDir(), 'plugins/' + MOD_ID);
var receiver = null;
var action = 'com.astos.shell.PREVIEW_INSTALL_' + java.util.UUID.randomUUID().toString();
function toast(message) { astos.ui.toast(String(message)); }
function card(value) { astos.ui.card({id:'apply',title:MOD_TITLE,value:String(value),onTap:install}); }
function onLoad() {
  receiver = new JavaAdapter(android.content.BroadcastReceiver, {
    onReceive:function(c,i) {
      var status=i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
      if(status===android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
        var consent=i.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
        if(consent!==null) {consent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(consent);}
      } else if(status===android.content.pm.PackageInstaller.STATUS_SUCCESS) card('installed — reopen Home');
      else {card('not installed — tap to retry');toast('Install failed: '+i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE));}
    }
  });
  context.registerReceiver(receiver,new android.content.IntentFilter(action));
  card('tap to install; keep all settings');
}
function onUnload() { if(receiver!==null)try{context.unregisterReceiver(receiver);}catch(e){} }
function install() {
  var handler=new android.os.Handler(android.os.Looper.getMainLooper());
  handler.post(new java.lang.Runnable({run:function(){
    // Use a live Home activity solely to present the normal native consent dialog.
    // The PackageInstaller system confirmation is always shown if required.
    toast('Preparing '+MOD_TITLE+' — confirm the app update when asked');
    new java.lang.Thread(new java.lang.Runnable({run:function(){
      var session=null, input=null, output=null;
      try {
        var apk=new java.io.File(root,'AstShell.apk'), pm=context.getPackageManager();
        var old=pm.getPackageInfo(context.getPackageName(),64);
        var incoming=pm.getPackageArchiveInfo(apk.getAbsolutePath(),64);
        if(incoming===null || String(incoming.packageName)!==String(context.getPackageName()))throw new Error('Invalid AstShell package');
        if(old.signatures.length!==incoming.signatures.length)throw new Error('ROM signing key mismatch — stopped safely');
        for(var i=0;i<old.signatures.length;i++)if(!old.signatures[i].equals(incoming.signatures[i]))throw new Error('ROM signing key mismatch — stopped safely. No update or data removal attempted.');
        var installer=pm.getPackageInstaller();
        var params=new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());params.setSize(apk.length());
        session=installer.openSession(installer.createSession(params));
        input=new java.io.FileInputStream(apk);output=session.openWrite('base.apk',0,apk.length());
        var buffer=java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE,65536), count;
        while((count=input.read(buffer))!==-1)output.write(buffer,0,count);
        session.fsync(output);output.close();output=null;input.close();input=null;
        var intent=new android.content.Intent(action).setPackage(context.getPackageName());
        var result=android.app.PendingIntent.getBroadcast(context,772,intent,android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        session.commit(result.getIntentSender());card('update submitted — confirm installation');
      }catch(e){if(session!==null)try{session.abandon();}catch(ignored){}card('not installed — tap to retry');toast(String(e));}
      finally{if(input!==null)try{input.close();}catch(e){}if(output!==null)try{output.close();}catch(e){}if(session!==null)try{session.close();}catch(e){}}
    }}),'astos-preview-install').start();
  }}));
}
