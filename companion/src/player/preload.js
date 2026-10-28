'use strict';
const {contextBridge,ipcRenderer}=require('electron');
const channel=process.argv.find(x=>x.startsWith('--astos-audio-channel='))?.split('=')[1];
if(channel)contextBridge.exposeInMainWorld('pcAudio',{
 receive:fn=>ipcRenderer.on(channel,(_e,data)=>fn(data)),
 send:data=>ipcRenderer.send(channel,data)
});
