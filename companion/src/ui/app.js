/**
 * app.js — AstOS Companion renderer.
 * Screens: welcome -> pair -> dashboard/setup/mods/update/tools.
 */
'use strict';

const $ = (id) => document.getElementById(id);
const THE_TABLET = { name: '', ver: '' };

const THEMES = [
  { key: 'dune', label: 'dune', chips: ['#efe7d8', '#cfdca6', '#c4742a', '#2a241c'] },
  { key: 'forest', label: 'forest', chips: ['#e4e6d3', '#c9d6a3', '#6b8f4e', '#252a20'] },
  { key: 'rust', label: 'rust', chips: ['#f0e3d6', '#e8c9a8', '#b3572b', '#2e2118'] },
  { key: 'mono', label: 'mono', chips: ['#eeece5', '#c8c6be', '#6a675f', '#26241f'] },
  { key: 'ember', label: 'ember', chips: ['#241f1a', '#3a3128', '#c96f4a', '#f2e7da'] },
  { key: 'pine', label: 'pine', chips: ['#e6ebe2', '#c2d4bc', '#718a66', '#232a22'] }
];

// ------------------------------------------------------------ chrome

function spawnMotes() {
  const wrap = $('motes');
  for (let i = 0; i < 22; i++) {
    const m = document.createElement('i');
    m.className = 'mote';
    m.style.left = (Math.random() * 100) + '%';
    m.style.top = (Math.random() * 100) + '%';
    const d = 30 + Math.random() * 60;
    m.style.animationDuration = d + 's';
    m.style.animationDelay = (-Math.random() * d) + 's';
    wrap.appendChild(m);
  }
}

function spawnLogoBlocks() {
  const w = $('logoBlocks');
  for (let i = 0; i < 6; i++) w.appendChild(document.createElement('i'));
}

function showView(name) {
  document.querySelectorAll('.view').forEach(v => v.classList.remove('on'));
  $('v-' + name).classList.add('on');
  document.querySelectorAll('.tab').forEach(t =>
    t.classList.toggle('on', t.dataset.view === name));
}

function enableTabs(paired) {
  ['dash', 'setup', 'mods', 'update', 'tools'].forEach(v => {
    const b = document.querySelector(`.tab[data-view="${v}"]`);
    if (b) b.disabled = !paired;
  });
  const pill = $('linkPill');
  if (paired) {
    pill.textContent = (THE_TABLET.name || 'tablet') + ' ' + (THE_TABLET.ver || '');
    pill.className = 'link-pill ok';
  } else {
    pill.textContent = 'no tablet';
    pill.className = 'link-pill bad';
  }
}

// ------------------------------------------------------------ link plumbing

async function rpc(cmd, args, timeout) {
  const r = await window.astos.rpc(cmd, args, timeout);
  if (!r.ok) throw new Error(r.error);
  return r.result;
}

function status(id, msg, cls) {
  const el = $(id);
  el.textContent = msg || '';
  el.className = 'statusline ' + (cls || '');
}

// ------------------------------------------------------------ tabs + goto

document.querySelectorAll('.tab').forEach(t => {
  t.addEventListener('click', () => showView(t.dataset.view));
});
document.addEventListener('click', ev => {
  const g = ev.target.closest('[data-goto]');
  if (g) showView(g.dataset.goto);
});

// ------------------------------------------------------------ connect

$('btnUsb').addEventListener('click', () => findTablet('usb'));
$('btnBt').addEventListener('click', () => findTablet('bluetooth'));

async function findTablet(kind) {
  const card = kind === 'usb' ? $('usbCard') : $('btCard');
  const line = kind === 'usb' ? $('usbProgress') : $('btProgress');
  card.classList.add('scanning');
  line.textContent = 'scanning…';
  const off = window.astos.on('scan-progress', m => { line.textContent = m; });
  try {
    const r = await window.astos.findTablet(kind);
    off();
    if (!r.ok) {
      line.textContent = r.error || 'scan failed';
      return;
    }
    if (r.needCode) {
      $('codeOverlay').classList.remove('hidden');
      $('codeInput').focus();
      line.textContent = 'tablet found — enter the code';
      return;
    }
    onPaired(r.info);
  } catch (e) {
    off();
    line.textContent = String(e.message || e);
  } finally {
    card.classList.remove('scanning');
  }
}

