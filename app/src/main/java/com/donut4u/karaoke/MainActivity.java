
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

        if (user != null && user.isEmailVerified()) {
            openSongSearch();
            return;
        }

        if (user != null) {
            FirebaseAuth.getInstance().signOut();
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
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("Donut4U Karaoke");
        title.setTextColor(GREEN);
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidthWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Your songs. Your karaoke.");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(24));
        root.addView(subtitle, fullWidthWrap());

        Button createButton = makeButton("Create account");
        addButton(root, createButton, 10);
        createButton.setOnClickListener(v ->
                openAuthentication(true));

        Button signInButton = makeButton("Sign in");
        addButton(root, signInButton, 10);
        signInButton.setOnClickListener(v ->
                openAuthentication(false));

        Button shareButton = makeButton("Share app");
        addButton(root, shareButton, 18);
        shareButton.setOnClickListener(v -> shareApp());

        TextView advertisement = new TextView(this);
        advertisement.setText("Advertisement");
        advertisement.setTextColor(MUTED);
        advertisement.setTextSize(13);
        advertisement.setGravity(Gravity.CENTER);
        advertisement.setPadding(0, dp(14), 0, dp(14));
        root.addView(advertisement, fullWidth(dp(54)));

        Button donateButton = makeButton("Donate");
        addButton(root, donateButton, 10);
        donateButton.setOnClickListener(v ->
                showDonationMessage());

        Button questionButton = makeButton("Question");
        addButton(root, questionButton, 10);
        questionButton.setOnClickListener(v ->
                showQuestionDialog());

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private void addButton(
            LinearLayout root,
            Button button,
            int bottomMargin
    ) {
        LinearLayout.LayoutParams params =
                fullWidth(dp(52));
        params.bottomMargin = dp(bottomMargin);
        root.addView(button, params);
    }

    private void shareApp() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
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
    }

    private void showDonationMessage() {
        new AlertDialog.Builder(this)
                .setTitle("Support Donut4U Karaoke")
                .setMessage(
                        "Sign in to access the song screen "
                                + "and its available donation options. "
                                + "Your support helps keep the app running "
                                + "and growing."
                )
                .setPositiveButton("Sign in", (dialog, which) ->
                        openAuthentication(false))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showQuestionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Question and Answer")
                .setMessage(
                        "Welcome to Donut4U Karaoke!\n\n"
                                + "Create an account or sign in to "
                                + "continue to the karaoke song screen "
                                + "and its available support options."
                )
                .setPositiveButton("OK", null)
                .show();
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
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));

        GradientDrawable background = new GradientDrawable();
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
