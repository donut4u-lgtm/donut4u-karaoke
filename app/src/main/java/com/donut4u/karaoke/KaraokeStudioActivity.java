
package com.donut4u.karaoke;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class KaraokeStudioActivity extends Activity {

    private static final String TAG = "Donut4UKaraoke";

    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/2970292504";

    private static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final int BG = Color.rgb(18, 18, 24);
    private static final int PANEL = Color.rgb(35, 35, 46);
    private static final int ACCENT = Color.rgb(255, 185, 70);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(195, 195, 205);
    private static final int GREEN = Color.rgb(45, 220, 120);

    private static final int REQUEST_STAGE2_MICROPHONE = 2201;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout searchPanel;
    private LinearLayout playerPanel;
    private LinearLayout activePanel;

    private ScrollView mainScroll;

    private LinearLayout resultsContainer;
    private EditText searchInput;
    private TextView statusText;
    private TextView nowPlayingText;
    private TextView adStatusText;
    private ProgressBar progressBar;
    private WebView videoWebView;
    private AdView adView;

    // PHONE-TO-TV KEYBOARD
    private boolean tvMode;
    private TvKeyboardReceiver tvKeyboardReceiver;
    private TextView tvKeyboardCodeText;
    private TextView tvKeyboardStatusText;

    // STAGE 2 MICROPHONE AND SINGING SCORE
    private Stage3MicrophoneMonitor microphoneMonitor;

    private final Stage12PerformanceScorer performanceScorer =
            new Stage12PerformanceScorer();

    private Button microphoneButton;
    private TextView microphoneStatus;
    private TextView microphoneLevelText;
    private TextView microphonePitchText;
    private TextView microphoneScoreText;
    private TextView microphoneStreakText;
    private TextView microphoneRatingText;
    private View microphoneLevelFill;

    private boolean extractorInitialized;
    private boolean searching;
    private boolean pageReady;
    private boolean activityDestroyed;

    private String currentTitle = "";
    private String currentArtist = "";
    private String currentVideoUrl = "";
    private String currentVideoId = "";

    private final List<SongResult> currentResults =
            new ArrayList<>();

    private static final class SongResult {

        final String title;
        final String uploader;
        final String url;
        final long durationSeconds;
        final int score;

        SongResult(
                String title,
                String uploader,
                String url,
                long durationSeconds,
                int score
        ) {
            this.title = title;
            this.uploader = uploader;
            this.url = url;
            this.durationSeconds = durationSeconds;
            this.score = score;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tvMode = getIntent().getBooleanExtra(
                EXTRA_TV_MODE,
                false
        );

        FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();

        if (user == null || !user.isEmailVerified()) {

            Toast.makeText(
                    this,
                    "Please sign in with a verified account first.",
                    Toast.LENGTH_LONG
            ).show();

            finish();
            return;
        }

        buildInterface();

        try {

            NewPipe.init(
                    new AndroidDownloader(),
                    Localization.DEFAULT,
                    ContentCountry.DEFAULT
            );

            extractorInitialized = true;

        } catch (Exception e) {

            Log.e(TAG, "NewPipe initialization failed", e);

            setStatus(
                    "Song search could not initialize. Check your connection."
            );
        }

        try {

            MobileAds.initialize(this, initializationStatus -> {

                if (activityDestroyed || adView == null) {
                    return;
                }

                adView.post(() -> {

                    if (activityDestroyed || adView == null) {
                        return;
                    }

                    if (adStatusText != null) {
                        adStatusText.setText(
                                "Loading advertisement..."
                        );
                    }

                    adView.loadAd(
                            new AdRequest.Builder().build()
                    );
                });
            });

        } catch (Exception e) {

            Log.e(TAG, "AdMob initialization failed", e);

            if (adStatusText != null) {
                adStatusText.setText(
                        "AdMob initialization failed."
                );
            }
        }
    }

    // ------------------------------------------------
    // MAIN INTERFACE
    // ------------------------------------------------

    private void buildInterface() {

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                dp(16), dp(16), dp(16), dp(16)
        );
        root.setBackgroundColor(BG);

        mainScroll.addView(root);
        setContentView(mainScroll);

        TextView title = text(
                "DONUT4U KARAOKE", 25, ACCENT, true
        );

        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text(
                "Find a song. Play. Sing.",
                15, MUTED, false
        );

        subtitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams subtitleParams =
                matchWrap();

        subtitleParams.bottomMargin = dp(14);

        root.addView(subtitle, subtitleParams);

        // Shared status stays visible on both screens.
        statusText = text(
                "Enter a song title or artist to begin.",
                14, MUTED, false
        );

        LinearLayout.LayoutParams statusParams =
                matchWrap();

        statusParams.bottomMargin = dp(10);

        root.addView(statusText, statusParams);

        // --------------------------------------------
        // SEARCH SCREEN
        // --------------------------------------------

        searchPanel = new LinearLayout(this);
        searchPanel.setOrientation(LinearLayout.VERTICAL);

        root.addView(searchPanel, matchWrap());

        activePanel = searchPanel;

        addSectionTitle("FIND MY SONG");

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(WHITE);
        searchInput.setHintTextColor(MUTED);
        searchInput.setHint("karaoke - air supply");
        searchInput.setTextSize(16);
        searchInput.setPadding(
                dp(12), dp(8), dp(12), dp(8)
        );

        if (tvMode) {
            searchInput.setBackground(
                    createTvSearchBackground()
            );
            searchInput.setFocusable(true);
            searchInput.setFocusableInTouchMode(true);
            searchInput.setShowSoftInputOnFocus(false);
            searchInput.setSelectAllOnFocus(false);
        } else {
            searchInput.setBackgroundColor(PANEL);
        }

        searchPanel.addView(searchInput, matchWrap());

        Button searchButton = button("SEARCH SONGS");

        LinearLayout.LayoutParams searchParams =
                matchWrap();

        searchParams.topMargin = dp(8);

        searchPanel.addView(searchButton, searchParams);

        searchButton.setOnClickListener(v -> searchSongs());

        if (tvMode) {

            addTvPhoneKeyboardPanel();

        } else {

            Button phoneRemoteButton =
                    button("USE PHONE AS KEYBOARD");

            LinearLayout.LayoutParams remoteParams =
                    matchWrap();

            remoteParams.topMargin = dp(8);

            searchPanel.addView(
                    phoneRemoteButton,
                    remoteParams
            );

            phoneRemoteButton.setOnClickListener(v -> {
                try {
                    startActivity(
                            new Intent(
                                    KaraokeStudioActivity.this,
                                    PhoneRemoteActivity.class
                            )
                    );
                } catch (Exception e) {
                    Log.e(
                            TAG,
                            "Could not open phone remote",
                            e
                    );

                    Toast.makeText(
                            KaraokeStudioActivity.this,
                            "Unable to open Phone Remote.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        }

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);

        LinearLayout.LayoutParams progressParams =
                matchWrap();

        progressParams.topMargin = dp(8);

        searchPanel.addView(progressBar, progressParams);

        addSectionTitle("ONLINE SONG RESULTS");

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(
                LinearLayout.VERTICAL
        );

        searchPanel.addView(resultsContainer, matchWrap());

        // --------------------------------------------
        // PLAYER SCREEN
        // --------------------------------------------

        playerPanel = new LinearLayout(this);
        playerPanel.setOrientation(LinearLayout.VERTICAL);
        playerPanel.setVisibility(View.GONE);

        root.addView(playerPanel, matchWrap());

        activePanel = playerPanel;

        Button backToSearchButton =
                button("BACK TO SEARCH RESULTS");

        LinearLayout.LayoutParams returnParams =
                matchWrap();

        returnParams.topMargin = dp(8);
        returnParams.bottomMargin = dp(8);

        playerPanel.addView(
                backToSearchButton,
                returnParams
        );

        backToSearchButton.setOnClickListener(
                v -> showSearchScreen()
        );

        addSectionTitle("KARAOKE PLAYER");

        nowPlayingText = text(
                "No song selected", 16, WHITE, true
        );

        playerPanel.addView(nowPlayingText, matchWrap());

        videoWebView = new WebView(this);
        videoWebView.setBackgroundColor(Color.BLACK);
        videoWebView.setLayerType(
                View.LAYER_TYPE_HARDWARE, null
        );

        WebSettings settings =
                videoWebView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        CookieManager cookies =
                CookieManager.getInstance();

        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(
                videoWebView, true
        );

        videoWebView.addJavascriptInterface(
                new PlayerBridge(), "DonutBridge"
        );

        videoWebView.setWebChromeClient(
                new WebChromeClient() {

            @Override
            public boolean onConsoleMessage(
                    ConsoleMessage message
            ) {

                Log.d(
                        TAG,
                        "YouTube: " + message.message()
                );

                return true;
            }
        });

        videoWebView.setWebViewClient(
                new WebViewClient() {

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {

                super.onPageStarted(
                        view, url, favicon
                );

                pageReady = false;
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {

                super.onPageFinished(
                        view, url
                );

                if (url == null
                        || "about:blank".equals(url)) {

                    pageReady = false;
                }
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error
            ) {

                super.onReceivedError(
                        view, request, error
                );

                if (request.isForMainFrame()) {

                    setStatus(
                            "Player page failed to load. Check your internet."
                    );

                    Log.e(
                            TAG,
                            "WebView error: "
                                    + error.getDescription()
                    );
                }
            }
        });

        // PLAYER HEIGHT: 280dp
        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(280)
                );

        videoParams.topMargin = dp(8);

        playerPanel.addView(videoWebView, videoParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(
                LinearLayout.HORIZONTAL
        );
        controls.setGravity(Gravity.CENTER);

        Button playButton = button("PLAY");
        Button pauseButton = button("PAUSE");
        Button stopButton = button("STOP");

        controls.addView(
                playButton, weightedWrap()
        );

        controls.addView(
                pauseButton, weightedWrap()
        );

        controls.addView(
                stopButton, weightedWrap()
        );

        playerPanel.addView(controls, matchWrap());

        playButton.setOnClickListener(v -> {

            if (currentVideoId.isEmpty()) {

                Toast.makeText(
                        this,
                        "Choose a song first.",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            if (pageReady && videoWebView != null) {

                videoWebView.evaluateJavascript(
                        "if(window.player){player.playVideo();}",
                        null
                );

                setStatus("Play command sent.");

            } else {

                setStatus(
                        "Player is still loading. Please wait."
                );
            }
        });

        pauseButton.setOnClickListener(v -> {

            if (videoWebView != null && pageReady) {

                videoWebView.evaluateJavascript(
                        "if(window.player){player.pauseVideo();}",
                        null
                );
            }
        });

        stopButton.setOnClickListener(
                v -> stopPlayback()
        );

        // STAGE 2 SINGING PERFORMANCE PANEL
        addStage2MicrophonePanel();

        addSectionTitle("SUPPORT DONUT4U KARAOKE");

        TextView donationText = text(
                "Help keep running and growing DONATE",
                15, MUTED, false
        );

        playerPanel.addView(donationText, matchWrap());

        Button donateButton = button("DONATE");

        playerPanel.addView(donateButton, matchWrap());

        donateButton.setOnClickListener(
                v -> showDonationQr()
        );

        Button questionButton =
                button("QUESTION AND ANSWER");

        playerPanel.addView(questionButton, matchWrap());

        questionButton.setOnClickListener(
                v -> openQuestionsFacebook()
        );

        Button backButton = button("BACK TO STAGE");

        playerPanel.addView(backButton, matchWrap());

        backButton.setOnClickListener(v -> finish());

        Button logoutButton = button("LOG OUT");

        playerPanel.addView(logoutButton, matchWrap());

        logoutButton.setOnClickListener(v -> {

            stopPlayback();
            stopStage2Microphone(false);
            stopTvPhoneKeyboardReceiver();

            FirebaseAuth.getInstance().signOut();

            Intent intent = new Intent();

            intent.setClassName(
                    this,
                    "com.donut4u.karaoke.AuthActivity"
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
            );

            startActivity(intent);
            finish();
        });

        TextView adLabel = text(
                "Advertisement", 12, MUTED, false
        );

        adLabel.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams adLabelParams =
                matchWrap();

        adLabelParams.topMargin = dp(16);

        playerPanel.addView(adLabel, adLabelParams);

        adStatusText = text(
                "Loading advertisement...",
                12, MUTED, false
        );

        adStatusText.setGravity(Gravity.CENTER);

        playerPanel.addView(adStatusText, matchWrap());

        adView = new AdView(this);

        adView.setAdSize(
                AdSize.MEDIUM_RECTANGLE
        );

        adView.setAdUnitId(AD_UNIT_ID);

        adView.setAdListener(new AdListener() {

            @Override
            public void onAdLoaded() {

                runOnUiThread(() -> {

                    if (adStatusText != null) {
                        adStatusText.setText(
                                "Advertisement loaded."
                        );
                    }
                });

                Log.i(
                        TAG,
                        "Real Stage 2 banner loaded."
                );
            }

            @Override
            public void onAdFailedToLoad(
                    LoadAdError error
            ) {

                String message =
                        "Advertisement unavailable. Code "
                                + error.getCode()
                                + ": "
                                + error.getMessage();

                runOnUiThread(() -> {

                    if (adStatusText != null) {
                        adStatusText.setText(message);
                    }
                });

                Log.e(TAG, message);
            }
        });

        LinearLayout.LayoutParams adParams =
                new LinearLayout.LayoutParams(
                        dp(300), dp(250)
                );

        adParams.gravity =
                Gravity.CENTER_HORIZONTAL;

        adParams.topMargin = dp(6);

        playerPanel.addView(adView, adParams);

        // Start on the search screen.
        showSearchScreen();
    }

    // ------------------------------------------------
    // SCREEN NAVIGATION
    // ------------------------------------------------

    private void showSearchScreen() {

        if (activityDestroyed
                || searchPanel == null
                || playerPanel == null) {
            return;
        }

        searchPanel.setVisibility(View.VISIBLE);
        playerPanel.setVisibility(View.GONE);

        if (mainScroll != null) {
            mainScroll.post(() -> {
                if (!activityDestroyed && mainScroll != null) {
                    mainScroll.scrollTo(0, 0);
                }
            });
        }
    }

    private void showPlayerScreen() {

        if (activityDestroyed
                || searchPanel == null
                || playerPanel == null) {
            return;
        }

        searchPanel.setVisibility(View.GONE);
        playerPanel.setVisibility(View.VISIBLE);

        if (mainScroll != null) {
            mainScroll.post(() -> {
                if (!activityDestroyed && mainScroll != null) {
                    mainScroll.scrollTo(0, 0);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {

        if (playerPanel != null
                && playerPanel.getVisibility() == View.VISIBLE) {

            showSearchScreen();
            return;
        }

        super.onBackPressed();
    }

    // ------------------------------------------------
    // TV PHONE KEYBOARD
    // ------------------------------------------------

    private GradientDrawable createSearchDrawable(
            int fillColor,
            int strokeColor,
            int strokeWidth
    ) {
        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(8));

        if (strokeWidth > 0) {
            drawable.setStroke(
                    dp(strokeWidth),
                    strokeColor
            );
        }

        return drawable;
    }

    private StateListDrawable createTvSearchBackground() {

        StateListDrawable states =
                new StateListDrawable();

        GradientDrawable focused =
                createSearchDrawable(
                        Color.BLACK,
                        GREEN,
                        3
                );

        GradientDrawable normal =
                createSearchDrawable(
                        PANEL,
                        Color.rgb(95, 95, 110),
                        1
                );

        states.addState(
                new int[]{android.R.attr.state_focused},
                focused
        );

        states.addState(
                new int[]{android.R.attr.state_selected},
                focused
        );

        states.addState(
                new int[]{},
                normal
        );

        return states;
    }

    private void addTvPhoneKeyboardPanel() {

        addSectionTitle("PHONE KEYBOARD");

        LinearLayout panel =
                new LinearLayout(this);

        panel.setOrientation(
                LinearLayout.VERTICAL
        );

        panel.setPadding(
                dp(14), dp(12), dp(14), dp(12)
        );

        panel.setBackground(
                createSearchDrawable(
                        Color.rgb(24, 48, 37),
                        GREEN,
                        1
                )
        );

        LinearLayout.LayoutParams panelParams =
                matchWrap();

        panelParams.topMargin = dp(8);

        activePanel.addView(panel, panelParams);

        TextView instruction = text(
                "Connect your phone and TV to the same Wi-Fi "
                        + "or phone hotspot. Open Donut4U Phone Remote "
                        + "on your phone and enter this code.",
                14, WHITE, false
        );

        panel.addView(
                instruction,
                matchWrap()
        );

        tvKeyboardCodeText = text(
                "------",
                32, GREEN, true
        );

        tvKeyboardCodeText.setGravity(
                Gravity.CENTER
        );

        tvKeyboardCodeText.setLetterSpacing(0.15f);

        LinearLayout.LayoutParams codeParams =
                matchWrap();

        codeParams.topMargin = dp(8);
        codeParams.bottomMargin = dp(4);

        panel.addView(
                tvKeyboardCodeText,
                codeParams
        );

        tvKeyboardStatusText = text(
                "Starting phone keyboard connection...",
                13, MUTED, false
        );

        tvKeyboardStatusText.setGravity(
                Gravity.CENTER
        );

        panel.addView(
                tvKeyboardStatusText,
                matchWrap()
        );
    }

    private void startTvPhoneKeyboardReceiver() {

        if (!tvMode || activityDestroyed) {
            return;
        }

        if (tvKeyboardReceiver != null) {
            return;
        }

        if (tvKeyboardCodeText == null
                || tvKeyboardStatusText == null) {
            return;
        }

        tvKeyboardReceiver =
                new TvKeyboardReceiver(
                        getApplicationContext(),
                        new TvKeyboardReceiver.Listener() {

            @Override
            public void onPairingCodeReady(String code) {

                if (activityDestroyed
                        || tvKeyboardCodeText == null) {
                    return;
                }

                tvKeyboardCodeText.setText(code);
            }

            @Override
            public void onSearchRequested(String songTitle) {

                if (activityDestroyed
                        || searchInput == null) {
                    return;
                }

                showSearchScreen();

                searchInput.setText(songTitle);
                searchInput.setSelection(
                        searchInput.getText().length()
                );

                searchSongs();
            }

            @Override
            public void onStatusChanged(String message) {

                if (activityDestroyed
                        || tvKeyboardStatusText == null) {
                    return;
                }

                tvKeyboardStatusText.setText(message);
            }

            @Override
            public void onError(String message) {

                if (activityDestroyed
                        || tvKeyboardStatusText == null) {
                    return;
                }

                tvKeyboardStatusText.setText(message);

                Log.e(TAG, message);
            }
        });

        tvKeyboardReceiver.start();
    }

    private void stopTvPhoneKeyboardReceiver() {

        if (tvKeyboardReceiver != null) {

            tvKeyboardReceiver.stop();
            tvKeyboardReceiver = null;
        }

        if (tvKeyboardStatusText != null
                && !activityDestroyed) {

            tvKeyboardStatusText.setText(
                    "Phone keyboard disconnected."
            );
        }
    }

    // ------------------------------------------------
    // STAGE 2 MICROPHONE PANEL
    // ------------------------------------------------

    private void addStage2MicrophonePanel() {

        addSectionTitle("💚 SINGING PERFORMANCE");

        LinearLayout panel = new LinearLayout(this);

        panel.setOrientation(
                LinearLayout.VERTICAL
        );

        panel.setPadding(
                dp(14), dp(14), dp(14), dp(14)
        );

        panel.setBackgroundColor(
                Color.rgb(24, 48, 37)
        );

        LinearLayout.LayoutParams panelParams =
                matchWrap();

        panelParams.topMargin = dp(4);

        activePanel.addView(panel, panelParams);

        microphoneButton =
                new Button(this);

        microphoneButton.setText(
                "🎤 TURN MICROPHONE ON"
        );

        microphoneButton.setTextColor(Color.BLACK);

        microphoneButton.setAllCaps(false);

        microphoneButton.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        microphoneButton.setBackgroundTintList(
                ColorStateList.valueOf(GREEN)
        );

        panel.addView(
                microphoneButton, matchWrap()
        );

        microphoneButton.setOnClickListener(
                v -> toggleStage2Microphone()
        );

        microphoneStatus = text(
                "Microphone is OFF. Tap the button to start.",
                14, WHITE, true
        );

        LinearLayout.LayoutParams statusParams =
                matchWrap();

        statusParams.topMargin = dp(10);

        panel.addView(
                microphoneStatus, statusParams
        );

        microphoneLevelText = text(
                "MIC LEVEL: 0%",
                14, WHITE, true
        );

        LinearLayout.LayoutParams levelTextParams =
                matchWrap();

        levelTextParams.topMargin = dp(12);

        panel.addView(
                microphoneLevelText,
                levelTextParams
        );

        FrameLayout meter = new FrameLayout(this);

        meter.setBackgroundColor(
                Color.rgb(65, 65, 65)
        );

        LinearLayout.LayoutParams meterParams =
                matchWrap();

        meterParams.height = dp(18);
        meterParams.topMargin = dp(6);

        panel.addView(meter, meterParams);

        microphoneLevelFill = new View(this);

        microphoneLevelFill.setBackgroundColor(
                GREEN
        );

        FrameLayout.LayoutParams fillParams =
                new FrameLayout.LayoutParams(
                        0,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.LEFT
                );

        meter.addView(
                microphoneLevelFill,
                fillParams
        );

        microphonePitchText = text(
                "PITCH: --",
                14, WHITE, true
        );

        LinearLayout.LayoutParams pitchParams =
                matchWrap();

        pitchParams.topMargin = dp(12);

        panel.addView(
                microphonePitchText,
                pitchParams
        );

        microphoneScoreText = text(
                "SINGING SCORE: 0%",
                22, GREEN, true
        );

        LinearLayout.LayoutParams scoreParams =
                matchWrap();

        scoreParams.topMargin = dp(10);

        panel.addView(
                microphoneScoreText,
                scoreParams
        );

        microphoneStreakText = text(
                "BEST STREAK: 0",
                14, WHITE, true
        );

        LinearLayout.LayoutParams streakParams =
                matchWrap();

        streakParams.topMargin = dp(6);

        panel.addView(
                microphoneStreakText,
                streakParams
        );

        microphoneRatingText = text(
                "RATING: WAITING FOR SINGING",
                15, GREEN, true
        );

        LinearLayout.LayoutParams ratingParams =
                matchWrap();

        ratingParams.topMargin = dp(6);

        panel.addView(
                microphoneRatingText,
                ratingParams
        );

        TextView explanation = text(
                "The score measures pitch stability and vocal control. "
                        + "It does not compare your voice against the exact "
                        + "song melody.",
                12, MUTED, false
        );

        LinearLayout.LayoutParams explanationParams =
                matchWrap();

        explanationParams.topMargin = dp(10);

        panel.addView(
                explanation, explanationParams
        );
    }

    private void toggleStage2Microphone() {

        if (activityDestroyed) return;

        if (microphoneMonitor != null
                && microphoneMonitor.isRunning()) {

            stopStage2Microphone(true);
            return;
        }

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO
                    },
                    REQUEST_STAGE2_MICROPHONE
            );

            if (microphoneStatus != null) {
                microphoneStatus.setText(
                        "Waiting for microphone permission..."
                );
            }

            return;
        }

        startStage2Microphone();
    }

    private void startStage2Microphone() {

        if (activityDestroyed) return;

        if (microphoneMonitor != null) {
            microphoneMonitor.stop();
            microphoneMonitor = null;
        }

        performanceScorer.reset();

        updateStage2MicrophoneDisplay(
                0, -1, 0.0
        );

        microphoneButton.setText(
                "STOP MICROPHONE"
        );

        microphoneStatus.setText(
                "Starting microphone..."
        );

        microphoneMonitor =
                new Stage3MicrophoneMonitor(
                        new Stage3MicrophoneMonitor.Listener() {

            @Override
            public void onUpdate(
                    int levelPercent,
                    int midiNote,
                    double confidence
            ) {

                if (activityDestroyed) return;

                performanceScorer.update(
                        levelPercent,
                        midiNote,
                        confidence
                );

                updateStage2MicrophoneDisplay(
                        levelPercent,
                        midiNote,
                        confidence
                );
            }

            @Override
            public void onError(String message) {

                if (activityDestroyed) return;

                runOnUiThread(() -> {

                    if (activityDestroyed) return;

                    if (microphoneMonitor != null) {
                        microphoneMonitor.stop();
                        microphoneMonitor = null;
                    }

                    if (microphoneButton != null) {
                        microphoneButton.setText(
                                "🎤 TURN MICROPHONE ON"
                        );
                    }

                    if (microphoneStatus != null) {
                        microphoneStatus.setText(message);
                    }

                    Toast.makeText(
                            KaraokeStudioActivity.this,
                            message,
                            Toast.LENGTH_LONG
                    ).show();

                    Log.e(TAG, message);
                });
            }
        });

        boolean started =
                microphoneMonitor.start();

        if (!started) {

            microphoneButton.setText(
                    "🎤 TURN MICROPHONE ON"
            );

            if (microphoneStatus != null) {
                microphoneStatus.setText(
                        "Microphone could not start."
                );
            }

            return;
        }

        microphoneButton.setText(
                "STOP MICROPHONE"
        );

        microphoneStatus.setText(
                "Microphone ON. Start singing!"
        );
    }

    private void updateStage2MicrophoneDisplay(
            int level,
            int note,
            double confidence
    ) {

        if (activityDestroyed) return;

        if (microphoneLevelText != null) {

            microphoneLevelText.setText(
                    "MIC LEVEL: " + level + "%"
            );
        }

        if (microphoneLevelFill != null) {

            View parent =
                    (View) microphoneLevelFill.getParent();

            if (parent != null) {

                int availableWidth =
                        parent.getWidth();

                if (availableWidth > 0) {

                    ViewGroup.LayoutParams params =
                            microphoneLevelFill.getLayoutParams();

                    params.width =
                            availableWidth
                                    * Math.max(
                                            0,
                                            Math.min(100, level)
                                    )
                                    / 100;

                    microphoneLevelFill.setLayoutParams(
                            params
                    );
                }
            }
        }

        if (microphonePitchText != null) {

            if (note >= 0 && confidence >= 0.72) {

                microphonePitchText.setText(
                        "PITCH: " + noteName(note)
                );

            } else {

                microphonePitchText.setText(
                        "PITCH: Listening..."
                );
            }
        }

        if (microphoneScoreText != null) {

            microphoneScoreText.setText(
                    "SINGING SCORE: "
                            + performanceScorer.getScorePercent()
                            + "%"
            );
        }

        if (microphoneStreakText != null) {

            microphoneStreakText.setText(
                    "BEST STREAK: "
                            + performanceScorer.getBestStreak()
            );
        }

        if (microphoneRatingText != null) {

            microphoneRatingText.setText(
                    "RATING: "
                            + performanceScorer.getRating()
            );
        }

        if (microphoneStatus != null
                && level >= 8) {

            microphoneStatus.setText(
                    "Microphone ON. Voice detected."
            );
        }
    }

    private String noteName(int midiNote) {

        String[] notes = {
                "C", "C#", "D", "D#", "E", "F",
                "F#", "G", "G#", "A", "A#", "B"
        };

        int noteIndex =
                Math.floorMod(midiNote, 12);

        int octave =
                Math.floorDiv(midiNote, 12) - 1;

        return notes[noteIndex] + octave;
    }

    private void stopStage2Microphone(
            boolean showResult
    ) {

        if (microphoneMonitor != null) {

            microphoneMonitor.stop();
            microphoneMonitor = null;
        }

        if (microphoneButton != null
                && !activityDestroyed) {

            microphoneButton.setText(
                    "🎤 TURN MICROPHONE ON"
            );
        }

        if (microphoneStatus != null
                && !activityDestroyed) {

            microphoneStatus.setText(
                    "Microphone is OFF."
            );
        }

        if (showResult && !activityDestroyed) {

            int score =
                    performanceScorer.getScorePercent();

            int pitch =
                    performanceScorer.getPitchStabilityPercent();

            int control =
                    performanceScorer.getVocalControlPercent();

            int streak =
                    performanceScorer.getBestStreak();

            String rating =
                    performanceScorer.getRating();

            String result =
                    "Singing score: " + score + "%\n\n"
                            + "Pitch stability: " + pitch + "%\n"
                            + "Vocal control: " + control + "%\n"
                            + "Best streak: " + streak + "\n\n"
                            + "Rating: " + rating;

            new AlertDialog.Builder(this)
                    .setTitle("YOUR SINGING RESULT")
                    .setMessage(result)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQUEST_STAGE2_MICROPHONE) {

            if (grantResults.length > 0
                    && grantResults[0]
                    == PackageManager.PERMISSION_GRANTED) {

                startStage2Microphone();

            } else {

                if (microphoneStatus != null) {
                    microphoneStatus.setText(
                            "Microphone permission was denied."
                    );
                }

                Toast.makeText(
                        this,
                        "Allow microphone permission to use singing score.",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    // ------------------------------------------------
    // DONATION
    // ------------------------------------------------

    private void showDonationQr() {

        LinearLayout content =
                new LinearLayout(this);

        content.setOrientation(
                LinearLayout.VERTICAL
        );

        content.setPadding(
                dp(16), dp(8), dp(16), dp(8)
        );

        content.setBackgroundColor(Color.WHITE);

        TextView instruction =
                new TextView(this);

        instruction.setText(
                "Open GCash on another phone and scan this QR code.\n\n"
                        + "Thank you for supporting Donut4U Karaoke!"
        );

        instruction.setTextColor(Color.BLACK);
        instruction.setTextSize(16);
        instruction.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams instructionParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        instructionParams.bottomMargin = dp(12);

        content.addView(
                instruction, instructionParams
        );

        ImageView qrImage = new ImageView(this);

        qrImage.setImageResource(
                R.drawable.gcash_qr
        );

        qrImage.setScaleType(
                ImageView.ScaleType.FIT_CENTER
        );

        qrImage.setAdjustViewBounds(true);

        qrImage.setContentDescription(
                "GCash donation QR code"
        );

        LinearLayout.LayoutParams qrParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(300)
                );

        qrParams.gravity =
                Gravity.CENTER_HORIZONTAL;

        content.addView(qrImage, qrParams);

        new AlertDialog.Builder(this)
                .setTitle("Support Donut4U Karaoke")
                .setView(content)
                .setPositiveButton("CLOSE", null)
                .show();
    }

    private void openQuestionsFacebook() {

        String facebookUrl =
                "https://www.facebook.com/share/17zkgf3o8j/";

        try {

            startActivity(
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(facebookUrl)
                    )
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not open Facebook",
                    e
            );

            Toast.makeText(
                    this,
                    "Unable to open Facebook. Please try again.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // ------------------------------------------------
    // YOUTUBE PLAYER BRIDGE
    // ------------------------------------------------

    private final class PlayerBridge {

        @JavascriptInterface
        public void onReady() {

            mainHandler.post(() -> {

                if (activityDestroyed) return;

                pageReady = true;

                setStatus(
                        "YouTube player ready. Tap PLAY if needed."
                );
            });
        }

        @JavascriptInterface
        public void onError(String code) {

            mainHandler.post(() -> {

                if (activityDestroyed) return;

                pageReady = false;

                setStatus(
                        "YouTube could not play this video (error "
                                + code
                                + "). Try another result."
                );

                Log.e(
                        TAG,
                        "YouTube player error: " + code
                );
            });
        }

        @JavascriptInterface
        public void onState(String state) {

            mainHandler.post(() -> {

                if (activityDestroyed) return;

                if ("1".equals(state)) {

                    setStatus(
                            "Playing: " + currentTitle
                    );

                } else if ("2".equals(state)) {

                    setStatus("Playback paused.");

                } else if ("0".equals(state)) {

                    setStatus("Song finished.");
                }
            });
        }
    }

    // ------------------------------------------------
    // SEARCH
    // ------------------------------------------------

    private void addSectionTitle(String label) {

        TextView heading =
                text(label, 17, ACCENT, true);

        LinearLayout.LayoutParams params =
                matchWrap();

        params.topMargin = dp(18);
        params.bottomMargin = dp(8);

        activePanel.addView(heading, params);
    }

    private void searchSongs() {

        if (searching) return;

        String query =
                searchInput.getText().toString().trim();

        if (query.isEmpty()) {

            searchInput.setError(
                    "Enter a song title or artist"
            );

            return;
        }

        hideKeyboard();

        if (!extractorInitialized) {

            setStatus(
                    "Search is not initialized. Restart the app and retry."
            );

            return;
        }

        searching = true;

        progressBar.setVisibility(View.VISIBLE);

        resultsContainer.removeAllViews();

        currentResults.clear();

        setStatus("Searching for songs...");

        new Thread(() -> {

            List<SongResult> found =
                    new ArrayList<>();

            String failure = null;

            try {

                StreamingService service =
                        NewPipe.getService("YouTube");

                SearchQueryHandler handler =
                        service.getSearchQHFactory()
                                .fromQuery(query);

                SearchExtractor extractor =
                        service.getSearchExtractor(handler);

                extractor.fetchPage();

                SearchInfo info =
                        SearchInfo.getInfo(extractor);

                for (org.schabi.newpipe.extractor.InfoItem item
                        : info.getRelatedItems()) {

                    if (!(item instanceof StreamInfoItem)) {
                        continue;
                    }

                    StreamInfoItem stream =
                            (StreamInfoItem) item;

                    String songTitle =
                            safe(stream.getName());

                    String songUrl =
                            safe(stream.getUrl());

                    String uploader =
                            safe(stream.getUploaderName());

                    if (songTitle.isEmpty()
                            || songUrl.isEmpty()) {
                        continue;
                    }

                    found.add(
                            new SongResult(
                                    songTitle,
                                    uploader,
                                    songUrl,
                                    stream.getDuration(),
                                    scoreResult(
                                            songTitle,
                                            uploader,
                                            query
                                    )
                            )
                    );
                }

                Collections.sort(
                        found,
                        (a, b) ->
                                Integer.compare(
                                        b.score, a.score
                                )
                );

            } catch (Exception e) {

                failure = e.getMessage();

                Log.e(
                        TAG,
                        "YouTube search failed",
                        e
                );
            }

            final List<SongResult> results =
                    found;

            final String error =
                    failure;

            mainHandler.post(() -> {

                if (activityDestroyed) return;

                searching = false;

                progressBar.setVisibility(
                        View.GONE
                );

                if (results.isEmpty()) {

                    setStatus(
                            error == null
                                    ? "No results found. Try another search."
                                    : "Search failed: " + error
                    );

                    return;
                }

                currentResults.addAll(results);

                setStatus(
                        results.size()
                                + " song results found."
                );

                for (SongResult result : results) {
                    addSongResult(result);
                }
            });

        }).start();
    }

    private int scoreResult(
            String title,
            String uploader,
            String query
    ) {

        String content =
                (title + " " + uploader)
                        .toLowerCase(Locale.ROOT);

        int score = 0;

        if (content.contains("karaoke")) score += 100;
        if (content.contains("instrumental")) score += 90;
        if (content.contains("minus one")) score += 90;
        if (content.contains("minus-one")) score += 90;
        if (content.contains("sing along")) score += 70;
        if (content.contains("sing-along")) score += 70;
        if (content.contains("backing track")) score += 65;
        if (content.contains("lyrics")) score += 10;
        if (content.contains("official audio")) score -= 10;
        if (content.contains("official music video")) score -= 15;

        for (String word :
                query.toLowerCase(Locale.ROOT).split("\\s+")) {

            if (word.length() > 2
                    && content.contains(word)) {

                score += 3;
            }
        }

        return score;
    }

    private void addSongResult(
            SongResult result
    ) {

        LinearLayout card =
                new LinearLayout(this);

        card.setOrientation(
                LinearLayout.VERTICAL
        );

        card.setPadding(
                dp(12), dp(12), dp(12), dp(12)
        );

        card.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams cardParams =
                matchWrap();

        cardParams.bottomMargin = dp(8);

        resultsContainer.addView(
                card, cardParams
        );

        TextView songTitle =
                text(result.title, 16, WHITE, true);

        card.addView(
                songTitle, matchWrap()
        );

        String details = result.uploader;

        if (result.durationSeconds > 0) {

            details += " • "
                    + formatDuration(
                            result.durationSeconds
                    );
        }

        TextView metadata =
                text(details, 12, MUTED, false);

        LinearLayout.LayoutParams metaParams =
                matchWrap();

        metaParams.topMargin = dp(4);

        card.addView(
                metadata, metaParams
        );

        Button playButton =
                button("PLAY THIS SONG");

        LinearLayout.LayoutParams playParams =
                matchWrap();

        playParams.topMargin = dp(8);

        card.addView(
                playButton, playParams
        );

        playButton.setOnClickListener(v ->
                loadAndPlay(
                        result.url,
                        result.title,
                        result.uploader
                )
        );
    }

    // ------------------------------------------------
    // YOUTUBE PLAYBACK
    // ------------------------------------------------

    private void loadAndPlay(
            String url,
            String title,
            String artist
    ) {

        String videoId =
                extractYouTubeVideoId(url);

        if (videoId.isEmpty()) {

            setStatus(
                    "Invalid YouTube link. Choose another result."
            );

            return;
        }

        currentTitle = safe(title);
        currentArtist = safe(artist);
        currentVideoUrl = safe(url);
        currentVideoId = videoId;

        pageReady = false;

        nowPlayingText.setText(
                currentTitle
                        + (currentArtist.isEmpty()
                        ? ""
                        : "\n" + currentArtist)
        );

        // Move to the dedicated player screen.
        showPlayerScreen();

        setStatus("Opening YouTube player...");

        String html =
                "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,"
                + "initial-scale=1,maximum-scale=1'>"
                + "<meta name='referrer' content='strict-origin-when-cross-origin'>"
                + "<style>html,body,#player{margin:0;padding:0;width:100%;"
                + "height:100%;background:#000;overflow:hidden}</style>"
                + "</head><body><div id='player'></div><script>"
                + "var player;"
                + "function onYouTubeIframeAPIReady(){"
                + "player=new YT.Player('player',{"
                + "width:'100%',height:'100%',"
                + "videoId:'" + videoId + "',"
                + "playerVars:{autoplay:1,playsinline:1,controls:1,rel:0,"
                + "origin:'https://donut4u-karaoke.onrender.com'},"
                + "events:{"
                + "onReady:function(e){DonutBridge.onReady();e.target.playVideo();},"
                + "onStateChange:function(e){DonutBridge.onState(String(e.data));},"
                + "onError:function(e){DonutBridge.onError(String(e.data));}"
                + "}"
                + "});"
                + "}"
                + "</script>"
                + "<script src='https://www.youtube.com/iframe_api'></script>"
                + "</body></html>";

        videoWebView.stopLoading();

        videoWebView.loadDataWithBaseURL(
                "https://donut4u-karaoke.onrender.com/",
                html,
                "text/html",
                "UTF-8",
                null
        );
    }

    private String extractYouTubeVideoId(
            String url
    ) {

        if (url == null || url.trim().isEmpty()) {
            return "";
        }

        try {

            Uri uri = Uri.parse(url);

            String host = uri.getHost();

            if (host == null) return "";

            if (host.equalsIgnoreCase("youtu.be")) {

                String path = uri.getPath();

                if (path != null && path.length() > 1) {

                    return path.substring(1)
                            .split("/")[0];
                }
            }

            if (host.toLowerCase(Locale.ROOT)
                    .contains("youtube.com")) {

                String id =
                        uri.getQueryParameter("v");

                if (id != null && !id.trim().isEmpty()) {
                    return id.trim();
                }

                String path = uri.getPath();

                if (path != null) {

                    String[] parts =
                            path.split("/");

                    for (int i = 0;
                         i < parts.length - 1;
                         i++) {

                        if ("embed".equals(parts[i])
                                || "shorts".equals(parts[i])) {

                            return parts[i + 1];
                        }
                    }
                }
            }

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Could not parse YouTube URL",
                    e
            );
        }

        return "";
    }

    private void stopPlayback() {

        pageReady = false;

        currentVideoId = "";
        currentVideoUrl = "";
        currentTitle = "";
        currentArtist = "";

        if (videoWebView != null) {

            videoWebView.stopLoading();
            videoWebView.loadUrl("about:blank");
        }

        if (nowPlayingText != null) {
            nowPlayingText.setText("No song selected");
        }

        setStatus("Playback stopped.");
    }

    private void setStatus(String message) {

        if (statusText != null) {
            statusText.setText(message);
        }
    }

    // ------------------------------------------------
    // UI HELPERS
    // ------------------------------------------------

    private TextView text(
            String value,
            float size,
            int color,
            boolean bold
    ) {

        TextView view =
                new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);

        if (bold) {

            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
            );
        }

        return view;
    }

    private Button button(String label) {

        Button view =
                new Button(this);

        view.setText(label);
        view.setTextColor(Color.BLACK);
        view.setAllCaps(false);

        view.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        view.setBackgroundTintList(
                ColorStateList.valueOf(ACCENT)
        );

        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {

        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedWrap() {

        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
    }

    private int dp(float value) {

        return (int) (
                value
                        * getResources()
                        .getDisplayMetrics().density
                        + 0.5f
        );
    }

    private String formatDuration(long seconds) {

        return String.format(
                Locale.ROOT,
                "%d:%02d",
                seconds / 60,
                seconds % 60
        );
    }

    private String safe(String value) {

        return value == null ? "" : value.trim();
    }

    private void hideKeyboard() {

        // Keep the TV search field focused so its green
        // focus border remains visible after a phone search.
        if (tvMode) {
            return;
        }

        try {

            InputMethodManager manager =
                    (InputMethodManager)
                            getSystemService(
                                    Context.INPUT_METHOD_SERVICE
                            );

            View focus = getCurrentFocus();

            if (manager != null && focus != null) {

                manager.hideSoftInputFromWindow(
                        focus.getWindowToken(), 0
                );

                focus.clearFocus();
            }

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Could not hide keyboard",
                    e
            );
        }
    }

    // ------------------------------------------------
    // ACTIVITY LIFECYCLE
    // ------------------------------------------------

    @Override
    protected void onResume() {

        super.onResume();

        if (videoWebView != null) {
            videoWebView.onResume();
        }

        if (adView != null) {
            adView.resume();
        }

        if (tvMode) {

            startTvPhoneKeyboardReceiver();

            if (searchInput != null) {
                searchInput.post(() -> {
                    if (!activityDestroyed
                            && searchInput != null) {
                        searchInput.requestFocus();
                    }
                });
            }
        }
    }

    @Override
    protected void onPause() {

        // Stop the local receiver when Stage 2 is no
        // longer the active TV screen.
        stopTvPhoneKeyboardReceiver();

        // Stop microphone capture when leaving Stage 2.
        // Tap the microphone button again after returning.
        stopStage2Microphone(false);

        if (adView != null) {
            adView.pause();
        }

        if (videoWebView != null) {

            videoWebView.evaluateJavascript(
                    "if(window.player){player.pauseVideo();}",
                    null
            );

            videoWebView.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        activityDestroyed = true;

        stopTvPhoneKeyboardReceiver();

        if (microphoneMonitor != null) {

            microphoneMonitor.stop();
            microphoneMonitor = null;
        }

        if (adView != null) {

            adView.destroy();
            adView = null;
        }

        if (videoWebView != null) {

            videoWebView.stopLoading();

            videoWebView.removeJavascriptInterface(
                    "DonutBridge"
            );

            videoWebView.loadUrl("about:blank");
            videoWebView.removeAllViews();
            videoWebView.destroy();

            videoWebView = null;
        }

        mainHandler.removeCallbacksAndMessages(null);

        super.onDestroy();
    }

    // ------------------------------------------------
    // NEWPIPE DOWNLOADER
    // ------------------------------------------------

    private static final class AndroidDownloader
            extends Downloader {

        @Override
        public Response execute(Request request)
                throws IOException, ReCaptchaException {

            HttpURLConnection connection = null;

            try {

                connection = (HttpURLConnection)
                        new URL(request.url())
                                .openConnection();

                connection.setRequestMethod(
                        request.httpMethod()
                );

                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);

                for (Map.Entry<String, List<String>> header
                        : request.headers().entrySet()) {

                    if (header.getKey() == null
                            || header.getValue() == null) {
                        continue;
                    }

                    for (String value : header.getValue()) {

                        if (value != null) {

                            connection.addRequestProperty(
                                    header.getKey(),
                                    value
                            );
                        }
                    }
                }

                byte[] data =
                        request.dataToSend();

                if (data != null && data.length > 0) {

                    connection.setDoOutput(true);

                    try (java.io.OutputStream output =
                                 connection.getOutputStream()) {

                        output.write(data);
                    }
                }

                int code =
                        connection.getResponseCode();

                InputStream stream =
                        code >= 400
                                ? connection.getErrorStream()
                                : connection.getInputStream();

                String body =
                        stream == null
                                ? ""
                                : readStream(stream);

                return new Response(
                        code,
                        connection.getResponseMessage(),
                        connection.getHeaderFields(),
                        body,
                        connection.getURL().toString()
                );

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private static String readStream(
                InputStream input
        ) throws IOException {

            try (
                    InputStream in = input;
                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream()
            ) {

                byte[] buffer = new byte[8192];

                int count;

                while ((count = in.read(buffer)) != -1) {

                    output.write(
                            buffer, 0, count
                    );
                }

                return output.toString("UTF-8");
            }
        }
    }
}
