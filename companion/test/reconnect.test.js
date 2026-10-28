'use strict';
// Exercise real main-process policy with fake time and transports; no radios or open sockets.
const vm=require('node:vm'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict'),{EventEmitter}=require('node:events');
(async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ast-retry-')),handlers={},intervals=[],sent=[];let now=100000,attempts=0,allow=false,btAttempts=0,savedId;
 fs.writeFileSync(path.join(dir,'pairing.bin'),JSON.stringify({token:'saved-secret',host:'192.168.1.9',btAddress:'112233445566',via:'wifi'}));
 const app=new EventEmitter();app.requestSingleInstanceLock=()=>true;app.whenReady=()=>Promise.resolve();app.getPath=()=>dir;app.quit=()=>app.emit('before-quit');
 class Win extends EventEmitter{constructor(){super();this.webContents={send:(...v)=>sent.push(v)};}loadFile(){}isDestroyed(){return false;}hide(){}show(){}focus(){}}
 class Tray extends EventEmitter{setToolTip(){}setContextMenu(){}}
 const beacon=new EventEmitter();beacon.bind=()=>{};beacon.close=()=>{};
 function active(label){return {label,dead:false,close(){this.dead=true;},async call(_t,cmd,args){if(cmd==='hello'){assert.equal(args.token,'saved-secret');assert.ok(args.clientId);savedId=args.clientId;return {name:'Tablet',setupDone:true,app:'astshell'};}return {setupDone:true};}};}
 const T={LinkError:Error,discoverWifi:async()=>[],connectWifi:async(_h,_token,_pair,_port,id)=>{attempts++;if(!allow)throw Error('offline');assert.equal(id,savedId||id);return active('wifi:192.168.1.9');},connectBluetoothAddress:async()=>{attempts++;btAttempts++;throw Error('offline');},listUsbDevices(){throw Error('No USB fallback allowed');}};
 const base=path.resolve(__dirname,'../src');const req=name=>name==='electron'?{app,BrowserWindow:Win,ipcMain:{handle:(n,f)=>handlers[n]=f,on(){}},dialog:{},shell:{},Menu:{setApplicationMenu(){},buildFromTemplate:m=>m},safeStorage:{isEncryptionAvailable:()=>true,encryptString:s=>Buffer.from(s),decryptString:b=>b.toString()},Tray,nativeImage:{createFromPath:()=>({})}}:name==='./transports'?T:name==='node:dgram'?{createSocket:()=>beacon}:name==='./spotify-bridge'?{SpotifyBridge:class{async start(){}disconnect(){}close(){}}}:name==='./audio-levels'?{AudioLevels:class{start(){}stop(){}}}:require(name.startsWith('.')?path.resolve(base,name):name);
 try{
 vm.runInNewContext(fs.readFileSync(path.join(base,'main.js'),'utf8'),{require:req,__dirname:base,process,console,Buffer,Date:class extends Date{static now(){return now;}},setInterval:(fn,ms)=>{intervals.push({fn,ms});return 1;},clearInterval(){},setTimeout,clearTimeout});await new Promise(r=>setImmediate(r));
 const retry=intervals.find(x=>x.fn.name==='reconnectWifi');assert.equal(retry.ms,4000);
 await handlers['resume-pairing']();assert.equal(attempts,1);await retry.fn();assert.equal(attempts,1,'Do not retry before four seconds');
 for(let i=1;i<50;i++){now+=4000;await retry.fn();beacon.emit('message',Buffer.from('ASTOS_PRESENT_V1'),{address:'192.168.1.9'});await new Promise(r=>setImmediate(r));}assert.equal(attempts,40,'Exactly forty attempts despite continuous presence beacons, then passive wait');assert.equal(btAttempts,20,'Remembered Bluetooth is retried too');
 beacon.emit('message',Buffer.from('untrusted garbage'),{address:'192.168.1.9'});await new Promise(r=>setImmediate(r));assert.equal(attempts,40);
 now+=16000;allow=true;beacon.emit('message',Buffer.from('ASTOS_PRESENT_V1'),{address:'192.168.1.9'});await new Promise(r=>setImmediate(r));assert.equal(attempts,41);assert.ok(sent.some(x=>x[0]==='paired'));
 const saved=JSON.parse(fs.readFileSync(path.join(dir,'pairing.bin')));assert.equal(saved.clientId,savedId,'Stable client identity persisted');
 await handlers['disconnect-tablet']();now+=5000;beacon.emit('message',Buffer.from('ASTOS_PRESENT_V1'),{address:'192.168.1.9'});await retry.fn();assert.equal(attempts,41,'User disconnect must suppress beacon and timer reconnect');
 app.quit();console.log('RECONNECT PASS: forty attempts/4s cadence, Wi-Fi + remembered BT, passive wait, authenticated wake, stable PC identity, no USB fallback, explicit-disconnect consent');
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exit(1);});
