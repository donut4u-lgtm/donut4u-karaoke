package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KaraokeStudioActivity extends Activity {

    private static final String API_BASE =
            "https://donut4u-karaoke.onrender.com";

    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/7288125709";

    private static final int BG = Color.rgb(18, 22, 20);
    private static final int PANEL = Color.rgb(35, 42, 38);
    private static final int GREEN = Color.rgb(0, 190, 95);
    private static final int WHITE = Color.WHITE;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private LinearLayout root;
    private LinearLayout resultsContainer;
    private LinearLayout lyricsContainer;

    private EditText searchInput;
    private TextView searchStatus;
    private TextView selectedSong;
    private TextView playbackStatus;
    private TextView lyricsStatus;

    private Button searchButton;
    private Button playButton;
    private Button makeKaraokeButton;

    private WebView webView;
    private AdView adView;
    private MediaPlayer mediaPlayer;

    private Uri selectedAudioUri;
    private String selectedSourceUrl = "";
    private String currentJobId = "";
    private String currentJobStatus = "";

    private boolean processingKaraoke = false;
    private boolean destroyed = false;

    private int pollAttempts = 0;
    private static final int MAX_POLL_ATTEMPTS = 240;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(22));

        scroll.addView(content);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(text(
                "DONUT4U KARAOKE",
                25, GREEN, true), matchWrap());

        TextView subtitle = text(
                "Find a song. Play. Sing.",
                15, WHITE, false);

        subtitle.setPadding(0, dp(6), 0, dp(14));
        content.addView(subtitle, matchWrap());

        section(content, "1. SEARCH THE INTERNET");

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Song title, artist, or music URL");
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setTextColor(WHITE);
        searchInput.setTextSize(16);
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchInput.setBackgroundColor(PANEL);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);

        content.addView(searchInput, matchWrap());

        searchButton = button("SEARCH SONGS ONLINE");
        content.addView(searchButton, matchWrap());
        searchButton.setOnClickListener(v -> searchInternet());

        searchInput.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        searchInternet();
                        return true;
                    }
                    return false;
                });

        searchStatus = text(
                "Search for a song or artist.",
                14, WHITE, false);

        searchStatus.setPadding(0, dp(10), 0, dp(8));
        content.addView(searchStatus, matchWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsContainer, matchWrap());

        section(content, "2. PLAY A RESULT");

        TextView webInfo = text(
                "Open a search result here when the website supports "
                        + "in-app playback. Some services may require "
                        + "their own app or a signed-in account.",
                13, WHITE, false);

        content.addView(webInfo, matchWrap());

        Button openSourceButton =
                button("OPEN SELECTED SONG WEBSITE");

        content.addView(openSourceButton, matchWrap());
        openSourceButton.setOnClickListener(
                v -> openSelectedSource());

        Button closeWebButton = button("CLOSE WEB PLAYER");
        content.addView(closeWebButton, matchWrap());
        closeWebButton.setOnClickListener(v -> closeWebPlayer());

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        webView.setBackgroundColor(Color.BLACK);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        LinearLayout.LayoutParams webParams = matchWrap();
        webParams.topMargin = dp(8);
        webView.setLayoutParams(webParams);
        webView.setMinimumHeight(dp(220));
        content.addView(webView);

        section(content, "3. MAKE KARAOKE");

        selectedSong = text(
                "No song selected.",
                14, WHITE, false);

        selectedSong.setPadding(0, dp(4), 0, dp(8));
        content.addView(selectedSong, matchWrap());

        TextView processingInfo = text(
                "Choose a result that contains accessible audio. "
                        + "The server will attempt to create an instrumental "
                        + "track and generate timed lyrics. Processing time "
                        + "depends on the audio length and server availability.",
                13, WHITE, false);

        content.addView(processingInfo, matchWrap());

        makeKaraokeButton =
                button("MAKE KARAOKE FROM SELECTED RESULT");

        content.addView(makeKaraokeButton, matchWrap());
        makeKaraokeButton.setOnClickListener(v -> makeKaraoke());

        Button chooseAudioButton = button("CHOOSE AUDIO FILE");
        content.addView(chooseAudioButton, matchWrap());
        chooseAudioButton.setOnClickListener(v -> chooseAudio());

        playbackStatus = text("Ready.", 14, WHITE, false);
        playbackStatus.setPadding(0, dp(10), 0, dp(4));
        content.addView(playbackStatus, matchWrap());

        playButton = button("PLAY INSTRUMENTAL");
        content.addView(playButton, matchWrap());
        playButton.setOnClickListener(v -> togglePlayback());

        Button stopButton = button("STOP AUDIO");
        content.addView(stopButton, matchWrap());
        stopButton.setOnClickListener(v -> stopPlayback());

        section(content, "4. LYRICS");

        lyricsStatus = text(
                "Lyrics will appear here when available.",
                14, WHITE, false);

        content.addView(lyricsStatus, matchWrap());

        lyricsContainer = new LinearLayout(this);
        lyricsContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsContainer.setPadding(
                dp(12), dp(12), dp(12), dp(12));
        lyricsContainer.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams lyricsParams = matchWrap();
        lyricsParams.topMargin = dp(8);
        content.addView(lyricsContainer, lyricsParams);

        Button backButton = button("BACK");
        content.addView(backButton, matchWrap());
        backButton.setOnClickListener(v -> finish());

        TextView footer = text(
                "DONUT4U KARAOKE — Your songs, your karaoke.",
                12, Color.LTGRAY, false);

        footer.setPadding(0, dp(16), 0, dp(4));
        content.addView(footer, matchWrap());

        LinearLayout adContainer = new LinearLayout(this);
        adContainer.setGravity(Gravity.CENTER);
        adContainer.setBackgroundColor(BG);

        root.addView(adContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        initializeAds(adContainer);
    }

    private void searchInternet() {
        String query = searchInput.getText().toString().trim();

        if (query.isEmpty()) {
            searchInput.setError("Enter a song title or artist");
            return;
        }

        hideKeyboard();

        try {
            String encoded = URLEncoder.encode(
                    query, StandardCharsets.UTF_8.name());

            selectedSourceUrl =
                    "https://www.google.com/search?q=" + encoded;

            selectedAudioUri = null;

            selectedSong.setText(
                    "Google search: " + query
                            + "\nChoose a suitable music source, then "
                            + "use OPEN SELECTED SONG WEBSITE.");

            searchStatus.setText(
                    "Opening Google Search results...");

            resultsContainer.removeAllViews();

            searchButton.setEnabled(true);

            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(selectedSourceUrl);

            playbackStatus.setText(
                    "Google Search is open below. Select a suitable "
                            + "music source. Karaoke conversion works "
                            + "only when the source provides audio "
                            + "the server can access.");

        } catch (Exception e) {
            searchButton.setEnabled(true);
            searchStatus.setText(
                    "Unable to open Google Search: "
                            + (e.getMessage() == null
                            ? "Unknown error" : e.getMessage()));
        }
    }

    private void displayResults(JSONArray results) {
        resultsContainer.removeAllViews();

        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.optJSONObject(i);

            if (result == null) {
                continue;
            }

            String title = firstNonEmpty(
                    result.optString("title", ""),
                    result.optString("name", ""),
                    "Untitled result");

            String url = firstNonEmpty(
                    result.optString("url", ""),
                    result.optString("link", ""),
                    result.optString("href", ""));

            String description = firstNonEmpty(
                    result.optString("snippet", ""),
                    result.optString("description", ""),
                    result.optString("artist", ""));

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(PANEL);

            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = dp(9);
            resultsContainer.addView(card, cardParams);

            card.addView(text(
                    title, 17, GREEN, true), matchWrap());

            if (!description.isEmpty()) {
                TextView descriptionView = text(
                        description, 13, WHITE, false);

                descriptionView.setPadding(
                        0, dp(5), 0, dp(5));

                card.addView(descriptionView, matchWrap());
            }

            if (!url.isEmpty()) {
                TextView urlView = text(
                        url, 12, Color.LTGRAY, false);

                urlView.setPadding(0, dp(4), 0, dp(4));
                card.addView(urlView, matchWrap());

                Button playResultButton =
                        button("OPEN AND PLAY THIS RESULT");

                card.addView(playResultButton, matchWrap());

                playResultButton.setOnClickListener(v -> {
                    selectedSourceUrl = url;
                    selectedAudioUri = null;

                    selectedSong.setText(
                            "Selected result:\n" + title
                                    + "\n" + url);

                    openSelectedSource();
                });

                Button karaokeButton =
                        button("MAKE KARAOKE FROM THIS RESULT");

                card.addView(karaokeButton, matchWrap());

                karaokeButton.setOnClickListener(v -> {
                    selectedSourceUrl = url;
                    selectedAudioUri = null;

                    selectedSong.setText(
                            "Selected for karaoke:\n" + title
                                    + "\n" + url);

                    makeKaraoke();
                });

            } else {
                card.addView(text(
                        "This result does not provide a usable URL.",
                        12, Color.LTGRAY, false), matchWrap());
            }
        }
    }

    private void openSelectedSource() {
        if (selectedSourceUrl == null
                || selectedSourceUrl.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Select a search result first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (!isHttpUrl(selectedSourceUrl)) {
            Toast.makeText(
                    this,
                    "This result does not contain a valid web URL.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(selectedSourceUrl);

        playbackStatus.setText(
                "Opening website. Playback depends on the source website.");
    }

    private void closeWebPlayer() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setVisibility(View.GONE);
        }
    }

    private void makeKaraoke() {
        if (processingKaraoke) {
            Toast.makeText(
                    this,
                    "Karaoke processing is already running.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedSourceUrl == null
                || selectedSourceUrl.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Select an internet result first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (!isHttpUrl(selectedSourceUrl)) {
            Toast.makeText(
                    this,
                    "The selected result is not a valid web URL.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        processingKaraoke = true;
        pollAttempts = 0;
        currentJobId = "";

        makeKaraokeButton.setEnabled(false);
        playbackStatus.setText(
                "Sending the selected URL to the karaoke server...");

        lyricsStatus.setText("Waiting for lyrics...");
        lyricsContainer.removeAllViews();

        final String sourceUrl = selectedSourceUrl;
        final String title = selectedSong.getText().toString();

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(API_BASE + "/v1/jobs/from-url");

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);

                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8");

                connection.setRequestProperty(
                        "Accept", "application/json");

                JSONObject payload = new JSONObject();
                payload.put("url", sourceUrl);
                payload.put("title", title);
                payload.put("artist", "");

                byte[] body = payload.toString().getBytes(
                        StandardCharsets.UTF_8);

                connection.setFixedLengthStreamingMode(body.length);

                try (OutputStream output =
                             connection.getOutputStream()) {
                    output.write(body);
                }

                int code = connection.getResponseCode();

                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                String response = readStream(stream);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Karaoke server HTTP " + code
                                    + responseSuffix(response));
                }

                JSONObject json = new JSONObject(response);

                String jobId = firstNonEmpty(
                        json.optString("job_id", ""),
                        json.optString("id", ""));

                if (jobId.isEmpty()) {
                    throw new Exception(
                            "The server did not return a job ID.");
                }

                mainHandler.post(() -> {
                    if (destroyed) {
                        return;
                    }

                    currentJobId = jobId;

                    playbackStatus.setText(
                            "Karaoke job created. Processing audio...");

                    lyricsStatus.setText(
                            "Generating instrumental and lyrics...");

                    pollJobStatus();
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Unable to start karaoke processing"
                        : e.getMessage();

                mainHandler.post(() -> {
                    if (destroyed) {
                        return;
                    }

                    processingKaraoke = false;
                    makeKaraokeButton.setEnabled(true);

                    playbackStatus.setText(
                            "Karaoke processing failed: " + message);
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void pollJobStatus() {
        if (destroyed || currentJobId.isEmpty()) {
            return;
        }

        if (pollAttempts >= MAX_POLL_ATTEMPTS) {
            processingKaraoke = false;
            makeKaraokeButton.setEnabled(true);

            playbackStatus.setText(
                    "Processing is taking too long. Check again later.");

            return;
        }

        pollAttempts++;

        final String jobId = currentJobId;

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(
                        API_BASE + "/v1/jobs/" + Uri.encode(jobId));

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);

                connection.setRequestProperty(
                        "Accept", "application/json");

                int code = connection.getResponseCode();

                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                String response = readStream(stream);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Job status HTTP " + code
                                    + responseSuffix(response));
                }

                JSONObject json = new JSONObject(response);

                String status = firstNonEmpty(
                        json.optString("status", ""),
                        json.optString("state", ""),
                        "processing");

                String message = firstNonEmpty(
                        json.optString("message", ""),
                        json.optString("error", ""),
                        status);

                mainHandler.post(() -> {
                    if (destroyed
                            || !jobId.equals(currentJobId)) {
                        return;
                    }

                    currentJobStatus = status;

                    playbackStatus.setText(
                            "Karaoke status: " + message);

                    String normalized =
                            status.toLowerCase(Locale.US);

                    if (normalized.equals("completed")
                            || normalized.equals("complete")
                            || normalized.equals("succeeded")
                            || normalized.equals("success")
                            || normalized.equals("done")) {

                        processingKaraoke = false;
                        makeKaraokeButton.setEnabled(true);

                        playbackStatus.setText(
                                "Karaoke is ready. Loading instrumental and lyrics...");

                        loadGeneratedLyrics(jobId);

                    } else if (normalized.equals("failed")
                            || normalized.equals("error")
                            || normalized.equals("cancelled")) {

                        processingKaraoke = false;
                        makeKaraokeButton.setEnabled(true);

                        playbackStatus.setText(
                                "Karaoke processing failed: " + message);

                    } else {
                        mainHandler.postDelayed(
                                this::pollJobStatus, 5000);
                    }
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Unable to check job status"
                        : e.getMessage();

                mainHandler.post(() -> {
                    if (destroyed
                            || !jobId.equals(currentJobId)) {
                        return;
                    }

                    playbackStatus.setText(
                            "Checking status: " + message);

                    mainHandler.postDelayed(
                            this::pollJobStatus, 8000);
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void loadGeneratedLyrics(String jobId) {
        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(
                        API_BASE + "/v1/jobs/"
                                + Uri.encode(jobId) + "/lyrics");

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);

                connection.setRequestProperty(
                        "Accept", "application/json");

                int code = connection.getResponseCode();

                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                String response = readStream(stream);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Lyrics HTTP " + code
                                    + responseSuffix(response));
                }

                JSONObject json = new JSONObject(response);

                JSONArray words = json.optJSONArray("words");
                JSONArray lines = json.optJSONArray("lines");

                String plainLyrics = firstNonEmpty(
                        json.optString("lyrics", ""),
                        json.optString("text", ""),
                        "");

                mainHandler.post(() -> {
                    if (destroyed
                            || !jobId.equals(currentJobId)) {
                        return;
                    }

                    displayLyrics(words, lines, plainLyrics);

                    playbackStatus.setText(
                            "Karaoke processing completed.");
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Lyrics are not available"
                        : e.getMessage();

                mainHandler.post(() -> {
                    if (destroyed
                            || !jobId.equals(currentJobId)) {
                        return;
                    }

                    lyricsStatus.setText(
                            "Lyrics could not be loaded: " + message);

                    loadInstrumental(jobId);
                });

                return;

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            mainHandler.post(() -> {
                if (!destroyed && jobId.equals(currentJobId)) {
                    loadInstrumental(jobId);
                }
            });
        });
    }

    private void displayLyrics(
            JSONArray words, JSONArray lines, String plainLyrics) {

        lyricsContainer.removeAllViews();

        if (words != null && words.length() > 0) {
            lyricsStatus.setText(
                    "Generated word-timed lyrics are available.");

            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.optJSONObject(i);

                if (word == null) {
                    continue;
                }

                String value = firstNonEmpty(
                        word.optString("text", ""),
                        word.optString("word", ""));

                if (!value.isEmpty()) {
                    lyricsContainer.addView(
                            text(value, 18, WHITE, false),
                            matchWrap());
                }
            }

        } else if (lines != null && lines.length() > 0) {
            lyricsStatus.setText("Generated lyrics:");

            for (int i = 0; i < lines.length(); i++) {
                Object item = lines.opt(i);
                String value;

                if (item instanceof JSONObject) {
                    JSONObject line = (JSONObject) item;

                    value = firstNonEmpty(
                            line.optString("text", ""),
                            line.optString("line", ""));
                } else {
                    value = String.valueOf(item);
                }

                if (!value.isEmpty()) {
                    TextView lineView = text(
                            value, 17, WHITE, false);

                    lineView.setPadding(
                            0, dp(4), 0, dp(4));

                    lyricsContainer.addView(
                            lineView, matchWrap());
                }
            }

        } else if (!plainLyrics.isEmpty()) {
            lyricsStatus.setText("Lyrics:");

            TextView lyricView = text(
                    plainLyrics, 17, WHITE, false);

            lyricsContainer.addView(
                    lyricView, matchWrap());

        } else {
            lyricsStatus.setText(
                    "No lyrics were returned for this job.");
        }
    }

    private void loadInstrumental(String jobId) {
        stopPlayback();

        try {
            String instrumentalUrl = API_BASE + "/v1/jobs/"
                    + Uri.encode(jobId) + "/instrumental";

            playbackStatus.setText(
                    "Instrumental ready. Preparing playback...");

            executor.execute(() -> {
                HttpURLConnection connection = null;

                try {
                    URL url = new URL(instrumentalUrl);

                    connection =
                            (HttpURLConnection) url.openConnection();

                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(60000);

                    int code = connection.getResponseCode();

                    if (code < 200 || code >= 300) {
                        String error = readStream(
                                connection.getErrorStream());

                        throw new Exception(
                                "Instrumental HTTP " + code
                                        + responseSuffix(error));
                    }

                    mainHandler.post(() -> {
                        if (destroyed
                                || !jobId.equals(currentJobId)) {
                            return;
                        }

                        playRemoteInstrumental(instrumentalUrl);
                    });

                } catch (Exception e) {
                    String message = e.getMessage() == null
                            ? "Instrumental is not ready"
                            : e.getMessage();

                    mainHandler.post(() -> {
                        if (destroyed
                                || !jobId.equals(currentJobId)) {
                            return;
                        }

                        playbackStatus.setText(
                                "Instrumental could not load: " + message);
                    });

                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });

        } catch (Exception e) {
            playbackStatus.setText(
                    "Unable to prepare the instrumental.");
        }
    }

    private void playRemoteInstrumental(String audioUrl) {
        stopPlayback();

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioUrl);

            mediaPlayer.setOnPreparedListener(player -> {
                if (destroyed) {
                    player.release();
                    return;
                }

                player.start();
                playButton.setText("PAUSE AUDIO");

                playbackStatus.setText(
                        "Playing instrumental. Sing along with the lyrics.");
            });

            mediaPlayer.setOnCompletionListener(player -> {
                stopPlayback();

                playbackStatus.setText(
                        "Instrumental playback finished.");
            });

            mediaPlayer.setOnErrorListener(
                    (player, what, extra) -> {
                        stopPlayback();

                        playbackStatus.setText(
                                "Unable to play the instrumental audio.");

                        return true;
                    });

            playbackStatus.setText(
                    "Loading instrumental audio...");

            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            stopPlayback();

            playbackStatus.setText(
                    "Unable to open instrumental audio.");
        }
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(intent, 4101);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to open audio picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 4101
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {

            stopPlayback();

            selectedAudioUri = data.getData();
            selectedSourceUrl = "";

            try {
                getContentResolver().takePersistableUriPermission(
                        selectedAudioUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }

            selectedSong.setText(
                    "Selected local audio:\n" + selectedAudioUri);

            playbackStatus.setText("Local audio ready.");
            playButton.setText("PLAY AUDIO");
        }
    }

    private void togglePlayback() {
        if (selectedAudioUri != null) {
            toggleLocalPlayback();
            return;
        }

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    playButton.setText("RESUME AUDIO");
                    playbackStatus.setText("Paused.");
                } else {
                    mediaPlayer.start();
                    playButton.setText("PAUSE AUDIO");
                    playbackStatus.setText("Playing.");
                }
            } catch (Exception e) {
                stopPlayback();

                playbackStatus.setText(
                        "Unable to continue playback.");
            }

            return;
        }

        if (!currentJobId.isEmpty()
                && (currentJobStatus.equalsIgnoreCase("completed")
                || currentJobStatus.equalsIgnoreCase("complete")
                || currentJobStatus.equalsIgnoreCase("succeeded")
                || currentJobStatus.equalsIgnoreCase("success")
                || currentJobStatus.equalsIgnoreCase("done"))) {

            loadInstrumental(currentJobId);
            return;
        }

        Toast.makeText(
                this,
                "Select an audio file or create a karaoke track first.",
                Toast.LENGTH_LONG).show();
    }

    private void toggleLocalPlayback() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    playButton.setText("RESUME AUDIO");
                    playbackStatus.setText("Paused.");
                } else {
                    mediaPlayer.start();
                    playButton.setText("PAUSE AUDIO");
                    playbackStatus.setText("Playing local audio.");
                }
            } catch (Exception e) {
                stopPlayback();
            }

            return;
        }

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, selectedAudioUri);

            mediaPlayer.setOnPreparedListener(player -> {
                player.start();
                playButton.setText("PAUSE AUDIO");

                playbackStatus.setText(
                        "Playing local audio.");
            });

            mediaPlayer.setOnCompletionListener(player -> {
                stopPlayback();
                playbackStatus.setText("Playback finished.");
            });

            mediaPlayer.setOnErrorListener(
                    (player, what, extra) -> {
                        stopPlayback();

                        playbackStatus.setText(
                                "Unable to play this audio file.");

                        return true;
                    });

            playbackStatus.setText("Loading audio...");
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            stopPlayback();

            playbackStatus.setText(
                    "Unable to open this audio file.");
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }

            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }

        if (playButton != null) {
            playButton.setText("PLAY INSTRUMENTAL");
        }
    }

    private boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }

        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();

            return uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme));

        } catch (Exception e) {
            return false;
        }
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null
                    && !value.trim().isEmpty()
                    && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }

        return "";
    }

    private String responseSuffix(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "";
        }

        String trimmed = response.trim();

        if (trimmed.length() > 400) {
            trimmed = trimmed.substring(0, 400);
        }

        return ": " + trimmed;
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }

    private void initializeAds(LinearLayout container) {
        try {
            MobileAds.initialize(this, status -> {});

            adView = new AdView(this);
            adView.setAdUnitId(AD_UNIT_ID);
            adView.setAdSize(AdSize.BANNER);

            container.addView(adView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            adView.loadAd(new AdRequest.Builder().build());

        } catch (Exception ignored) {
            // Keep the studio usable if ads are unavailable.
        }
    }

    private void section(LinearLayout parent, String title) {
        TextView heading = text(title, 17, GREEN, true);
        heading.setPadding(0, dp(20), 0, dp(8));
        parent.addView(heading, matchWrap());
    }

    private TextView text(
            String value, int size, int color, boolean bold) {

        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(GREEN));

        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(7);
        params.bottomMargin = dp(3);
        button.setLayoutParams(params);

        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);

            if (manager != null && searchInput != null) {
                manager.hideSoftInputFromWindow(
                        searchInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
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
        destroyed = true;

        stopPlayback();

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        if (adView != null) {
            adView.destroy();
            adView = null;
        }

        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);

        super.onDestroy();
    }
}
