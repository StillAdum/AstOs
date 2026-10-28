import com.astos.shell.*;import java.io.*;import java.nio.file.*;import java.util.zip.*;
public class RuntimeTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static File zip(File dir,String name,String... entries)throws Exception{File f=new File(dir,name);try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){for(int i=0;i<entries.length;i+=2){z.putNextEntry(new ZipEntry(entries[i]));z.write(entries[i+1].getBytes("UTF-8"));z.closeEntry();}}return f;}
 static void rejected(File zip,File target,String main)throws Exception{try{PluginArchive.extract(zip,target,main);throw new AssertionError("Accepted invalid mod");}catch(IOException expected){}}
 public static void main(String[] args)throws Exception{
  short[] silence=new short[640],voice=new short[640];java.util.Arrays.fill(voice,(short)3000);
  SpeechGate gate=new SpeechGate();for(int i=0;i<174;i++)check(!gate.accept(silence,640),"Early silence cutoff");check(gate.accept(silence,640)&&!gate.heard(),"No-speech timeout");
  gate=new SpeechGate();for(int i=0;i<20;i++)check(!gate.accept(voice,640),"Speech clipped");check(gate.heard(),"Voice detection");for(int i=0;i<27;i++)check(!gate.accept(silence,640),"Silence tail clipped");check(gate.accept(silence,640),"Endpoint missing");
  gate=new SpeechGate();for(int i=0;i<749;i++)check(!gate.accept(voice,640),"Maximum clipped");check(gate.accept(voice,640),"Thirty second bound");
  File dir=Files.createTempDirectory("ast-import").toFile();File old=new File(dir,"installed/plugin.js");old.getParentFile().mkdirs();Files.write(old.toPath(),"old".getBytes());
  File valid=zip(dir,"valid.zip","manifest.json","{}","plugin.js","new","assets/a","safe");PluginArchive.extract(valid,new File(dir,"stage"),"plugin.js");check(new File(dir,"stage/assets/a").isFile(),"Nested assets");
  rejected(zip(dir,"slip.zip","../escape","no","plugin.js","x"),new File(dir,"bad"),"plugin.js");check(!new File(dir,"escape").exists(),"Zip traversal");
  rejected(zip(dir,"abs.zip","/absolute","no"),new File(dir,"bad2"),"plugin.js");rejected(zip(dir,"missing.zip","manifest.json","{}"),new File(dir,"bad3"),"plugin.js");
  check(new String(Files.readAllBytes(old.toPath())).equals("old"),"Bad import damaged installed mod");
  System.out.println("RUNTIME PASS: PCM silence/voice/max-duration endpointing; nested assets, zip traversal and missing-main rejection; staging preserves installed mod");
 }
}
