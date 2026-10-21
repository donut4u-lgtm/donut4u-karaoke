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
    private EditText audioUrlBox;

    private TextView statusText;
    private TextView nowPlayingText;
    private TextView lyricsStatus;

    private ProgressBar progressBar;
    private AdView adView;
    private MediaPlayer mediaPlayer;

    private String selectedUrl = "";
    private String selectedTitle = "";
    private String currentJobId = "";

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
                    "Please sign in before using Karaoke Studio.",
                    Toast.LENGTH_LONG
            ).show();

            startActivity(
                    new android.content.Intent(
                            this,
                            AuthActivity.class
                    )
            );

            finish();
            return;
        }

        buildScreen();

        try {
            MobileAds.initialize(
                    this,
                    initializationStatus -> {}
            );

            adView = new AdView(this);
            adView.setAdUnitId(AD_UNIT_ID);
            adView.setAdSize(AdSize.BANNER);
            adView.loadAd(
                    new AdRequest.Builder().build()
            );

            LinearLayout adContainer =
                    new LinearLayout(this);

            adContainer.setGravity(Gravity.CENTER);
            adContainer.setBackgroundColor(Color.BLACK);
            adContainer.addView(adView);

            root.addView(
                    adContainer,
                    new LinearLayout.LayoutParams(
                            -1,
                            dp(100)
                    )
            );

        } catch (Exception ignored) {
        }
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);

        ScrollView scroll = new ScrollView(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(16)
        );

        TextView title =
                text("DONUT4U KARAOKE", 25, GREEN);

        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        content.addView(title, matchWrap());

        TextView subtitle =
                text(
                        "Search the internet. Find a song. Sing.",
                        14,
                        MUTED
                );

        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(14));

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

        searchBox.setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8)
        );

        searchBox.setBackgroundColor(PANEL);

        content.addView(
                searchBox,
                new LinearLayout.LayoutParams(-1, dp(52))
        );

        Button search = button("SEARCH THE INTERNET");
        search.setOnClickListener(v -> searchWeb());
        content.addView(search, fullButton());

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        content.addView(progressBar, matchWrap());

        statusText =
                text("Search results will appear below.", 14, MUTED);

        statusText.setPadding(0, dp(8), 0, dp(8));
        content.addView(statusText, matchWrap());

        TextView resultsHeading =
                text("ONLINE RESULTS", 18, GREEN);

        resultsHeading.setTypeface(null, Typeface.BOLD);
        content.addView(resultsHeading, matchWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsContainer, matchWrap());

        TextView playerHeading =
                text("KARAOKE PLAYER", 18, GREEN);

        playerHeading.setTypeface(null, Typeface.BOLD);
        playerHeading.setPadding(0, dp(14), 0, dp(4));
        content.addView(playerHeading, matchWrap());

        nowPlayingText =
                text("No song selected.", 14, WHITE);

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

        TextView urlHeading =
                text("DIRECT AUDIO LINK (IF NEEDED)", 16, GREEN);

        urlHeading.setPadding(0, dp(12), 0, dp(4));
        content.addView(urlHeading, matchWrap());

        audioUrlBox = new EditText(this);
        audioUrlBox.setSingleLine(true);
        audioUrlBox.setTextColor(WHITE);
        audioUrlBox.setHintTextColor(MUTED);
        audioUrlBox.setHint("https://example.com/song.mp3");

        audioUrlBox.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI
        );

        audioUrlBox.setBackgroundColor(PANEL);

        content.addView(
                audioUrlBox,
                new LinearLayout.LayoutParams(-1, dp(50))
        );

        Button vocal =
                button("REMOVE VOCALS / MAKE KARAOKE");

        vocal.setOnClickListener(v -> createInstrumental());
        content.addView(vocal, fullButton());

        TextView lyricsHeading =
                text("LYRICS", 18, GREEN);

        lyricsHeading.setTypeface(null, Typeface.BOLD);
        lyricsHeading.setPadding(0, dp(14), 0, dp(4));
        content.addView(lyricsHeading, matchWrap());

        Button lyricsButton = button("FIND LYRICS");
        lyricsButton.setOnClickListener(v -> searchLyrics());
        content.addView(lyricsButton, fullButton());

        lyricsStatus =
                text("Lyrics from LRCLIB when available.", 13, MUTED);

        content.addView(lyricsStatus, matchWrap());

        lyricsContainer = new LinearLayout(this);
        lyricsContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsContainer.setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
        );
        lyricsContainer.setBackgroundColor(PANEL);

        content.addView(lyricsContainer, matchWrap());

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

    private void searchWeb() {
        String query =
                searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            Toast.makeText(
                    this,
                    "Enter a song title or singer.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        int serial = ++searchSerial;

        resultsContainer.removeAllViews();
        loading(true);
        statusText.setText("Searching the public internet...");

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String address =
                        API + "/v1/web-search?q="
                                + encode(
                                        query + " karaoke OR instrumental"
                                );

                connection = open(address, "GET");

                int code = connection.getResponseCode();
                String body = read(connection, code);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Search returned HTTP " + code
                    );
                }

                JSONArray results =
                        new JSONObject(body).optJSONArray("results");

                runOnUiThread(() -> {
                    if (closed || serial != searchSerial) {
                        return;
                    }

                    loading(false);

                    if (results == null || results.length() == 0) {
                        statusText.setText(
                                "No results found. Try a different song title."
                        );
                        return;
                    }

                    statusText.setText(
                            results.length()
                                    + " internet result(s). Select one below."
                    );

                    showResults(results);
                });

            } catch (Exception e) {
                String msg = safe(e);

                runOnUiThread(() -> {
                    if (closed || serial != searchSerial) {
                        return;
                    }

                    loading(false);
                    statusText.setText(
                            "Internet search failed: " + msg
                    );
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void showResults(JSONArray results) {
        resultsContainer.removeAllViews();

        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);

            if (item == null) {
                continue;
            }

            String title =
                    item.optString("title", "Internet result");

            String url = item.optString("url", "");
            String source = item.optString("source", "");

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
            );
            card.setBackgroundColor(PANEL);

            LinearLayout.LayoutParams cp = matchWrap();
            cp.bottomMargin = dp(8);

            TextView t = text(title, 15, WHITE);
            t.setTypeface(null, Typeface.BOLD);

            card.addView(t, matchWrap());

            card.addView(
                    text(source + "\n" + url, 11, MUTED),
                    matchWrap()
            );

            LinearLayout buttons = new LinearLayout(this);

            Button select = button("SELECT");

            select.setOnClickListener(v -> {
                selectedUrl = url;
                selectedTitle = title;

                audioUrlBox.setText(url);
                nowPlayingText.setText(title);

                statusText.setText(
                        "Selected. If this page does not expose audio, "
                                + "paste a direct audio-file link."
                );
            });

            Button lyrics = button("LYRICS");

            lyrics.setOnClickListener(v -> {
                searchBox.setText(title);
                searchLyrics();
            });

            buttons.addView(select, weightButton());
            buttons.addView(lyrics, weightButton());

            card.addView(buttons, matchWrap());
            resultsContainer.addView(card, cp);
        }
    }

    private void playSelected() {
        String url =
                audioUrlBox.getText().toString().trim();

        if (url.isEmpty()) {
            url = selectedUrl;
        }

        if (url.isEmpty()) {
            Toast.makeText(
                    this,
                    "Select a result or enter a direct audio link.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (!url.matches("(?i)^https?://.*")) {
            statusText.setText(
                    "Enter a valid http or https URL."
            );
            return;
        }

        playAudio(
                url,
                selectedTitle.isEmpty()
                        ? "Selected internet audio"
                        : selectedTitle
        );
    }

    private void createInstrumental() {
        String url =
                audioUrlBox.getText().toString().trim();

        if (url.isEmpty()) {
            url = selectedUrl;
        }

        if (url.isEmpty()) {
            Toast.makeText(
                    this,
                    "Select an internet result or paste an audio/page URL first.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (!url.matches("(?i)^https?://.*")) {
            statusText.setText(
                    "Enter a valid http or https URL."
            );
            return;
        }

        if (!currentJobId.isEmpty()) {
            Toast.makeText(
                    this,
                    "A vocal-removal job is already running.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        final String sourceUrl = url;
        final String titleForJob =
                selectedTitle.isEmpty()
                        ? searchBox.getText().toString().trim()
                        : selectedTitle;

        int serial = ++jobSerial;

        loading(true);
        statusText.setText(
                "Sending online audio URL for vocal removal..."
        );

        executor.execute(() -> {
            HttpURLConnection c = null;

            try {
                c = open(API + "/v1/jobs/from-url", "POST");
                c.setDoOutput(true);

                c.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );

                JSONObject payload = new JSONObject();
                payload.put("url", sourceUrl);
                payload.put("title", titleForJob);
                payload.put("artist", "");

                try (OutputStream out = c.getOutputStream()) {
                    out.write(
                            payload.toString().getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );
                }

                int code = c.getResponseCode();
                String body = read(c, code);

                if (code < 200 || code >= 300) {
                    JSONObject error = new JSONObject(body);

                    throw new Exception(
                            error.optString(
                                    "detail",
                                    "Server returned HTTP " + code
                            )
                    );
                }

                String job =
                        new JSONObject(body).optString("job_id", "");

                if (job.isEmpty()) {
                    throw new Exception(
                            "Server did not return a job ID."
                    );
                }

                runOnUiThread(() -> {
                    if (closed || serial != jobSerial) {
                        return;
                    }

                    currentJobId = job;
                    loading(false);

                    statusText.setText(
                            "Vocal removal started. This may take several minutes."
                    );

                    pollJob(job, serial, 0);
                });

            } catch (Exception e) {
                String msg = safe(e);

                runOnUiThread(() -> {
                    if (closed || serial != jobSerial) {
                        return;
                    }

                    loading(false);
                    statusText.setText(
                            "Vocal removal failed: " + msg
                    );
                });

            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    private void pollJob(String job, int serial, int attempt) {
        if (closed || serial != jobSerial) {
            return;
        }

        if (attempt >= 100) {
            currentJobId = "";

            statusText.setText(
                    "Processing timed out. Try again with a direct audio-file URL."
            );

            return;
        }

        pollRunnable = () -> executor.execute(() -> {
            HttpURLConnection c = null;

            try {
                c = open(
                        API + "/v1/jobs/" + encode(job),
                        "GET"
                );

                int code = c.getResponseCode();
                String body = read(c, code);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Status returned HTTP " + code
                    );
                }

                JSONObject o = new JSONObject(body);

                String state =
                        o.optString("status", "")
                                .toLowerCase(Locale.ROOT);

                String error = o.optString("error", "");

                runOnUiThread(() -> {
                    if (closed || serial != jobSerial) {
                        return;
                    }

                    if ("completed".equals(state)
                            || "complete".equals(state)
                            || "success".equals(state)) {

                        currentJobId = "";
                        loading(false);

                        statusText.setText(
                                "Karaoke instrumental ready. Starting playback..."
                        );

                        playAudio(
                                API + "/v1/jobs/" + job + "/instrumental",
                                selectedTitle.isEmpty()
                                        ? "Karaoke instrumental"
                                        : selectedTitle
                        );

                    } else if ("failed".equals(state)
                            || "error".equals(state)) {

                        currentJobId = "";
                        loading(false);

                        statusText.setText(
                                "Processing failed: "
                                        + (error.isEmpty()
                                        ? "try a direct audio link"
                                        : error)
                        );

                    } else {
                        statusText.setText(
                                "Vocal removal: "
                                        + o.optString("progress", state)
                                        + "..."
                        );

                        pollJob(job, serial, attempt + 1);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!closed && serial == jobSerial) {
                        pollJob(job, serial, attempt + 1);
                    }
                });

            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });

        handler.postDelayed(pollRunnable, 3000);
    }

    private void searchLyrics() {
        String query =
                searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            query = selectedTitle;
        }

        if (query.isEmpty()) {
            Toast.makeText(
                    this,
                    "Enter a song title first.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        final String lyricQuery = query;
        int serial = ++lyricsSerial;

        lyricsContainer.removeAllViews();
        lyricsStatus.setText("Searching for lyrics...");

        executor.execute(() -> {
            HttpURLConnection c = null;

            try {
                c = open(
                        "https://lrclib.net/api/search?q="
                                + encode(lyricQuery),
                        "GET"
                );

                int code = c.getResponseCode();
                String body = read(c, code);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Lyrics service returned HTTP " + code
                    );
                }

                JSONArray arr = new JSONArray(body);

                JSONObject best =
                        arr.length() > 0
                                ? arr.getJSONObject(0)
                                : null;

                String lyrics = "";

                if (best != null) {
                    lyrics = best.optString("syncedLyrics", "");

                    if (lyrics.isEmpty()) {
                        lyrics = best.optString("plainLyrics", "");
                    }
                }

                String finalLyrics = lyrics;

                runOnUiThread(() -> {
                    if (closed || serial != lyricsSerial) {
                        return;
                    }

                    lyricsContainer.removeAllViews();

                    if (finalLyrics.trim().isEmpty()) {
                        lyricsStatus.setText(
                                "No lyrics found for this title."
                        );

                    } else {
                        lyricsStatus.setText(
                                "Lyrics found. Synced lyrics are shown with their timestamps."
                        );

                        TextView view =
                                text(finalLyrics, 14, WHITE);

                        view.setTextIsSelectable(true);
                        view.setPadding(
                                dp(8),
                                dp(8),
                                dp(8),
                                dp(8)
                        );

                        lyricsContainer.addView(
                                view,
                                matchWrap()
                        );
                    }
                });

            } catch (Exception e) {
                String msg = safe(e);

                runOnUiThread(() -> {
                    if (!closed && serial == lyricsSerial) {
                        lyricsStatus.setText(
                                "Lyrics search failed: " + msg
                        );
                    }
                });

            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    private void playAudio(String url, String title) {
        releasePlayer();

        statusText.setText("Loading audio...");
        nowPlayingText.setText(title);

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

            mediaPlayer.setOnPreparedListener(mp -> {
                if (!closed) {
                    mp.start();
                    statusText.setText("Playing: " + title);
                }
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                if (!closed) {
                    statusText.setText("Song finished.");
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (!closed) {
                    statusText.setText(
                            "This page is not a direct playable audio stream. "
                                    + "Try a direct audio-file link."
                    );
                }

                releasePlayer();
                return true;
            });

            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            releasePlayer();

            statusText.setText(
                    "Playback failed: " + safe(e)
            );
        }
    }

    private void pausePlayback() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
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

    private HttpURLConnection open(
            String address,
            String method
    ) throws Exception {

        HttpURLConnection c =
                (HttpURLConnection) new URL(address).openConnection();

        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);

        c.setRequestProperty(
                "Accept",
                "application/json, audio/*, */*"
        );

        return c;
    }

    private String read(
            HttpURLConnection c,
            int code
    ) throws Exception {

        InputStream in =
                code >= 200 && code < 400
                        ? c.getInputStream()
                        : c.getErrorStream();

        if (in == null) {
            return "";
        }

        StringBuilder out = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        in,
                        StandardCharsets.UTF_8
                ))) {

            String line;

            while ((line = br.readLine()) != null) {
                out.append(line);
            }
        }

        return out.toString();
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
            progressBar.setVisibility(
                    value ? View.VISIBLE : View.GONE
            );
        }
    }

    private int dp(float v) {
        return (int) (
                v * getResources().getDisplayMetrics().density
                        + 0.5f
        );
    }

    private TextView text(
            String s,
            int size,
            int color
    ) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundColor(GREEN);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullButton() {
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(-1, dp(48));

        p.topMargin = dp(5);
        p.bottomMargin = dp(5);

        return p;
    }

    private LinearLayout.LayoutParams weightButton() {
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(0, dp(46), 1);

        p.setMargins(dp(2), dp(2), dp(2), dp(2));

        return p;
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
