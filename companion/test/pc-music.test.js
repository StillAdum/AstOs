'use strict';
const assert=require('node:assert/strict'),{EventEmitter}=require('node:events');
const {MediaResolver,pageUrl,mediaUrl}=require('../src/media-resolver');
const {PcMusic}=require('../src/pc-music');const {NativeAudio}=require('../src/native-audio');
const fixture={title:'Run It Back',uploader:'Artist',webpage_url:'https://www.youtube.com/watch?v=abc',url:'https://r1.googlevideo.com/audio',duration:180,http_headers:{'User-Agent':'Test'}};
(async()=>{
 const calls=[];let failYoutube=false,failAll=false;
 const resolver=new MediaResolver('/bundled',{exists:()=>true,run:(exe,args,opts,cb)=>{
  calls.push({exe,args,opts});const query=args.at(-1);
  if(failAll||failYoutube&&query.startsWith('ytsearch'))return cb(Error('source blocked'),'','Source temporarily unavailable');
  cb(null,JSON.stringify(query.startsWith('ytsearch')||query.startsWith('scsearch')?{entries:[fixture]}:fixture),'');
 }});
 const result=await resolver.resolve('run it back');assert.equal(result.title,'Run It Back');assert.equal(result.stream,fixture.url);
 assert.equal(calls[0].args.at(-2),'--');assert.equal(calls[0].args.at(-1),'ytsearch1:run it back');assert(calls[0].args.includes('--ignore-config'));assert(calls[0].args.includes('--no-plugin-dirs'));assert.equal(calls[0].opts.windowsHide,true);
 failYoutube=true;assert.equal((await resolver.resolve('run it back')).title,'Run It Back');assert(calls.at(-1).args.at(-1).startsWith('scsearch1:'));
 assert.equal((await resolver.search('run it back')).tracks[0].uri,fixture.webpage_url);
 failAll=true;await assert.rejects(()=>resolver.resolve('run it back'),/Could not play/);
 for(const url of ['file:///etc/passwd','http://127.0.0.1','https://youtube.com.evil.example/a','https://user:pw@youtube.com/watch?v=a'])assert.throws(()=>pageUrl(url));
 assert.throws(()=>mediaUrl('https://127.0.0.1/audio'));assert.throws(()=>mediaUrl('https://googlevideo.com.evil.example/audio'));
 const ipc=new EventEmitter();let window,failAudio=false,closeListeners=[];
 class Win extends EventEmitter{
  constructor(options){super();window=this;this.options=options;this.webContents=new EventEmitter();this.webContents.setWindowOpenHandler=()=>{};this.webContents.session={setPermissionRequestHandler(){},webRequest:{onBeforeRequest(){},onHeadersReceived(){},onBeforeSendHeaders(){}}};this.webContents.send=(channel,c)=>setImmediate(()=>ipc.emit(channel,{sender:this.webContents},{type:'reply',id:c.id,generation:c.generation,...(failAudio?{error:'Audio source unavailable'}:{state:{playing:c.action!=='pause',positionMs:1234}})}));}
  isDestroyed(){return false;}async loadFile(){}destroy(){this.emit('closed');}
 }
 const audio=new NativeAudio({BrowserWindow:Win,ipcMain:ipc},'/fixture',{ready:true,resolve:async()=>({...fixture,uri:fixture.webpage_url,stream:fixture.url,durationMs:180000}),search:async()=>({tracks:[]})});
 const played=await audio.execute({action:'play',query:'run it back'});assert.equal(played.accepted,true);assert.equal(audio.state.playing,true);assert.equal(window.options.show,false);assert.equal(window.options.webPreferences.backgroundThrottling,false);assert.equal(window.options.webPreferences.sandbox,true);
 await audio.execute({action:'pause'});assert.equal(audio.state.playing,false);await audio.execute({action:'resume'});assert.equal(audio.state.playing,true);await audio.execute({action:'volset',query:'42'});assert.equal(audio.state.volume,42);
 failAudio=true;await assert.rejects(()=>audio.execute({action:'play',query:'missing'}),/Audio source unavailable/);assert.equal(audio.state.playing,false,'Do not claim playback on player rejection');audio.close();assert.equal(ipc.listenerCount(audio.channel),0);
 let delayedResolve;const cancelled=new NativeAudio({BrowserWindow:Win,ipcMain:ipc},'/fixture',{ready:true,resolve:()=>new Promise(r=>delayedResolve=r)});
 const pending=cancelled.execute({action:'play',query:'a song'});await cancelled.execute({action:'pause'});delayedResolve({...fixture,uri:fixture.webpage_url,stream:fixture.url});await assert.rejects(()=>pending,/cancelled/);cancelled.close();
 const nativeCalls=[],spotifyCalls=[];const native={ready:true,state:{title:'Native'},execute:async c=>{nativeCalls.push(c);return {accepted:true}},search:async()=>({tracks:[{title:'Run It Back'}]}),close(){}};
 const spotify={connected:false,state:{playing:false},execute:async c=>{spotifyCalls.push(c);return {accepted:true}},close(){},disconnect(){}};
 const router=new PcMusic(native,spotify);assert.equal(router.connected,true,'PC music must be available without Spotify');
 const c={id:'one',action:'play',query:'run it back'};await router.execute(c);await router.execute(c);assert.equal(nativeCalls.length,1,'Repeated AstLink delivery must not restart a song');assert.equal(spotifyCalls.length,0);assert.equal(router.state.title,'Native');
 await assert.rejects(()=>router.execute({id:'two',action:'play',query:'spotify:track:abc'}),/Spotify link needs/);
 spotify.connected=true;await router.execute({id:'three',action:'play',query:'run it back'});assert.equal(nativeCalls.length,2,'A song name still uses native audio when Spotify happens to be open');
 await router.execute({id:'four',action:'play',query:'spotify:track:abc'});assert.equal(spotifyCalls.length,1);assert.equal(nativeCalls.at(-1).action,'pause');
 router.disconnect();router.close();
 console.log('PC MUSIC PASS: exact run it back request, YouTube/SoundCloud fallback, bounded safe subprocess, URL restrictions, real player acknowledgement, failure propagation, pause/resume/volume, cancellation, no Spotify requirement, source selection and dedup');
})().catch(e=>{console.error(e);process.exit(1)});
