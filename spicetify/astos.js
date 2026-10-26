// AstOS Spotify bridge. Install as a Spicetify extension. No cloud tokens needed.
(async function astos() {
  while (!window.Spicetify?.Player || !Spicetify?.CosmosAsync || !Spicetify?.Menu) {
    await new Promise(r => setTimeout(r, 500));
  }
  const base = 'http://127.0.0.1:58518';
  let token = localStorage.getItem('astos.bridge.token') || '';
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
    const timer = setTimeout(() => controller.abort(), 4000);
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
  async function execute(c) {
    if (c.action === 'pause') { await Spicetify.Player.pause(); sticky = false; return; }
    if (c.action === 'next') await Spicetify.Player.next();
    else if (c.action === 'previous') await Spicetify.Player.back();
    else if (c.action === 'resume' || (c.action === 'play' && !c.query)) await Spicetify.Player.play();
    else if (c.action === 'play') {
      const data = await Spicetify.CosmosAsync.get('https://api.spotify.com/v1/search?type=track&limit=1&q=' + encodeURIComponent(c.query));
      const track = data?.tracks?.items?.[0];
      if (!track?.uri) throw new Error('No Spotify track found');
      await Spicetify.Player.playUri(track.uri);
    } else throw new Error('Unknown playback action');
    sticky = !!c.sticky;
  }
  setInterval(async () => {
    if (sticky && Date.now() - lastOk > 15000) {
      sticky = false; await Spicetify.Player.pause();
      Spicetify.showNotification('AstOS disconnected — PC playback paused');
    }
    if (!token || busy) return;
    busy = true;
    try {
      const state = await request('/poll');
      if (state.tabletConnected) lastOk = Date.now();
      for (const c of state.commands) {
        let result = results.get(c.id);
        if (!result) {
          try { await execute(c); result = {id: c.id, ok: true}; }
          catch (e) { result = {id: c.id, ok: false, error: e.message}; }
          results.set(c.id, result);
          if (results.size > 128) results.delete(results.keys().next().value);
        }
        await request('/result', result);
      }
    } catch (e) { /* Offline is normal. Never replay unacknowledged actions. */ }
    finally { busy = false; }
  }, 750);
})();
