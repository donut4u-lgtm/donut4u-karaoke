
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LinearLayout root;
    private LinearLayout resultsContainer;
    private EditText searchInput;
    private TextView searchStatus;
    private TextView selectedSong;
    private TextView playbackStatus;
    private Button searchButton;
    private Button playButton;

    private AdView adView;
    private MediaPlayer mediaPlayer;
    private Uri selectedAudioUri;

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

        TextView title = text("DONUT4U KARAOKE STUDIO", 24, GREEN, true);
        content.addView(title, matchWrap());

        TextView subtitle = text(
                "PAGE 2 — Search songs, find lyrics, and play audio.",
                14, WHITE, false);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        content.addView(subtitle, matchWrap());

        section(content, "1. SEARCH ONLINE MUSIC");

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Enter song title or artist");
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setTextColor(WHITE);
        searchInput.setTextSize(16);
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchInput.setBackgroundColor(PANEL);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(searchInput, matchWrap());

        searchButton = button("SEARCH SONGS ONLINE");
        content.addView(searchButton, matchWrap());
        searchButton.setOnClickListener(v -> searchCatalog());

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchCatalog();
                return true;
            }
            return false;
        });

        searchStatus = text("Enter a song title to search the catalog.",
                14, WHITE, false);
        searchStatus.setPadding(0, dp(12), 0, dp(8));
        content.addView(searchStatus, matchWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsContainer, matchWrap());

        Button lyricsButton = button("FIND LYRICS");
        content.addView(lyricsButton, matchWrap());
        lyricsButton.setOnClickListener(v -> {
            String query = searchInput.getText().toString().trim();
            if (query.isEmpty()) {
                searchInput.setError("Enter a song title or artist");
                return;
            }
            openWebSearch(query + " lyrics");
        });

        Button urlButton = button("SEARCH INTERNET");
        content.addView(urlButton, matchWrap());
        urlButton.setOnClickListener(v -> {
            String query = searchInput.getText().toString().trim();
            if (query.isEmpty()) {
                searchInput.setError("Enter a song title or artist");
                return;
            }
            openWebSearch(query);
        });

        section(content, "2. CHOOSE AUDIO");

        Button chooseButton = button("CHOOSE AUDIO FILE");
        content.addView(chooseButton, matchWrap());
        chooseButton.setOnClickListener(v -> chooseAudio());

        selectedSong = text("No audio file selected.", 14, WHITE, false);
        selectedSong.setPadding(0, dp(8), 0, dp(8));
        content.addView(selectedSong, matchWrap());

        section(content, "3. KARAOKE PLAYER");

        TextView info = text(
                "Choose an audio file to play. To use an instrumental track, "
                        + "select an instrumental audio file you have permission to use.",
                13, WHITE, false);
        content.addView(info, matchWrap());

        playbackStatus = text("Ready.", 14, WHITE, false);
        playbackStatus.setPadding(0, dp(10), 0, dp(4));
        content.addView(playbackStatus, matchWrap());

        playButton = button("PLAY AUDIO");
        content.addView(playButton, matchWrap());
        playButton.setOnClickListener(v -> togglePlayback());

        Button stopButton = button("STOP AUDIO");
        content.addView(stopButton, matchWrap());
        stopButton.setOnClickListener(v -> stopPlayback());

        Button backButton = button("BACK");
        content.addView(backButton, matchWrap());
        backButton.setOnClickListener(v -> finish());

        TextView footer = text(
                "Online catalog results depend on the music service. "
                        + "A catalog result does not necessarily provide a playable audio file.",
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

    private void searchCatalog() {
        String query = searchInput.getText().toString().trim();

        if (query.isEmpty()) {
            searchInput.setError("Enter a song title or artist");
            return;
        }

        searchButton.setEnabled(false);
        searchStatus.setText("Connecting to music catalog...");
        resultsContainer.removeAllViews();
        hideKeyboard();

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String encoded = URLEncoder.encode(
                        query, StandardCharsets.UTF_8.name());

                URL url = new URL(
                        API_BASE + "/v1/catalog-search?q=" + encoded);

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Accept", "application/json");

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                String response = readStream(stream);

                if (code < 200 || code >= 300) {
                    throw new Exception("Catalog server HTTP " + code
                            + (response.isEmpty() ? "" : ": " + response));
                }

                JSONObject json = new JSONObject(response);
                JSONArray results = json.optJSONArray("results");

                if (results == null) {
                    throw new Exception("The catalog response has no results list.");
                }

                JSONArray tracks = results;

                mainHandler.post(() -> {
                    searchButton.setEnabled(true);
                    if (tracks.length() == 0) {
                        searchStatus.setText(
                                "No songs returned. Try another title or artist.");
                    } else {
                        searchStatus.setText(
                                "Found " + tracks.length() + " catalog results.");
                        displayResults(tracks);
                    }
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Unknown connection error" : e.getMessage();

                mainHandler.post(() -> {
                    searchButton.setEnabled(true);
                    searchStatus.setText("Search failed: " + message);
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }

    private void displayResults(JSONArray results) {
        resultsContainer.removeAllViews();

        for (int i = 0; i < results.length(); i++) {
            JSONObject track = results.optJSONObject(i);
            if (track == null) {
                continue;
            }

            String name = track.optString("name", "Unknown song");
            String artist = track.optString("artist", "Unknown artist");
            String id = track.optString("id", "");
            int duration = track.optInt("duration", 0);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(PANEL);

            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = dp(8);
            resultsContainer.addView(card, cardParams);

            card.addView(text(name, 17, GREEN, true), matchWrap());
            card.addView(text("Artist: " + artist, 14, WHITE, false),
                    matchWrap());
            card.addView(text("Duration: " + formatDuration(duration),
                    13, Color.LTGRAY, false), matchWrap());

            if (!id.isEmpty()) {
                card.addView(text("Catalog ID: " + id,
                        12, Color.LTGRAY, false), matchWrap());
            }

            Button webButton = button("SEARCH THIS SONG ON THE INTERNET");
            card.addView(webButton, matchWrap());
            webButton.setOnClickListener(v -> openWebSearch(name + " " + artist));
        }
    }

    private String formatDuration(int seconds) {
        if (seconds <= 0) {
            return "Unknown";
        }
        return String.format(Locale.US, "%d:%02d",
                seconds / 60, seconds % 60);
    }

    private void openWebSearch(String query) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q="
                            + Uri.encode(query)));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open internet search.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(intent, 4101);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open audio picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 4101 && resultCode == RESULT_OK
                && data != null && data.getData() != null) {

            stopPlayback();
            selectedAudioUri = data.getData();

            try {
                getContentResolver().takePersistableUriPermission(
                        selectedAudioUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }

            selectedSong.setText("Selected audio:\n" + selectedAudioUri);
            playbackStatus.setText("Audio ready.");
        }
    }

    private void togglePlayback() {
        if (selectedAudioUri == null) {
            Toast.makeText(this, "Choose an audio file first.",
                    Toast.LENGTH_LONG).show();
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
            }
            return;
        }

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, selectedAudioUri);

            mediaPlayer.setOnPreparedListener(player -> {
                player.start();
                playButton.setText("PAUSE AUDIO");
                playbackStatus.setText("Playing.");
            });

            mediaPlayer.setOnCompletionListener(player -> {
                stopPlayback();
                playbackStatus.setText("Playback finished.");
            });

            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                stopPlayback();
                playbackStatus.setText("Unable to play this audio file.");
                return true;
            });

            playbackStatus.setText("Loading audio...");
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            stopPlayback();
            playbackStatus.setText("Unable to open this audio file.");
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
            playButton.setText("PLAY AUDIO");
        }
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
        } catch (Exception e) {
            // Keep Studio usable if ads fail to initialize.
        }
    }

    private void section(LinearLayout parent, String title) {
        TextView heading = text(title, 17, GREEN, true);
        heading.setPadding(0, dp(20), 0, dp(8));
        parent.addView(heading, matchWrap());
    }

    private TextView text(String value, int size, int color, boolean bold) {
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
        return (int) (value * getResources().getDisplayMetrics().density
                + 0.5f);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
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
        stopPlayback();

        if (adView != null) {
            adView.destroy();
            adView = null;
        }

        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
