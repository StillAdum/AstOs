package com.astos.shell;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/**
 * The pixel power menu — native, readable on the dark scrim
 * (replaces the white AOSP global-actions dialog).
 */
public class PowerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_power);
        Ui.apply(this, findViewById(android.R.id.content));

        View restart = findViewById(R.id.optRestart);
        View off = findViewById(R.id.optPower);
        View cancel = findViewById(R.id.optCancel);

        Ui.press(restart, 3);
        Ui.press(off, 3);
        Ui.press(cancel, 3);

        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SysPower.reboot(PowerActivity.this);
            }
        });
        off.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SysPower.shutdown(PowerActivity.this);
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    @Override
    public void onBackPressed() { finish(); }
}
