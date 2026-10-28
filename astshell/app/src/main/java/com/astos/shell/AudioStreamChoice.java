package com.astos.shell;

/** NewPipe reports average bitrate in kbps, not bits/second. */
public final class AudioStreamChoice {
    private AudioStreamChoice(){}
    public static int score(boolean progressive,boolean hls,boolean m4a,int bitrate){
        if(!progressive&&!(hls&&m4a))return Integer.MIN_VALUE;
        return score(true,m4a,bitrate)+(progressive?100:0);
    }
    public static int score(boolean progressive,boolean m4a,int bitrate){
        if(!progressive)return Integer.MIN_VALUE;
        int kbps=bitrate>10000?bitrate/1000:bitrate;
        return (m4a?10000:0)-Math.min(5000,Math.abs(128-Math.max(0,kbps)));
    }
}
