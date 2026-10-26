/** verify-frames.js — parse the Java-written golden frames with the PC codec. */
'use strict';
const fs = require('fs');
const path = require('path');
const { parseFrame } = require(path.join(__dirname, '..', 'src', 'protocol.js'));

const lines = fs.readFileSync(process.argv[2], 'utf8').split('\n').filter(l => l.trim());
if (lines.length !== 3) throw new Error('expected 3 frames, got ' + lines.length);

// frame 1: hello request
const hello = parseFrame(lines[0]);
if (hello.id !== 'c1' || hello.cmd !== 'hello' || hello.v !== 1)
  throw new Error('hello frame mangled: ' + lines[0]);
if (hello.args.token !== 'aabbcc' || hello.args.proto !== 1)
  throw new Error('hello args mangled');

// frame 2: rich result — arrays, numbers, escapes
const reply = parseFrame(lines[1]);
if (!reply.ok || reply.id !== 'c2') throw new Error('reply frame mangled');
if (reply.result.name !== 'AstTab') throw new Error('string lost');
if (reply.result.ver !== '3.1') throw new Error('version lost');
if (!Array.isArray(reply.result.entities) || reply.result.entities.length !== 3)
  throw new Error('array lost');
if (reply.result.entities[2] !== 42) throw new Error('int lost: ' + typeof reply.result.entities[2]);
if (reply.result.pct !== 87) throw new Error('pct lost');
if (reply.result.note !== 'quote" back\\slash \n newline é')
  throw new Error('escapes lost: ' + JSON.stringify(reply.result.note));

// frame 3: error shape
const err = parseFrame(lines[2]);
if (err.ok !== false || err.error !== 'need-code') throw new Error('error frame mangled');

console.log('VERIFIED 3');
