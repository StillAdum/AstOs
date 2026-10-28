'use strict';
const vm=require('node:vm'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict'),{EventEmitter}=require('node:events');
(async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'astos-main-'));const handlers={},intervals=[],windows=[],sent=[],sessions=[];let badChecksum=false,aborted=0;
 fs.writeFileSync(path.join(dir,'pairing.bin'),JSON.stringify({token:'test-token',host:'192.168.1.5',via:'wifi'}));
 const app=new EventEmitter();app.requestSingleInstanceLock=()=>true;app.whenReady=()=>Promise.resolve();app.getPath=()=>dir;app.quit=()=>app.emit('before-quit');
 class Win extends EventEmitter{constructor(){super();windows.push(this);this.webContents={send:(...v)=>sent.push(v)};}loadFile(){}isDestroyed(){return false;}hide(){this.hidden=true;}show(){this.hidden=false;}focus(){}}
 class Tray extends EventEmitter{setToolTip(){}setContextMenu(m){this.menu=m;}}
 class Bridge{constructor(){this.token='test';this.connected=true;this.state={available:true,title:'Test'};}async start(){}disconnect(){}close(){}async execute(){}}
 const transport={LinkError:Error,discoverWifi:async()=>[{host:'192.168.1.5'}],connectWifi:async host=>{const s={label:'wifi:'+host,dead:false,close(){this.dead=true;},call:async(_t,cmd)=>cmd==='file.begin'?{offset:0,path:'/private/link-inbox/plugin/test.astpl'}:cmd==='file.chunk'?{complete:true,shaOk:badChecksum?'mismatch':'ok'}:cmd==='file.abort'?(aborted++,{}):cmd==='hello'?{name:'Test tablet'}:cmd==='info'?{name:'Test tablet',setupDone:true,wifi:{ip:host}}:[]};sessions.push(s);return s;},listUsbDevices:()=>{throw Error('USB must not be required');}};
 const base=path.resolve(__dirname,'../src');const customRequire=name=>name==='electron'?{app,BrowserWindow:Win,ipcMain:{handle:(n,f)=>handlers[n]=f,on(){}},dialog:{},shell:{openExternal(){}},Menu:{setApplicationMenu(){},buildFromTemplate:m=>m},safeStorage:{isEncryptionAvailable:()=>true,encryptString:s=>Buffer.from(s),decryptString:b=>b.toString()},Tray,nativeImage:{createFromPath:()=>({})}}:name==='./transports'?transport:name==='./spotify-bridge'?{SpotifyBridge:Bridge}:name==='./audio-levels'?{AudioLevels:class{start(){}stop(){}get current(){return [];}}}:require(name.startsWith('.')?path.resolve(base,name):name);
 try{
 vm.runInNewContext(fs.readFileSync(path.join(base,'main.js'),'utf8'),{require:customRequire,__dirname:base,process,console,Buffer,setInterval:(fn,ms)=>{intervals.push({fn,ms});return 1;},clearInterval(){},setTimeout,clearTimeout});
 await new Promise(r=>setImmediate(r));assert.equal(windows.length,1);const result=await handlers['resume-pairing']();assert.equal(result.ok,true);assert.equal(sessions.length,1);
 const file=path.join(dir,'test.astpl');fs.writeFileSync(file,'package');const uploaded=await handlers['transfer'](null,file,'plugin');assert.equal(uploaded.ok,true);assert.equal(uploaded.result.path,'/private/link-inbox/plugin/test.astpl');badChecksum=true;const corrupt=await handlers['transfer'](null,file,'plugin');assert.equal(corrupt.ok,false);assert.match(corrupt.error,/checksum/);assert.equal(aborted,1);
 let prevented=false;windows[0].emit('close',{preventDefault(){prevented=true;}});assert.ok(prevented&&windows[0].hidden,'Close must hide, not exit');
 sessions[0].dead=true;sessions[0].onDead();await intervals.find(i=>i.fn.name==='reconnectWifi').fn();assert.equal(sessions.length,2,'Reconnect without opening the window');
 await handlers['disconnect-tablet']();await intervals.find(i=>i.fn.name==='reconnectWifi').fn();assert.equal(sessions.length,2,'Explicit disconnect must suppress auto-resume');
 app.quit();console.log('LIFECYCLE PASS: saved Wi-Fi resume without USB, close-to-tray, background reconnection, explicit-disconnect consent, clean quit');
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exit(1)});
