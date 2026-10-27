'use strict';
const {spawn}=require('node:child_process'),path=require('node:path'),fs=require('node:fs');
class AudioLevels {
 constructor(){this.child=null;this.bands=[];this.updated=0;this.error='';}
 start(){if(this.child||process.platform!=='win32')return;const exe=path.join(process.resourcesPath||path.join(__dirname,'..','resources'),'audio','AstAudio.exe');if(!fs.existsSync(exe)){this.error='Audio analyzer is not installed';return;}
  const child=spawn(exe,[],{windowsHide:true});this.child=child;let buffer='';child.stdout.on('data',d=>{buffer+=d.toString();if(buffer.length>8192){buffer='';return;}let i;while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);try{const b=JSON.parse(line);if(Array.isArray(b)&&b.length===9){this.bands=b.map(n=>Math.max(0,Math.min(1,Number(n)||0)));this.updated=Date.now();}}catch{}}});
  child.stderr.on('data',d=>{this.error=d.toString().slice(0,180);});child.on('error',e=>{this.error=e.message;});child.on('close',()=>{if(this.child===child)this.child=null;this.bands=[];});
 }
 stop(){const child=this.child;this.child=null;child?.kill();this.bands=[];}
 get current(){return Date.now()-this.updated<3000?this.bands:[];}
}
module.exports={AudioLevels};
