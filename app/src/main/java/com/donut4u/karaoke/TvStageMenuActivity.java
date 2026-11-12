
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public class TvStageMenuActivity extends Activity {

    public static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    public static final String EXTRA_TV_STAGE1 =
            "com.donut4u.karaoke.EXTRA_TV_STAGE1";

    private static final int BG = Color.rgb(8, 18, 25);
    private static final int PANEL = Color.rgb(18, 42, 48);
    private static final int GREEN = Color.rgb(50, 220, 130);
    private static final int WHITE = Color.WHITE;

    private TextView tvPairingCodeText;
    private TextView tvPairingStatusText;

    private LinearLayout stageButtonsContainer;

    private boolean phonePaired = false;
    private boolean openingStage = false;

    private int lastOpenedStage = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        buildMenu();
        startTvPairing();
    }

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

                            if (!phonePaired
                                    && tvPairingStatusText != null) {
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
                    public void onStateReceived(
                            JSONObject state,
                            long sequence
                    ) {
                        runOnUiThread(() ->
                                handlePhoneState(state)
                        );
                    }

                    @Override
                    public void onStatusChanged(String message) {
                        runOnUiThread(() ->
                                handleConnectionStatus(message)
                        );
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

        String currentCode =
                TvKeyboardReceiver.getCurrentPairingCode();

        if (currentCode != null && !currentCode.isEmpty()
                && tvPairingCodeText != null) {
            tvPairingCodeText.setText(currentCode);
        }
    }

    private void handleConnectionStatus(String message) {
        if (tvPairingStatusText != null) {
            tvPairingStatusText.setText(message);
        }

        if (message == null) {
            return;
        }

        if (message.contains("Encrypted phone controller connected.")) {
            phonePaired = true;
            openingStage = false;
            lastOpenedStage = -1;
            updateStageMenuVisibility();
            return;
        }

        if (message.contains("Phone disconnected.")
                || message.contains("Phone connection ended.")
                || message.contains("Phone disconnected or TLS")) {

            phonePaired = false;
            openingStage = false;
            lastOpenedStage = -1;
            updateStageMenuVisibility();
        }
    }

    private void handlePhoneState(JSONObject state) {
        if (state == null || !phonePaired) {
            return;
        }

        int stage = state.optInt("stage", -1);

        if (stage < 1 || stage > 4) {
            return;
        }

        if (openingStage || lastOpenedStage == stage) {
            return;
        }

        lastOpenedStage = stage;
        openingStage = true;

        openStage(stage);
    }

    private void updateStageMenuVisibility() {
        if (stageButtonsContainer != null) {
            stageButtonsContainer.setVisibility(
                    phonePaired ? View.GONE : View.VISIBLE
            );
        }

        if (tvPairingStatusText != null && !phonePaired) {
            tvPairingStatusText.setText(
                    "STANDALONE TV MODE - USE YOUR TV REMOTE"
            );
        }
    }

    private void buildMenu() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(36), dp(28), dp(36), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(GREEN);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("PHONE CONTROLLED KARAOKE");
        subtitle.setTextColor(WHITE);
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(24));
        root.addView(subtitle, matchWrap());

        TextView pairingLabel = new TextView(this);
        pairingLabel.setText("PHONE PAIRING CODE");
        pairingLabel.setTextColor(WHITE);
        pairingLabel.setTextSize(15);
        pairingLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pairingLabel.setGravity(Gravity.CENTER);
        pairingLabel.setPadding(0, dp(2), 0, dp(4));
        root.addView(pairingLabel, matchWrap());

        tvPairingCodeText = new TextView(this);
        tvPairingCodeText.setText("------");
        tvPairingCodeText.setTextColor(GREEN);
        tvPairingCodeText.setTextSize(40);
        tvPairingCodeText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvPairingCodeText.setGravity(Gravity.CENTER);
        tvPairingCodeText.setLetterSpacing(0.18f);
        tvPairingCodeText.setPadding(0, 0, 0, dp(2));
        root.addView(tvPairingCodeText, matchWrap());

        tvPairingStatusText = new TextView(this);
        tvPairingStatusText.setText("STARTING PHONE CONNECTION...");
        tvPairingStatusText.setTextColor(WHITE);
        tvPairingStatusText.setTextSize(13);
        tvPairingStatusText.setGravity(Gravity.CENTER);
        tvPairingStatusText.setPadding(0, 0, 0, dp(12));
        root.addView(tvPairingStatusText, matchWrap());

        stageButtonsContainer = new LinearLayout(this);
        stageButtonsContainer.setOrientation(LinearLayout.VERTICAL);
        stageButtonsContainer.setGravity(Gravity.CENTER_HORIZONTAL);

        addStageButton(
                stageButtonsContainer,
                "STAGE 1  -  YOUTUBE KARAOKE",
                1
        );

        addStageButton(
                stageButtonsContainer,
                "STAGE 2  -  KARAOKE STUDIO",
                2
        );

        addStageButton(
                stageButtonsContainer,
                "STAGE 3  -  MIDI / KAR SONGBOOK",
                3
        );

        addStageButton(
                stageButtonsContainer,
                "STAGE 4  -  TV SONGBOOK",
                4
        );

        root.addView(
                stageButtonsContainer,
                matchWrap()
        );

        setContentView(scroll);
        updateStageMenuVisibility();
    }

    private void addStageButton(
            LinearLayout root,
            String label,
            int stage
    ) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(WHITE);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(background(PANEL));
        button.setPadding(dp(18), dp(18), dp(18), dp(18));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setOnClickListener(v -> {
            if (!phonePaired) {
                openStage(stage);
            }
        });

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(76)
                );

        lp.bottomMargin = dp(14);
        root.addView(button, lp);
    }

    private void openStage(int stage) {
        Class<?> target;

        switch (stage) {
            case 1:
                target = OnlineSongActivity.class;
                break;

            case 2:
                target = KaraokeStudioActivity.class;
                break;

            case 3:
                target = Stage3SongbookActivity.class;
                break;

            case 4:
            default:
                target = Stage4TvActivity.class;
                break;
        }

        Intent intent = new Intent(this, target);
        intent.putExtra(EXTRA_TV_MODE, true);

        if (stage == 1) {
            intent.putExtra(EXTRA_TV_STAGE1, true);
        }

        startActivity(intent);
    }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(2), GREEN);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    @Override
    protected void onDestroy() {
        /*
         * Do not disable the shared receiver here.
         * TV stage activities use the same application-wide session.
         */
        super.onDestroy();
    }
}
