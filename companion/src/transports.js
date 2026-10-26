/**
 * transports.js — the two ways the PC reaches the tablet:
 *
 *   BluetoothTransport:  a paired Windows tablet shows up as a virtual
 *                        serial port ("Standard Serial over Bluetooth link").
 *                        We probe each Bluetooth COM port with a hello and
 *                        keep the one that speaks AstLink.
 *
 *   UsbTransport:       the bundled adb.exe from resources/adb/. The app
 *                        runs `adb forward tcp:58517 tcp:58517` and speaks
 *                        the same protocol over plain TCP — fast (USB2 real
 *                        world ~8-12 MB/s) and pairing-free.
 */
'use strict';

const { spawn } = require('child_process');
const net = require('net');
const path = require('path');
const fs = require('fs');

// ---------------------------------------------------------------- shared session

class LinkSession {
  constructor(writeFn, closeFn, label) {
    this.write = writeFn;
    this.close = closeFn;
    this.label = label;
    this.pending = new Map();   // id -> {resolve, reject, timer}
    this.parser = require('./protocol').makeParser();
    this.onDead = null;
    this.dead = false;
  }

  ingest(chunk) {
    for (const line of this.parser.feed(chunk)) {
      let f;
      try {
        f = require('./protocol').parseFrame(line);
      } catch (e) { continue; }
      if (f.id && this.pending.has(f.id)) {
        const p = this.pending.get(f.id);
        this.pending.delete(f.id);
        clearTimeout(p.timer);
        if (f.ok) p.resolve(f.result);
        else p.reject(new LinkError(f.error));
      }
    }
  }

  ended() {
    if (this.dead) return;
    this.dead = true;
    for (const [, p] of this.pending) {
      clearTimeout(p.timer);
      p.reject(new LinkError('connection closed'));
    }
    this.pending.clear();
    if (this.onDead) this.onDead();
  }

  call(transport, cmd, args, timeoutMs) {
    if (this.dead) return Promise.reject(new LinkError("connection closed"));
    const proto = require('./protocol');
    const frame = proto.makeFrame(cmd, args);
    const m = /^{"v":1,"id":"(c\d+)"/.exec(frame);
    const id = m ? m[1] : null;
    return new Promise((resolve, reject) => {
      if (!id) return reject(new LinkError('frame build failed'));
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new LinkError(cmd + ' timed out'));
      }, timeoutMs || 25000);
      this.pending.set(id, { resolve, reject, timer });
      try { this.write(frame + '\n'); }
      catch (e) { clearTimeout(timer); this.pending.delete(id); reject(new LinkError(e.message)); }
    });
  }
}

class LinkError extends Error {
  constructor(msg) { super(msg); this.astLink = true; }
}

// ---------------------------------------------------------------- Bluetooth

/** Lists COM ports that look like Bluetooth serial links. */
async function listBluetoothPorts() {
  const { SerialPort } = require('serialport');
  const ports = await SerialPort.list();
  // serialport can omit labels. Windows' PNP IDs retain the BTHENUM
  // identity; never probe unrelated serial equipment with AstLink frames.
  if (process.platform === 'win32') {
    try {
      const extra = await new Promise((resolve, reject) => {
        const script = "@(Get-CimInstance Win32_SerialPort | Where-Object { $_.PNPDeviceID -like 'BTHENUM*' } | Select-Object DeviceID,Name,PNPDeviceID) | ConvertTo-Json -Compress";
        const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {windowsHide:true});
        let text = ''; const timer = setTimeout(() => { child.kill(); reject(new Error('Bluetooth port enumeration timed out')); }, 8000);
        child.stdout.on('data', b => { text += b; });
        child.on('error', e => { clearTimeout(timer); reject(e); });
        child.on('close', code => { clearTimeout(timer); if (code) reject(new Error('Bluetooth enumeration failed')); else { try { resolve(text ? JSON.parse(text) : []); } catch (e) { reject(e); } } });
      });
      for (const p of (Array.isArray(extra) ? extra : [extra])) {
        if (!ports.some(x => x.path === p.DeviceID)) ports.push({path:p.DeviceID, friendlyName:p.Name, pnpId:p.PNPDeviceID});
        else { const old = ports.find(x => x.path === p.DeviceID); old.pnpId = p.PNPDeviceID; }
      }
    } catch (e) { /* labeled ports below remain usable without PowerShell */ }
  }
  return ports.filter(p => /bluetooth|bthenum/i.test(`${p.friendlyName || ''} ${p.manufacturer || ''} ${p.pnpId || ''}`)
    || /^\/dev\/rfcomm/.test(p.path || ''));
}

/** Probe a COM port for the AstLink protocol. Resolves a LinkSession or
 *  rejects after `timeoutMs`. */
