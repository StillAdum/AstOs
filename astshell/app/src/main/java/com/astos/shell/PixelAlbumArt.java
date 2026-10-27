package com.astos.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 8x8 mirrored pixel album art, deterministic per track name —
 * the same idea as the mock's drawArt() canvas.
 */
public class PixelAlbumArt extends View {

    private android.graphics.Bitmap cover;private String requested="";
    public void showBitmap(android.graphics.Bitmap image,String name){requested="bitmap:"+name;cover=image;seed(name);}
    public void load(String url,String name){
      if(url==null)url="";String identity=url+"|"+name;if(identity.equals(requested))return;requested=identity;cover=null;seed(name);
      if(!url.startsWith("https://"))return;final String source=url,tag=identity;
      new Thread(()->{android.graphics.Bitmap image=null;java.net.HttpURLConnection c=null;
       try{c=(java.net.HttpURLConnection)new java.net.URL(source).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);if(c.getResponseCode()!=200)return;
        java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=c.getInputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){data.write(buf,0,n);if(data.size()>2097152)return;}}
        byte[] bytes=data.toByteArray();android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)return;
        bounds.inJustDecodeBounds=false;bounds.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/512);image=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
       }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
       final android.graphics.Bitmap loaded=image;post(()->{if(tag.equals(requested)){cover=loaded;invalidate();}});
      },"cover-art").start();
    }
    private static final int N = 8;
    private final boolean[][] mCells = new boolean[N][N];
    private final int[] mPalette = new int[3];
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random mRnd = new Random();

    public PixelAlbumArt(Context c, AttributeSet a) {
        super(c, a);
        seed("astos");
    }

    public void seed(String name) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) h = 31 * h + name.charAt(i);
        mRnd.setSeed(h);
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N / 2; x++)
                mCells[y][x] = mRnd.nextInt(100) < 46;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if(cover!=null){mP.setFilterBitmap(true);c.drawBitmap(cover,null,new android.graphics.Rect(0,0,getWidth(),getHeight()),mP);return;}
        mPalette[0] = Ui.cur.off;
        mPalette[1] = Ui.cur.accent;
        mPalette[2] = Ui.cur.ink;
        long h = 1;
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if (mCells[y][x]) h = h * 31 + y * 7 + x;
        mRnd.setSeed(h ^ 0x5EED);
        int w = getWidth(), ht = getHeight();
        if (w <= 0 || ht <= 0) return;
        float cw = w / (float) N, ch = ht / (float) N;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N / 2; x++) {
                if (!mCells[y][x]) continue;
                int col = mPalette[mRnd.nextInt(100) < 60 ? 0 : (mRnd.nextInt(100) < 60 ? 1 : 2)];
                mP.setColor(col);
                c.drawRect(x * cw, y * ch, x * cw + cw, y * ch + ch, mP);
                int mx = N - 1 - x;
                c.drawRect(mx * cw, y * ch, mx * cw + cw, y * ch + ch, mP);
            }
        }
    }
}
