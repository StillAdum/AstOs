'use strict';
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
(async()=>{
 let tick, now=100000, state={tabletConnected:true,commands:[]};const results=[],actions=[],snapshots=[],events={};
 const spotify={
  Player:{data:{item:{uri:'spotify:track:test',metadata:{title:'Test title',artist_name:'Test artist'}}},isPlaying:()=>true,getProgress:()=>321,getDuration:()=>9000,getVolume:()=>0.4,setVolume:async v=>actions.push(v),addEventListener:(e,cb)=>events[e]=cb,play:async()=>actions.push('resume'),pause:async()=>actions.push('pause'),next:async()=>actions.push('next'),back:async()=>actions.push('previous'),playUri:async uri=>actions.push(uri)},
  CosmosAsync:{get:async()=>({tracks:{items:[{uri:'spotify:track:test'}]}})},
  Menu:{Item:class{register(){}}},showNotification(){}
 };
 const context={window:{Spicetify:spotify},Spicetify:spotify,localStorage:{getItem:()=> 'test-token'},
  prompt:()=>null,AbortController,setTimeout,clearTimeout,setInterval:fn=>{tick=fn},Date:{now:()=>now},
  fetch:async(url,options)=>({ok:true,json:async()=>{
   if(url.endsWith('/state')) {snapshots.push(JSON.parse(options.body));return {};}
   if(url.endsWith('/result')) {results.push(JSON.parse(options.body));return {};}
   return state;
  }})};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../spicetify/astos.js'),'utf8'), context);
 await Promise.resolve();assert.equal(typeof tick,'function');
 state.commands=[{id:'1',action:'play',query:'a song',sticky:true}]; await tick();
 assert.equal(snapshots.at(-1).title,'Test title');assert.equal(snapshots.at(-1).artist,'Test artist');assert.equal(snapshots.at(-1).positionMs,321);assert.equal(typeof events.songchange,'function');
 assert.deepEqual(actions,['spotify:track:test']);assert.equal(results[0].ok,true);
 await tick();assert.equal(actions.length,1,'Repeated poll must not replay song');
 state={tabletConnected:false,commands:[]};now+=16000;await tick();
 assert.equal(actions.at(-1),'pause','Sticky playback must pause after tablet disconnect');
 state={tabletConnected:true,commands:[{id:'2',action:'shell'}]};await tick();assert.equal(results.at(-1).ok,false);
 state={tabletConnected:true,commands:[{id:'3',action:'volset',query:75}]};await tick();assert.equal(actions.at(-1),0.75);
 console.log('SPICETIFY PASS: search/play, acknowledgement, duplicate suppression, disconnect pause, allowlist');
})().catch(e=>{console.error(e);process.exit(1)});