$('btnCode').addEventListener('click', async () => {
  const code = $('codeInput').value.trim();
  if (code.length !== 6) { $('codeErr').textContent = '6 digits — check the tablet screen'; return; }
  $('codeErr').textContent = '';
  const r = await window.astos.auth(code);
  if (!r.ok) { $('codeErr').textContent = r.error; return; }
  $('codeOverlay').classList.add('hidden');
  onPaired(r.info);
});
$('btnCodeCancel').addEventListener('click', () => {
  $('codeOverlay').classList.add('hidden');
});
$('codeInput').addEventListener('keydown', e => {
  if (e.key === 'Enter') $('btnCode').click();
});

window.astos.on('paired', info => {
  THE_TABLET.name = info.name || '';
  THE_TABLET.ver = info.ver || '';
});
window.astos.on('link-dead', info => {
  enableTabs(false);
  showView('connect');
  $('btProgress').textContent = 'connection lost: ' + (info.reason || '');
});

async function onPaired(info) {
  THE_TABLET.name = info.name || '';
  THE_TABLET.ver = info.ver || '';
  enableTabs(true);
  showView('dash');
  loadDash();
}

// ------------------------------------------------------------ dashboard

function fmtUptime(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}

async function loadDash() {
  try {
    const info = await rpc('info');
    $('dashTitle').textContent = info.name || 'tablet';
    $('kvName').textContent = info.name || '—';
    $('kvVer').textContent = 'AstOS ' + (info.ver || '?');
    const bat = info.battery || {};
    $('kvBat').textContent = bat.pct >= 0
      ? `${bat.pct}%${bat.charging ? ' ⚡'.replace('⚡', ' (charging)') : ''}`
      : '—';
    const wifi = info.wifi || {};
    $('kvWifi').textContent = wifi.connected
      ? (wifi.ssid || 'connected') : 'not connected';
    const st = info.storage || {};
    $('kvStore').textContent = st.freeMb
      ? `${(st.freeMb / 1024).toFixed(1)} GB free` : '—';
    $('kvUp').textContent = fmtUptime(info.uptimeMs || 0);
    loadToggles(info);
  } catch (e) {
    $('kvName').textContent = 'error: ' + e.message;
  }
}

$('btnRefresh').addEventListener('click', loadDash);

// ------------------------------------------------------------ setup

let pickedSsid = '';

async function loadSetup() {
  // wifi list from the tablet's own radio
  try {
    const nets = await rpc('wifi.list', {}, 30000);
    const list = $('netList');
    list.innerHTML = '';
    if (!nets.length) {
      list.innerHTML = '<div class="dim" style="padding:10px">no networks found — check the tablet\'s wifi is on</div>';
    }
    nets.forEach(n => {
      const row = document.createElement('div');
      row.className = 'netrow';
      row.innerHTML = `<span>${n.ssid}</span>
        <span class="sig">${n.secure ? '🔒'.replace('🔒', 'lock') : 'open'} · ${n.level} dBm</span>`;
      row.addEventListener('click', () => {
        pickedSsid = n.ssid;
        [...list.children].forEach(c => c.classList.remove('sel'));
        row.classList.add('sel');
      });
      list.appendChild(row);
    });
    const cur = await rpc('setup.get');
    $('city').value = cur.city || '';
    $('groq').value = cur.groq || '';
    $('haUrl').value = cur.haUrl || '';
    $('haToken').value = cur.haToken || '';
    $('h24').checked = cur.h24 !== false;
    $('kiosk').checked = cur.kiosk !== false;
    $('keepScreen').checked = cur.keepScreen !== false;
    if (cur.name) $('dashTitle').textContent = cur.name;
  } catch (e) {
    status('setupStatus', 'wifi list failed: ' + e.message, 'err');
  }
}

document.querySelector('.tab[data-view="setup"]').addEventListener('click', loadSetup);

function themeRow() {
  const grid = $('themeGrid');
  grid.innerHTML = '';
  THEMES.forEach(t => {
    const el = document.createElement('div');
    el.className = 'themeswatch';
    el.dataset.key = t.key;
    el.innerHTML = `<div class="chips">${t.chips.map(c => `<i style="background:${c}"></i>`).join('')}</div>${t.label}`;
    el.addEventListener('click', () => {
      pickedTheme = t.key;
      [...grid.children].forEach(c => c.classList.remove('sel'));
      el.classList.add('sel');
    });
    grid.appendChild(el);
  });
  grid.firstChild.classList.add('sel');
}
let pickedTheme = 'dune';

