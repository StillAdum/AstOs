'use strict';
const assert = require('node:assert/strict');
const {SpotifyBridge} = require('../src/spotify-bridge');
(async () => {
  let clock=1000;
  const bridge = new SpotifyBridge('test-only-not-a-secret', 0,()=>clock);
  await bridge.start();
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const headers = {Authorization: 'Bearer test-only-not-a-secret'};
  try {
    assert.equal((await fetch(base + '/poll')).status, 401);
    assert.equal((await fetch(base + '/poll', {headers: {...headers, Origin: 'https://evil.example'}})).status, 403);
    await assert.rejects(bridge.execute({id:'x',action:'play'}), /disconnected/);
    let state = await (await fetch(base + '/poll', {headers})).json();
    assert.equal(state.tabletConnected, false);
    assert.equal((await fetch(base+'/state',{method:'POST',body:'{}'})).status,401);
    await fetch(base+'/state',{method:'POST',headers,body:JSON.stringify({available:true,title:'Track',artist:'Artist',playing:true,positionMs:1234,durationMs:8000,volume:150})});
    assert.equal(bridge.state.title,'Track'); assert.equal(bridge.state.volume,100); assert.equal(bridge.state.positionMs,1234);clock+=2000;assert.equal(bridge.state.positionMs,3234,'Bridge timeline extrapolates while renderer is backgrounded');
    assert.equal((await fetch(base+'/state',{method:'POST',headers,body:'invalid'})).status,400);
    const events=fetch(base+'/events',{headers});
    await new Promise(r=>setTimeout(r,30));
    const longJob=bridge.execute({id:'long',action:'resume'});
    const delivery=await (await events).json();assert.equal(delivery.commands[0].id,'long');
    await fetch(base+'/result',{method:'POST',headers,body:JSON.stringify({id:'long',ok:true})});await longJob;
    bridge.tabletSeen = Date.now();
    const job = bridge.execute({id:'c1',action:'next',sticky:true});
    state = await (await fetch(base + '/poll', {headers})).json();
    assert.equal(state.tabletConnected, true);
    assert.equal(state.commands[0].action, 'next');
    // Unacknowledged commands stay available; caller deduplicates IDs.
    assert.equal((await (await fetch(base + '/poll', {headers})).json()).commands[0].id, 'c1');
    await fetch(base + '/result', {method:'POST', headers, body:JSON.stringify({id:'c1',ok:true,data:{tracks:[{uri:'spotify:track:result'}]}})});
    assert.equal((await job).tracks[0].uri,'spotify:track:result','Structured tool results propagate');
    assert.deepEqual((await (await fetch(base + '/poll', {headers})).json()).commands, []);
    await assert.rejects(bridge.execute({id:'x',action:'shell'}), /Unsupported/);
    const failed = bridge.execute({id:'c2',action:'play'});
    const rejected = assert.rejects(failed, /No track/);
    await fetch(base + '/result', {method:'POST',headers,body:JSON.stringify({id:'c2',ok:false,error:'No track'})});
    await rejected;
    const lost = bridge.execute({id:'c3',action:'pause'});
    const disconnected = assert.rejects(lost, /disconnected/);
    bridge.disconnect(); await disconnected; assert.equal(bridge.state.available,true,'Tablet link loss must not erase the Spotify session');
    console.log('SPOTIFY BRIDGE PASS: auth, origin, lease, ack, failure, disconnect');
  } finally { bridge.close(); }
})().catch(e => {console.error(e); process.exit(1);});
