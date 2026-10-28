'use strict';
const {execFile}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path');
const PAGE_HOSTS=['youtube.com','youtu.be','soundcloud.com'];
function pageUrl(value){
 const u=new URL(value);const h=u.hostname.toLowerCase();
 if(u.protocol!=='https:'||u.username||u.password||u.port||!PAGE_HOSTS.some(x=>h===x||h.endsWith('.'+x)))throw Error('Use a YouTube or SoundCloud HTTPS link, or a song title');
 return u.href;
}
function mediaUrl(value){
 const u=new URL(value);const h=u.hostname.toLowerCase();
 if(u.protocol!=='https:'||u.username||u.password||u.port||!['googlevideo.com','sndcdn.com','soundcloud.cloud'].some(x=>h===x||h.endsWith('.'+x)))throw Error('The source returned an unsupported audio CDN');
 return u.href;
}
function track(info){
 const raw=info.webpage_url||info.original_url||info.url||(info.id&&`https://www.youtube.com/watch?v=${info.id}`);
 return {uri:pageUrl(raw),title:String(info.title||'Untitled').slice(0,512),artist:String(info.artist||info.uploader||info.channel||'').slice(0,256),artUrl:/^https:\/\//.test(info.thumbnail||'')?info.thumbnail:'',durationMs:Math.max(0,Number(info.duration)||0)*1000};
}
class MediaResolver{
 constructor(resources,{run=execFile,exists=fs.existsSync}={}){this.exe=path.join(resources,'yt-dlp.exe');this.node=path.join(resources,'node.exe');this.run=run;this.exists=exists;}
 get ready(){return this.exists(this.exe)&&this.exists(this.node);}
 json(input,extra=[]){
  if(!this.ready)return Promise.reject(Error('PC music engine is missing. Install the current AstOS Companion release.'));
  const args=['--ignore-config','--no-plugin-dirs','--no-remote-components','--no-warnings','--no-progress','--no-playlist','--socket-timeout','5','--retries','0','--extractor-retries','0','--js-runtimes',`node:${this.node}`,'--dump-single-json','--skip-download',...extra,'--',input];
  return new Promise((resolve,reject)=>this.run(this.exe,args,{windowsHide:true,timeout:10000,maxBuffer:8*1024*1024,encoding:'utf8'},(err,out,stderr)=>{
   if(err)return reject(Error(String(stderr||err.message).replace(/https?:\/\/\S+/g,'[source]').trim().slice(-700)||'Music source failed'));
   try{resolve(JSON.parse(out));}catch{reject(Error('Music source returned invalid metadata'));}
  }));
 }
 async search(query,limit=5){
  query=String(query||'').trim();if(!query||query.length>512)throw Error('Enter a song title and optionally its artist');
  if(/^https?:/i.test(query)){const info=await this.json(pageUrl(query),['--flat-playlist']);return {tracks:[track(info)]};}
  if(/^[a-z][a-z0-9+.-]*:/i.test(query))throw Error('Unsupported music link');
  const errors=[];
  for(const provider of ['ytsearch','scsearch']){
   try{
    const data=await this.json(`${provider}${limit}:${query}`,['--flat-playlist']);
    const tracks=(data.entries||[]).filter(Boolean).slice(0,limit).map(track);
    if(tracks.length)return {tracks};errors.push(provider+': no matches');
   }catch(e){errors.push(e.message);}
  }
  throw Error('No playable search results: '+errors.join('; ').slice(-900));
 }
 async resolve(query){
  const errors=[];
  // Try SoundCloud if YouTube searches successfully but its audio extraction fails too.
  const direct=/^https?:/i.test(query);
  const candidates=direct?[pageUrl(query)]:['ytsearch1:'+query,'scsearch1:'+query];
  for(const input of candidates){
   try{
    const data=await this.json(input,['-f','bestaudio[ext=webm][protocol=https]/bestaudio[ext=m4a][protocol=https]/bestaudio[protocol=https]/bestaudio[ext=m4a]']);
    const info=data.entries?.find(Boolean)||data;if(info.is_live)throw Error('Live streams are not supported yet');
    return {...track(info),stream:mediaUrl(info.url),hls:String(info.protocol||'').includes('m3u8'),headers:info.http_headers||{}};
   }catch(e){errors.push(e.message);}
  }
  throw Error('Could not play this song: '+errors.join('; ').slice(-1000));
 }
}
module.exports={MediaResolver,pageUrl,mediaUrl,track};
