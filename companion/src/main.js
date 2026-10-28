/**
 * main.js — AstOS Companion: Electron main process.
 * Pair with an AstOS tablet over Bluetooth or USB, run setup, manage it,
 * install .astpl mods, and apply ROM updates (TWRP zips).
 */
'use strict';

const { app, BrowserWindow, ipcMain, dialog, shell, Menu, safeStorage, Tray, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const T = require('./transports');
const { makeFrame, parseFrame } = require('./protocol');

const APP_VERSION = require('../package.json').version;
let win = null, tray=null, quitting=false, reconnectTimer, reconnectBusy=false, autoConnect=true;
if(!app.requestSingleInstanceLock()){app.quit();process.exit(0);}
app.on('second-instance',()=>{if(win){win.show();win.focus();}});
let authenticated = false;
let session = null;          // LinkSession
let paired = { token: '', name: '', ver: '' };
let spotifyBridge;
const audioLevels=new (require("./audio-levels").AudioLevels)();
let pcTimer;
let pcBusy = false,lastPcPoll=0;
let pairBusy = false;
let pendingPair = {};
let retryCount=0,nextRetry=0,presenceSocket;
let clientId='',linkGeneration=0;
function identity(extra={}){return {clientId,pcName:require('os').hostname(),...extra};}
const handledPc = new Map();
const pendingPc = new Set();
function persistPair() {
  if (!safeStorage.isEncryptionAvailable()) return;
  fs.writeFileSync(path.join(app.getPath('userData'), 'pairing.bin'), safeStorage.encryptString(JSON.stringify(paired)));
}
async function init() {
  fs.mkdirSync(app.getPath('userData'), {recursive:true});
  try { paired = JSON.parse(safeStorage.decryptString(fs.readFileSync(path.join(app.getPath('userData'), 'pairing.bin')))); } catch {}
  clientId=paired.clientId||require('crypto').randomUUID();paired.clientId=clientId;persistPair();
  startPresenceListener();
  const tokenPath = path.join(app.getPath('userData'), 'spotify-token');
  let token;
  try { token = fs.readFileSync(tokenPath, 'utf8'); } catch {
    token = require('crypto').randomBytes(32).toString('hex');
    fs.writeFileSync(tokenPath, token, {mode: 0o600});
  }
  spotifyBridge = new (require('./spotify-bridge').SpotifyBridge)(token);
  try { await spotifyBridge.start(); } catch (e) { console.error('Spotify bridge unavailable:', e.message); }
  if(paired.audioVisualizer)audioLevels.start();
  createWindow();
  tray=new Tray(nativeImage.createFromPath(path.join(__dirname,'..','build','icon.png')));
  tray.setToolTip('AstOS Companion — running in background');
  tray.setContextMenu(Menu.buildFromTemplate([{label:'Open AstOS Companion',click:()=>{win.show();win.focus();}},{label:'Reconnect tablet over Wi-Fi',click:()=>{autoConnect=true;reconnectWifi();}},{type:'separator'},{label:'Quit companion',click:()=>app.quit()}]));
  tray.on('double-click',()=>{win.show();win.focus();});
  reconnectTimer=setInterval(reconnectWifi,4000);
  pcTimer = setInterval(pollPc, 150);
}
async function pollPc() {
  if (pcBusy || !session || !authenticated) return;
  if(session.label.startsWith("bluetooth:")&&Date.now()-lastPcPoll<750)return;lastPcPoll=Date.now();
  pcBusy = true;
  const active = session;
  try {
    const commands = await active.call(null, 'pc.poll', {spotify: !!spotifyBridge?.connected, state: spotifyBridge?.connected ? {...spotifyBridge.state,bands:audioLevels.current} : {available:false}}, 5000);
    spotifyBridge.tabletSeen = Date.now();
    for (const command of commands || []) {
      const result = handledPc.get(command.id);
      if (result) {
        await active.call(null, 'pc.result', result, 5000);
      } else if (!pendingPc.has(command.id)) {
        pendingPc.add(command.id);
        spotifyBridge.execute(command).then(
          data => ({id: command.id, ok: true, data}),
          e => ({id: command.id, ok: false, error: e.message})
        ).then(result => {
          handledPc.set(command.id, result); pendingPc.delete(command.id);
          if (handledPc.size > 128) handledPc.delete(handledPc.keys().next().value);
        });
      }
    }
  } catch (e) {
    if (active === session && /closed|timed out/.test(e.message)) dieSession(e.message);
  } finally { pcBusy = false; }
}

// ------------------------------------------------------------ window

function createWindow() {
  win = new BrowserWindow({
    width: 1080,
    height: 720,
    minWidth: 880,
    minHeight: 600,
    backgroundColor: '#efe7d8',
    title: 'AstOS Companion',
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false
    }
  });
  win.on('close',event=>{if(!quitting){event.preventDefault();win.hide();}});
  win.on('minimize',event=>{event.preventDefault();win.hide();});
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  Menu.setApplicationMenu(null);
}

