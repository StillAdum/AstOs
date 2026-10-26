'use strict';
// Runs the real preload and renderer against deterministic IPC fixtures.
const {app, BrowserWindow, ipcMain} = require('electron');
const path = require('path');
const calls = [];
ipcMain.handle('find-tablet', () => ({ok:true, info:{name:'Test tablet',ver:'test'}}));
ipcMain.handle('spotify-info', () => ({token:'test-token',connected:true}));
ipcMain.handle('rpc', (_e, cmd, args) => {
  calls.push({cmd,args});
  return {ok:true,result:cmd==='wifi.list'?[{ssid:'<img src=x onerror=alert(1)>',secure:true,level:-40}]:cmd==='info'?{name:'Test tablet',wifi:{connected:true}}:{}};
});
app.disableHardwareAcceleration();
app.whenReady().then(async () => {
  const win = new BrowserWindow({show:false,webPreferences:{preload:path.join(__dirname,'../src/preload.js'),contextIsolation:true,nodeIntegration:false,sandbox:false}});
  let failure;
  win.webContents.on('render-process-gone', (_e, d) => { failure = d.reason; });
  const run = code => win.webContents.executeJavaScript(code);
  const wait = async expr => { for(let i=0;i<100;i++){if(await run(expr)) return;await new Promise(r=>setTimeout(r,50));} throw new Error('Timed out: '+expr); };
  try {
    await win.loadFile(path.join(__dirname,'../src/ui/index.html'));
    await run("document.getElementById('btnNew').click();document.getElementById('btnUsb').click();");
    await wait("!!document.querySelector('#v-setup.on #netList .netrow')");
    if(await run("document.querySelectorAll('#netList img').length")) throw new Error('SSID injected HTML');
    await run("document.querySelector('#netList .netrow').click();document.getElementById('btnRescanWifi').click();");
    await new Promise(r=>setTimeout(r,200));
    if(calls.filter(c=>c.cmd==='wifi.list').length<2)throw new Error('Rescan failed');
    await run("document.getElementById('btnExisting').click();document.getElementById('btnUsb').click();");
    await wait("!!document.querySelector('#v-dash.on')");
    await run("document.getElementById('btnSpotify').click()");
    await wait("document.getElementById('spotifyToken').textContent==='test-token'");
    if(failure)throw new Error(failure);
    console.log('RENDERER PASS: real preload, existing/new, rescan, SSID safety, Spotify token');
    win.destroy();app.exit(0);
  }catch(e){console.error(e);win.destroy();app.exit(1);}
});
