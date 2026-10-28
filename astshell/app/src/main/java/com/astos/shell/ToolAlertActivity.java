package com.astos.shell;
import android.app.*;
import android.os.*;
import android.content.*;
public final class ToolAlertActivity extends Activity {
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setWindowAnimations(0);getWindow().addFlags(0x80000|0x200000|0x80);
        new com.astos.link.PixelUi.Builder(this).setTitle(getIntent().getStringExtra("kind").toUpperCase(java.util.Locale.US)).setMessage(getIntent().getStringExtra("label")).setCancelable(false).setPositiveButton("DISMISS",(d,w)->{stopService(new Intent(this,ToolAlertService.class));finish();}).show();
    }
}
