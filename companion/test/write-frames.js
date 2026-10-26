/** write-frames.js — the PC codec writes frames for the Java side to verify. */
'use strict';
const fs = require('fs');
const path = require('path');
const { makeFrame } = require(path.join(__dirname, '..', 'src', 'protocol.js'));

const f1 = makeFrame('wifi.join', { ssid: 'Café "5G"', pass: 'p@ss w0rd \\', seq: 7 });
const f2 = JSON.stringify({
  v: 1, id: 'j2', cmd: 'file.chunk',
  args: { id: 'x1', seq: 3, b64: Buffer.from('chunk-payload-raw').toString('base64') }
});
const f3 = makeFrame('rom.apply', { name: 'AstOs-3.1.zip', sha256: 'ab'.repeat(32), delayMs: 3000 });

fs.writeFileSync(process.argv[2], [f1, f2, f3].join('\n') + '\n', 'utf8');
console.log('WROTEOK');
