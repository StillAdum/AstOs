 'use strict';
const http = require('http');
const crypto = require('crypto');
const ORIGIN = 'https://xpui.app.spotify.com';
class SpotifyBridge {
  constructor(token, port = 58518) {
    this.token = token; this.port = port; this.lastSeen = 0;
    this.waiters=new Set(); this.jobs = new Map(); this.server = null; this.tabletSeen = 0; this.state = {available:false};
  }
  get connected() { return Date.now() - this.lastSeen < 75000; }
  start() {
    this.server = http.createServer(async (req, res) => {
      const origin = req.headers.origin;
      if (origin && origin !== ORIGIN) { res.writeHead(403); return res.end(); }
      if (origin) res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
      res.setHeader('Access-Control-Allow-Private-Network', 'true');
      res.setHeader('Cache-Control', 'no-store');
      if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
      const auth = Buffer.from(req.headers.authorization || '');
      const expected = Buffer.from('Bearer ' + this.token);
      if (auth.length !== expected.length || !crypto.timingSafeEqual(auth, expected)) {
        res.writeHead(401); return res.end();
      }
      res.setHeader('Content-Type', 'application/json');
      if(req.url==='/events'&&req.method==='GET'){
        this.lastSeen=Date.now();const send=()=>{clearTimeout(timer);this.waiters.delete(send);if(!res.writableEnded)res.end(JSON.stringify({tabletConnected:Date.now()-this.tabletSeen<45000,commands:[...this.jobs.values()].map(j=>j.command)}));};
        const timer=setTimeout(send,25000);this.waiters.add(send);res.on('close',()=>{clearTimeout(timer);this.waiters.delete(send);});if(this.jobs.size)send();return;
      }
      if (req.url === '/poll' && req.method === 'GET') {
        this.lastSeen = Date.now();
        return res.end(JSON.stringify({tabletConnected: Date.now() - this.tabletSeen < 8000, commands: [...this.jobs.values()].map(j => j.command)}));
      }
      if ((req.url === '/result' || req.url === '/state') && req.method === 'POST') {
        let data = '';
        req.on('data', c => { data += c; if (data.length > 8192) req.destroy(); });
        req.on('end', () => {
          try {
            const result = JSON.parse(data);
            if (req.url === '/state') {
              this.lastSeen = Date.now();
              const number = (value, max) => Math.max(0, Math.min(max, Number(value) || 0));
              this.state = {available: result.available === true, title: String(result.title || '').slice(0,512), artist: String(result.artist || '').slice(0,256), artUrl:/^https:\/\//.test(result.artUrl||'')?String(result.artUrl).slice(0,1024):'', uri: String(result.uri || '').slice(0,512), playing: result.playing === true, positionMs:number(result.positionMs,86400000),durationMs:number(result.durationMs,86400000),volume:number(result.volume,100)};
              return res.end('{}');
            }
            const job = this.jobs.get(result.id);
            if (job) {
              this.jobs.delete(result.id); clearTimeout(job.timer);
              if (result.ok) job.resolve(); else job.reject(new Error(String(result.error || 'Spotify failed')));
            }
            res.end('{}');
          } catch { res.writeHead(400); res.end('{}'); }
        });
        return;
      }
      res.writeHead(404); res.end('{}');
    });
    return new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(this.port, '127.0.0.1', resolve);
    });
  }
  execute(command) {
    if (!this.connected) return Promise.reject(new Error('Spotify extension is disconnected'));
    if (!['play', 'pause', 'resume', 'next', 'previous', 'volup', 'voldown', 'volset'].includes(command.action))
      return Promise.reject(new Error('Unsupported playback action'));
    if (this.jobs.size >= 16) return Promise.reject(new Error('Spotify queue full'));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.jobs.delete(command.id); reject(new Error('Spotify did not respond')); }, 30000);
      this.jobs.set(command.id, {command, resolve, reject, timer});
      for(const send of [...this.waiters])send();
    });
  }
  disconnect() {
    this.tabletSeen = 0;
    for (const j of this.jobs.values()) { clearTimeout(j.timer); j.reject(new Error('Tablet disconnected')); }
    this.jobs.clear();
  }
  close() { this.disconnect(); for(const send of [...this.waiters])send(); if (this.server) this.server.close(); }
}
module.exports = { SpotifyBridge };
