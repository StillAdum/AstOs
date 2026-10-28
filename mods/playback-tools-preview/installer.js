// Native receiver/helper is precompiled DEX, not a Rhino JavaAdapter subclass.
// Install only after an explicit card tap. No ROM partition writes or data reset.
var root = new java.io.File(context.getFilesDir(), 'plugins/' + MOD_ID);
var helper = null;
function onLoad() {
  var optimized = new java.io.File(context.getCodeCacheDir(), MOD_ID);
  optimized.mkdirs();
  var loader = new dalvik.system.DexClassLoader(new java.io.File(root,'installer.jar').getAbsolutePath(), optimized.getAbsolutePath(), null, context.getClassLoader());
  helper = loader.loadClass('com.astos.preview.ModInstaller').newInstance();
  helper.attach(context);
  astos.ui.card({id:'apply',title:MOD_TITLE,value:'tap to install; keep all settings',onTap:install});
}
function onUnload() { if(helper!==null)helper.dispose(); }
function install() { helper.install(context,new java.io.File(root,'AstShell.apk').getAbsolutePath()); }
