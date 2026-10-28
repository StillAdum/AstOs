'use strict';
// Source selection is independent of Spotify's availability. Ordinary song names
// use the bundled resolver/player; explicit Spotify links use the extension.
class PcMusic{
 constructor(native,spotify){this.native=native;this.spotify=spotify;this.target='';this.jobs=new Map();}
 get connected(){return this.native.ready||this.spotify.connected;}
 get state(){return this.target==='native'?{...this.native.state,source:'pc-audio'}:this.spotify.connected?{...this.spotify.state,source:'spotify'}:{...this.native.state,source:'pc-audio'};}
 execute(c){
  if(this.jobs.has(c.id))return this.jobs.get(c.id);
  if(this.jobs.size>=128)this.jobs.delete(this.jobs.keys().next().value);
  const p=this.run(c);this.jobs.set(c.id,p);return p;
 }
 async run(c){
  const explicit=/^(spotify:|https:\/\/open\.spotify\.com\/)/i.test(c.query||'');
  if(c.action==='search'&&!explicit)return this.native.search(c.query);
  if(c.action==='play'&&c.query){
   if(explicit){if(!this.spotify.connected)throw Error('Spotify link needs Spotify and the AstOS extension. Use a song title for Spotify-free PC playback.');const result=await this.spotify.execute(c);if(this.target==='native')await this.native.execute({action:'pause'});this.target='spotify';return result;}
   this.target='native';const result=await this.native.execute(c);if(this.spotify.connected&&this.spotify.state.playing)this.spotify.execute({...c,id:c.id+'-pause-spotify',action:'pause',query:''}).catch(()=>{});this.target='native';return result;
  }
  return this.target==='native'||!this.spotify.connected?this.native.execute(c):this.spotify.execute(c);
 }
 disconnect(){this.spotify.disconnect();} // Network loss never pauses local audio.
 close(){this.native.close();this.spotify.close();}
}
module.exports={PcMusic};
