package com.astos.shell;
import java.io.*;import java.util.*;import java.util.zip.*;
/** Validate a whole package in staging before replacing any installed plugin. */
public final class PluginArchive {
 private PluginArchive(){}
 public static void recover(File root)throws IOException{File[] files=root.listFiles();if(files==null)return;
  for(File f:files){String name=f.getName();if(!name.endsWith(".previous"))continue;String id=name.substring(0,name.length()-9);if(!id.matches("[a-z0-9_]{2,32}"))continue;File current=new File(root,id);if(!current.exists()){if(!f.renameTo(current))throw new IOException("Cannot restore interrupted mod update "+id);}else remove(f);}
  files=root.listFiles();if(files!=null)for(File f:files)if(f.getName().matches("[a-z0-9_]{2,32}\\.incoming"))remove(f);
 }
 private static void remove(File f)throws IOException{File[] children=f.listFiles();if(children!=null)for(File child:children)remove(child);if(f.exists()&&!f.delete())throw new IOException("Cannot clean mod staging: "+f.getName());}

 public static void extract(File zip,File dir,String main)throws IOException{
  if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create mod staging folder");
  String base=dir.getCanonicalPath()+File.separator;Set<String> names=new HashSet<>();long total=0;boolean sawMain=false;int entries=0;
  try(ZipFile z=new ZipFile(zip)){Enumeration<? extends ZipEntry> all=z.entries();while(all.hasMoreElements()){
   ZipEntry e=all.nextElement();if(++entries>2048)throw new IOException("Too many mod entries");String n=e.getName();File out=new File(dir,n);
   if(n.startsWith("/")||n.contains("\\")||!out.getCanonicalPath().startsWith(base)||!names.add(out.getCanonicalPath()))throw new IOException("Unsafe or duplicate mod path");
   if(e.isDirectory()){if(!out.isDirectory()&&!out.mkdirs())throw new IOException("Cannot create mod directory");continue;}
   if(!out.getParentFile().isDirectory()&&!out.getParentFile().mkdirs())throw new IOException("Cannot create mod directory");
   try(InputStream in=z.getInputStream(e);OutputStream dest=new FileOutputStream(out)){byte[] b=new byte[16384];int len;while((len=in.read(b))!=-1){total+=len;if(total>512L*1024*1024)throw new IOException("Expanded mod exceeds 512 MB");dest.write(b,0,len);}}
   if(n.equals(main))sawMain=true;
  }}if(!sawMain)throw new IOException("Mod has no "+main);
 }
}