app.whenReady().then(init);
app.on('before-quit', () => { quitting=true; presenceSocket?.close(); clearInterval(reconnectTimer); clearInterval(pcTimer); audioLevels.stop(); spotifyBridge?.close(); session?.close(); });
app.on('window-all-closed', () => {});

// ------------------------------------------------------------ helpers

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

function dieSession(reason) {
  if (!session) return;
  const old=session;session = null;
  if(old){old.onDead=null;old.close();}
  authenticated = false;
  spotifyBridge?.disconnect();
  retryCount=0;nextRetry=0;
  send('link-dead', { reason: reason || 'connection lost' });
}

/** call + auto-retry once (BT lines hiccup) */
async function rpc(cmd, args, timeoutMs) {
  if (!session) throw new T.LinkError('not connected');
  try {
    return await session.call(null, cmd, args, timeoutMs);
  } catch (e) {
    if (e && e.astLink && /timed out/.test(e.message) && /^(info|setup.get|wifi.state|settings.get)$/.test(cmd)) {
      return await session.call(null, cmd, args, timeoutMs);
    }
    throw e;
  }
}

// ------------------------------------------------------------ pairing

async function tryHello(s, token,timeout=8000) {
  try {
    const r = await s.call(null, 'hello', identity({ token: token || '' }), timeout);
    return r;                      // {name, ver, ...} when paired:true
  } catch (e) {
    if (e.astLink && /need-code/.test(e.message)) return { needCode: true };
    throw e;
  }
}

