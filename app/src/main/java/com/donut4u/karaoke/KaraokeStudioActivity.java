
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class KaraokeStudioActivity extends Activity {

    private static final String TAG = "Donut4UKaraoke";

    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/7288125709";

    private static final int BG = Color.rgb(18, 18, 24);
    private static final int PANEL = Color.rgb(35, 35, 46);
    private static final int ACCENT = Color.rgb(255, 185, 70);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(195, 195, 205);

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout resultsContainer;
    private EditText searchInput;
    private TextView statusText;
    private TextView nowPlayingText;
    private TextView lyricsText;
    private ProgressBar progressBar;
    private Button playButton;
    private Button pauseButton;
    private Button stopButton;

    // Embedded YouTube player replaces direct stream extraction.
    private WebView videoWebView;
    private AdView adView;

    private final List<SongResult> currentResults =
            new ArrayList<>();

    private String currentTitle = "";
    private String currentArtist = "";
    private String currentVideoUrl = "";
    private String currentVideoId = "";

    private boolean extractorInitialized = false;
    private boolean searching = false;
    private boolean loadingPlayback = false;
    private boolean pageReady = false;

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
        initializeAds();

        try {
            NewPipe.init(
                    new AndroidDownloader(),
                    Localization.DEFAULT,
                    ContentCountry.DEFAULT
            );

            extractorInitialized = true;

        } catch (Exception e) {
            Log.e(TAG, "NewPipe initialization failed", e);
            setStatus("Search initialization failed.");
        }
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(BG);

        scroll.addView(root);
        setContentView(scroll);

        TextView title = text(
                "DONUT4U KARAOKE", 25, ACCENT, true
        );
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text(
                "Find a song. Play. Sing.", 15, MUTED, false
        );
        subtitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.bottomMargin = dp(18);
        root.addView(subtitle, subtitleParams);

        addSectionTitle("FIND MY SONG");

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(WHITE);
        searchInput.setHintTextColor(MUTED);
        searchInput.setHint("karaoke - air supply");
        searchInput.setTextSize(16);
        searchInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchInput.setBackgroundColor(PANEL);
        root.addView(searchInput, matchWrap());

        TextView searchHelp = text(
                "Search for karaoke, instrumental, minus-one, or sing-along tracks.",
                13, MUTED, false
        );

        LinearLayout.LayoutParams helpParams = matchWrap();
        helpParams.topMargin = dp(5);
        helpParams.bottomMargin = dp(8);
        root.addView(searchHelp, helpParams);

        Button searchButton = button("SEARCH SONGS");
        root.addView(searchButton, matchWrap());
        searchButton.setOnClickListener(v -> searchSongs());

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);

        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(8);
        root.addView(progressBar, progressParams);

        statusText = text(
                "Enter a song title or artist to begin.",
                14, MUTED, false
        );

        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        statusParams.bottomMargin = dp(12);
        root.addView(statusText, statusParams);

        addSectionTitle("ONLINE SONG RESULTS");

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, matchWrap());

        addSectionTitle("KARAOKE PLAYER");

        nowPlayingText = text(
                "No song selected", 16, WHITE, true
        );
        root.addView(nowPlayingText, matchWrap());

        // YouTube video is displayed inside this WebView.
        videoWebView = new WebView(this);
        videoWebView.setBackgroundColor(Color.BLACK);
        videoWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings webSettings = videoWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setSupportMultipleWindows(false);

        videoWebView.setWebChromeClient(new WebChromeClient());

        videoWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                super.onPageFinished(view, url);

                if (url != null && url.startsWith("about:blank")) {
                    return;
                }

                pageReady = true;

                if (!currentVideoId.isEmpty()) {
                    setStatus(
                            "YouTube player loaded. If playback has not started, tap PLAY."
                    );
                }
            }
        });

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(220)
                );
        videoParams.topMargin = dp(10);
        videoParams.bottomMargin = dp(6);
        root.addView(videoWebView, videoParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, dp(10));

        playButton = button("PLAY");
        pauseButton = button("PAUSE");
        stopButton = button("STOP");

        controls.addView(playButton, weightedWrap());
        controls.addView(pauseButton, weightedWrap());
        controls.addView(stopButton, weightedWrap());
        root.addView(controls, matchWrap());

        playButton.setOnClickListener(v -> {
            if (currentVideoId.isEmpty()) {
                Toast.makeText(
                        this,
                        "Choose a song first.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            if (videoWebView != null && pageReady) {
                videoWebView.evaluateJavascript(
                        "if(window.player){player.playVideo();}",
                        null
                );
                setStatus("Playing in the embedded YouTube player.");
            } else {
                loadAndPlay(
                        currentVideoUrl,
                        currentTitle,
                        currentArtist
                );
            }
        });

        pauseButton.setOnClickListener(v -> {
            if (videoWebView != null && pageReady) {
                videoWebView.evaluateJavascript(
                        "if(window.player){player.pauseVideo();}",
                        null
                );
                setStatus("Playback paused.");
            }
        });

        stopButton.setOnClickListener(v -> stopPlayback());

        addSectionTitle("LYRICS");

        lyricsText = text(
                "Lyrics will appear here when available.",
                15, WHITE, false
        );
        lyricsText.setPadding(dp(12), dp(12), dp(12), dp(12));
        lyricsText.setBackgroundColor(PANEL);
        root.addView(lyricsText, matchWrap());

        addSectionTitle("SUPPORT DONUT4U KARAOKE");

        TextView donateMessage = text(
                "Help keep running and growing DONATE",
                15, MUTED, false
        );
        root.addView(donateMessage, matchWrap());

        Button donateButton = button("DONATE");
        root.addView(donateButton, matchWrap());

        donateButton.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Support Donut4U Karaoke")
                        .setMessage(
                                "Thank you for supporting Donut4U Karaoke. " +
                                "Please use the official donation details " +
                                "provided by the app owner."
                        )
                        .setPositiveButton("OK", null)
                        .show()
        );

        Button questionButton = button("QUESTION AND ANSWER");
        root.addView(questionButton, matchWrap());

        questionButton.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Question and Answer")
                        .setMessage(
                                "For help, please use the official " +
                                "Donut4U Karaoke Questions and Suggestions channel."
                        )
                        .setPositiveButton("OK", null)
                        .show()
        );

        Button backButton = button("BACK TO STAGE");
        root.addView(backButton, matchWrap());
        backButton.setOnClickListener(v -> finish());

        Button logoutButton = button("LOG OUT");
        root.addView(logoutButton, matchWrap());

        logoutButton.setOnClickListener(v -> {
            stopPlayback();
            FirebaseAuth.getInstance().signOut();

            Intent intent = new Intent();
            intent.setClassName(
                    this,
                    "com.donut4u.karaoke.AuthActivity"
            );
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            );
            startActivity(intent);
            finish();
        });

        View bottomSpace = new View(this);
        root.addView(
                bottomSpace,
                new LinearLayout.LayoutParams(1, dp(12))
        );

        TextView adLabel = text(
                "Advertisement", 11, MUTED, false
        );
        adLabel.setGravity(Gravity.CENTER);
        root.addView(adLabel, matchWrap());

        adView = new AdView(this);
        adView.setAdSize(AdSize.BANNER);
        adView.setAdUnitId(AD_UNIT_ID);
        root.addView(adView, matchWrap());
    }

    private void addSectionTitle(String label) {
        TextView heading = text(label, 17, ACCENT, true);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(18);
        params.bottomMargin = dp(8);
        root.addView(heading, params);
    }

    private void searchSongs() {
        if (searching) return;

        String query = searchInput.getText().toString().trim();

        if (query.isEmpty()) {
            searchInput.setError("Enter a song title or artist");
            return;
        }

        hideKeyboard();

        if (!extractorInitialized) {
            setStatus("Search is not ready. Please restart the app.");
            return;
        }

        searching = true;
        progressBar.setVisibility(View.VISIBLE);
        resultsContainer.removeAllViews();
        currentResults.clear();
        setStatus("Searching YouTube for song results…");

        new Thread(() -> {
            List<SongResult> found = new ArrayList<>();
            String error = null;

            try {
                StreamingService service =
                        NewPipe.getService("YouTube");

                SearchQueryHandler handler =
                        service.getSearchQHFactory().fromQuery(query);

                SearchExtractor extractor =
                        service.getSearchExtractor(handler);

                extractor.fetchPage();
                SearchInfo info = SearchInfo.getInfo(extractor);

                for (org.schabi.newpipe.extractor.InfoItem item
                        : info.getRelatedItems()) {
                    if (!(item instanceof StreamInfoItem)) continue;

                    StreamInfoItem streamItem = (StreamInfoItem) item;
                    String title = safe(streamItem.getName());
                    String url = safe(streamItem.getUrl());
                    String uploader = safe(streamItem.getUploaderName());

                    if (title.isEmpty() || url.isEmpty()) continue;

                    found.add(new SongResult(
                            title,
                            uploader,
                            url,
                            streamItem.getDuration(),
                            scoreResult(title, uploader, query)
                    ));
                }

                Collections.sort(
                        found,
                        (a, b) -> Integer.compare(b.score, a.score)
                );

            } catch (Exception e) {
                error = e.getMessage();
                Log.e(TAG, "YouTube search failed", e);
            }

            final List<SongResult> results = found;
            final String failure = error;

            mainHandler.post(() -> {
                searching = false;
                progressBar.setVisibility(View.GONE);

                if (results.isEmpty()) {
                    setStatus(
                            "No results loaded. The source may be " +
                            "temporarily unavailable. Try again later."
                    );

                    if (failure != null && !failure.trim().isEmpty()) {
                        Log.e(TAG, "Search detail: " + failure);
                    }
                    return;
                }

                currentResults.addAll(results);
                setStatus(results.size() + " song results found.");

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
                (title + " " + uploader).toLowerCase(Locale.ROOT);
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

        String[] words =
                query.toLowerCase(Locale.ROOT).split("\\s+");

        for (String word : words) {
            if (word.length() > 2 && content.contains(word)) {
                score += 3;
            }
        }

        return score;
    }

    private void addSongResult(SongResult result) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = dp(8);
        resultsContainer.addView(card, cardParams);

        TextView songTitle = text(result.title, 16, WHITE, true);
        card.addView(songTitle, matchWrap());

        String details = result.uploader;

        if (result.durationSeconds > 0) {
            details += " • " + formatDuration(result.durationSeconds);
        }

        TextView metadata = text(details, 12, MUTED, false);
        LinearLayout.LayoutParams metaParams = matchWrap();
        metaParams.topMargin = dp(4);
