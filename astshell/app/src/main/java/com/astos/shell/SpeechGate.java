package com.astos.shell;
/** PCM16/16 kHz endpoint detector. Minimum voiced duration rejects clicks; bounded noise adaptation. */
public final class SpeechGate {
 private double noise=160;private int elapsed,voiced,lastVoice;private boolean heard;
 public boolean accept(short[] pcm,int count){double energy=0;for(int i=0;i<count;i++)energy+=(double)pcm[i]*pcm[i];double rms=Math.sqrt(energy/Math.max(1,count));int ms=count*1000/16000;elapsed+=ms;
  boolean voice=rms>Math.max(500,noise*2.8);if(!heard&&!voice)noise=Math.min(1800,noise*.96+rms*.04);
  if(voice){voiced+=ms;lastVoice=elapsed;if(voiced>=180)heard=true;}
  return elapsed>=30000 || (heard&&elapsed-lastVoice>=1100) || (!heard&&elapsed>=7000);
 }
 public boolean heard(){return heard;}
}
