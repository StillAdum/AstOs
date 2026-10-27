'use strict';
const assert=require('node:assert/strict'),net=require('node:net'),crypto=require('node:crypto');
const {SecureLink}=require('../src/secure-link'),T=require('../src/transports');
(async()=>{
 const a=new SecureLink('token','challenge','shared'),b=new SecureLink('token','challenge','shared');
 const envelope=a.seal('Unicode ♫');assert.equal(b.open(envelope),'Unicode ♫');assert.throws(()=>b.open(envelope),/Replayed/);assert.throws(()=>new SecureLink('wrong','challenge','shared').open(envelope));
 let message;
 const server=net.createServer(socket=>{const dh=crypto.createECDH('prime256v1');dh.generateKeys();const challenge=crypto.randomBytes(24).toString('hex');const prefix=Buffer.from('3059301306072a8648ce3d020106082a8648ce3d030107034200','hex');
 socket.write(JSON.stringify({secure:true,challenge,publicKey:Buffer.concat([prefix,dh.getPublicKey()]).toString('base64')})+'\n');let channel,buffer='';
 socket.on('data',d=>{buffer+=d;let i;while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(!channel){const intro=JSON.parse(line);const shared=dh.computeSecret(Buffer.from(intro.publicKey,'base64')).toString('hex');channel=new SecureLink('test-token',challenge,shared);continue;}
 const req=JSON.parse(channel.open(line));message=req;socket.write(channel.seal(JSON.stringify({v:1,id:req.id,ok:true,result:{name:'Test tablet',setupDone:true}}))+'\n');}});
 });await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try{const s=await T.connectWifi('127.0.0.1','test-token',false,server.address().port);const result=await s.call(null,'info',{},2000);assert.equal(result.setupDone,true);assert.equal(message.cmd,'info');s.close();await assert.rejects(T.connectWifi('invalid/host','token'),/address/);}finally{await new Promise(r=>server.close(r));}
 console.log('SECURE LAN PASS: real socket ECDH/AES-GCM, Unicode, command roundtrip, replay/tamper/wrong-key rejection, host validation');
})().catch(e=>{console.error(e);process.exit(1)});
