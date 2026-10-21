package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private static final String TAG = "Donut4UKaraoke";

    private static final String API =
            "https://donut4u-karaoke.onrender.com";

    private static final String CATALOG_ENDPOINT =
            "/v1/catalog-search";

    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/7288125709";

    private static final int DARK =
            Color.rgb(13, 20, 17);

    private static final int PANEL =
            Color.rgb(25, 37, 31);

    private static final int GREEN =
            Color.rgb(0, 190, 115);

    private static final int WHITE =
            Color.WHITE;

    private static final int MUTED =
            Color.rgb(190, 205, 196);

    private final ExecutorService executor =
            Executors.newFixedThreadPool(3);

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout resultsContainer;
    private LinearLayout lyricsContainer;

    private EditText searchBox;
    private TextView statusText;
    private TextView nowPlayingText;
    private TextView lyricsStatus;

    private ProgressBar progressBar;
    private AdView adView;

    private MediaPlayer mediaPlayer;

    private String currentTrackId = "";
    private String currentTrackName = "";
    private String currentJobId = "";

    private boolean activityClosed = false;
    private int searchRequestNumber = 0;
    private int lyricsRequestNumber = 0;
    private int jobRequestNumber = 0;

    private Runnable jobPollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            openAccountScreen();
            return;
        }

        buildScreen();
        initializeAds();
    }

    private void openAccountScreen() {
        Toast.makeText(
                this,
                "Please sign in before using Karaoke Studio.",
                Toast.LENGTH_LONG
        ).show();

        try {
            startActivity(
                    new android.content.Intent(
                            this,
                            AuthActivity.class
                    )
            );
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to open the account screen.",
                    Toast.LENGTH_LONG
            ).show();
        }

        finish();
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(GREEN);
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(5));
        content.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Find a song. Play. Sing.");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(16));
        content.addView(subtitle, matchWrap());

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(MUTED);
        searchBox.setHint("Song title or singer");
        searchBox.setInputType(
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        searchBox.setPadding(
                dp(12), dp(8), dp(12), dp(8)
        );
        searchBox.setBackgroundColor(PANEL);

        content.addView(
                searchBox,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52)
                )
        );

        Button searchButton = makeButton("SEARCH SONGS");
        searchButton.setOnClickListener(v -> searchSongs());

        LinearLayout.LayoutParams searchButtonParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(50)
                );

        searchButtonParams.topMargin = dp(8);
        content.addView(searchButton, searchButtonParams);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);

        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        progressParams.gravity = Gravity.CENTER;
        progressParams.topMargin = dp(8);
        content.addView(progressBar, progressParams);

        statusText = makeText(
                "Search for a song to begin.",
                13,
                MUTED
        );
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(8), 0, dp(8));
        content.addView(statusText, matchWrap());

        TextView resultsTitle = makeText(
                "ONLINE SONG RESULTS",
                16,
                GREEN
        );
        resultsTitle.setTypeface(null, Typeface.BOLD);
        resultsTitle.setPadding(0, dp(12), 0, dp(8));
        content.addView(resultsTitle, matchWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsContainer, matchWrap());

        addDivider(content);

        TextView playerTitle = makeText(
                "KARAOKE PLAYER",
                16,
                GREEN
        );
        playerTitle.setTypeface(null, Typeface.BOLD);
        playerTitle.setPadding(0, dp(12), 0, dp(6));
        content.addView(playerTitle, matchWrap());

        nowPlayingText = makeText(
                "No song selected.",
                14,
                WHITE
        );
        nowPlayingText.setPadding(0, dp(4), 0, dp(10));
        content.addView(nowPlayingText, matchWrap());

        LinearLayout playbackButtons = new LinearLayout(this);
        playbackButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button playButton = makeButton("PLAY");
        playButton.setOnClickListener(v -> resumePlayback());

        Button pauseButton = makeButton("PAUSE");
        pauseButton.setOnClickListener(v -> pausePlayback());

        Button stopButton = makeButton("STOP");
        stopButton.setOnClickListener(v -> stopPlayback());

        playbackButtons.addView(
                playButton,
                new LinearLayout.LayoutParams(0, dp(46), 1)
        );

        playbackButtons.addView(
                pauseButton,
                new LinearLayout.LayoutParams(0, dp(46), 1)
        );

        playbackButtons.addView(
                stopButton,
                new LinearLayout.LayoutParams(0, dp(46), 1)
        );

        content.addView(playbackButtons, matchWrap());

        addDivider(content);

        TextView lyricsTitle = makeText(
                "LYRICS",
                16,
                GREEN
        );
        lyricsTitle.setTypeface(null, Typeface.BOLD);
        lyricsTitle.setPadding(0, dp(12), 0, dp(6));
        content.addView(lyricsTitle, matchWrap());

        Button lyricsButton = makeButton("FIND LYRICS");
        lyricsButton.setOnClickListener(v -> searchLyrics());

        content.addView(
                lyricsButton,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(46)
                )
        );

        lyricsStatus = makeText(
                "Lyrics will appear here when available.",
                12,
                MUTED
        );
        lyricsStatus.setPadding(0, dp(6), 0, dp(6));
        content.addView(lyricsStatus, matchWrap());

        lyricsContainer = new LinearLayout(this);
        lyricsContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsContainer.setPadding(
                dp(12), dp(12), dp(12), dp(12)
        );
        lyricsContainer.setBackgroundColor(PANEL);
        content.addView(lyricsContainer, matchWrap());

        addDivider(content);

        TextView vocalTitle = makeText(
                "VOCAL REMOVAL",
                16,
                GREEN
        );
        vocalTitle.setTypeface(null, Typeface.BOLD);
        vocalTitle.setPadding(0, dp(12), 0, dp(6));
        content.addView(vocalTitle, matchWrap());

        TextView vocalDescription = makeText(
                "Select an online catalog track. If the server supports " +
                "processing that track, Donut4U will request an instrumental " +
                "and play it when processing is complete.",
                13,
                MUTED
        );
        content.addView(vocalDescription, matchWrap());

        Button removeVocalsButton = makeButton(
                "CREATE KARAOKE VERSION"
        );
        removeVocalsButton.setOnClickListener(
                v -> createInstrumental()
        );

        LinearLayout.LayoutParams vocalParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(50)
                );

        vocalParams.topMargin = dp(8);
        content.addView(removeVocalsButton, vocalParams);

        Button backButton = makeButton("BACK");
        backButton.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(46)
                );

        backParams.topMargin = dp(16);
        content.addView(backButton, backParams);

        scrollView.addView(
                content,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        root.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        /*
         * Dedicated bottom advertising area.
         * The banner is kept outside the scrolling content so it
         * remains visible while users browse songs and lyrics.
         */
        LinearLayout adArea = new LinearLayout(this);
        adArea.setOrientation(LinearLayout.VERTICAL);
        adArea.setGravity(Gravity.CENTER);
        adArea.setBackgroundColor(Color.BLACK);
        adArea.setPadding(dp(4), dp(6), dp(4), dp(6));

        TextView adLabel = makeText(
                "ADVERTISEMENT",
                10,
                MUTED
        );
        adLabel.setGravity(Gravity.CENTER);
        adArea.addView(adLabel, matchWrap());

        adView = new AdView(this);
        adView.setAdUnitId(AD_UNIT_ID);
        adView.setAdSize(
                new AdSize(
                        AdSize.FULL_WIDTH,
                        100
                )
        );

        adArea.addView(
                adView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(100)
                )
        );

        root.addView(
                adArea,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(128)
                )
        );

        setContentView(root);

        searchBox.setOnEditorActionListener(
                (v, actionId, event) -> {
                    searchSongs();
                    return true;
                }
        );
    }

    private void initializeAds() {
        try {
            MobileAds.initialize(
                    getApplicationContext(),
                    initializationStatus -> runOnUiThread(() -> {
                        if (activityClosed || adView == null) {
                            return;
                        }

                        AdRequest request =
                                new AdRequest.Builder().build();

                        adView.loadAd(request);
                    })
            );
        } catch (Exception e) {
            android.util.Log.e(TAG, "AdMob initialization failed", e);
        }
    }

    private void searchSongs() {
        String query = searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            Toast.makeText(
                    this,
                    "Enter a song title or singer.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        int requestId = ++searchRequestNumber;

        resultsContainer.removeAllViews();
        showLoading(true);
        statusText.setText("Searching the online catalog...");

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String url = API + CATALOG_ENDPOINT
                        + "?q=" + encode(query);

                connection = openConnection(url, "GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);

                int code = connection.getResponseCode();
                String response = readResponse(connection, code);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Catalog server returned HTTP " + code
                    );
                }

                JSONObject object = new JSONObject(response);
                JSONArray results = object.optJSONArray("results");

                if (results == null) {
                    results = object.optJSONArray("tracks");
                }

                if (results == null) {
                    results = object.optJSONArray("items");
                }

                final JSONArray finalResults = results;

                runOnUiThread(() -> {
                    if (activityClosed ||
                            requestId != searchRequestNumber) {
                        return;
                    }

                    showLoading(false);

                    if (finalResults == null ||
                            finalResults.length() == 0) {
                        statusText.setText(
                                "No catalog tracks found. Try another search."
                        );
                        return;
                    }

                    statusText.setText(
                            finalResults.length() +
                            " result(s) found."
                    );

                    displayResults(finalResults);
                });

            } catch (Exception e) {
                String message = safeMessage(e);

                runOnUiThread(() -> {
                    if (activityClosed ||
                            requestId != searchRequestNumber) {
                        return;
                    }

                    showLoading(false);
                    statusText.setText(
                            "Search failed: " + message
                    );
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void displayResults(JSONArray results) {
        resultsContainer.removeAllViews();

        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);

            if (item == null) {
                continue;
            }

            String id = firstNonEmpty(
                    item.optString("id", ""),
                    item.optString("track_id", ""),
                    item.optString("trackId", "")
            );

            String name = firstNonEmpty(
                    item.optString("name", ""),
                    item.optString("title", ""),
                    item.optString("track_name", "")
            );

            String artist = firstNonEmpty(
                    item.optString("artist", ""),
                    item.optString("artist_name", ""),
                    item.optString("artistName", "")
            );

            String duration = item.optString("duration", "");

            if (name.isEmpty()) {
                name = "Untitled track";
            }

            final String trackId = id;
            final String trackName = name;
            final String trackArtist = artist;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(PANEL);

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );

            cardParams.bottomMargin = dp(8);

            TextView songName = makeText(name, 16, WHITE);
            songName.setTypeface(null, Typeface.BOLD);
            card.addView(songName, matchWrap());

            String details = artist;

            if (!duration.isEmpty()) {
                details = details.isEmpty()
                        ? "Duration: " + duration
                        : details + " • " + duration;
            }

            if (details.isEmpty()) {
                details = "Online catalog track";
            }

            TextView artistView = makeText(
                    details,
                    12,
                    MUTED
            );

            artistView.setPadding(0, dp(4), 0, dp(8));
            card.addView(artistView, matchWrap());

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);

            Button play = makeButton("PLAY");
            play.setTextSize(12);
            play.setOnClickListener(v -> {
                currentTrackId = trackId;
                currentTrackName = trackName;

                String audioUrl = findAudioUrl(item);

                if (!audioUrl.isEmpty()) {
                    playAudio(audioUrl, trackName);
                } else {
                    statusText.setText(
                            "This catalog result has no direct audio URL. " +
                            "Trying the server's vocal-removal workflow."
                    );

                    createInstrumental();
                }
            });

            Button lyrics = makeButton("LYRICS");
            lyrics.setTextSize(12);
            lyrics.setOnClickListener(v -> {
                currentTrackName = trackName;
                searchBox.setText(trackName);
                searchLyrics();
            });

            buttons.addView(
                    play,
                    new LinearLayout.LayoutParams(0, dp(44), 1)
            );

            buttons.addView(
                    lyrics,
                    new LinearLayout.LayoutParams(0, dp(44), 1)
            );

            card.addView(buttons, matchWrap());
            resultsContainer.addView(card, cardParams);

            card.setOnClickListener(v -> {
                currentTrackId = trackId;
                currentTrackName = trackName;
            });
        }
    }

    private String findAudioUrl(JSONObject item) {
        return firstNonEmpty(
                item.optString("audio_url", ""),
                item.optString("audioUrl", ""),
                item.optString("preview_url", ""),
                item.optString("previewUrl", ""),
                item.optString("stream_url", ""),
                item.optString("streamUrl", ""),
                item.optString("url", "")
        );
    }

    private void searchLyrics() {
        String query = searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            query = currentTrackName;
        }

        if (query == null || query.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Enter a song title first.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        final String lyricQuery = query.trim();
        final int requestId = ++lyricsRequestNumber;

        lyricsContainer.removeAllViews();
        lyricsStatus.setText("Searching for lyrics...");

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String url =
                        "https://lrclib.net/api/search?q="
                        + encode(lyricQuery);

                connection = openConnection(url, "GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(25000);
                connection.setRequestProperty(
                        "User-Agent",
                        "Donut4UKaraoke/1.0"
                );

                int code = connection.getResponseCode();
                String response = readResponse(connection, code);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Lyrics service returned HTTP " + code
                    );
                }

                JSONArray results = new JSONArray(response);
                JSONObject chosen = null;

                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);

                    if (item == null) {
                        continue;
                    }

                    String synced = item.optString(
                            "syncedLyrics", ""
                    );

                    String plain = item.optString(
                            "plainLyrics", ""
                    );

                    if (!synced.trim().isEmpty() ||
                            !plain.trim().isEmpty()) {
                        chosen = item;
                        break;
                    }
                }

                final JSONObject lyricResult = chosen;

                runOnUiThread(() -> {
                    if (activityClosed ||
                            requestId != lyricsRequestNumber) {
                        return;
                    }

                    lyricsContainer.removeAllViews();

                    if (lyricResult == null) {
                        lyricsStatus.setText(
                                "No lyrics were found for this search."
                        );
                        return;
                    }

                    String synced = lyricResult.optString(
                            "syncedLyrics", ""
                    );

                    String plain = lyricResult.optString(
                            "plainLyrics", ""
                    );

                    String text = !synced.trim().isEmpty()
                            ? synced
                            : plain;

                    TextView lyricsView = makeText(
                            text,
                            14,
                            WHITE
                    );

                    lyricsView.setTextIsSelectable(true);
                    lyricsView.setGravity(Gravity.CENTER);
                    lyricsView.setPadding(
                            dp(6), dp(6), dp(6), dp(6)
                    );

                    lyricsContainer.addView(
                            lyricsView,
                            matchWrap()
                    );

                    String track = lyricResult.optString(
                            "trackName", lyricQuery
                    );

                    String artist = lyricResult.optString(
                            "artistName", ""
                    );

                    lyricsStatus.setText(
                            artist.isEmpty()
                                    ? "Lyrics: " + track
                                    : "Lyrics: " + track + " — " + artist
                    );
                });

            } catch (Exception e) {
                String message = safeMessage(e);

                runOnUiThread(() -> {
                    if (activityClosed ||
                            requestId != lyricsRequestNumber) {
                        return;
                    }

                    lyricsStatus.setText(
                            "Lyrics lookup failed: " + message
                    );
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void createInstrumental() {
        if (currentTrackId == null ||
                currentTrackId.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Choose a track from the online catalog first.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (currentJobId != null &&
                !currentJobId.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "A vocal-removal job is already running.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        showLoading(true);
        statusText.setText("Requesting vocal removal...");

        final String trackId = currentTrackId;
        final int requestId = ++jobRequestNumber;

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String url = API + "/v1/jobs/from-track/"
                        + encodePath(trackId);

                connection = openConnection(url, "POST");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );

                try (OutputStream out =
                             connection.getOutputStream()) {
                    out.write(
                            "{}".getBytes(StandardCharsets.UTF_8)
                    );
                }

                int code = connection.getResponseCode();
                String response = readResponse(connection, code);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Vocal-removal server returned HTTP " + code
                    );
                }

                JSONObject object = new JSONObject(response);

                String jobId = firstNonEmpty(
                        object.optString("job_id", ""),
                        object.optString("jobId", ""),
                        object.optString("id", "")
                );

                String instrumentalUrl = firstNonEmpty(
                        object.optString("instrumental_url", ""),
                        object.optString("instrumentalUrl", ""),
                        object.optString("output_url", ""),
                        object.optString("outputUrl", "")
                );

                if (jobId.isEmpty() && instrumentalUrl.isEmpty()) {
                    throw new Exception(
                            "The server did not return a job ID or " +
                            "instrumental URL."
                    );
                }

                runOnUiThread(() -> {
                    if (activityClosed ||
                            requestId != jobRequestNumber) {
                        return;
                    }

                    showLoading(false);

                    if (!instrumentalUrl.isEmpty()) {
                        statusText.setText(
                                "Instrumental ready. Starting playback..."
                        );

                        playAudio(
                                resolveUrl(instrumentalUrl),
                                currentTrackName
                        );

                    } else {
                        currentJobId = jobId;
                        statusText.setText(
                                "Vocal removal started. Waiting for the result..."
                        );
                        pollJobStatus(jobId, requestId, 0);
                    }
                });

            } catch (Exception e) {
                String message = safeMessage(e);

                runOnUiThread(() -> {
                    if (activityClosed ||
                            requestId != jobRequestNumber) {
                        return;
                    }

                    showLoading(false);
                    statusText.setText(
                            "Vocal removal failed: " + message
                    );
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void pollJobStatus(
            String jobId,
            int requestId,
            int attempt
    ) {
        if (activityClosed ||
                requestId != jobRequestNumber) {
            return;
        }

        if (attempt >= 60) {
            currentJobId = "";
            statusText.setText(
                    "Processing is taking too long. Search again " +
                    "or try the vocal-removal button later."
            );
            return;
        }

        jobPollRunnable = () -> {
            if (activityClosed ||
                    requestId != jobRequestNumber) {
                return;
            }

            executor.execute(() -> {
                HttpURLConnection connection = null;

                try {
                    String url = API + "/v1/jobs/"
                            + encodePath(jobId);

                    connection = openConnection(url, "GET");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);

                    int code = connection.getResponseCode();
                    String response = readResponse(connection, code);

                    if (code < 200 || code >= 300) {
                        throw new Exception(
                                "Job status returned HTTP " + code
                        );
                    }

                    JSONObject object = new JSONObject(response);

                    String status = firstNonEmpty(
                            object.optString("status", ""),
                            object.optString("state", "")
                    ).toLowerCase(Locale.ROOT);

                    String instrumentalUrl = firstNonEmpty(
                            object.optString("instrumental_url", ""),
                            object.optString("instrumentalUrl", ""),
                            object.optString("output_url", ""),
                            object.optString("outputUrl", ""),
                            object.optString("download_url", ""),
                            object.optString("downloadUrl", "")
                    );

                    final String finalStatus = status;
                    final String finalInstrumentalUrl = instrumentalUrl;

                    runOnUiThread(() -> {
                        if (activityClosed ||
                                requestId != jobRequestNumber) {
                            return;
                        }

                        if (!finalInstrumentalUrl.isEmpty()) {
                            currentJobId = "";

                            String resolved = resolveUrl(
                                    finalInstrumentalUrl
                            );

                            statusText.setText(
                                    "Instrumental ready. Starting playback..."
                            );

                            playAudio(resolved, currentTrackName);
                            return;
                        }

                        if (isFailedStatus(finalStatus)) {
                            currentJobId = "";
                            statusText.setText(
                                    "The server could not complete vocal removal."
                            );
                            return;
                        }

                        if (isCompleteStatus(finalStatus)) {
                            fetchInstrumental(jobId, requestId);
                            return;
                        }

                        statusText.setText(
                                "Vocal removal: " +
                                (finalStatus.isEmpty()
                                        ? "processing"
                                        : finalStatus) +
                                "..."
                        );

                        pollJobStatus(
                                jobId,
                                requestId,
                                attempt + 1
                        );
                    });

                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (activityClosed ||
                                requestId != jobRequestNumber) {
                            return;
                        }

                        statusText.setText(
                                "Checking processing status... " +
                                safeMessage(e)
                        );

                        pollJobStatus(
                                jobId,
                                requestId,
                                attempt + 1
                        );
                    });

                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });
        };

        handler.postDelayed(jobPollRunnable, 3000);
    }

    private void fetchInstrumental(
            String jobId,
            int requestId
    ) {
        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String url = API + "/v1/jobs/"
                        + encodePath(jobId)
                        + "/instrumental";

                connection = openConnection(url, "GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);

                int code = connection.getResponseCode();

                if (code < 200 || code >= 300) {
                    String body = readResponse(connection, code);

                    throw new Exception(
                            "Instrumental endpoint returned HTTP " +
                            code + (body.isEmpty() ? "" : ": " + body)
                    );
                }

                String contentType = connection.getContentType();

                if (contentType != null &&
                        contentType.toLowerCase(Locale.ROOT)
                                .contains("json")) {
                    String response = readResponse(connection, code);
                    JSONObject object = new JSONObject(response);

                    String audioUrl = firstNonEmpty(
                            object.optString("instrumental_url", ""),
                            object.optString("instrumentalUrl", ""),
                            object.optString("url", ""),
                            object.optString("download_url", "")
                    );

                    if (audioUrl.isEmpty()) {
                        throw new Exception(
                                "The server returned JSON without an audio URL."
                        );
                    }

                    runOnUiThread(() -> {
                        if (activityClosed ||
                                requestId != jobRequestNumber) {
                            return;
                        }

                        currentJobId = "";
                        playAudio(
                                resolveUrl(audioUrl),
                                currentTrackName
                        );
                    });

                } else {
                    /*
                     * Some servers return the instrumental file directly.
                     * Stream the response through the server endpoint.
                     */
                    runOnUiThread(() -> {
                        if (activityClosed ||
                                requestId != jobRequestNumber) {
                            return;
                        }

                        currentJobId = "";
                        playAudio(url, currentTrackName);
                    });
                }

            } catch (Exception e) {
                String message = safeMessage(e);

                runOnUiThread(() -> {
                    if (activityClosed ||
                            requestId != jobRequestNumber) {
                        return;
                    }

                    currentJobId = "";
                    statusText.setText(
                            "Could not retrieve the instrumental: " +
                            message
                    );
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private boolean isFailedStatus(String status) {
        return status.contains("failed")
                || status.contains("error")
                || status.contains("cancelled")
                || status.contains("canceled");
    }

    private boolean isCompleteStatus(String status) {
        return status.equals("complete")
                || status.equals("completed")
                || status.equals("success")
                || status.equals("succeeded")
                || status.equals("finished")
                || status.equals("done");
    }

    private void playAudio(String audioUrl, String title) {
        if (audioUrl == null ||
                audioUrl.trim().isEmpty()) {
            statusText.setText(
                    "This track does not provide a playable audio URL."
            );
            return;
        }

        if (!audioUrl.startsWith("https://") &&
                !audioUrl.startsWith("http://")) {
            statusText.setText(
                    "The server returned an invalid audio URL."
            );
            return;
        }

        releasePlayer();

        currentTrackName = title == null
                ? "Selected track"
                : title;

        nowPlayingText.setText(currentTrackName);
        statusText.setText("Loading audio...");

        try {
            mediaPlayer = new MediaPlayer();

            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(
                                    AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
            );

            mediaPlayer.setOnPreparedListener(mp -> {
                if (activityClosed) {
                    return;
                }

                mp.start();

                statusText.setText("Playing: " + currentTrackName);
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                if (!activityClosed) {
                    statusText.setText("Song finished.");
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (!activityClosed) {
                    statusText.setText(
                            "Playback failed. This source may not " +
                            "allow direct audio streaming."
                    );
                }

                releasePlayer();
                return true;
            });

            mediaPlayer.setDataSource(audioUrl);
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            releasePlayer();
            statusText.setText(
                    "Unable to play this audio: " + safeMessage(e)
            );
        }
    }

    private void resumePlayback() {
        if (mediaPlayer == null) {
            Toast.makeText(
                    this,
                    "Select a playable track first.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        try {
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                statusText.setText("Playing: " + currentTrackName);
            }
        } catch (Exception e) {
            statusText.setText(
                    "Unable to resume playback."
            );
        }
    }

    private void pausePlayback() {
        if (mediaPlayer == null) {
            return;
        }

        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                statusText.setText("Paused.");
            }
        } catch (Exception ignored) {
        }
    }

    private void stopPlayback() {
        releasePlayer();
        nowPlayingText.setText("No song selected.");
        statusText.setText("Playback stopped.");
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setOnPreparedListener(null);
                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnErrorListener(null);
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }

            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }
    }

    private HttpURLConnection openConnection(
            String address,
            String method
    ) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(address)
                        .openConnection();

        connection.setRequestMethod(method);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty(
                "Accept",
                "application/json, audio/*, */*"
        );

        return connection;
    }

    private String readResponse(
            HttpURLConnection connection,
            int code
    ) throws Exception {
        InputStream input;

        if (code >= 200 && code < 400) {
            input = connection.getInputStream();
        } else {
            input = connection.getErrorStream();

            if (input == null) {
                return "";
            }
        }

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        input,
                        StandardCharsets.UTF_8
                )
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private String encodePath(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20");
    }

    private String resolveUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }

        if (value.startsWith("http://") ||
                value.startsWith("https://")) {
            return value;
        }

        if (value.startsWith("/")) {
            return API + value;
        }

        return API + "/" + value;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()
                    && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }

        return "";
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }

        return message;
    }

    private void showLoading(boolean loading) {
        if (progressBar != null) {
            progressBar.setVisibility(
                    loading ? View.VISIBLE : View.GONE
            );
        }
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private TextView makeText(
            String text,
            int size,
            int color
    ) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(
                Color.rgb(54, 75, 64)
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                );

        params.topMargin = dp(12);
        params.bottomMargin = dp(6);

        parent.addView(divider, params);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(float value) {
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
        activityClosed = true;

        searchRequestNumber++;
        lyricsRequestNumber++;
        jobRequestNumber++;

        if (jobPollRunnable != null) {
            handler.removeCallbacks(jobPollRunnable);
        }

        releasePlayer();

        if (adView != null) {
            adView.destroy();
            adView = null;
        }

        executor.shutdownNow();

        super.onDestroy();
    }
}
