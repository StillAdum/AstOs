/**
 * protocol.test.js — parser edge cases for the PC-side codec:
 * multiple frames per chunk, frames split across chunks, junk tolerance.
 */
'use strict';
const path = require('path');
const assert = require('assert');
const { makeFrame, makeParser, parseFrame, chunksFor } =
  require(path.join(__dirname, '..', 'src', 'protocol.js'));

// 1. ids increment
const a = makeFrame('ping');
const b = makeFrame('ping');
assert.notStrictEqual(JSON.parse(a).id, JSON.parse(b).id, 'ids must be unique');

// 2. several frames in one chunk
let p = makeParser();
const got = p.feed(makeFrame('ping') + '\n' + makeFrame('info') + '\n');
assert.strictEqual(got.length, 2, 'two frames from one chunk');
assert.strictEqual(parseFrame(got[0]).cmd, 'ping');
assert.strictEqual(parseFrame(got[1]).cmd, 'info');

// 3. a frame split byte-by-byte across many chunks
p = makeParser();
const frame = makeFrame('setup.set', { city: 'Berlin' });
let out = [];
for (const ch of frame) out = out.concat(p.feed(ch));
out = out.concat(p.feed('\n'));
assert.strictEqual(out.length, 1, 'reassembled one frame');
assert.strictEqual(parseFrame(out[0]).args.city, 'Berlin');

// 4. empty lines + junk lines are skipped, good frames survive
p = makeParser();
const r = p.feed('\n\n' + makeFrame('wifi.state') + '\n\n   \n');
assert.strictEqual(r.length, 1);

// 5. chunking math matches the tablet (seq * 8192 == offset)
const buf = Buffer.alloc(8192 * 3 + 1234, 7);
const frames = chunksFor(buf);
assert.strictEqual(frames.length, 4);
assert.strictEqual(frames[0].seq, 0);
assert.strictEqual(frames[3].seq, 3);
frames.forEach(f => {
  const raw = Buffer.from(f.b64, 'base64');
  assert.strictEqual(raw.length <= 8192, true);
  const offset = f.seq * 8192;
  assert.ok(raw.equals(buf.subarray(offset, Math.min(offset + 8192, buf.length))),
    'chunk ' + f.seq + ' must match its byte window');
});

// 6. CRLF tolerance (windows drivers sometimes munge line endings)
p = makeParser();
const crlf = p.feed(makeFrame('ping') + '\r\n');
assert.strictEqual(crlf.length, 1, 'CRLF frame parsed');

console.log('protocol.test.js OK — parser + chunker verified');
