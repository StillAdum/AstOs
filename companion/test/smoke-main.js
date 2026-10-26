/**
 * smoke-main.js — headless smoke test for the REAL renderer stack:
 * loads src/ui/index.html with the real preload (no mocks), then asserts
 * window.astos exists and the welcome view rendered.
 *   DISPLAY=:99 npx electron test/smoke-main.js --no-sandbox
 * Exit 0 = PASS, anything else = FAIL.
 */
'use strict';

const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

app.disableHardwareAcceleration();

// the real main.js registers this; smoke needs it for the unpaired test
ipcMain.handle('rpc', async (ev, cmd, args, timeoutMs) => {
  // no session in smoke mode -> graceful failure path
  return { ok: false, error: 'not connected' };
});

app.whenReady().then(async () => {
  let result = 'NOT-RUN';
  const win = new BrowserWindow({
    width: 1080, height: 720,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, '..', 'src', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  const fail = (msg) => {
    console.error('SMOKE FAIL:', msg);
    process.exit(1);
  };

  win.webContents.on('render-process-gone', (_e, d) => fail('renderer died: ' + d.reason));
  win.webContents.on('console-message', (_e, _lvl, msg) => {
    if (/error/i.test(msg)) console.log('[renderer]', msg);
  });

  try {
    await win.loadFile(path.join(__dirname, '..', 'src', 'ui', 'index.html'));
  } catch (e) {
    fail('loadFile: ' + e.message);
  }

  // let app.js wire up, then probe
  setTimeout(async () => {
    try {
      const probe = await win.webContents.executeJavaScript(`(() => {
        return {
          astos: typeof window.astos === 'object',
          astosRpc: typeof (window.astos && window.astos.rpc) === 'function',
          welcomeVisible: !!(document.getElementById('v-welcome')
            && document.getElementById('v-welcome').classList.contains('on')),
          tabs: document.querySelectorAll('.tab').length,
          logoBlocks: document.querySelectorAll('.logo-blocks i').length,
          motes: document.querySelectorAll('.mote').length,
          title: document.title
        };
      })()`);
      console.log('probe:', JSON.stringify(probe));
      if (!probe.astos || !probe.astosRpc) fail('window.astos missing — preload broken');
      if (!probe.welcomeVisible) fail('welcome view not shown');
      if (probe.tabs < 6) fail('tabs missing: ' + probe.tabs);
      if (probe.logoBlocks < 6) fail('logo blocks missing');
      if (probe.motes < 10) fail('motes missing');
      // preload IPC round-trip with no device (must fail gracefully, not throw)
      const rpc = await win.webContents.executeJavaScript(
        `window.astos.rpc('info', {}, 1000)`);
      if (rpc.ok) fail('rpc should fail when not paired, got: ' + JSON.stringify(rpc));
      console.log('rpc-unpaired:', JSON.stringify(rpc));
      console.log('SMOKE OK — real preload + renderer + UI wired');
      process.exit(0);
    } catch (e) {
      fail('probe: ' + e.message);
    }
  }, 1500);
});

app.on('window-all-closed', () => app.quit());
