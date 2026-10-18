
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        startActivity(
                new Intent(
                        MainActivity.this,
                        OnlineSongActivity.class
                )
        );

        finish();
    }
}
