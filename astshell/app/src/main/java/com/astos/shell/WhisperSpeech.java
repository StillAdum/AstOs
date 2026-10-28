package com.astos.shell;

import android.content.Context;import android.media.*;import android.os.*;
import java.io.*;import java.net.*;import org.json.JSONObject;import org.vosk.*;
/** Explicitly opted-in Groq transcription with PCM capture, silence endpointing and optional offline live captions. */
final class WhisperSpeech {
 interface Result {void done(String text,String error);}
 interface Partial {void update(String text);}
 private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
 private volatile AudioRecord recorder;private volatile HttpURLConnection connection;private volatile boolean cancelled,finishing;private Result callback;private volatile boolean delivered;
 WhisperSpeech(Context c){context=c.getApplicationContext();}
 synchronized void start(Result result,Model model,Partial partial)throws Exception{
  callback=result;cancelled=false;finishing=false;delivered=false;
  int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IOException("Microphone does not support 16 kHz audio");
  AudioRecord audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,6400));
  if(audio.getState()!=AudioRecord.STATE_INITIALIZED){audio.release();throw new IOException("Microphone could not initialize");}recorder=audio;
  try{audio.startRecording();}catch(Exception e){recorder=null;audio.release();throw e;}
  new Thread(()->capture(audio,model,partial),"ast-speech-capture").start();
 }
 synchronized void finish(){finishing=true;}
 synchronized void cancel(){cancelled=true;finishing=true;if(connection!=null)connection.disconnect();AudioRecord a=recorder;if(a!=null)try{a.stop();}catch(Exception ignored){}}
 private void capture(AudioRecord audio,Model model,Partial partial){File file=null;Recognizer captions=null;
  try{if(model!=null)try{captions=new Recognizer(model,16000);}catch(Exception ignored){}
   ByteArrayOutputStream pcm=new ByteArrayOutputStream();SpeechGate gate=new SpeechGate();short[] buf=new short[640];String previous="";
   while(!cancelled&&!finishing){int n=audio.read(buf,0,buf.length);if(n<=0){if(cancelled||finishing)break;throw new IOException("Microphone read failed: "+n);}byte[] bytes=new byte[n*2];for(int i=0;i<n;i++){bytes[i*2]=(byte)buf[i];bytes[i*2+1]=(byte)(buf[i]>>8);}pcm.write(bytes);
    if(captions!=null){boolean finalPart=captions.acceptWaveForm(bytes,bytes.length);String text=new JSONObject(finalPart?captions.getResult():captions.getPartialResult()).optString(finalPart?"text":"partial","");if(!text.isEmpty()&&!text.equals(previous)){previous=text;main.post(()->{if(!cancelled)partial.update(text);});}}
    if(gate.accept(buf,n))break;
   }
   if(cancelled)return;if(!gate.heard()){deliver(null,"No clear speech heard; move closer or tap the keyboard");return;}
   file=File.createTempFile("ast-speech-",".wav",context.getCacheDir());try(OutputStream out=new FileOutputStream(file)){byte[] data=pcm.toByteArray();wav(out,data.length);out.write(data);}
  }catch(Exception e){if(!cancelled)deliver(null,"Speech capture: "+e.getMessage());}
  finally{recorder=null;try{audio.stop();}catch(Exception ignored){}audio.release();if(captions!=null)captions.close();}
  if(file!=null){if(cancelled)file.delete();else upload(file);}
 }
 private static void le(OutputStream o,int value,int bytes)throws IOException{for(int i=0;i<bytes;i++)o.write(value>>>(i*8)&255);}
 private static void wav(OutputStream o,int size)throws IOException{o.write("RIFF".getBytes("US-ASCII"));le(o,size+36,4);o.write("WAVEfmt ".getBytes("US-ASCII"));le(o,16,4);le(o,1,2);le(o,1,2);le(o,16000,4);le(o,32000,4);le(o,2,2);le(o,16,2);o.write("data".getBytes("US-ASCII"));le(o,size,4);}
 private void upload(File audio){HttpURLConnection c=null;try{
  String boundary="AstSpeech"+java.util.UUID.randomUUID();c=(HttpURLConnection)new URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection();connection=c;if(cancelled)return;
  c.setConnectTimeout(10000);c.setReadTimeout(45000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+AiProviders.speechKey(context));c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
  String model=context.getSharedPreferences("voice",0).getString("whisper_model","whisper-large-v3");if(!"whisper-large-v3-turbo".equals(model))model="whisper-large-v3";
  try(OutputStream out=c.getOutputStream()){
   out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"+model+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"temperature\"\r\n\r\n0\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes("UTF-8"));
   try(InputStream in=new FileInputStream(audio)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(cancelled)return;out.write(b,0,n);}}out.write(("\r\n--"+boundary+"--\r\n").getBytes("UTF-8"));
  }
  int status=c.getResponseCode();if(status!=200)throw new IOException(status==401?"Check your Groq speech key":status==429?"Speech rate limit; retry or select offline voice":"Groq speech HTTP "+status);
  StringBuilder body=new StringBuilder();try(Reader in=new InputStreamReader(c.getInputStream(),"UTF-8")){char[] buf=new char[2048];int n;while((n=in.read(buf))!=-1){body.append(buf,0,n);if(body.length()>1000000)throw new IOException("Speech response too large");}}
  String text=new JSONObject(body.toString()).optString("text","").trim();deliver(text,text.isEmpty()?"No speech heard":null);
 }catch(Exception e){deliver(null,"Speech recognition: "+e.getMessage());}finally{if(c!=null)c.disconnect();connection=null;audio.delete();}}
 private void deliver(String text,String error){main.post(()->{if(!cancelled&&!delivered&&callback!=null){delivered=true;callback.done(text,error);}});}
}