$('btnJoin').addEventListener('click', async () => {
  try {
    const ssid = pickedSsid;
    const pass = $('wifiPass').value;
    if (!ssid) { status('setupStatus', 'pick a network from the list first', 'err'); return; }
    status('setupStatus', 'saving settings…');
    await rpc('setup.set', {
      city: $('city').value.trim(),
      groq: $('groq').value.trim(),
      haUrl: $('haUrl').value.trim(),
      haToken: $('haToken').value.trim(),
      theme: pickedTheme,
      h24: $('h24').checked,
      kiosk: $('kiosk').checked,
      keepScreen: $('keepScreen').checked
    });
    status('setupStatus', 'joining "' + ssid + '" … (the tablet leaves the setup hotspot)');
    const st = await rpc('wifi.join', { ssid, pass }, 60000);
    if (st && st.connected) {
      status('setupStatus', 'on ' + ssid + ' — ip ' + (st.ip || '?'), 'ok');
    } else {
      status('setupStatus', 'could not join — check the password', 'err');
      return;
    }
    status('setupStatus', 'finishing setup…', 'ok');
    await rpc('setup.finish', { ssid, pass });
    status('setupStatus', 'done — watch the tablet finish by itself', 'ok');
  } catch (e) {
    status('setupStatus', e.message, 'err');
  }
});

$('btnFinishNoWifi').addEventListener('click', async () => {
  try {
    status('setupStatus', 'saving settings…');
    await rpc('setup.set', {
      city: $('city').value.trim(),
      groq: $('groq').value.trim(),
      haUrl: $('haUrl').value.trim(),
      haToken: $('haToken').value.trim(),
      theme: pickedTheme,
      h24: $('h24').checked,
      kiosk: $('kiosk').checked,
      keepScreen: $('keepScreen').checked
    });
    await rpc('setup.finish', {});
    status('setupStatus', 'done — the tablet finishes on its own', 'ok');
  } catch (e) {
    status('setupStatus', e.message, 'err');
  }
});

// ------------------------------------------------------------ mods

document.querySelector('.tab[data-view="mods"]').addEventListener('click', loadMods);

async function loadMods() {
  const list = $('modList');
  try {
    const mods = await rpc('plugin.list', {}, 20000);
    list.innerHTML = '';
    if (!mods.length) {
      list.innerHTML = '<div class="dim">no mods installed on the tablet yet — install one below</div>';
      return;
    }
    mods.forEach(p => {
      const card = document.createElement('div');
      card.className = 'card';
      const row = document.createElement('div');
      row.className = 'modrow';
      row.innerHTML = `
        <div class="meta">
          <div class="name">${p.name} <span class="dim">${p.version || ''}</span></div>
          <div class="perms">${(p.permissions || []).join(' · ')}</div>
          ${p.error ? `<div class="errline">${p.error}</div>` : ''}
          <div class="state">${p.enabled ? 'enabled' : 'disabled'}</div>
        </div>`;
      const sw = document.createElement('div');
      sw.className = 'sw' + (p.enabled ? ' on' : '');
      sw.addEventListener('click', async () => {
        const r = await window.astos.rpc('rpc', 'plugin.set',
          { id: p.id, enabled: !p.enabled }, 15000);
        if (r.ok) loadMods();
        else sw.classList.add('shake');
      });
      const del = document.createElement('button');
      del.className = 'modbtn';
      del.textContent = 'x';
      del.title = 'remove mod';
      del.addEventListener('click', async () => {
        if (!confirm('Remove ' + p.name + ' from the tablet?')) return;
        const r = await window.astos.rpc('rpc', 'plugin.remove', { id: p.id }, 15000);
        if (r.ok) loadMods();
      });
      row.appendChild(sw);
      row.appendChild(del);
      card.appendChild(row);
      list.appendChild(card);
    });
  } catch (e) {
    list.innerHTML = `<div class="dim">${e.message}</div>`;
  }
}

$('btnInstallPlugin').addEventListener('click', async () => {
  const file = await window.astos.pickPlugin();
  if (!file) return;
  status('setupStatus', '', '');
  try {
    const up = await window.astos.transfer(file, 'plugin');
    if (!up.ok) throw new Error(up.error);
    const r = await window.astos.rpc('rpc', 'plugin.install',
      { path: up.result.path }, 30000);
    if (!r.ok) throw new Error(r.error);
    alert('mod installed: ' + (r.result && r.result.name ? r.result.name : file));
    loadMods();
  } catch (e) {
    alert('install failed: ' + e.message);
  }
});
$('btnReloadPlugins').addEventListener('click', loadMods);

// ------------------------------------------------------------ update

