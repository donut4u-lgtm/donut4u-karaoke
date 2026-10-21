package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends Activity {

    private static final int BG = Color.rgb(16, 19, 21);
    private static final int GREEN = Color.rgb(0, 210, 105);
    private static final int MUTED = Color.rgb(185, 194, 189);

    private static final String APK_URL =
            "https://github.com/donut4u-lgtm/donut4u-karaoke/releases/download/v1.0.0/app-release.apk";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();

        // PAGE 1: YouTube song search after successful sign-in.
        if (user != null && user.isEmailVerified()) {
            openSongSearch();
            return;
        }

        showWelcomeScreen();
    }

    private void openSongSearch() {
        Intent intent = new Intent(
                MainActivity.this,
                OnlineSongActivity.class
        );

        intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );

        startActivity(intent);
        finish();
    }

    private void showWelcomeScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("Donut4U Karaoke");
        title.setTextColor(GREEN);
        title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidthWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Your songs. Your karaoke.");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(28));
        root.addView(subtitle, fullWidthWrap());

        Button signInButton = makeButton("SIGN IN");
        LinearLayout.LayoutParams signInParams =
                fullWidth(dp(54));
        signInParams.bottomMargin = dp(12);
        root.addView(signInButton, signInParams);

        signInButton.setOnClickListener(v ->
                openAuthentication(false));

        Button createButton = makeButton("CREATE ACCOUNT");
        LinearLayout.LayoutParams createParams =
                fullWidth(dp(54));
        createParams.bottomMargin = dp(12);
        root.addView(createButton, createParams);

        createButton.setOnClickListener(v ->
                openAuthentication(true));

        Button shareButton = makeButton("SHARE APP");
        root.addView(shareButton, fullWidth(dp(54)));

        shareButton.setOnClickListener(v -> {
            Intent shareIntent =
                    new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(
                    Intent.EXTRA_SUBJECT,
                    "Donut4U Karaoke"
            );
            shareIntent.putExtra(
                    Intent.EXTRA_TEXT,
                    "Try Donut4U Karaoke! Download the app here:\n"
                            + APK_URL
            );

            startActivity(Intent.createChooser(
                    shareIntent,
                    "Share Donut4U Karaoke"
            ));
        });

        setContentView(root);
    }

    private void openAuthentication(boolean createMode) {
        Intent intent = new Intent(
                MainActivity.this,
                AuthActivity.class
        );

        intent.putExtra("create_mode", createMode);
        startActivity(intent);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));

        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(GREEN);
        background.setCornerRadius(dp(10));
        button.setBackground(background);

        return button;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }
}