async function findTablet(ev, kind, portPath, code) {
  if (authenticated && session) return {ok:true, info:await session.call(null, 'info', {}, 5000)};
  if (pairBusy) return {ok: false, error: 'A scan is already running'};
  pairBusy = true;linkGeneration++;
  pendingPair = {via:kind,port:portPath || ''};
  autoConnect=true;retryCount=0;
  try {
    if (session) { const old = session; session = null; old.onDead = null; old.close(); }
    if (kind === 'wifi') {
      let host=portPath || paired.host;
      if(!host){const found=await T.discoverWifi();if(found.length!==1)throw Error(found.length?'Multiple tablets found — enter the tablet IP address':'No tablet found on this Wi-Fi. Enter its IP or check guest-network isolation.');host=found[0].host;}
      pendingPair.host=host;
      const useCode=/^\d{6}$/.test(code||'');session=await T.connectWifi(host,useCode?code:paired.token,useCode,58517,clientId);
      const active=session;session.onDead=()=>{if(session===active)dieSession();};
      let info=await tryHello(session,useCode?'':paired.token);
      if(info.needCode&&useCode)info=await session.call(null,'auth',identity({code}),10000);
      else if(info.needCode)return {ok:true,needCode:true};
      authenticated=true;paired={...paired,...pendingPair,token:info.token||paired.token,name:info.name,ver:info.ver};persistPair();send('paired',{...info,via:'Wi-Fi'});return {ok:true,info};
    } else if (kind === 'usb') {
      const devices = await T.listUsbDevices();
      if (devices.length === 0) {
        return { ok: false, error: 'no USB tablet found — plug the cable in,\nenable USB debugging, then retry. (The tablet accepts the\nconnection when its ROM is AstOS - no prompt needed.)' };
      }
      session = await T.connectUsb(devices[0], m => send('scan-progress', m));
      { const active = session; session.onDead = () => { if (session === active) dieSession(); }; }
      const r = await tryHello(session, paired.token);
      if (r.needCode) return { ok: true, needCode: true };
      authenticated = true;
      paired = {...paired,...pendingPair, token:r.token || paired.token, name:r.name, ver:r.ver, via:kind, port:portPath || ''};
      persistPair();
      send('paired', { name: r.name, ver: r.ver, via: 'USB' });
      return { ok: true, info: r };
    } else {
      if(portPath&&!/^COM\d+$/i.test(portPath)&&!/^bt:[a-f0-9]{12}$/i.test(portPath))throw Error('Choose a discovered tablet or enter a COM port');
      let s;
      if(portPath?.startsWith('bt:')){const address=portPath.slice(3);await T.pairBluetooth(address);s=await T.connectBluetoothAddress(address);pendingPair.btAddress=address;}
      else s=portPath?await T.probeCom(portPath):paired.btAddress?await T.connectBluetoothAddress(paired.btAddress):await T.findBluetoothTablet(m=>send('scan-progress',String(m)));
      if (!s) {
        return { ok: false, error: 'no AstOS tablet found.\n\n1. On the tablet: Settings -> Bluetooth ON (it turns itself\n   on during setup).\n2. In Windows Bluetooth settings: Add device -> the tablet\n   named "AstSetup_xxxx" (or its normal name after setup).\n   Pair it — Windows then creates its serial COM port.\n3. Press SCAN again.' };
      }
      session = s;if(/^bluetooth:[A-Fa-f0-9]{12}$/.test(s.label))pendingPair.btAddress=s.label.slice(10);
      { const active = session; session.onDead = () => { if (session === active) dieSession(); }; }
      const r = await tryHello(session, paired.token);
      if (r.needCode) return { ok: true, needCode: true };
      authenticated = true;
      paired = {...paired,...pendingPair, token:r.token || paired.token, name:r.name, ver:r.ver, via:kind, port:portPath || ''};
      persistPair();
      send('paired', { name: r.name, ver: r.ver, via: 'Bluetooth' });
      return { ok: true, info: r };
    }
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  } finally { pairBusy = false; }
}
ipcMain.handle('find-tablet', findTablet);
ipcMain.handle('bluetooth-discover',async()=>{try{return {ok:true,devices:await T.discoverBluetooth()};}catch(e){return {ok:false,error:e.message};}});
ipcMain.handle('bluetooth-settings',()=>shell.openExternal('ms-settings:bluetooth')); 
ipcMain.handle('audio-visualizer',(_e,on)=>{if(typeof on==='boolean'){paired.audioVisualizer=on;persistPair();if(on)audioLevels.start();else audioLevels.stop();}return {enabled:!!paired.audioVisualizer,error:audioLevels.error};});
async function rememberNetwork(){if(!session||!authenticated)return;try{const info=await session.call(null,'info',{},6000);if(info.wifi?.ip){paired.host=info.wifi.ip;persistPair();}}catch{}}
// Forty scheduled attempts, four seconds between starts; thereafter only user action or presence wakes it.
async function reconnectWifi(force=false){
 if(reconnectBusy||pairBusy||!autoConnect||!paired.token||quitting||authenticated||retryCount>=40&&!force||Date.now()<nextRetry&&!force)return;
 if(session&&!authenticated&&!session.dead)return;
 reconnectBusy=true;const generation=linkGeneration;retryCount++;nextRetry=Date.now()+4000;
 let next;
 try{
  if(paired.btAddress&&retryCount%2===0){next=await T.connectBluetoothAddress(paired.btAddress,3000);}
  else{let host=paired.host;if(!host||retryCount%3===0){const found=await T.discoverWifi(650);host=found.find(x=>x.host===paired.host)?.host||found[0]?.host||host;}if(!host)return;next=await T.connectWifi(host,paired.token,false,58517,clientId,2200);}
  const info=await tryHello(next,paired.token,900);if(info.needCode)throw Error('Saved credentials rejected; use Pair new PC on tablet');
  if(!autoConnect||quitting||generation!==linkGeneration){next.close();return;}
  session=next;authenticated=true;retryCount=0;session.onDead=()=>{if(session===next)dieSession();};
  if(next.label.startsWith('wifi:'))paired.host=next.label.slice(5);persistPair();send('paired',{...info,via:next.label,reconnected:true});
 }catch{next?.close();}finally{reconnectBusy=false;}
}
function startPresenceListener(){
 presenceSocket=require('node:dgram').createSocket({type:'udp4',reuseAddr:true});
 presenceSocket.on('error',e=>console.warn('Tablet presence listener:',e.message));
 presenceSocket.on('message',(bytes,remote)=>{if(bytes.toString()!=='ASTOS_PRESENT_V1'||!autoConnect||!paired.token||authenticated)return;
  // The beacon is only a hint. The encrypted saved-secret handshake authenticates the tablet.
  if(!/^\d+\.\d+\.\d+\.\d+$/.test(remote.address)||Date.now()<nextRetry)return;
  paired.host=remote.address;retryCount=0;reconnectWifi(true);
 });presenceSocket.bind(58520);
}
ipcMain.handle('resume-pairing',async()=>{
 if(authenticated&&session)return {ok:true,info:await session.call(null,'info',{},5000)};
 if(!paired.token)return {ok:false,error:'No saved tablet'};
 autoConnect=true;retryCount=0;nextRetry=0;await reconnectWifi(true);
 return authenticated?{ok:true,info:await session.call(null,'info',{},5000)}:{ok:false,error:'Waiting for saved tablet; retrying up to 40 times at 4-second intervals'};
});
ipcMain.handle('disconnect-tablet', () => {
  linkGeneration++;
  const old = session; if (old) { old.onDead = null; old.close(); }
  session = null; authenticated = false; autoConnect=false; spotifyBridge?.disconnect();
  return {ok:true};
});

