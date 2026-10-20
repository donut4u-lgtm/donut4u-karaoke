
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final int BG = Color.rgb(16, 19, 21);
    private static final int GREEN = Color.rgb(0, 210, 105);
    private static final int PANEL = Color.rgb(35, 39, 42);
    private static final int MUTED = Color.rgb(185, 194, 189);

    private static final String TEST_BANNER_ID =
            "ca-app-pub-5625332026157890/7288125709";

    private static final String VERSION_URL =
            "https://github.com/donut4u-lgtm/donut4u-karaoke/releases/download/v1.0.0/version.txt";

    private static final String APK_URL =
            "https://github.com/donut4u-lgtm/donut4u-karaoke/releases/download/v1.0.0/app-release.apk";

    private AdView adView;
    private boolean updateDialogShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        showHomeScreen();

        MobileAds.initialize(this, initializationStatus -> {
            runOnUiThread(() -> {
                if (adView != null && !isFinishing()) {
                    adView.loadAd(
                            new AdRequest.Builder().build()
                    );
                }
            });
        });

        checkForUpdates();
    }

    private void checkForUpdates() {
        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(
                        VERSION_URL + "?t=" + System.currentTimeMillis()
                );

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setUseCaches(false);
                connection.setRequestProperty(
                        "Cache-Control",
                        "no-cache"
                );

                int responseCode = connection.getResponseCode();

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return;
                }

                StringBuilder response = new StringBuilder();

                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input)
                     )) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line.trim());
                    }
                }

                String versionText = response.toString().trim();

                if (versionText.isEmpty()) {
                    return;
                }

                int latestVersionCode =
                        Integer.parseInt(versionText);

                int installedVersionCode =
                        BuildConfig.VERSION_CODE;

                if (latestVersionCode > installedVersionCode) {
                    runOnUiThread(() -> showUpdateDialog(
                            latestVersionCode
                    ));
                }

            } catch (Exception ignored) {
                // App continues normally if update checking fails.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });

        thread.start();
    }

    private void showUpdateDialog(int latestVersionCode) {
        if (isFinishing()
                || isDestroyed()
                || updateDialogShown) {
            return;
        }

        updateDialogShown = true;

        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage(
                        "A newer version of Donut4U Karaoke is available.\n\n"
                                + "Installed version code: "
                                + BuildConfig.VERSION_CODE
                                + "\nLatest version code: "
                                + latestVersionCode
                                + "\n\nDownload the latest APK now?"
                )
                .setPositiveButton("DOWNLOAD UPDATE", (dialog, which) -> {
                    Intent intent = new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(APK_URL)
                    );

                    startActivity(intent);
                })
                .setNegativeButton("LATER", (dialog, which) -> {
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void showHomeScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));

        scroll.addView(root);

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
        subtitle.setPadding(0, dp(8), 0, dp(28));

        root.addView(subtitle, fullWidthWrap());

        Button createButton = makeButton("CREATE ACCOUNT");

        LinearLayout.LayoutParams createParams =
                fullWidth(dp(54));
        createParams.bottomMargin = dp(12);
        root.addView(createButton, createParams);

        createButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    MainActivity.this,
                    AuthActivity.class
            );
            intent.putExtra("create_mode", true);
            startActivity(intent);
        });

        Button signInButton = makeButton("SIGN IN");

        LinearLayout.LayoutParams signInParams =
                fullWidth(dp(54));
        signInParams.bottomMargin = dp(24);
        root.addView(signInButton, signInParams);

        signInButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    MainActivity.this,
                    AuthActivity.class
            );
            intent.putExtra("create_mode", false);
            startActivity(intent);
        });

        // NEW: KARAOKE STUDIO BUTTON
        Button karaokeStudioButton = makeButton("KARAOKE STUDIO");

        LinearLayout.LayoutParams studioParams =
                fullWidth(dp(54));
        studioParams.bottomMargin = dp(12);
        root.addView(karaokeStudioButton, studioParams);

        karaokeStudioButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    MainActivity.this,
                    KaraokeStudioActivity.class
            );
            startActivity(intent);
        });

        Button shareButton = makeButton("SHARE APP");

        LinearLayout.LayoutParams shareParams =
                fullWidth(dp(54));
        shareParams.bottomMargin = dp(24);
        root.addView(shareButton, shareParams);

        shareButton.setOnClickListener(v -> {
            String shareMessage =
                    "Try Donut4U Karaoke! Download the Android app here:\n"
                    + APK_URL;

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(
                    Intent.EXTRA_SUBJECT,
                    "Donut4U Karaoke"
            );
            shareIntent.putExtra(
                    Intent.EXTRA_TEXT,
                    shareMessage
            );

            startActivity(
                    Intent.createChooser(
                            shareIntent,
                            "Share Donut4U Karaoke"
                    )
            );
        });

        TextView adLabel = new TextView(this);
        adLabel.setText("ADVERTISEMENT");
        adLabel.setTextColor(MUTED);
        adLabel.setTextSize(11);
        adLabel.setGravity(Gravity.CENTER);
        adLabel.setPadding(0, dp(8), 0, dp(8));

        root.addView(adLabel, fullWidthWrap());

        LinearLayout adContainer = new LinearLayout(this);
        adContainer.setOrientation(LinearLayout.VERTICAL);
        adContainer.setGravity(Gravity.CENTER);
        adContainer.setBackground(
                roundDrawable(PANEL, dp(10))
        );
        adContainer.setPadding(dp(4), dp(12), dp(4), dp(12));

        LinearLayout.LayoutParams adContainerParams =
                fullWidthWrap();
        adContainerParams.bottomMargin = dp(24);
        root.addView(adContainer, adContainerParams);

        adView = new AdView(this);
        adView.setAdUnitId(TEST_BANNER_ID);
        adView.setAdSize(AdSize.BANNER);

        adContainer.addView(
                adView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setWeightSum(2);

        Button donateButton = makeButton("DONATE");
        Button questionButton = makeButton("QUESTION");

        LinearLayout.LayoutParams actionParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(52),
                        1
                );
        actionParams.setMargins(dp(4), 0, dp(4), 0);

        actions.addView(donateButton, actionParams);

        LinearLayout.LayoutParams questionParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(52),
                        1
                );
        questionParams.setMargins(dp(4), 0, dp(4), 0);

        actions.addView(questionButton, questionParams);

        root.addView(actions, fullWidthWrap());

        donateButton.setOnClickListener(v ->
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Support Donut4U Karaoke")
                        .setMessage(
                                "Thank you for supporting the app. "
                                + "Donation details have not been configured yet."
                        )
                        .setPositiveButton("OK", null)
                        .show()
        );

        questionButton.setOnClickListener(v ->
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Help")
                        .setMessage(
                                "Create an account or sign in to access karaoke. "
                                + "After signing in, search for a song to begin."
                        )
                        .setPositiveButton("OK", null)
                        .show()
        );

        setContentView(scroll);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(13);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setBackground(roundDrawable(GREEN, dp(10)));
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable roundDrawable(
            int color,
            int radius
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (adView != null) {
            adView.resume();
        }
    }

    @Override
    protected void onPause() {
        if (adView != null) {
            adView.pause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
            adView = null;
        }

        super.onDestroy();
    }
}
