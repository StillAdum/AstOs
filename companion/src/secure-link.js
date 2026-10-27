'use strict';
const crypto=require('node:crypto');
class SecureLink {
 constructor(secret,challenge,shared){this.key=crypto.createHash('sha256').update(secret+'\n'+challenge+'\n'+shared).digest().subarray(0,16);this.sent=0;this.received=0;}
 seal(frame){const nonce=crypto.randomBytes(12),c=crypto.createCipheriv('aes-128-gcm',this.key,nonce);const data=Buffer.concat([c.update(JSON.stringify({seq:++this.sent,frame}),'utf8'),c.final(),c.getAuthTag()]);return JSON.stringify({nonce:nonce.toString('base64'),data:data.toString('base64')});}
 open(line){const e=JSON.parse(line),raw=Buffer.from(e.data,'base64'),nonce=Buffer.from(e.nonce,'base64');if(nonce.length!==12||raw.length<16)throw Error('Bad encrypted frame');const c=crypto.createDecipheriv('aes-128-gcm',this.key,nonce);c.setAuthTag(raw.subarray(-16));const data=JSON.parse(Buffer.concat([c.update(raw.subarray(0,-16)),c.final()]).toString());if(!Number.isSafeInteger(data.seq)||data.seq<=this.received)throw Error('Replayed frame');this.received=data.seq;return data.frame;}
}
module.exports={SecureLink};
