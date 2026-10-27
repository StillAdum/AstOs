/**
 * main.js — AstOS Companion: Electron main process.
 * Pair with an AstOS tablet over Bluetooth or USB, run setup, manage it,
 * install .astpl mods, and apply ROM updates (TWRP zips).
 */
'use strict';

const { app, BrowserWindow, ipcMain, dialog, shell, Menu, safeStorage } = require('electron');
const path = require('path');
const fs = require('fs');
const T = require('./transports');
const { makeFrame, parseFrame } = require('./protocol');

const APP_VERSION = require('../package.json').version;
let win = null;
let authenticated = false;
let session = null;          // LinkSession
let paired = { token: '', name: '', ver: '' };
let spotifyBridge;
let pcTimer;
let pcBusy = false;
let pairBusy = false;
let pendingPair = {};
const handledPc = new Map();
const pendingPc = new Set();
function persistPair() {
  if (!safeStorage.isEncryptionAvailable()) return;
  fs.writeFileSync(path.join(app.getPath('userData'), 'pairing.bin'), safeStorage.encryptString(JSON.stringify(paired)));
}
async function init() {
  fs.mkdirSync(app.getPath('userData'), {recursive:true});
  try { paired = JSON.parse(safeStorage.decryptString(fs.readFileSync(path.join(app.getPath('userData'), 'pairing.bin')))); } catch {}
  const tokenPath = path.join(app.getPath('userData'), 'spotify-token');
  let token;
  try { token = fs.readFileSync(tokenPath, 'utf8'); } catch {
    token = require('crypto').randomBytes(32).toString('hex');
    fs.writeFileSync(tokenPath, token, {mode: 0o600});
  }
  spotifyBridge = new (require('./spotify-bridge').SpotifyBridge)(token);
  try { await spotifyBridge.start(); } catch (e) { console.error('Spotify bridge unavailable:', e.message); }
  createWindow();
  pcTimer = setInterval(pollPc, 750);
}
async function pollPc() {
  if (pcBusy || !session || !authenticated) return;
  pcBusy = true;
  const active = session;
  try {
    const commands = await active.call(null, 'pc.poll', {spotify: !!spotifyBridge?.connected, state: spotifyBridge?.connected ? spotifyBridge.state : {available:false}}, 5000);
    spotifyBridge.tabletSeen = Date.now();
    for (const command of commands || []) {
      const result = handledPc.get(command.id);
      if (result) {
        await active.call(null, 'pc.result', result, 5000);
      } else if (!pendingPc.has(command.id)) {
        pendingPc.add(command.id);
        spotifyBridge.execute(command).then(
          () => ({id: command.id, ok: true}),
          e => ({id: command.id, ok: false, error: e.message})
        ).then(result => {
          handledPc.set(command.id, result); pendingPc.delete(command.id);
          if (handledPc.size > 128) handledPc.delete(handledPc.keys().next().value);
        });
      }
    }
  } catch (e) {
    if (active === session && /closed/.test(e.message)) dieSession(e.message);
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
      sandbox: false
    }
  });
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  Menu.setApplicationMenu(null);
}

app.whenReady().then(init);
app.on('before-quit', () => { clearInterval(pcTimer); spotifyBridge?.close(); session?.close(); });
app.on('window-all-closed', () => app.quit());

// ------------------------------------------------------------ helpers

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

