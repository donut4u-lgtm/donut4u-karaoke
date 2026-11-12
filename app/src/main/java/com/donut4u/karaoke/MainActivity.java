
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends Activity {

    public static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final int BG = Color.rgb(16, 19, 21);
    private static final int GREEN = Color.rgb(0, 210, 105);
    private static final int PANEL = Color.rgb(35, 39, 42);
    private static final int MUTED = Color.rgb(185, 194, 189);

    private static final String BANNER_AD_UNIT_ID =
            "ca-app-pub-5625332026157890/7288125709";

    private static final String APP_OPEN_AD_UNIT_ID =
            "ca-app-pub-5625332026157890/8915386770";

    private static final String APK_URL =
            "https://github.com/donut4u-lgtm/donut4u-karaoke/releases/download/v1.0.0/Donut4U-Karaoke.apk";

    private AdView adView;
    private AppOpenAd appOpenAd;

    private TextView tvPairingCodeText;
    private TextView tvPairingStatusText;

    private boolean verifiedUserAtLaunch;
    private boolean continuedAfterAd;
    private boolean appOpenAdShowing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        /*
         * ANDROID TV:
         * Start the TV pairing server and open the TV menu.
         *
         * TV startup does not require a separate Firebase sign-in.
         * The TV menu is responsible for the standalone TV experience.
         */
        if (isAndroidTv()) {
            startTvPairing();

            AppUpdateChecker.checkForUpdates(this);

            openSongSearch();
            return;
        }

        /*
         * ANDROID PHONE:
         * Preserve the existing phone authentication flow.
         */
        FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();

        verifiedUserAtLaunch =
                user != null && user.isEmailVerified();

        if (user != null && !user.isEmailVerified()) {
            FirebaseAuth.getInstance().signOut();
        }

        AppUpdateChecker.checkForUpdates(this);

        if (!verifiedUserAtLaunch) {
            showWelcomeScreen();

            MobileAds.initialize(this, initializationStatus ->
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            loadBannerAd();
                        }
                    })
            );

        } else {
            MobileAds.initialize(this, initializationStatus ->
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            loadAppOpenAd();
                        }
                    })
            );
        }
    }

    /**
     * Starts one application-wide TV pairing session.
     */
    private void startTvPairing() {
        TvKeyboardReceiver.enableSession(
                getApplicationContext(),
                new TvKeyboardReceiver.Listener() {

                    @Override
                    public void onPairingCodeReady(String code) {
                        runOnUiThread(() -> {
                            if (tvPairingCodeText != null) {
                                tvPairingCodeText.setText(code);
                            }

                            if (tvPairingStatusText != null) {
                                tvPairingStatusText.setText(
                                        "READY - CONNECT YOUR PHONE"
                                );
                            }
                        });
                    }

                    @Override
                    public void onSearchRequested(String songTitle) {
                        // The active TV stage handles search requests.
                    }

                    @Override
                    public void onStatusChanged(String message) {
                        runOnUiThread(() -> {
                            if (tvPairingStatusText != null) {
                                tvPairingStatusText.setText(message);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (tvPairingStatusText != null) {
                                tvPairingStatusText.setText(
                                        "PAIRING ERROR: " + message
                                );
                            }
                        });
                    }
                }
        );
    }

    private boolean isAndroidTv() {
        boolean hasLeanback =
                getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_LEANBACK
                );

        boolean hasTelevisionUi =
                (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_TYPE_MASK)
                        == Configuration.UI_MODE_TYPE_TELEVISION;

        return hasLeanback || hasTelevisionUi;
    }

    private void loadBannerAd() {
        if (isAndroidTv()
                || adView != null
                || verifiedUserAtLaunch
                || isFinishing()
                || isDestroyed()) {
            return;
        }

        View content = findViewById(android.R.id.content);

        if (!(content instanceof ViewGroup)) {
            return;
        }

        View bannerContainer =
                ((ViewGroup) content).findViewWithTag(
                        "donut4u_banner_container"
                );

        if (!(bannerContainer instanceof LinearLayout)) {
            return;
        }

        adView = new AdView(this);
        adView.setAdSize(AdSize.BANNER);
        adView.setAdUnitId(BANNER_AD_UNIT_ID);

        ((LinearLayout) bannerContainer).addView(
                adView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        adView.loadAd(new AdRequest.Builder().build());
    }

    private void loadAppOpenAd() {
        if (isAndroidTv()
                || isFinishing()
                || isDestroyed()) {
            return;
        }

        AdRequest request = new AdRequest.Builder().build();

        AppOpenAd.load(
                this,
                APP_OPEN_AD_UNIT_ID,
                request,
                new AppOpenAd.AppOpenAdLoadCallback() {

                    @Override
                    public void onAdLoaded(AppOpenAd ad) {
                        appOpenAd = ad;

                        if (isFinishing() || isDestroyed()) {
                            appOpenAd = null;
                            return;
                        }

                        showAppOpenAd();
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        appOpenAd = null;
                        continueAfterAd();
                    }
                }
        );
    }

    private void showAppOpenAd() {
        if (appOpenAd == null
                || isFinishing()
                || isDestroyed()) {
            continueAfterAd();
            return;
        }

        if (appOpenAdShowing) {
            return;
        }

        appOpenAdShowing = true;

        appOpenAd.setFullScreenContentCallback(
                new FullScreenContentCallback() {

                    @Override
                    public void onAdShowedFullScreenContent() {
                        appOpenAdShowing = true;
                    }

                    @Override
                    public void onAdDismissedFullScreenContent() {
                        appOpenAd = null;
                        appOpenAdShowing = false;
                        continueAfterAd();
                    }

                    @Override
                    public void onAdFailedToShowFullScreenContent(
                            AdError error) {
                        appOpenAd = null;
                        appOpenAdShowing = false;
                        continueAfterAd();
                    }
                }
        );

        appOpenAd.show(this);
    }

    private void continueAfterAd() {
        if (continuedAfterAd
                || isFinishing()
                || isDestroyed()) {
            return;
        }

        continuedAfterAd = true;

        if (verifiedUserAtLaunch) {
            openSongSearch();
        }
    }

    private void openSongSearch() {
        if (isAndroidTv()) {
            Intent tvIntent = new Intent(
                    MainActivity.this,
                    TvStageMenuActivity.class
            );

            tvIntent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
            );

            startActivity(tvIntent);
            finish();
            return;
        }

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

        scrollView.addView(root);

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

        if (isAndroidTv()) {
            TextView pairingLabel = new TextView(this);
            pairingLabel.setText("PHONE KEYBOARD PAIRING CODE");
            pairingLabel.setTextColor(MUTED);
            pairingLabel.setTextSize(15);
            pairingLabel.setTypeface(null, Typeface.BOLD);
            pairingLabel.setGravity(Gravity.CENTER);
            pairingLabel.setPadding(0, dp(8), 0, dp(4));
            root.addView(pairingLabel, fullWidthWrap());

            tvPairingCodeText = new TextView(this);
            tvPairingCodeText.setText(
                    TvKeyboardReceiver.getCurrentPairingCode().isEmpty()
                            ? "------"
                            : TvKeyboardReceiver.getCurrentPairingCode()
            );
            tvPairingCodeText.setTextColor(GREEN);
            tvPairingCodeText.setTextSize(42);
            tvPairingCodeText.setTypeface(null, Typeface.BOLD);
            tvPairingCodeText.setGravity(Gravity.CENTER);
            tvPairingCodeText.setLetterSpacing(0.18f);
            tvPairingCodeText.setPadding(0, dp(2), 0, dp(2));
            root.addView(tvPairingCodeText, fullWidthWrap());

            tvPairingStatusText = new TextView(this);
            tvPairingStatusText.setText("STARTING PHONE CONNECTION...");
            tvPairingStatusText.setTextColor(MUTED);
            tvPairingStatusText.setTextSize(13);
            tvPairingStatusText.setGravity(Gravity.CENTER);
            tvPairingStatusText.setPadding(0, 0, 0, dp(18));
            root.addView(tvPairingStatusText, fullWidthWrap());
        }

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

        if (!isAndroidTv()) {
            TextView advertisement = new TextView(this);
            advertisement.setText("Advertisement");
            advertisement.setTextColor(MUTED);
            advertisement.setTextSize(12);
            advertisement.setGravity(Gravity.CENTER);
            advertisement.setPadding(0, dp(8), 0, dp(8));
            root.addView(advertisement, fullWidthWrap());

            LinearLayout adContainer = new LinearLayout(this);
            adContainer.setTag("donut4u_banner_container");
            adContainer.setOrientation(LinearLayout.VERTICAL);
            adContainer.setGravity(Gravity.CENTER);
            adContainer.setBackground(
                    roundDrawable(PANEL, dp(10))
            );
            adContainer.setPadding(
                    dp(4), dp(12), dp(4), dp(12)
            );

            LinearLayout.LayoutParams adParams = fullWidthWrap();
            adParams.bottomMargin = dp(20);
            root.addView(adContainer, adParams);
        }

        Button donateButton = makeButton("Donate");
        addButton(root, donateButton, 10);
        donateButton.setOnClickListener(v ->
                showDonationMessage());

        Button questionButton = makeButton("Question");
        addButton(root, questionButton, 10);
        questionButton.setOnClickListener(v ->
                showQuestionDialog());

        setContentView(scrollView);

        signInButton.setFocusable(true);
        signInButton.setFocusableInTouchMode(true);
        signInButton.requestFocus();
    }

    private void addButton(
            LinearLayout root,
            Button button,
            int bottomMargin) {

        LinearLayout.LayoutParams params = fullWidth(dp(52));
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
                        "Thank you for supporting Donut4U Karaoke. "
                                + "The donation options will be available "
                                + "when their payment details are configured."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void showQuestionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Question and Answer")
                .setMessage(
                        "Create an account or sign in to continue "
                                + "to the karaoke song screen. "
                                + "You can find songs and access the "
                                + "available support options there."
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

        if (isAndroidTv()) {
            intent.putExtra(EXTRA_TV_MODE, true);
        }

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
        button.setBackground(roundDrawable(GREEN, dp(10)));
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable roundDrawable(
            int color,
            int radius) {

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

        appOpenAd = null;

        super.onDestroy();
    }
}
