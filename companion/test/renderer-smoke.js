'use strict';
const {app,BrowserWindow,ipcMain}=require('electron');
const path=require('path');let setupDone=true,scans=0;
ipcMain.handle('resume-pairing',()=>({ok:true,info:{name:'Test tablet'}}));
ipcMain.handle('disconnect-tablet',()=>{setupDone=false;return {ok:true};});
ipcMain.handle('find-tablet',()=>({ok:true,info:{name:'Test tablet'}}));
ipcMain.handle('spotify-info',()=>({token:'test-token',connected:true}));
ipcMain.handle('rpc',(_e,cmd)=>{
 if(cmd==='wifi.list')return ++scans>1?{ok:false,error:'scan unavailable'}:{ok:true,result:[{ssid:'<img src=x onerror=alert(1)>',secure:true,level:-40}]};
 return {ok:true,result:cmd==='info'?{name:'Test tablet',setupDone,wifi:{connected:true}}:cmd==='setup.get'?{city:'Test city',theme:'pine'}:{}};
});
app.disableHardwareAcceleration();
app.whenReady().then(async()=>{
 const win=new BrowserWindow({show:false,webPreferences:{preload:path.join(__dirname,'../src/preload.js'),contextIsolation:true,nodeIntegration:false,sandbox:false}});
 try{await win.loadFile(path.join(__dirname,'../src/ui/index.html'));await require('./renderer-cases')(code=>win.webContents.executeJavaScript(code));win.destroy();app.exit(0);}
 catch(e){console.error(e);win.destroy();app.exit(1);}
});
