'use strict';
const vm=require('node:vm'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
async function harness(optIn=false,native=false){
 let offline=false,tick,now=100000,raw=40000,playing=true,state={tabletConnected:true,commands:[]},searchFailure=false;
 const results=[],actions=[],snapshots=[],events={},timeouts=[];
 const spotify={Player:{data:{item:{uri:'spotify:track:test',metadata:{title:'Test title',artist_name:'Test artist',image_url:'spotify:image:abc'}}},isPlaying:()=>playing,getProgress:()=>raw,getDuration:()=>180000,getVolume:()=>0.4,setVolume:async v=>actions.push(v),addEventListener:(e,cb)=>events[e]=cb,play:async()=>actions.push('resume'),pause:async()=>actions.push('pause'),next:async()=>actions.push('next'),back:async()=>actions.push('previous'),playUri:native?undefined:async uri=>actions.push(uri)},
 Platform:{PlayerAPI:{play:async track=>actions.push(track.uri)}},CosmosAsync:{get:async()=>{if(searchFailure)throw new Error('401');return {tracks:{items:[{uri:'spotify:track:test',name:'A song',artists:[{name:'Artist'}]}]}};}},Menu:{Item:class{register(){}}},showNotification(){}};
 const context={window:{Spicetify:spotify},Spicetify:spotify,localStorage:{getItem:k=>k==='astos.pauseOnDisconnect'?String(optIn):'test-token'},prompt:()=>null,AbortController,setTimeout:(f,t)=>{timeouts.push(t);return setTimeout(f,t)},clearTimeout,setInterval:fn=>{tick=fn},Date:{now:()=>now},fetch:async(url,options)=>({ok:!offline,json:async()=>{if(url.endsWith('/state')){snapshots.push(JSON.parse(options.body));return {};}if(url.endsWith('/result')){results.push(JSON.parse(options.body));return {};}return state;}})};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../spicetify/astos.js'),'utf8'),context);await Promise.resolve();
 return {actions,results,snapshots,timeouts,events,run:async commands=>{state.commands=commands;await tick();},advance:n=>now+=n,disconnect:()=>state.tabletConnected=false,offline:()=>offline=true,raw:n=>raw=n,playing:v=>playing=v,failSearch:()=>searchFailure=true};
}
(async()=>{
 let h=await harness();await h.run([{id:'1',action:'play',query:'a song',sticky:true}]);assert.equal(h.actions[0],'spotify:track:test');assert.equal(h.results.at(-1).data.uri,'spotify:track:test');
 await h.run([{id:'1',action:'play',query:'a song'}]);assert.equal(h.actions.length,1,'Duplicate delivery must not restart playback');
 h.advance(13000);await h.run([]);assert.equal(h.snapshots.at(-1).positionMs,53000,'Frozen Spotify progress must advance between native updates');assert.equal(h.snapshots.at(-1).artUrl,'https://i.scdn.co/image/abc');
 h.raw(2000);h.advance(2000);await h.run([]);assert.equal(h.snapshots.at(-1).positionMs,2000,'Seek must reset clock');h.raw(4000);h.playing(false);h.advance(2000);await h.run([]);h.advance(2000);await h.run([]);assert.equal(h.snapshots.at(-1).positionMs,4000,'Paused progress stays fixed');
 h.disconnect();h.advance(46000);await h.run([]);h.offline();h.advance(46000);await h.run([]);assert.equal(h.actions.includes('pause'),false,'Ordinary playback must NEVER auto-pause during link/bridge loss');assert(h.timeouts.includes(30000),'Long-poll deadline must exceed server hold time');
 h=await harness();await h.run([]);h.advance(13000);await h.run([]);h.playing(false);h.events.onplaypause();await h.run([]);h.advance(2000);await h.run([]);assert.equal(h.snapshots.at(-1).positionMs,53000,'Pausing with a frozen native sample must preserve the cursor');h.playing(true);h.events.onplaypause();await h.run([]);h.advance(2000);await h.run([]);assert.equal(h.snapshots.at(-1).positionMs,55000,'Resume continues from the preserved cursor');
 h=await harness(true);await h.run([{id:'1',action:'resume',sticky:true}]);h.disconnect();h.advance(46000);await h.run([]);assert.equal(h.actions.at(-1),'pause','Only explicit opt-in allows disconnect pause');
 h=await harness();await h.run([{id:'s',action:'search',query:'find me'}]);assert.equal(h.results.at(-1).data.tracks[0].title,'A song');assert.equal(h.actions.length,0,'Search must not start playback');
 await h.run([{id:'link',action:'play',query:'https://open.spotify.com/track/abc123?si=x'}]);assert.equal(h.actions.at(-1),'spotify:track:abc123');
 h.failSearch();await h.run([{id:'bad',action:'play',query:'missing'}]);assert.equal(h.results.at(-1).ok,false);assert.match(h.results.at(-1).error,/search failed/);assert.equal(h.actions.length,1);
 await h.run([{id:'old',action:'next',expiresAt:1}]);assert.equal(h.results.at(-1).ok,false);assert.equal(h.actions.length,1,'Expired command must not mutate playback');
 await h.run([{id:'unknown',action:'shell'}]);assert.equal(h.results.at(-1).ok,false);
 h=await harness(false,true);await h.run([{id:'native',action:'play',query:'spotify:track:abc'}]);assert.equal(h.actions.at(-1),'spotify:track:abc');
 console.log('SPICETIFY PREVIEW PASS: deadlines, no unsolicited pauses, opt-in policy, frozen progress, seek/pause, search results, URI, fallback, expired commands, dedup, errors');
})().catch(e=>{console.error(e);process.exit(1)});