ipcMain.handle('auth', async (ev, code) => {
  if (!session) return { ok: false, error: 'not connected' };
  try {
    const r = await session.call(null, 'auth', identity({ code }), 10000);
    authenticated = true;
    paired = {...paired, ...pendingPair, token:r.token, name:r.name, ver:r.ver};
    persistPair();
    await rememberNetwork();
    send('paired', { ...r, via: session.label });
    return { ok: true, info: r };
  } catch (e) {
    if (e.astLink && /bad-code/.test(e.message))
      return { ok: false, error: 'wrong code — check the number on the tablet screen' };
    return { ok: false, error: String(e.message || e) };
  }
});

// ------------------------------------------------------------ generic rpc

ipcMain.handle('rpc', async (ev, cmd, args, timeoutMs) => {
  try {
    const r = await rpc(cmd, args, timeoutMs);
    return { ok: true, result: r };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

// ------------------------------------------------------------ file transfer

/** Transfer a big file with ack-driven flow control + progress events. */
async function transferFile(filePath, target, label, onPct) {
  if (!session) throw new T.LinkError('not connected');
  const buf = fs.readFileSync(filePath);
  const name = path.basename(filePath);
  const crypto = require('crypto');
  const sha256 = crypto.createHash('sha256').update(buf).digest('hex');
  const transferId=crypto.randomUUID();
  const active=session;
  const begin = await active.call(null, 'file.begin', {
    id: transferId, name, size: buf.length, sha256, target
  }, 20000);
  let offset = (begin && typeof begin.offset === 'number') ? begin.offset : 0;
  if (offset > buf.length) offset = 0;
  const { chunksFor } = require('./protocol');
  const step = 8192;
  const startSeq = Math.floor(offset / step);
  const frames = chunksFor(buf.subarray(offset), step);let verified=false;
  let ackEvery = target === 'rom' ? 4 : 64;   // ROMs want tighter flow control
  const t0 = Date.now();
  for (let i = 0; i < frames.length; i++) {
    const f = frames[i];
    if(session!==active||active.dead)throw new T.LinkError('Tablet changed during transfer; select the file and retry');
    const ack=await active.call(null, 'file.chunk', {
      id: transferId, seq: startSeq + f.seq, b64: f.b64
    }, 30000);
    if(ack?.complete&&ack.shaOk!=='ok'){await active.call(null,'file.abort',{id:transferId},5000);throw new T.LinkError('Upload checksum failed; file was not installed');}
    if(ack?.complete&&ack.shaOk==='ok')verified=true;
    if (f.seq % ackEvery === 0 || i === frames.length - 1) {
      const written = offset + (f.seq + 1) * step;
      const pct = Math.min(100, Math.round(written * 100 / buf.length));
      const speed = Math.round((written - offset) / 1024 /
        Math.max(0.001, (Date.now() - t0) / 1000));
      onPct({ pct, speed, written: Math.min(written, buf.length), total: buf.length });
      await active.call(null, 'file.status', { id: transferId }, 30000);
    }
  }
  if(!verified)throw new T.LinkError('Tablet did not verify the complete upload');
  return { name, sha256, path: begin && begin.path ? begin.path : ('/sdcard/AstOs/' + name) };
}

let transferBusy=false;
ipcMain.handle('transfer', async (ev, filePath, target) => {
  if(transferBusy)return {ok:false,error:'Another file transfer is running'};transferBusy=true;
  try {
    let r;
    try{r=await transferFile(filePath,target,'',p=>send('transfer-progress',p));}
    catch(e){if(target!=='plugin'||!session||!authenticated||!/permission|denied|ENOENT|FileNotFound|Cannot create upload|write failed|mkdir|directory/i.test(e.message))throw e;
      r=await require('./legacy-upload').legacyUpload(session,fs.readFileSync(filePath),p=>send('transfer-progress',p));
    }
    return { ok: true, result: r };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }finally{transferBusy=false;}
});

ipcMain.handle('pick-file', async (ev, filters) => {
  const r = await dialog.showOpenDialog(win, {
    properties: ['openFile'],
    filters: filters || [{ name: 'AstOS ROM zip', extensions: ['zip'] }]
  });
  return r.canceled ? null : r.filePaths[0];
});

ipcMain.handle('pick-plugin', async (ev) => {
  const r = await dialog.showOpenDialog(win, {
    properties: ['openFile'],
    filters: [{ name: 'AstOS plugin (.astpl)', extensions: ['astpl', 'zip'] }]
  });
  return r.canceled ? null : r.filePaths[0];
});

ipcMain.handle('about', async () => ({
  ok: true, result: { version: APP_VERSION }
}));

ipcMain.on('open-external', (ev, url) => {
  if (/^https:\/\//.test(url)) shell.openExternal(url);
});

ipcMain.handle('spotify-info', () => ({ token: spotifyBridge?.token || '', connected: !!spotifyBridge?.connected }));
