import com.astos.shell.AudioStreamChoice;
public final class AudioStreamChoiceTest {
    public static void main(String[] args){
        int[] bitrates={0,64,96,128,160,192,256,128000};
        for(int n:bitrates){if(AudioStreamChoice.score(true,true,n)<=Integer.MIN_VALUE)throw new AssertionError("Valid stream rejected: "+n);}
        if(AudioStreamChoice.score(false,true,128)!=Integer.MIN_VALUE)throw new AssertionError("Non-progressive accepted");
        if(AudioStreamChoice.score(true,true,128)!=AudioStreamChoice.score(true,true,128000))throw new AssertionError("Units differ");
        if(AudioStreamChoice.score(true,true,128)<=AudioStreamChoice.score(true,false,128))throw new AssertionError("AAC preference lost");
        if(AudioStreamChoice.score(false,true,true,128)==Integer.MIN_VALUE)throw new AssertionError("AAC HLS fallback rejected");
        if(AudioStreamChoice.score(false,false,true,128)!=Integer.MIN_VALUE)throw new AssertionError("DASH accepted");
        // Reproduce the original -1 baseline / bits-per-second bug.
        int best=Integer.MIN_VALUE,picked=-1;for(int i=0;i<bitrates.length;i++){int rank=AudioStreamChoice.score(true,true,bitrates[i]);if(rank>best){picked=i;best=rank;}}
        if(picked<0||bitrates[picked]!=128)throw new AssertionError("No valid best stream");
        System.out.println("AUDIO CHOICE PASS: 64–256 kbps, bps normalization, AAC preference, progressive-only, regression for all-streams-rejected bug");
    }
}