function probeCom(portPath, timeoutMs = 10000) {
  const { SerialPort } = require('serialport');
  return new Promise((resolve, reject) => {
    let settled = false;
    let timer;
    let sp;
    const done = (err, session) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (err) { reject(err); try { sp && sp.close(); } catch (e) {} }
      else resolve(session);
    };
    sp = new SerialPort({
      path: portPath,
      baudRate: 115200,
      autoOpen: true
    }, err => { if (err) return done(new LinkError('open failed: ' + err.message)); });

    const session = new LinkSession(
      s => sp.write(s, err => { if (err) session.ended(); }),
      () => { try { sp.close(); } catch (e) {} },
      'bluetooth:' + portPath
    );

    sp.on('data', d => session.ingest(d));
    sp.on('error', e => done(new LinkError(e.message)));
    sp.on('close', () => session.ended());

    const proto = require('./protocol');
    const frame = proto.makeFrame('hello', {});
    const id = /^{"v":1,"id":"(c\d+)"/.exec(frame)[1];
    timer = setTimeout(() => done(new LinkError('timeout')), timeoutMs);
    session.pending.set(id, {
      resolve: r => done(null, session),
      reject: e => {
        // a hello that answers need-code IS our tablet
        if (e && e.astLink && /need-code/.test(e.message)) done(null, session);
        else done(e);
      },
      timer
    });
    sp.once('open', () => {
      try { sp.write(frame + '\n', e => { if (e) done(new LinkError(e.message)); }); }
      catch (e) { done(new LinkError(e.message)); }
    });
  });
}

/** Walk all Bluetooth COM ports, return the first that speaks AstLink. */
async function findBluetoothTablet(progress) {
  const ports = await listBluetoothPorts();
  if (progress) progress(ports.map(p => p.path));
  for (const p of ports) {
    if (progress) progress('probing ' + p.path);
    try {
      const s = await probeCom(p.path);
      if (progress) progress('found ' + p.path);
      return s;
    } catch (e) {
      if (progress) progress('skip ' + p.path);
    }
  }
  return null;
}

// ---------------------------------------------------------------- USB (adb)

function adbBinary() {
  const exe = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const dir = path.join(process.resourcesPath || '.', 'adb');
  const full = path.join(dir, exe);
  if (fs.existsSync(full)) return full;
  return exe;   // fall back to PATH (dev builds)
}

function runAdb(args, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const p = spawn(adbBinary(), args, { windowsHide: true });
    let out = '', err = '';
    const t = setTimeout(() => { p.kill(); reject(new Error('adb timeout')); }, timeoutMs);
    p.stdout.on('data', d => out += d);
    p.stderr.on('data', d => err += d);
    p.on('error', e => { clearTimeout(t); reject(e); });
    p.on('close', code => { clearTimeout(t); resolve({ code, out: out.trim(), err: err.trim() }); });
  });
}

async function listUsbDevices() {
  try {
    const r = await runAdb(['devices']);
    return r.out.split('\n').map(l => l.trim()).filter(l =>
      l && !l.startsWith('List') && l.endsWith('device'))
      .map(l => l.split('\t')[0]);
  } catch (e) {
    return [];
  }
}

/** Connect over USB: forward + TCP to the tablet's AstLink server. */
function connectUsb(serial, progress) {
  return new Promise(async (resolve, reject) => {
    try {
      if (progress) progress('starting adb');
      await runAdb(['start-server']);
      if (serial) await runAdb(['-s', serial, 'wait-for-device']);
      if (progress) progress('forwarding port');
      const f = await runAdb(serial
        ? ['-s', serial, 'forward', 'tcp:58517', 'tcp:58517']
        : ['forward', 'tcp:58517', 'tcp:58517']);
      if (f.code !== 0) throw new LinkError(f.err || "USB port forwarding failed");
      if (progress) progress('connecting');
      const sock = net.connect(58517, '127.0.0.1');
      const session = new LinkSession(
        s => { try { sock.write(s); } catch (e) {} },
        () => { try { sock.destroy(); } catch (e) {} },
        'usb' + (serial ? ':' + serial : '')
      );
      sock.on('data', d => session.ingest(d));
      sock.setTimeout(8000, () => sock.destroy(new Error('USB connection timed out')));
      sock.on('error', e => { session.ended(); sock.destroy(); reject(new LinkError(e.message)); });
      sock.on('close', () => session.ended());
      sock.on('connect', async () => {
        try {
          await session.call(null, 'ping', {}, 4000);
          sock.setTimeout(0);
          if (progress) progress('connected');
          resolve(session);
        } catch (e) {
          sock.destroy();
          reject(e);
        }
      });
    } catch (e) {
      reject(new LinkError(e.message));
    }
  });
}

module.exports = {
  LinkSession, LinkError,
  listBluetoothPorts, probeCom, findBluetoothTablet,
  adbBinary, runAdb, listUsbDevices, connectUsb
};
