/**
 * preload.js — the small bridge the renderer gets as window.astos.
 * Everything else stays in the main process.
 */
'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('astos', {
  version: () => process.env.npm_package_version || 'dev',

  findTablet: (kind, port) => ipcRenderer.invoke('find-tablet', kind, port),
  resume: () => ipcRenderer.invoke('resume-pairing'),
  disconnect: () => ipcRenderer.invoke('disconnect-tablet'),
  auth: (code) => ipcRenderer.invoke('auth', code),
  spotifyInfo: () => ipcRenderer.invoke('spotify-info'),

  rpc: (cmd, args, timeoutMs) => ipcRenderer.invoke('rpc', cmd, args, timeoutMs),

  transfer: (path, target) => ipcRenderer.invoke('transfer', path, target),
  pickFile: (filters) => ipcRenderer.invoke('pick-file', filters),
  pickPlugin: () => ipcRenderer.invoke('pick-plugin'),

  on: (channel, fn) => {
    const allowed = [
      'scan-progress', 'paired', 'link-dead',
      'transfer-progress', 'transfer-done'
    ];
    if (!allowed.includes(channel)) return () => {};
    const listener = (_e, payload) => fn(payload);
    ipcRenderer.on(channel, listener);
    return () => ipcRenderer.removeListener(channel, listener);
  }
});
