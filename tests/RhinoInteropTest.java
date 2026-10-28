import org.mozilla.javascript.*;
public class RhinoInteropTest {
 public static void main(String[] args){Context cx=Context.enter();try{cx.setOptimizationLevel(-1);cx.setLanguageVersion(Context.VERSION_ES6);Scriptable scope=cx.initStandardObjects();Scriptable packages=(Scriptable)ScriptableObject.getProperty(scope,"Packages");scope.put("dalvik",scope,ScriptableObject.getProperty(packages,"dalvik"));scope.put("android",scope,ScriptableObject.getProperty(packages,"android"));
  Object a=cx.evaluateString(scope,"new Packages.dalvik.system.DexClassLoader('a','b',null,null)","new-installer",1,null),b=cx.evaluateString(scope,"new dalvik.system.DexClassLoader('a','b',null,null)","legacy-installed",1,null);
  if(!(Context.jsToJava(a,Object.class) instanceof dalvik.system.DexClassLoader)||!(Context.jsToJava(b,Object.class) instanceof dalvik.system.DexClassLoader))throw new AssertionError("Package aliases failed");System.out.println("RHINO PASS: corrected Packages.dalvik path and old installed dalvik alias instantiate the JVM fixture (DEX execution separately compiled, not hardware tested)");
 }finally{Context.exit();}}
}
