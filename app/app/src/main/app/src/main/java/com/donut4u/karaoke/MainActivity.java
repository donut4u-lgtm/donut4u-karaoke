
package com.donut4u.karaoke;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;

public class MainActivity extends Activity {

    private static final String TAG = "Donut4uStartup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.setPadding(24, 24, 24, 24);
            layout.setBackgroundColor(Color.WHITE);

            TextView title = new TextView(this);
            title.setText("donut4u karaoke");
            title.setTextSize(28);
            title.setTextColor(Color.rgb(25, 125, 75));
            title.setGravity(Gravity.CENTER);

            TextView message = new TextView(this);
            message.setText(
                "The app started successfully!\n\n"
                + "Startup test passed.\n"
                + "Next, we can restore the karaoke screen."
            );
            message.setTextSize(18);
            message.setTextColor(Color.BLACK);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, 24, 0, 0);

            layout.addView(title);
            layout.addView(message);

            setContentView(layout);

            Log.i(TAG, "Startup test passed.");

        } catch (Exception e) {
            Log.e(TAG, "Startup test failed", e);
            throw new RuntimeException(
                "donut4u karaoke startup failed", e
            );
        }
    }
}
