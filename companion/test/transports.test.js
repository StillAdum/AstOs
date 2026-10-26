'use strict';
const assert = require('node:assert/strict');
const EventEmitter = require('node:events');
const Module = require('node:module');
const originalLoad = Module._load;
let opened = false;
class FakePort extends EventEmitter {
  static async list() { return [{path:'COM7',pnpId:'BTHENUM\\test'},{path:'COM9',manufacturer:'Other hardware'}]; }
  constructor(options) {
    super(); this.path = options.path;
    queueMicrotask(() => { opened = true; this.emit('open'); });
  }
  write(frame, callback) {
    assert.ok(opened, 'Must not write before serial open');
    callback?.(); if (this.path === 'silent') return;
    const req = JSON.parse(frame);
    queueMicrotask(() => this.emit('data', Buffer.from(JSON.stringify(req.cmd === 'hello'
      ? {v:1,id:req.id,ok:false,error:'need-code'}
      : {v:1,id:req.id,ok:true,result:'pong'}) + '\n')));
  }
  close() { this.emit('close'); }
}
Module._load = function(name, parent, isMain) { return name === 'serialport' ? {SerialPort:FakePort} : originalLoad.apply(this, arguments); };
const T = require('../src/transports');
(async () => {
  try {
    assert.deepEqual((await T.listBluetoothPorts()).map(p=>p.path), ['COM7']);
    const session = await T.probeCom('COM7', 100);
    assert.equal(await session.call(null,'ping',{},100), 'pong');
    let dead = 0;session.onDead=()=>dead++;
    session.close();session.ended(); assert.equal(dead,1);
    await assert.rejects(session.call(null,'ping',{}), /closed/);
    await assert.rejects(T.probeCom('silent',5), /timeout/);
    console.log('TRANSPORT PASS: PNP discovery, open ordering, need-code probe, requests, disconnect, timeout');
  } finally { Module._load = originalLoad; }
})().catch(e=>{console.error(e);process.exit(1)});
