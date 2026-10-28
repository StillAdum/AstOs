'use strict';
// Actual Electron media element/IPC, not a mocked play() promise. The HTTPS
// protocol fixture avoids network/site variability while testing real decoding.
const {app,BrowserWindow,ipcMain}=require('electron'),assert=require('node:assert/strict');
const {NativeAudio}=require('../src/native-audio');
app.commandLine.appendSwitch('mute-audio');app.disableHardwareAcceleration();
function wav(){const rate=8000,n=rate*4,b=Buffer.alloc(44+n*2);b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(rate,24);b.writeUInt32LE(rate*2,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(n*2,40);for(let i=0;i<n;i++)b.writeInt16LE(Math.round(Math.sin(i*2*Math.PI*440/rate)*800),44+i*2);return b;}
app.whenReady().then(async()=>{
 const audio=new NativeAudio({BrowserWindow,ipcMain},'/fixture',{ready:true,resolve:async()=>({uri:'https://www.youtube.com/watch?v=test',stream:'https://fixture.googlevideo.com/test.wav',title:'Test audio',artist:'Fixture',durationMs:4000,headers:{}})});
 try{
  await audio.ensure();await audio.window.webContents.session.protocol.handle('https',()=>new Response(wav(),{headers:{'Content-Type':'audio/wav'}}));
  await audio.execute({action:'play',query:'run it back'});assert.equal(audio.state.playing,true);
  await new Promise(r=>setTimeout(r,1400));assert(audio.state.positionMs>100,'Real media timeline must advance with the window hidden');
  await audio.execute({action:'pause'});assert.equal(audio.state.playing,false);const paused=audio.state.positionMs;
  await new Promise(r=>setTimeout(r,400));assert(Math.abs(audio.state.positionMs-paused)<120);
  await audio.execute({action:'resume'});assert.equal(audio.state.playing,true);await audio.execute({action:'volset',query:'35'});assert.equal(audio.state.volume,35);
  console.log('NATIVE AUDIO SMOKE PASS: actual Electron decode, hidden-window timeline, pause/resume, volume, isolated IPC');audio.close();app.exit(0);
 }catch(e){console.error(e);audio.close();app.exit(1);}
});
