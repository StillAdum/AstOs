/**
 * protocol.js — the AstLink frame codec, mirrored from the tablet's
 * astlink/src/main/java/com/astos/link/LinkJson.java + LinkServer.java.
 *
 * Frames are single-line JSON objects:
 *   {v:1, id:"<n>", cmd:"<name>", args:{...}}
 * and every reply is:
 *   {v:1, id:"<n>", ok:true,  result:<any>}
 *   {v:1, id:"<n>", ok:false, error:"why"}
 *
 * Kept dependency-free so `node test/protocol.test.js` can prove both
 * sides speak the same bytes.
 */
'use strict';

let seq = 0;

function makeFrame(cmd, args) {
  return JSON.stringify({ v: 1, id: 'c' + (++seq), cmd, args: args || {} });
}

/** Parse one newline-terminated frame off a socket. Call feed() with
 *  chunks; it returns {line} objects as frames complete. */
function makeParser() {
  let buf = '';
  return {
    feed(chunk) {
      const out = [];
      if (typeof chunk === 'string') buf += chunk;
      else buf += chunk.toString('utf8');
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (line) out.push(line);
      }
      return out;
    }
  };
}

function parseFrame(line) {
  const f = JSON.parse(line);
  if (typeof f !== 'object' || f === null || !('id' in f)) {
    throw new Error('bad frame: ' + line.slice(0, 80));
  }
  return f;
}

/** base64 chunking for file transfers — 8192 raw bytes per frame,
 *  matching the tablet's expectation (seq * 8192 == written). */
function chunksFor(buf, size) {
  const frames = [];
  const step = size || 8192;
  for (let off = 0; off < buf.length; off += step) {
    frames.push({
      seq: Math.floor(off / step),
      b64: buf.subarray(off, Math.min(off + step, buf.length)).toString('base64')
    });
  }
  return frames;
}

module.exports = { makeFrame, makeParser, parseFrame, chunksFor };
