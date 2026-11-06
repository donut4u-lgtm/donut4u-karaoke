
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class Stage4TvActivity extends Activity {

    private static final int BACKGROUND =
            Color.rgb(8, 20, 25);

    private static final int GREEN =
            Color.rgb(0, 220, 115);

    private static final int WHITE =
            Color.rgb(245, 250, 247);

    private static final int MUTED =
            Color.rgb(175, 193, 187);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        buildScreen();
    }

    private void buildScreen() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(
                dp(64),
                dp(36),
                dp(64),
                dp(36)
        );

        TextView brand = new TextView(this);
        brand.setText("DONUT4U KARAOKE");
        brand.setTextColor(GREEN);
        brand.setTextSize(20);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);

        root.addView(
                brand,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView title = new TextView(this);
        title.setText("KARAOKE ON YOUR TV");
        title.setTextColor(WHITE);
        title.setTextSize(38);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(24), 0, dp(12));

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView description = new TextView(this);
        description.setText(
                "A dedicated big-screen karaoke experience "
                        + "is being prepared."
        );
        description.setTextColor(MUTED);
        description.setTextSize(21);
        description.setGravity(Gravity.CENTER);

        root.addView(
                description,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView status = new TextView(this);
        status.setText(
                "STAGE 4A — TV SCREEN PREVIEW\n\n"
                        + "Your TV will become the main karaoke "
                        + "playback screen.\n\n"
                        + "The TV songbook, lyrics playback, "
                        + "and phone remote will be added "
                        + "in later milestones."
        );
        status.setTextColor(WHITE);
        status.setTextSize(19);
        status.setGravity(Gravity.CENTER);
        status.setPadding(
                dp(28),
                dp(28),
                dp(28),
                dp(28)
        );

        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.rgb(20, 39, 42));
        panel.setCornerRadius(dp(18));
        panel.setStroke(dp(2), GREEN);

        status.setBackground(panel);

        LinearLayout.LayoutParams panelParams =
                new LinearLayout.LayoutParams(
                        dp(720),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        panelParams.topMargin = dp(32);
        panelParams.bottomMargin = dp(30);

        root.addView(status, panelParams);

        Button closeButton = new Button(this);
        closeButton.setText("CLOSE TV PREVIEW");
        closeButton.setTextColor(Color.BLACK);
        closeButton.setTextSize(17);
        closeButton.setTypeface(null, Typeface.BOLD);
        closeButton.setAllCaps(false);
        closeButton.setMinHeight(dp(60));
        closeButton.setPadding(
                dp(28),
                dp(8),
                dp(28),
                dp(8)
        );

        GradientDrawable buttonBackground =
                new GradientDrawable();
        buttonBackground.setColor(GREEN);
        buttonBackground.setCornerRadius(dp(12));

        closeButton.setBackground(buttonBackground);
        closeButton.setFocusable(true);
        closeButton.setFocusableInTouchMode(true);
        closeButton.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        dp(300),
                        dp(64)
                );

        root.addView(closeButton, buttonParams);

        setContentView(root);

        closeButton.requestFocus();
    }

    private int dp(int value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }
}
