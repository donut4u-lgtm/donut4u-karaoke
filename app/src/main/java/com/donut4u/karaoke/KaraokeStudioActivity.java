package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout resultsContainer;
    private EditText searchInput;
    private TextView statusText;
    private TextView nowPlayingText;
    private TextView lyricsText;
    private ProgressBar progressBar;
    private WebView videoWebView;
    private AdView adView;

    private boolean extractorInitialized;
    private boolean searching;
    private boolean pageReady;
    private boolean activityDestroyed;

    private String currentTitle = "";
    private String currentArtist = "";
    private String currentVideoUrl = "";
    private String currentVideoId = "";

    private final List<SongResult> currentResults = new ArrayList<>();

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

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

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
            setStatus("Song search could not initialize. Check your connection.");
        }

        try {
            MobileAds.initialize(this, status -> { });
            if (adView != null) {
                adView.loadAd(new AdRequest.Builder().build());
            }
        } catch (Exception e) {
            Log.e(TAG, "AdMob initialization failed", e);
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

        TextView title = text("DONUT4U KARAOKE", 25, ACCENT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text(
                "Find a song. Play. Sing.", 15, MUTED, false
        );
        subtitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.bottomMargin = dp(14);
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

        Button searchButton = button("SEARCH SONGS");
        LinearLayout.LayoutParams searchParams = matchWrap();
        searchParams.topMargin = dp(8);
        root.addView(searchButton, searchParams);
        searchButton.setOnClickListener(v -> searchSongs());

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, matchWrap());

        statusText = text(
                "Enter a song title or artist to begin.",
                14, MUTED, false
        );

        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        root.addView(statusText, statusParams);

        addSectionTitle("ONLINE SONG RESULTS");

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, matchWrap());

        addSectionTitle("KARAOKE PLAYER");

        nowPlayingText = text("No song selected", 16, WHITE, true);
        root.addView(nowPlayingText, matchWrap());

        videoWebView = new WebView(this);
        videoWebView.setBackgroundColor(Color.BLACK);
        videoWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = videoWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(videoWebView, true);

        videoWebView.addJavascriptInterface(new PlayerBridge(), "DonutBridge");

        videoWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, "YouTube: " + message.message());
                return true;
            }
        });

        videoWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(
                    WebView view, String url, android.graphics.Bitmap favicon
            ) {
                super.onPageStarted(view, url, favicon);
                pageReady = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (url == null || "about:blank".equals(url)) {
                    pageReady = false;
                }

                // The actual YouTube player-ready callback sets pageReady.
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error
            ) {
                super.onReceivedError(view, request, error);

                if (request.isForMainFrame()) {
                    setStatus("Player page failed to load. Check your internet.");
                    Log.e(TAG, "WebView error: " + error.getDescription());
                }
            }
        });

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(220)
                );
        videoParams.topMargin = dp(8);
        root.addView(videoWebView, videoParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        Button playButton = button("PLAY");
        Button pauseButton = button("PAUSE");
        Button stopButton = button("STOP");

        controls.addView(playButton, weightedWrap());
        controls.addView(pauseButton, weightedWrap());
        controls.addView(stopButton, weightedWrap());
        root.addView(controls, matchWrap());

        playButton.setOnClickListener(v -> {
            if (currentVideoId.isEmpty()) {
                Toast.makeText(
                        this, "Choose a song first.", Toast.LENGTH_SHORT
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
                setStatus("Player is still loading. Please wait.");
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

        TextView donationText = text(
                "Help keep running and growing DONATE",
                15, MUTED, false
        );
        root.addView(donationText, matchWrap());

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
                                "Please use the official Donut4U Karaoke " +
                                "Questions and Suggestions channel for help."
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
            intent.setClassName(this, "com.donut4u.karaoke.AuthActivity");
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
            );
            startActivity(intent);
            finish();
        });

        TextView adLabel = text("Advertisement", 11, MUTED, false);
        adLabel.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams adLabelParams = matchWrap();
        adLabelParams.topMargin = dp(12);
        root.addView(adLabel, adLabelParams);

        adView = new AdView(this);
        adView.setAdSize(AdSize.BANNER);
        adView.setAdUnitId(AD_UNIT_ID);
        root.addView(adView, matchWrap());
    }

    private final class PlayerBridge {
        @JavascriptInterface
        public void onReady() {
            mainHandler.post(() -> {
                if (activityDestroyed) return;
                pageReady = true;
                setStatus("YouTube player ready. Tap PLAY if needed.");
            });
        }

        @JavascriptInterface
        public void onError(String code) {
            mainHandler.post(() -> {
                if (activityDestroyed) return;
                pageReady = false;
                setStatus(
                        "YouTube could not play this video (error "
                                + code + "). Try another result."
                );
                Log.e(TAG, "YouTube player error: " + code);
            });
        }

        @JavascriptInterface
        public void onState(String state) {
            mainHandler.post(() -> {
                if (activityDestroyed) return;

                if ("1".equals(state)) {
                    setStatus("Playing: " + currentTitle);
                } else if ("2".equals(state)) {
                    setStatus("Playback paused.");
                } else if ("0".equals(state)) {
                    setStatus("Song finished.");
                }
            });
        }
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
            setStatus("Search is not initialized. Restart the app and retry.");
            return;
        }

        searching = true;
        progressBar.setVisibility(View.VISIBLE);
        resultsContainer.removeAllViews();
        currentResults.clear();
        setStatus("Searching for songs…");

        new Thread(() -> {
            List<SongResult> found = new ArrayList<>();
            String failure = null;

            try {
                StreamingService service = NewPipe.getService("YouTube");
                SearchQueryHandler handler =
                        service.getSearchQHFactory().fromQuery(query);
                SearchExtractor extractor =
                        service.getSearchExtractor(handler);

                extractor.fetchPage();
                SearchInfo info = SearchInfo.getInfo(extractor);

                for (org.schabi.newpipe.extractor.InfoItem item
                        : info.getRelatedItems()) {
                    if (!(item instanceof StreamInfoItem)) continue;

                    StreamInfoItem stream = (StreamInfoItem) item;
                    String songTitle = safe(stream.getName());
                    String songUrl = safe(stream.getUrl());
                    String uploader = safe(stream.getUploaderName());

                    if (songTitle.isEmpty() || songUrl.isEmpty()) continue;

                    found.add(new SongResult(
                            songTitle,
                            uploader,
                            songUrl,
                            stream.getDuration(),
                            scoreResult(songTitle, uploader, query)
                    ));
                }

                Collections.sort(
                        found, (a, b) -> Integer.compare(b.score, a.score)
                );
            } catch (Exception e) {
                failure = e.getMessage();
                Log.e(TAG, "YouTube search failed", e);
            }

            final List<SongResult> results = found;
            final String error = failure;

            mainHandler.post(() -> {
                searching = false;
                progressBar.setVisibility(View.GONE);

                if (results.isEmpty()) {
                    setStatus(
                            error == null
                                    ? "No results found. Try another search."
                                    : "Search failed: " + error
                    );
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

    private int scoreResult(String title, String uploader, String query) {
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

        for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (word.length() > 2 && content.contains(word)) score += 3;
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
        card.addView(metadata, metaParams);

        Button playButton = button("PLAY THIS SONG");
        LinearLayout.LayoutParams playParams = matchWrap();
        playParams.topMargin = dp(8);
        card.addView(playButton, playParams);

        playButton.setOnClickListener(v ->
                loadAndPlay(result.url, result.title, result.uploader)
        );
    }

    private void loadAndPlay(String url, String title, String artist) {
        String videoId = extractYouTubeVideoId(url);

        if (videoId.isEmpty()) {
            setStatus("Invalid YouTube link. Choose another result.");
            return;
        }

        currentTitle = safe(title);
        currentArtist = safe(artist);
        currentVideoUrl = safe(url);
        currentVideoId = videoId;
        pageReady = false;

        nowPlayingText.setText(
                currentTitle
                        + (currentArtist.isEmpty() ? "" : "\n" + currentArtist)
        );
        lyricsText.setText("Looking for lyrics…");
        setStatus("Opening YouTube player…");

        String html =
                "<!DOCTYPE html><html><head>" +
                "<meta name='viewport' content='width=device-width," +
                "initial-scale=1,maximum-scale=1'>" +
                "<meta name='referrer' content='strict-origin-when-cross-origin'>" +
                "<style>html,body,#player{margin:0;padding:0;width:100%;" +
                "height:100%;background:#000;overflow:hidden}</style>" +
                "</head><body><div id='player'></div><script>" +
                "var player;" +
                "function onYouTubeIframeAPIReady(){" +
                "player=new YT.Player('player',{" +
                "width:'100%',height:'100%'," +
                "videoId:'" + videoId + "'," +
                "playerVars:{autoplay:1,playsinline:1,controls:1,rel:0," +
                "origin:'https://donut4u-karaoke.onrender.com'}," +
                "events:{" +
                "onReady:function(e){DonutBridge.onReady();e.target.playVideo();}," +
                "onStateChange:function(e){DonutBridge.onState(String(e.data));}," +
                "onError:function(e){DonutBridge.onError(String(e.data));}" +
                "}" +
                "});" +
                "}" +
                "</script>" +
                "<script src='https://www.youtube.com/iframe_api'></script>" +
                "</body></html>";

        videoWebView.stopLoading();
        videoWebView.loadDataWithBaseURL(
                "https://donut4u-karaoke.onrender.com/",
                html,
                "text/html",
                "UTF-8",
                null
        );

        lookupLyrics(currentTitle, currentArtist);
    }

    private String extractYouTubeVideoId(String url) {
        if (url == null || url.trim().isEmpty()) return "";

        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return "";

            if (host.equalsIgnoreCase("youtu.be")) {
                String path = uri.getPath();
                if (path != null && path.length() > 1) {
                    return path.substring(1).split("/")[0];
                }
            }

            if (host.toLowerCase(Locale.ROOT).contains("youtube.com")) {
                String id = uri.getQueryParameter("v");
                if (id != null && !id.trim().isEmpty()) return id.trim();

                String path = uri.getPath();
                if (path != null) {
                    String[] parts = path.split("/");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if ("embed".equals(parts[i])
                                || "shorts".equals(parts[i])) {
                            return parts[i + 1];
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not parse YouTube URL", e);
        }

        return "";
    }

    private void lookupLyrics(String title, String artist) {
        new Thread(() -> {
            String lyrics = null;

            try {
                String endpoint =
                        "https://lrclib.net/api/search?track_name=" +
                        URLEncoder.encode(safe(title), "UTF-8") +
                        "&artist_name=" +
                        URLEncoder.encode(safe(artist), "UTF-8");

                JSONArray results = new JSONArray(httpGet(endpoint));

                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;

                    String plain = item.optString("plainLyrics", "");
                    String synced = item.optString("syncedLyrics", "");

                    if (!plain.trim().isEmpty()) {
                        lyrics = plain;
                        break;
                    }

                    if (!synced.trim().isEmpty()) lyrics = synced;
                }
            } catch (Exception e) {
                Log.w(TAG, "Lyrics lookup failed", e);
            }

            final String finalLyrics = lyrics;

            mainHandler.post(() -> {
                if (activityDestroyed || lyricsText == null) return;

                if (finalLyrics == null || finalLyrics.trim().isEmpty()) {
                    lyricsText.setText(
                            "Lyrics are not available for this song."
                    );
                } else {
                    lyricsText.setText(finalLyrics);
                }
            });
        }).start();
    }

    private String httpGet(String address) throws IOException {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty(
                    "User-Agent", "Donut4U-Karaoke/1.0"
            );
            connection.setRequestProperty("Accept", "application/json");

            int code = connection.getResponseCode();
            InputStream stream = code >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            String body = stream == null ? "" : readFully(stream);

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }

            return body;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readFully(InputStream input) throws IOException {
        try (InputStream in = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = in.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            return output.toString("UTF-8");
        }
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

        if (lyricsText != null) {
            lyricsText.setText("Lyrics will appear here when available.");
        }

        setStatus("Playback stopped.");
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    private TextView text(
            String value, float size, int color, boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);

        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextColor(Color.BLACK);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
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
                value * getResources().getDisplayMetrics().density + 0.5f
        );
    }

    private String formatDuration(long seconds) {
        return String.format(
                Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE
                    );
            View focus = getCurrentFocus();

            if (manager != null && focus != null) {
                manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                focus.clearFocus();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not hide keyboard", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoWebView != null) videoWebView.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();

        if (videoWebView != null) {
            videoWebView.evaluateJavascript(
                    "if(window.player){player.pauseVideo();}", null
            );
            videoWebView.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;

        if (adView != null) {
            adView.destroy();
            adView = null;
        }

        if (videoWebView != null) {
            videoWebView.stopLoading();
            videoWebView.removeJavascriptInterface("DonutBridge");
            videoWebView.loadUrl("about:blank");
            videoWebView.removeAllViews();
            videoWebView.destroy();
            videoWebView = null;
        }

        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static final class AndroidDownloader extends Downloader {

        @Override
        public Response execute(Request request)
                throws IOException, ReCaptchaException {

            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection)
                        new URL(request.url()).openConnection();

                connection.setRequestMethod(request.httpMethod());
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);

                for (Map.Entry<String, List<String>> header
                        : request.headers().entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) {
                        continue;
                    }

                    for (String value : header.getValue()) {
                        if (value != null) {
                            connection.addRequestProperty(
                                    header.getKey(), value
                            );
                        }
                    }
                }

                byte[] data = request.dataToSend();

                if (data != null && data.length > 0) {
                    connection.setDoOutput(true);
                    try (java.io.OutputStream output =
                                 connection.getOutputStream()) {
                        output.write(data);
                    }
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream();

                String body = stream == null ? "" : readStream(stream);

                return new Response(
                        code,
                        connection.getResponseMessage(),
                        connection.getHeaderFields(),
                        body,
                        connection.getURL().toString()
                );
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private static String readStream(InputStream input)
                throws IOException {
            try (InputStream in = input;
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;

                while ((count = in.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }

                return output.toString("UTF-8");
            }
        }
    }
}
