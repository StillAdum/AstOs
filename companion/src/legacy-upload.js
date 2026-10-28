'use strict';
const crypto=require('node:crypto');
/** Repair path for older AstShell builds that cannot create shared-storage inboxes.
 * Uses the already authenticated, user-authorized AstLink system channel to write
 * only an opaque package to the app's OWN private files. No root/ADB/USB or storage
 * permission override. Filename/data are generated here, not interpolated user text.
 */
async function legacyUpload(session,bytes,onProgress=()=>{},root='/data/data/com.astos.shell/files/link-inbox/compat'){
 if(!Buffer.isBuffer(bytes)||!bytes.length||bytes.length>256*1024*1024)throw Error('Invalid mod package size');
 if(!/^\/[A-Za-z0-9_./-]+$/.test(root)||root.includes('..'))throw Error('Invalid private inbox');
 const info=await session.call(null,'info',{},5000);if(info.app!=='astshell')throw Error('Finish setup before installing a mod');
 const file=root+'/'+crypto.randomUUID()+'.astpl',part=file+'.part',expected=crypto.createHash('sha256').update(bytes).digest('hex');
 async function shell(cmd){if(session.dead)throw Error('Tablet disconnected during compatibility upload');const r=await session.call(null,'sys.shell',{cmd,timeoutMs:5000},15000);if(r?.code!==0)throw Error('Private mod upload: '+String(r?.err||r?.out||'command failed').slice(0,500));return String(r.out||'');}
 try{
  await shell("umask 077; mkdir -p '"+root+"' && : > '"+part+"'");const start=Date.now();
  for(let off=0;off<bytes.length;off+=65536){const data=bytes.subarray(off,off+65536);await shell("printf '%s' '"+data.toString('base64')+"' | base64 -d >> '"+part+"'");const written=Math.min(off+data.length,bytes.length);onProgress({pct:Math.round(written*100/bytes.length),written,total:bytes.length,speed:Math.round(written/1024/Math.max(.001,(Date.now()-start)/1000)),compatibility:true});}
  const actual=(await shell("sha256sum '"+part+"'")).trim().split(/\s+/)[0];if(actual!==expected)throw Error('Private mod upload checksum mismatch');
  await shell("mv '"+part+"' '"+file+"'");return {path:file,name:file.split('/').pop(),sha256:expected};
 }catch(e){try{await shell("rm -f '"+part+"'");}catch{}throw e;}
}
module.exports={legacyUpload};
