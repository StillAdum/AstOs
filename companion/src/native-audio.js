'use strict';
const path=require('node:path'),crypto=require('node:crypto');
const {MediaResolver,mediaUrl}=require('./media-resolver');
class NativeAudio{
 constructor({BrowserWindow,ipcMain},resources,resolver=new MediaResolver(resources)){
  this.BrowserWindow=BrowserWindow;this.ipcMain=ipcMain;this.resolver=resolver;
  this.window=null;this.loading=null;this.pending=new Map();this.generation=0;this.volume=67;this.current=null;this.history=[];this.retrying=false;this.closed=false;this.userPaused=false;this.recoveries=0;
  this.channel='astos-audio-'+crypto.randomBytes(12).toString('hex');
  this._state={available:false,playing:false,positionMs:0,durationMs:0,volume:this.volume};this.headers={};
 }
 get ready(){return !this.closed&&this.resolver.ready;}
 get state(){return {...this._state,volume:this.volume};}
 search(query){return this.resolver.search(query);}
 async ensure(){
  if(this.loading)return this.loading;
  if(this.window&&!this.window.isDestroyed())return;
  this.loading=(async()=>{
   const win=this.window=new this.BrowserWindow({show:false,webPreferences:{preload:path.join(__dirname,'player/preload.js'),contextIsolation:true,nodeIntegration:false,sandbox:true,backgroundThrottling:false,partition:this.channel,additionalArguments:['--astos-audio-channel='+this.channel],autoplayPolicy:'no-user-gesture-required'}});
   win.webContents.setWindowOpenHandler(()=>({action:'deny'}));win.webContents.on('will-navigate',e=>e.preventDefault());
   const ses=win.webContents.session;ses.setPermissionRequestHandler((_w,_p,cb)=>cb(false));
   ses.webRequest.onBeforeRequest((details,cb)=>{
    if(details.url.startsWith('file:')||(details.url.startsWith('blob:')&&details.resourceType==='media'))return cb({});
    try{mediaUrl(details.url);cb({});}catch{cb({cancel:true});}
   });
   ses.webRequest.onHeadersReceived((details,cb)=>{
    try{mediaUrl(details.url);cb({responseHeaders:{...details.responseHeaders,'Access-Control-Allow-Origin':['*']}});}catch{cb({});}
   });
   ses.webRequest.onBeforeSendHeaders((details,cb)=>{
    const requestHeaders={...details.requestHeaders};
    if(this.current&&details.url===this.current.stream)for(const k of ['User-Agent','Referer','Origin'])if(typeof this.headers[k]==='string'&&!/[\r\n]/.test(this.headers[k]))requestHeaders[k]=this.headers[k];
    cb({requestHeaders});
   });
   this.receive=(event,data)=>{
    if(event.sender!==win.webContents||!data||data.generation!==this.generation)return;
    if(data.type==='reply'){
     const p=this.pending.get(data.id);if(p){clearTimeout(p.timer);this.pending.delete(data.id);data.error?p.reject(Error(data.error)):p.resolve(data.state);}
    }
    if(data.state)this._state={...this._state,...data.state};
    if(data.type==='ended')this._state.playing=false;
    if(data.type==='error')this.recover(data.error);
   };
   this.ipcMain.on(this.channel,this.receive);
   win.on('closed',()=>{this.ipcMain.removeListener(this.channel,this.receive);this.window=null;this.failPending('PC audio player closed');});
   await win.loadFile(path.join(__dirname,'player/index.html'));
  })().finally(()=>{this.loading=null;});return this.loading;
 }
 failPending(message){for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(Error(message));}this.pending.clear();}
 async command(action,extra={}){
  await this.ensure();const id=crypto.randomUUID(),generation=this.generation;
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{this.pending.delete(id);reject(Error('PC audio player did not confirm '+action));},12000);
   this.pending.set(id,{resolve,reject,timer});this.window.webContents.send(this.channel,{id,generation,action,...extra});
  });
 }
 async play(query,positionMs=0,recovery=false){
  if(!String(query||'').trim()||String(query).length>512)throw Error('Enter a song title or YouTube/SoundCloud link');
  if(!recovery)this.recoveries=0;this.userPaused=false;
  const generation=++this.generation;this.failPending('Replaced by a newer playback request');
  const next=await this.resolver.resolve(query);
  if(this.closed||generation!==this.generation)throw Error('Playback request was replaced or cancelled');
  if(!recovery&&this.current)this.history.push(this.current.uri);
  this.current=next;this.headers=next.headers;
  this._state={available:true,playing:false,positionMs,durationMs:next.durationMs,volume:this.volume,title:next.title,artist:next.artist,uri:next.uri,artUrl:next.artUrl};
  try{
   await this.command('load',{url:next.stream,hls:!!next.hls,positionMs,volume:this.volume});
   if(generation!==this.generation)throw Error('Playback request was replaced');
   return {accepted:true,uri:next.uri,title:next.title,source:'pc-audio'};
  }catch(e){if(generation===this.generation){this._state.playing=false;this._state.title='Playback failed: '+e.message;}throw e;}
 }
 async recover(error){
  if(this.userPaused)return;
  if(this.retrying||this.recoveries>=1||!this.current){this._state.playing=false;this._state.title='Playback stopped: '+String(error||'audio source failed');return;}
  this.retrying=true;this.recoveries++;const uri=this.current.uri,pos=this._state.positionMs;
  try{await this.play(uri,pos,true);}catch(e){this._state.playing=false;this._state.title='Playback stopped: '+e.message;}finally{this.retrying=false;}
 }
 async execute(c){
  if(c.expiresAt&&Date.now()>c.expiresAt)throw Error('Playback command expired');
  if(c.action==='state')return this.state;
  if(c.action==='search')return this.search(c.query);
  if(c.action==='play'&&c.query)return this.play(c.query);
  if(c.action==='pause'){this.userPaused=true;this.generation++;this.failPending('Playback paused');if(!this.current)return {accepted:true};}
  if(!this.current)throw Error('No PC song selected. Say “play” followed by a song title.');
  if(c.action==='previous'){
   const prior=this.history.pop();if(prior){return this.play(prior,0,true);}await this.command('seek',{positionMs:0});return {accepted:true};
  }
  if(c.action==='next')throw Error('No next song is queued. Ask to play another song by name.');
  if(['volset','volup','voldown'].includes(c.action)){
   const n=Number(c.query);if(c.action==='volset'&&(!Number.isFinite(n)||n<0||n>100))throw Error('Volume must be 0–100');
   this.volume=Math.max(0,Math.min(100,c.action==='volset'?n:this.volume+(c.action==='volup'?5:-5)));
   await this.command('volume',{volume:this.volume});return {accepted:true};
  }
  const action=c.action==='play'?'resume':c.action;
  if(!['pause','resume'].includes(action))throw Error('Unsupported PC playback action');
  if(action==='resume')this.userPaused=false;
  await this.command(action);return {accepted:true};
 }
 close(){this.closed=true;this.generation++;this.failPending('PC music closed');this.window?.destroy();}
}
module.exports={NativeAudio};
