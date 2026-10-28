// AstOS Spotify bridge. Install as a Spicetify extension. No cloud tokens needed.
(async function astos() {
  while (!window.Spicetify?.Player || !Spicetify?.CosmosAsync || !Spicetify?.Menu) {
    await new Promise(r => setTimeout(r, 500));
  }
  const base = 'http://127.0.0.1:58518';
  let token = localStorage.getItem('astos.bridge.token') || '';
  let disconnectPause = localStorage.getItem("astos.pauseOnDisconnect") === "true";
  new Spicetify.Menu.Item("AstOS: pause on tablet disconnect (optional)", disconnectPause, item => {
    disconnectPause = !disconnectPause; item.setState?.(disconnectPause);
    localStorage.setItem("astos.pauseOnDisconnect", String(disconnectPause));
  }).register();
  const clock = () => window.performance?.now ? window.performance.now() : Date.now();
  let anchor = null;
  let sticky = false, lastOk = Date.now(), busy = false;
  const results = new Map();
  new Spicetify.Menu.Item('AstOS: pair PC companion', false, () => {
    const value = prompt('Paste the Spotify pairing token shown in AstOS Companion:', token);
    if (value !== null) {
      token = value.trim(); localStorage.setItem('astos.bridge.token', token);
      Spicetify.showNotification('AstOS pairing token saved');
    }
  }).register();
  async function request(path, data) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), path === '/events' ? 30000 : 8000);
    try {
      const response = await fetch(base + path, {
        method: data ? 'POST' : 'GET',
        headers: {Authorization: 'Bearer ' + token, ...(data ? {'Content-Type': 'application/json'} : {})},
        ...(data ? {body: JSON.stringify(data)} : {}), signal: controller.signal
      });
      if (!response.ok) throw new Error('Companion HTTP ' + response.status);
      return await response.json();
    } finally { clearTimeout(timer); }
  }
  function snapshot(forcePosition = false) {
    const player = Spicetify.Player, item = player.data?.item;
    const metadata = item?.metadata || {};
    let art=metadata.image_xlarge_url || metadata.image_large_url || metadata.image_url || item?.album?.images?.[0]?.url || '';
    if(art.startsWith('spotify:image:'))art='https://i.scdn.co/image/'+art.split(':').pop();
    const raw = Number(player.getProgress?.() || 0), playing = typeof player.isPlaying === 'function' ? player.isPlaying() : !player.data?.isPaused;
    const now = clock(), uri = item?.uri || '';
    if (!anchor || forcePosition || anchor.uri !== uri || anchor.raw !== raw)
      anchor = {uri, raw, base:raw, playing, at:now};
    else if (anchor.playing !== playing) {
      // A background client's raw position can remain frozen across pause/resume.
      // Preserve the estimated cursor, not the stale raw position, at that boundary.
      const cursor=anchor.base+(anchor.playing?now-anchor.at:0);
      anchor={uri,raw,base:cursor,playing,at:now};
    }
    const duration = Number(player.getDuration?.() || metadata.duration || 0);
    const position = Math.max(0, Math.min(duration || 86400000, anchor.base + (playing ? now-anchor.at : 0)));
    return {artUrl:art,available: !!item, uri: item?.uri || '', title: item?.name || metadata.title || '',
      artist: item?.artists?.map(a => a.name).join(', ') || metadata.artist_name || '',
      playing, positionMs: position, estimated:Math.abs(anchor.base-anchor.raw)>1 || (playing && now-anchor.at>2000),
      durationMs: typeof player.getDuration === 'function' ? player.getDuration() : Number(metadata.duration || 0),
      volume: typeof player.getVolume === 'function' ? Math.round(player.getVolume()*100) : 0};
  }
  async function publish(force = false) { if (token) await request('/state', snapshot(force)); }
  for (const event of ['songchange','onplaypause','onprogress']) Spicetify.Player.addEventListener?.(event, () => { publish(event === 'songchange').catch(() => {}); });
  function trackUri(query) {
    const value = String(query || '').trim();
    if (/^spotify:track:[A-Za-z0-9]+$/.test(value)) return value;
    const match = value.match(/^https:\/\/open\.spotify\.com\/(?:intl-[^/]+\/)?track\/([A-Za-z0-9]+)/);
    return match ? 'spotify:track:' + match[1] : null;
  }
  async function bounded(promise, label) {
    let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error(label+' timed out')),12000);})]);}finally{clearTimeout(timer);}
  }
  async function search(query) {
    if (!String(query || '').trim()) throw new Error('Enter a song title and optionally an artist');
    let data;
    try { data = await bounded(Spicetify.CosmosAsync.get('https://api.spotify.com/v1/search?type=track&limit=5&q=' + encodeURIComponent(query)), 'Spotify search'); }
    catch (e) { throw new Error('Spotify search failed: ' + String(e?.message || e) + '. Try a Spotify track link, or update Spicetify and sign in to Spotify again.'); }
    if (data?.error) throw new Error('Spotify search: ' + (data.error.message || data.error.status));
    const tracks = (data?.tracks?.items || []).filter(t => t?.uri && t.is_playable !== false).slice(0,5).map(t => ({uri:t.uri,title:t.name || '',artist:(t.artists || []).map(a=>a.name).join(', '),durationMs:t.duration_ms || 0}));
    if (!tracks.length) throw new Error('No playable Spotify tracks found for ' + query);
    return {tracks};
  }
  async function playUri(uri) {
    if (typeof Spicetify.Player.playUri === 'function') await Spicetify.Player.playUri(uri);
    else if (Spicetify.Platform?.PlayerAPI?.play) await Spicetify.Platform.PlayerAPI.play({uri}, {}, {});
    else throw new Error('Spotify playback API unavailable: update Spicetify');
  }
  async function execute(c) {
    if(c.expiresAt && Date.now()>c.expiresAt)throw new Error("Playback command expired; not replayed");
    const p = Spicetify.Player;
    if (c.action === 'search') return await search(c.query);
    if (c.action === 'state') return snapshot();
    if (['volup','voldown','volset'].includes(c.action)) {
      if (typeof p.setVolume !== 'function') throw new Error('Spotify volume API unavailable');
      const current = p.getVolume(), amount = Number(c.query);
      if(c.action === 'volset' && (!Number.isFinite(amount) || amount<0 || amount>100)) throw new Error('Volume must be 0–100');
      await p.setVolume(Math.max(0,Math.min(1,c.action === 'volset' ? amount/100 : current + (c.action === 'volup' ? 0.05 : -0.05))));
      return {accepted:true};
    }
    if (c.action === 'pause') { await p.pause(); sticky = false; return {accepted:true}; }
    if (c.action === 'next') await p.next();
    else if (c.action === 'previous') await p.back();
    else if (c.action === 'resume' || (c.action === 'play' && !c.query)) { await p.play(); sticky = !!c.sticky; }
    else if (c.action === 'play') {
      const uri = trackUri(c.query) || (await search(c.query)).tracks[0].uri;
      await playUri(uri); sticky = !!c.sticky;
      return {accepted:true,uri};
    } else throw new Error('Unknown playback action');
    return {accepted:true};
  }
  let lastPublished=0;
  async function poll() {
    if (!token || busy) return;
    busy = true;
    try {
      if(Date.now()-lastPublished>1000){await publish();lastPublished=Date.now();}
      const state = await request('/events');
      if (state.tabletConnected) lastOk = Date.now();
      else if(disconnectPause && sticky && Date.now()-lastOk>45000){sticky=false;await Spicetify.Player.pause();Spicetify.showNotification('AstOS disconnected — PC playback paused');}
      for (const c of state.commands) {
        let result = results.get(c.id);
        if (!result) {
          try { const data = await execute(c); result = {id: c.id, ok: true, data}; }
          catch (e) { result = {id: c.id, ok: false, error: e.message}; }
          results.set(c.id, result);
          if (results.size > 128) results.delete(results.keys().next().value);
        }
        await request('/result', result);
      }
    } catch (e) {
      if(disconnectPause&&sticky&&Date.now()-lastOk>45000){sticky=false;await Spicetify.Player.pause();Spicetify.showNotification('AstOS disconnected — PC playback paused');}
      if(window.document)await new Promise(r=>setTimeout(r,2000));
    }
    finally { busy = false; }
  }
  setInterval(poll,750);
  // Network-completion-driven long polling keeps commands alive when Chromium
  // throttles background timers. Retry delays are only used while offline.
  if(window.document)(async()=>{while(true){await poll();if(!token||busy)await new Promise(r=>setTimeout(r,1000));}})();
})();