let zipPath = '';
$('btnPickZip').addEventListener('click', async () => {
  const f = await window.astos.pickFile();
  if (!f) return;
  zipPath = f;
  $('zipName').textContent = f.split(/[\\/]/).pop();
  const size = await (await fetch('file://' + encodeURI(f))).blob().then(b => b.size).catch(() => 0);
  $('zipMeta').textContent = size ? (size / 1048576).toFixed(1) + ' MB — ready' : 'ready';
  $('btnStage').disabled = false;
});

window.astos.on('transfer-progress', p => {
  $('zipProg').style.width = (p.pct || 0) + '%';
  $('zipMeta').textContent =
    `${p.pct}% · ${(p.written / 1048576).toFixed(1)} / ${(p.total / 1048576).toFixed(1)} MB`
    + (p.speed ? ` · ${p.speed} KB/s` : '');
});

$('btnStage').addEventListener('click', async () => {
  if (!zipPath) return;
  if (!confirm('Send the ROM to the tablet and reboot it into TWRP to flash?\n\nThe tablet will flash the zip, then boot the new AstOS.\nTake a TWRP backup first!')) return;
  $('btnStage').disabled = true;
  try {
    $('zipMeta').textContent = 'transferring…';
    const up = await window.astos.transfer(zipPath, 'rom');
    if (!up.ok) throw new Error(up.error);
    $('zipProg').style.width = '100%';
    $('zipMeta').textContent = 'transferred · checksum ' + (up.result.shaOk || 'sent');
    const r = await window.astos.rpc('rpc', 'rom.apply',
      { name: up.result.name, sha256: up.result.sha256, delayMs: 3000 }, 30000);
    if (!r.ok) throw new Error(r.error);
    $('zipMeta').textContent = 'staged — the tablet is rebooting into recovery now. '
      + 'TWRP will flash it and boot the new AstOS. Leave it plugged in.';
  } catch (e) {
    $('zipMeta').textContent = 'failed: ' + e.message;
    $('btnStage').disabled = false;
  }
});

// ------------------------------------------------------------ tools

let toggles = [];
async function loadToggles(info) {
  toggles = info ? [] : toggles;
  let cur;
  try {
    cur = await rpc('settings.get');
  } catch (e) { return; }
  if (!info) return;
  const host = $('toolToggles');
  host.innerHTML = '';
  const rows = [
    ['kiosk', 'kiosk (no shade, fullscreen)', cur.kiosk !== false],
    ['keepScreen', 'keep screen on', cur.keepScreen !== false],
    ['speak', 'voice replies', cur.speak !== false],
    ['bootLaunch', 'open dashboard at boot', cur.bootLaunch !== false]
  ];
  rows.forEach(([key, label, on]) => {
    const row = document.createElement('div');
    row.className = 'togglerow';
    row.innerHTML = `<span>${label}</span>`;
    const sw = document.createElement('div');
    sw.className = 'sw' + (on ? ' on' : '');
    sw.style.marginLeft = 'auto';
    sw.addEventListener('click', async () => {
      const now = !sw.classList.contains('on');
      const args = {}; args[key] = now;
      await rpc('settings.set', args);
      sw.classList.toggle('on', now);
    });
    row.appendChild(sw);
    host.appendChild(row);
  });
}

document.querySelectorAll('[data-power]').forEach(b => {
  b.addEventListener('click', async () => {
    const mode = b.dataset.power;
    if (mode === 'shutdown' && !confirm('Power the tablet off?')) return;
    try {
      if (mode === 'wakeup') await rpc('sys.wakeup', {});
      else await rpc('power.' + (mode === 'recovery' ? 'reboot' : mode === 'shutdown' ? 'shutdown' : 'reboot'),
        mode === 'recovery' ? { mode: 'recovery' } : {});
      conlog(`> power ${mode}: sent`);
    } catch (e) {
      conlog(`> power ${mode}: ${e.message}`);
    }
  });
});

function conlog(line) {
  const c = $('consoleOut');
  c.textContent += '\n' + line;
  c.scrollTop = c.scrollHeight;
}

async function runCmd() {
  const cmd = $('consoleIn').value.trim();
  if (!cmd) return;
  conlog('\n> ' + cmd);
  try {
    const r = await rpc('sys.shell', { cmd, timeoutMs: 10000 }, 20000);
    conlog(r && r.out ? r.out : `(no output — exit ${r && r.code})`);
  } catch (e) {
    conlog('error: ' + e.message);
  }
}
$('btnRun').addEventListener('click', runCmd);
$('consoleIn').addEventListener('keydown', e => {
  if (e.key === 'Enter') runCmd();
});

// ------------------------------------------------------------ boot

spawnMotes();
spawnLogoBlocks();
themeRow();
enableTabs(false);
