/**
 * ui-mock.js — a fake window.astos for browser-only UI testing:
 * the renderer runs against a scripted AstOS tablet without Electron.
 */
'use strict';

(function () {
  const handlers = {};
  const listeners = {};

  const TABLET = {
    name: 'AstTab', ver: '3.1', model: 'Lenovo TB3-710F', device: 'TB3-710F',
    uptimeMs: 3 * 3600 * 1000 + 42 * 60 * 1000,
    battery: { pct: 87, charging: true },
    storage: { totalMb: 12288, freeMb: 5011, sdcard: '/sdcard' },
    wifi: { connected: true, ssid: 'Cafe 5G', ip: '192.168.1.42' },
    setupDone: true, pluginsOn: true,
    plugins: [
      { id: 'uptime_card', name: 'Uptime Card', version: '1.0',
        author: 'you', enabled: true, permissions: ['ui'], error: '', cards: 1 },
      { id: 'battery_guard', name: 'Battery Guard', version: '1.1',
        author: 'you', enabled: true, permissions: ['ui', 'voice'], error: '', cards: 2 },
      { id: 'sys_mod', name: 'System Mod Demo', version: '0.9',
        author: 'you', enabled: false, permissions: ['ui', 'system'], error: '', cards: 1 }
    ]
  };

  const NETS = [
    { ssid: 'Cafe 5G', level: -41, secure: true },
    { ssid: 'HomeFiber', level: -58, secure: true },
    { ssid: 'GuestNet', level: -66, secure: false },
    { ssid: 'Neighbour_2.4', level: -74, secure: true }
  ];

  function reply(cmd, args) {
    switch (cmd) {
      case 'ping': return 'pong';
      case 'info': return TABLET;
      case 'wifi.list': return NETS;
      case 'wifi.state': return TABLET.wifi;
      case 'wifi.join': return Object.assign({ ssid: args.ssid }, TABLET.wifi);
      case 'setup.get': return {
        done: true, name: TABLET.name, haUrl: 'http://192.168.1.50:8123',
        haToken: 'eyJfake.token', city: 'Berlin', groq: 'gsk_test',
        theme: 'dune', h24: true, kiosk: true, keepScreen: true
      };
      case 'setup.set': case 'setup.finish': return null;
      case 'settings.get': return {
        kiosk: true, keepScreen: true, speak: true, bootLaunch: true,
        pluginsOn: true, theme: 'dune', h24: true
      };
      case 'settings.set': return null;
      case 'plugin.list': return TABLET.plugins;
      case 'plugin.set': {
        const p = TABLET.plugins.find(x => x.id === args.id);
        if (p) p.enabled = args.enabled;
        return true;
      }
      case 'plugin.remove': {
        const i = TABLET.plugins.findIndex(x => x.id === args.id);
        TABLET.plugins.splice(i, 1);
        return true;
      }
      case 'plugin.install': return {
        id: 'new_mod', name: 'New Mod', version: '1.0',
        enabled: true, permissions: ['ui'], error: ''
      };
      case 'sys.shell': return { code: 0, out: '[ro.astos.version]: [3.1.0]', err: '' };
      case 'sys.wakeup': case 'power.reboot': case 'power.shutdown':
        return { sent: true };
      case 'rom.apply': return { staged: true, rebootInMs: 3000 };
      default: throw new Error('mock: no handler for ' + cmd);
    }
  }

  window.astos = {
    version: () => '1.1.0',

    findTablet: async (kind) => {
      if (listeners['scan-progress']) listeners['scan-progress']('probing COM7');
      await new Promise(r => setTimeout(r, 700));
      return { ok: true, info: { name: TABLET.name, ver: TABLET.ver, paired: true } };
    },
    auth: async (code) => {
      if (code === '123456') return { ok: true, info: { name: TABLET.name, ver: TABLET.ver } };
      return { ok: false, error: 'wrong code — check the number on the tablet screen' };
    },

    rpc: async (cmd, args) => {
      try {
        return { ok: true, result: reply(cmd, args || {}) };
      } catch (e) {
        return { ok: false, error: e.message };
      }
    },

    transfer: async (path, target) => {
      for (let i = 0; i <= 20; i++) {
        if (listeners['transfer-progress'])
          listeners['transfer-progress']({
            pct: Math.round(i * 5),
            written: 17 * i * 1048576, total: 350 * 1048576,
            speed: 2345
          });
        await new Promise(r => setTimeout(r, 60));
      }
      return { ok: true, result: { name: 'AstOs-3.1.zip', sha256: 'ab'.repeat(32),
        shaOk: 'ok', path: '/sdcard/AstOs/updates/AstOs-3.1.zip' } };
    },

    pickFile: async () => '/downloads/AstOs-3.1-TB3-710F.zip',
    pickPlugin: async () => '/downloads/uptime-card.astpl',

    on: (channel, fn) => {
      listeners[channel] = fn;
      return () => { delete listeners[channel]; };
    }
  };
})();
