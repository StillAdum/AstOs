'use strict';
const audio=document.getElementById('audio');let generation=0,position=0,hls=null;
function state(){return {playing:!audio.paused&&!audio.ended,positionMs:Math.round((audio.currentTime||0)*1000),...(Number.isFinite(audio.duration)?{durationMs:Math.round(audio.duration*1000)}:{}),volume:Math.round(audio.volume*100)};}
function send(type,extra={}){window.pcAudio.send({type,generation,state:state(),...extra});}
function error(){return ['','Audio loading was aborted','Audio network failed','Audio could not be decoded','Audio source is unavailable'][audio.error?.code||0]||'Audio playback failed';}
audio.addEventListener('error',()=>send('error',{error:error()}));
audio.addEventListener('ended',()=>send('ended'));
for(const name of ['timeupdate','playing','pause','volumechange','seeked'])audio.addEventListener(name,()=>send('state'));
audio.addEventListener('loadedmetadata',()=>{if(position>0){audio.currentTime=position/1000;position=0;}});
window.pcAudio.receive(async c=>{
 generation=c.generation;
 try{
  if(c.action==='load'){
   audio.pause();if(hls){hls.destroy();hls=null;}
   position=c.positionMs||0;audio.volume=c.volume/100;
   if(c.hls&&window.Hls?.isSupported()){
    hls=new Hls({enableWorker:false,maxBufferLength:30});
    await new Promise((resolve,reject)=>{
     hls.on(Hls.Events.MANIFEST_PARSED,resolve);
     hls.on(Hls.Events.ERROR,(_event,data)=>{if(data.fatal){const message='Audio stream failed: '+data.details;send('error',{error:message});reject(Error(message));}});
     hls.loadSource(c.url);hls.attachMedia(audio);
    });
   }else{audio.src=c.url;audio.load();}
   await audio.play();
  }else if(c.action==='pause')audio.pause();
  else if(c.action==='resume')await audio.play();
  else if(c.action==='volume')audio.volume=c.volume/100;
  else if(c.action==='seek')audio.currentTime=c.positionMs/1000;
  else throw Error('Unknown player action');
  window.pcAudio.send({type:'reply',generation:c.generation,state:state(),id:c.id});
 }catch(e){window.pcAudio.send({type:'reply',generation:c.generation,state:state(),id:c.id,error:audio.error?error():e.message});}
});
setInterval(()=>send('state'),1000);
