package com.astos.link;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;
/** Per-connection challenge-derived PSK channel. No pairing token travels in plaintext on LAN. */
public final class LinkCrypto {
 private final byte[] key; private long sent=0,received=0;
 public LinkCrypto(String secret,String challenge,String shared) throws Exception {
  key=java.util.Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest((secret+"\n"+challenge+"\n"+shared).getBytes("UTF-8")),16);
 }
 private static byte[] b64(String s){return android.util.Base64.decode(s,android.util.Base64.NO_WRAP);}
 private static String b64(byte[] b){return android.util.Base64.encodeToString(b,android.util.Base64.NO_WRAP);}
 public synchronized String seal(String frame) throws Exception {
  byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
  c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
  String data=LinkJson.write(new LinkJson.Obj().put("seq",++sent).put("frame",frame));
  return LinkJson.write(new LinkJson.Obj().put("nonce",b64(nonce)).put("data",b64(c.doFinal(data.getBytes("UTF-8")))));
 }
 public synchronized String open(String envelope) throws Exception {
  LinkJson.Obj e=LinkJson.parseObj(envelope);byte[] nonce=b64(e.str("nonce",""));if(nonce.length!=12)throw new Exception("bad nonce");
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
  LinkJson.Obj data=LinkJson.parseObj(new String(c.doFinal(b64(e.str("data",""))),"UTF-8"));
  long seq=data.lng("seq",0);if(seq<=received)throw new Exception("replayed frame");received=seq;return data.str("frame","");
 }
}
