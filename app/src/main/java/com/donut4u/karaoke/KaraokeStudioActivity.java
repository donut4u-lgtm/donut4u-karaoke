File: "app/src/main/java/com/donut4u/karaoke/KaraokeStudioActivity.java"

package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
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

    private static final String API =
            "https://donut4u-karaoke.onrender.com";

    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/7288125709";

    private static final int DARK = Color.rgb(13, 20, 17);
    private static final int PANEL = Color.rgb(25, 37, 31);
    private static final int GREEN = Color.rgb(0, 190, 115);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 205, 196);

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

    private String selectedTitle = "";
    private String currentJobId = "";
    private String currentInstrumentalUrl = "";

    private boolean closed = false;
    private int searchSerial = 0;
    private int lyricsSerial = 0;
    private int jobSerial = 0;

    private Runnable pollRunnable;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            Toast.makeText(
                    this,
                    "Please sign in first.",
                    Toast.LENGTH_LONG
            ).show();

            startActivity(new android.content.Intent(
                    this, AuthActivity.class
            ));
            finish();
            return;
        }

        buildScreen();
        setupAdMob();
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);

        ScrollView scroll = new ScrollView(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = text("KARAOKE STUDIO", 25, GREEN);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap());

        TextView subtitle = text(
                "Search a song. Select it. Start singing.",
                14, MUTED
        );
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(5), 0, dp(15));
        content.addView(subtitle, matchWrap());

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(MUTED);
        searchBox.setHint("Song title or singer");
        searchBox.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        searchBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBox.setBackgroundColor(PANEL);
        content.addView(
                searchBox,
                new LinearLayout.LayoutParams(-1, dp(52))
        );

        Button search = button("SEARCH SONGS");
        search.setOnClickListener(v -> searchCatalog());
        content.addView(search, fullButton());

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        content.addView(progressBar, matchWrap());

        statusText = text(
                "Search the music catalog to find available songs.",
                14, MUTED
        );
        statusText.setPadding(0, dp(8), 0, dp(8));
        content.addView(statusText, matchWrap());

        TextView resultsHeading = text("SONG RESULTS", 18, GREEN);
        resultsHeading.setTypeface(null, Typeface.BOLD);
        content.addView(resultsHeading, matchWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsContainer, matchWrap());

        TextView playerHeading = text("NOW PLAYING", 18, GREEN);
        playerHeading.setTypeface(null, Typeface.BOLD);
        playerHeading.setPadding(0, dp(14), 0, dp(4));
        content.addView(playerHeading, matchWrap());

        nowPlayingText = text("No song selected.", 15, WHITE);
        content.addView(nowPlayingText, matchWrap());

        LinearLayout playerButtons = new LinearLayout(this);

        Button play = button("PLAY");
        play.setOnClickListener(v -> playSelected());

        Button pause = button("PAUSE");
        pause.setOnClickListener(v -> pausePlayback());

        Button stop = button("STOP");
        stop.setOnClickListener(v -> stopPlayback());

        playerButtons.addView(play, weightButton());
        playerButtons.addView(pause, weightButton());
        playerButtons.addView(stop, weightButton());
        content.addView(playerButtons, matchWrap());

        TextView lyricsHeading = text("LYRICS", 18, GREEN);
        lyricsHeading.setTypeface(null, Typeface.BOLD);
        lyricsHeading.setPadding(0, dp(14), 0, dp(4));
        content.addView(lyricsHeading, matchWrap());

        lyricsStatus = text(
                "Lyrics will be searched automatically when you select a song.",
                13, MUTED
        );
        content.addView(lyricsStatus, matchWrap());

        lyricsContainer = new LinearLayout(this);
        lyricsContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsContainer.setPadding(dp(10), dp(10), dp(10), dp(10));
        lyricsContainer.setBackgroundColor(PANEL);
        content.addView(lyricsContainer, matchWrap());

        Button donate = button("DONATE TO SUPPORT DONUT4U");
        donate.setOnClickListener(v -> showDonate());
        content.addView(donate, fullButton());

        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        content.addView(back, fullButton());

        scroll.addView(content);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        setContentView(root);
    }

    private void setupAdMob() {
        try {
            MobileAds.initialize(this, initializationStatus -> {});

            adView = new AdView(this);
            adView.setAdUnitId(AD_UNIT_ID);

            int widthDp = (int) (
                    getResources().getDisplayMetrics().widthPixels
                            / getResources().getDisplayMetrics().density
            );

            AdSize adaptiveSize =
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                            this, Math.max(1, widthDp)
                    );

            adView.setAdSize(adaptiveSize);
            adView.loadAd(new AdRequest.Builder().build());

            LinearLayout adContainer = new LinearLayout(this);
            adContainer.setGravity(Gravity.CENTER);
            adContainer.setBackgroundColor(Color.BLACK);
            adContainer.setPadding(0, dp(8), 0, dp(8));
            adContainer.addView(adView);

            root.addView(
                    adContainer,
                    new LinearLayout.LayoutParams(
                            -1,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            );
        } catch (Exception e) {
            // Keep the Studio usable if ads cannot load.
        }
    }

    private void searchCatalog() {
        String query = searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            Toast.makeText(
                    this, "Enter a song title or singer.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        int serial = ++searchSerial;
        resultsContainer.removeAllViews();
        loading(true);
        statusText.setText("Searching the music catalog...");

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String address = API + "/v1/catalog-search?q="
                        + encode(query);

                connection = open(address, "GET");

                int code = connection.getResponseCode();
                String body = read(connection, code);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Catalog returned HTTP " + code
                    );
                }

                JSONObject response = new JSONObject(body);
                JSONArray results = response.optJSONArray("results");

                runOnUiThread(() -> {
                    if (closed || serial != searchSerial) return;

                    loading(false);

                    if (results == null || results.length() == 0) {
                        statusText.setText(
                                "No catalog tracks found. Try another title."
                        );
                        return;
                    }

                    statusText.setText(
                            results.length() + " result(s). Tap a song to start."
                    );
                    showResults(results);
                });

            } catch (Exception e) {
                String message = safe(e);

                runOnUiThread(() -> {
                    if (closed || serial != searchSerial) return;

                    loading(false);
                    statusText.setText(
                            "Catalog search failed: " + message
                    );
                });

            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void showResults(JSONArray results) {
        resultsContainer.removeAllViews();

        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;

            String title = firstValue(
                    item, "title", "name", "track_name"
            );

            String artist = firstValue(
                    item, "artist", "artist_name", "creator"
            );

            String audioUrl = firstValue(
                    item,
                    "audio_url",
                    "audio",
                    "download",
                    "download_url",
                    "url"
            );

            if (title.isEmpty()) title = "Untitled track";
            if (artist.isEmpty()) artist = "Unknown artist";

            final String songTitle = title;
            final String songArtist = artist;
            final String songUrl = audioUrl;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackgroundColor(PANEL);

            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = dp(8);

            TextView songName = text(songTitle, 16, WHITE);
            songName.setTypeface(null, Typeface.BOLD);
            card.addView(songName, matchWrap());

            card.addView(text(songArtist, 13, MUTED), matchWrap());

            Button select = button("SELECT SONG AND START KARAOKE");
            select.setOnClickListener(v -> {
                selectedTitle = songTitle;
                nowPlayingText.setText(songTitle + "\n" + songArtist);

                if (songUrl.isEmpty()
                        || !songUrl.matches("(?i)^https?://.*")) {
                    statusText.setText(
                            "This catalog result has no usable audio URL. "
                                    + "Choose another result."
                    );
                    return;
                }

                searchLyrics(songTitle, songArtist);
                createInstrumental(songUrl, songTitle, songArtist);
            });

            card.addView(select, fullButton());
            resultsContainer.addView(card, cardParams);
        }
    }

    private void createInstrumental(
            String sourceUrl,
            String title,
            String artist
    ) {
        if (!currentJobId.isEmpty()) {
            Toast.makeText(
                    this, "Karaoke processing is already running.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        int serial = ++jobSerial;
        loading(true);
        statusText.setText("Preparing your karaoke track...");

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                connection = open(API + "/v1/jobs/from-url", "POST");
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type", "application/json; charset=UTF-8"
                );

                JSONObject payload = new JSONObject();
                payload.put("url", sourceUrl);
                payload.put("title", title);
                payload.put("artist", artist);

                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload.toString().getBytes(
                            StandardCharsets.UTF_8
                    ));
                }

                int code = connection.getResponseCode();
                String body = read(connection, code);

                if (code < 200 || code >= 300) {
                    String detail = body;
                    try {
                        detail = new JSONObject(body).optString(
                                "detail", "HTTP " + code
                        );
                    } catch (Exception ignored) {}
                    throw new Exception(detail);
                }

                String jobId =
                        new JSONObject(body).optString("job_id", "");

                if (jobId.isEmpty()) {
                    throw new Exception("Server did not return a job ID.");
                }

                runOnUiThread(() -> {
                    if (closed || serial != jobSerial) return;

                    currentJobId = jobId;
                    statusText.setText(
                            "Creating the instrumental. Please wait..."
                    );
                    pollJob(jobId, serial, 0);
                });

            } catch (Exception e) {
                String message = safe(e);

                runOnUiThread(() -> {
                    if (closed || serial != jobSerial) return;

                    loading(false);
                    statusText.setText(
                            "Could not create karaoke: " + message
                    );
                });

            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void pollJob(String jobId, int serial, int attempt) {
        if (closed || serial != jobSerial) return;

        if (attempt >= 120) {
            currentJobId = "";
            loading(false);
            statusText.setText(
                    "Processing took too long. Please try again later."
            );
            return;
        }

        pollRunnable = () -> executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                connection = open(
                        API + "/v1/jobs/" + encode(jobId), "GET"
                );

                int code = connection.getResponseCode();
                String body = read(connection, code);

                if (code < 200 || code >= 300) {
                    throw new Exception("HTTP " + code);
                }

                JSONObject response = new JSONObject(body);

                String state = response.optString(
                        "status", ""
                ).toLowerCase(Locale.ROOT);

                String error = response.optString("error", "");

                runOnUiThread(() -> {
                    if (closed || serial != jobSerial) return;

                    if (state.equals("completed")
                            || state.equals("complete")
                            || state.equals("success")) {

                        currentJobId = "";
                        loading(false);

                        currentInstrumentalUrl =
                                API + "/v1/jobs/" + jobId + "/instrumental";

                        statusText.setText("Karaoke ready. Starting playback...");
                        playAudio(currentInstrumentalUrl, selectedTitle);

                    } else if (state.equals("failed")
                            || state.equals("error")) {

                        currentJobId = "";
                        loading(false);
                        statusText.setText(
                                "Karaoke processing failed: "
                                        + (error.isEmpty()
                                        ? "Please try another song."
                                        : error)
                        );

                    } else {
                        String progress = response.optString(
                                "progress", state
                        );
                        statusText.setText(
                                "Creating karaoke: " + progress
                        );
                        pollJob(jobId, serial, attempt + 1);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!closed && serial == jobSerial) {
                        pollJob(jobId, serial, attempt + 1);
                    }
                });

            } finally {
                if (connection != null) connection.disconnect();
            }
        });

        handler.postDelayed(pollRunnable, 3000);
    }

    private void searchLyrics(String title, String artist) {
        int serial = ++lyricsSerial;

        lyricsContainer.removeAllViews();
        lyricsStatus.setText("Loading lyrics automatically...");

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String query = title + " " + artist;
                connection = open(
                        "https://lrclib.net/api/search?q=" + encode(query),
                        "GET"
                );

                int code = connection.getResponseCode();
                String body = read(connection, code);

                if (code < 200 || code >= 300) {
                    throw new Exception("Lyrics service HTTP " + code);
                }

                JSONArray results = new JSONArray(body);
                String lyrics = "";

                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;

                    lyrics = item.optString("syncedLyrics", "");
                    if (lyrics.isEmpty()) {
                        lyrics = item.optString("plainLyrics", "");
                    }
                    if (!lyrics.trim().isEmpty()) break;
                }

                String finalLyrics = lyrics;

                runOnUiThread(() -> {
                    if (closed || serial != lyricsSerial) return;

                    lyricsContainer.removeAllViews();

                    if (finalLyrics.trim().isEmpty()) {
                        lyricsStatus.setText(
                                "Lyrics are not available for this song."
                        );
                        return;
                    }

                    lyricsStatus.setText("Lyrics loaded.");
                    TextView view = text(finalLyrics, 14, WHITE);
                    view.setTextIsSelectable(true);
                    view.setPadding(dp(8), dp(8), dp(8), dp(8));
                    lyricsContainer.addView(view, matchWrap());
                });

            } catch (Exception e) {
                String message = safe(e);

                runOnUiThread(() -> {
                    if (!closed && serial == lyricsSerial) {
                        lyricsStatus.setText(
                                "Could not load lyrics: " + message
                        );
                    }
                });

            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void playSelected() {
        if (currentInstrumentalUrl.isEmpty()) {
            statusText.setText(
                    "Select a song first and wait for karaoke processing."
            );
            return;
        }
        playAudio(currentInstrumentalUrl, selectedTitle);
    }

    private void playAudio(String url, String title) {
        releasePlayer();

        nowPlayingText.setText(title);
        statusText.setText("Loading karaoke audio...");

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
            );

            mediaPlayer.setOnPreparedListener(player -> {
                if (!closed) {
                    player.start();
                    statusText.setText("Playing karaoke: " + title);
                }
            });

            mediaPlayer.setOnCompletionListener(player -> {
                if (!closed) statusText.setText("Song finished.");
            });

            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                if (!closed) {
                    statusText.setText(
                            "Audio playback failed. Please try again."
                    );
                }
                releasePlayer();
                return true;
            });

            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            releasePlayer();
            statusText.setText("Playback failed: " + safe(e));
        }
    }

    private void pausePlayback() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                statusText.setText("Paused.");
            }
        } catch (Exception ignored) {}
    }

    private void stopPlayback() {
        releasePlayer();
        statusText.setText("Playback stopped.");
    }

    private void releasePlayer() {
        if (mediaPlayer == null) return;

        try {
            mediaPlayer.setOnPreparedListener(null);
            mediaPlayer.setOnCompletionListener(null);
            mediaPlayer.setOnErrorListener(null);
            mediaPlayer.stop();
        } catch (Exception ignored) {}

        try {
            mediaPlayer.reset();
            mediaPlayer.release();
        } catch (Exception ignored) {}

        mediaPlayer = null;
    }

    private void showDonate() {
        new AlertDialog.Builder(this)
                .setTitle("Support Donut4U Karaoke")
                .setMessage(
                        "Thank you for supporting Donut4U Karaoke. "
                                + "Use the Donate option on the main page "
                                + "to view the available donation details."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private String firstValue(JSONObject item, String... keys) {
        for (String key : keys) {
            String value = item.optString(key, "").trim();
            if (!value.isEmpty() && !value.equals("null")) {
                return value;
            }
        }
        return "";
    }

    private HttpURLConnection open(String address, String method)
            throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();

        connection.setRequestMethod(method);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty(
                "Accept", "application/json, audio/*, */*"
        );
        return connection;
    }

    private String read(HttpURLConnection connection, int code)
            throws Exception {
        InputStream in = (code >= 200 && code < 400)
                ? connection.getInputStream()
                : connection.getErrorStream();

        if (in == null) return "";

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
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

    private String safe(Exception e) {
        return e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private void loading(boolean value) {
        if (progressBar != null) {
            progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        }
    }

    private int dp(float value) {
        return (int) (
                value * getResources().getDisplayMetrics().density + 0.5f
        );
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullButton() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(5);
        params.bottomMargin = dp(5);
        return params;
    }

    private LinearLayout.LayoutParams weightButton() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        searchSerial++;
        lyricsSerial++;
        jobSerial++;

        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
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