function dieSession(reason) {
  if (!session) return;
  session = null;
  authenticated = false;
  spotifyBridge?.disconnect();
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

async function tryHello(s, token) {
  try {
    const r = await s.call(null, 'hello', { token: token || '' }, 8000);
    return r;                      // {name, ver, ...} when paired:true
  } catch (e) {
    if (e.astLink && /need-code/.test(e.message)) return { needCode: true };
    throw e;
  }
}

async function findTablet(ev, kind, portPath) {
  if (authenticated && session) return {ok:true, info:await session.call(null, 'info', {}, 5000)};
  if (pairBusy) return {ok: false, error: 'A scan is already running'};
  pairBusy = true;
  pendingPair = {via:kind,port:portPath || ''};
  try {
    if (session) { const old = session; session = null; old.onDead = null; old.close(); }
    if (kind === 'usb') {
      const devices = await T.listUsbDevices();
      if (devices.length === 0) {
        return { ok: false, error: 'no USB tablet found — plug the cable in,\nenable USB debugging, then retry. (The tablet accepts the\nconnection when its ROM is AstOS - no prompt needed.)' };
      }
      session = await T.connectUsb(devices[0], m => send('scan-progress', m));
      { const active = session; session.onDead = () => { if (session === active) dieSession(); }; }
      const r = await tryHello(session, paired.token);
      if (r.needCode) return { ok: true, needCode: true };
      authenticated = true;
      paired = {...paired, token:r.token || paired.token, name:r.name, ver:r.ver, via:kind, port:portPath || ''};
      persistPair();
      send('paired', { name: r.name, ver: r.ver, via: 'USB' });
      return { ok: true, info: r };
    } else {
      if (portPath && !/^COM\d+$/i.test(portPath)) throw new Error('Enter an outgoing Bluetooth COM port such as COM7');
      const s = portPath ? await T.probeCom(portPath) : await T.findBluetoothTablet(m => send('scan-progress', String(m)));
      if (!s) {
        return { ok: false, error: 'no AstOS tablet found.\n\n1. On the tablet: Settings -> Bluetooth ON (it turns itself\n   on during setup).\n2. In Windows Bluetooth settings: Add device -> the tablet\n   named "AstSetup_xxxx" (or its normal name after setup).\n   Pair it — Windows then creates its serial COM port.\n3. Press SCAN again.' };
      }
      session = s;
      { const active = session; session.onDead = () => { if (session === active) dieSession(); }; }
      const r = await tryHello(session, paired.token);
      if (r.needCode) return { ok: true, needCode: true };
      authenticated = true;
      paired = {...paired, token:r.token || paired.token, name:r.name, ver:r.ver, via:kind, port:portPath || ''};
      persistPair();
      send('paired', { name: r.name, ver: r.ver, via: 'Bluetooth' });
      return { ok: true, info: r };
    }
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  } finally { pairBusy = false; }
}
ipcMain.handle('find-tablet', findTablet);
ipcMain.handle('resume-pairing', async () => {
  if (authenticated && session) return {ok:true,info:await session.call(null,'info',{},5000)};
  if (!paired.token) return {ok:false,error:'No saved tablet'};
  const first = await findTablet(null, paired.via || 'usb', paired.port || '');
  if (first.ok) return first;
  return findTablet(null, paired.via === 'usb' ? 'bluetooth' : 'usb');
});
ipcMain.handle('disconnect-tablet', () => {
  const old = session; if (old) { old.onDead = null; old.close(); }
  session = null; authenticated = false; spotifyBridge?.disconnect();
  return {ok:true};
});

ipcMain.handle('auth', async (ev, code) => {
  if (!session) return { ok: false, error: 'not connected' };
  try {
    const r = await session.call(null, 'auth', { code }, 10000);
    authenticated = true;
    paired = {...paired, ...pendingPair, token:r.token, name:r.name, ver:r.ver};
    persistPair();
    send('paired', { name: r.name, ver: r.ver, via: session.label });
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
  const begin = await session.call(null, 'file.begin', {
    id: 'x1', name, size: buf.length, sha256, target
  }, 20000);
  let offset = (begin && typeof begin.offset === 'number') ? begin.offset : 0;
  if (offset > buf.length) offset = 0;
  const { chunksFor } = require('./protocol');
  const step = 8192;
  const startSeq = Math.floor(offset / step);
  const frames = chunksFor(buf.subarray(offset), step);
  let ackEvery = target === 'rom' ? 4 : 64;   // ROMs want tighter flow control
  const t0 = Date.now();
  for (let i = 0; i < frames.length; i++) {
    const f = frames[i];
    await session.call(null, 'file.chunk', {
      id: 'x1', seq: startSeq + f.seq, b64: f.b64
    }, 30000);
    if (f.seq % ackEvery === 0 || i === frames.length - 1) {
      const written = offset + (f.seq + 1) * step;
      const pct = Math.min(100, Math.round(written * 100 / buf.length));
      const speed = Math.round((written - offset) / 1024 /
        Math.max(0.001, (Date.now() - t0) / 1000));
      onPct({ pct, speed, written: Math.min(written, buf.length), total: buf.length });
      await session.call(null, 'file.status', { id: 'x1' }, 30000);
    }
  }
  return { name, sha256, path: begin && begin.path ? begin.path : ('/sdcard/AstOs/' + name) };
}

ipcMain.handle('transfer', async (ev, filePath, target) => {
  try {
    const r = await transferFile(filePath, target, '', p => send('transfer-progress', p));
    return { ok: true, result: r };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
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
