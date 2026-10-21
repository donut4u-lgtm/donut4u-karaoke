
package com.donut4u.karaoke;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
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

    private static final int REQUEST_AUDIO = 4101;

    private static final String API_BASE =
            "https://donut4u-karaoke.onrender.com";

    private final int GREEN = Color.rgb(0, 190, 95);
    private final int DARK = Color.rgb(18, 22, 20);
    private final int PANEL = Color.rgb(35, 42, 38);
    private final int WHITE = Color.WHITE;

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor();

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private TextView selectedSong;
    private TextView playbackStatus;
    private TextView searchStatus;
    private Button playButton;
    private Button searchButton;
    private EditText songSearch;
    private LinearLayout resultsContainer;
    private Uri selectedAudioUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    private void buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(DARK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        root.setBackgroundColor(DARK);

        scrollView.addView(root);
        setContentView(scrollView);

        TextView title = new TextView(this);
        title.setText("Donut4U Karaoke Studio");
        title.setTextColor(GREEN);
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "Search online music, choose audio, and prepare for karaoke."
        );
        subtitle.setTextColor(WHITE);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle);

        addSection(root, "1. SEARCH FOR A SONG");

        songSearch = new EditText(this);
        songSearch.setSingleLine(true);
        songSearch.setHint("Enter song title or artist");
        songSearch.setHintTextColor(Color.LTGRAY);
        songSearch.setTextColor(WHITE);
        songSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        songSearch.setBackgroundColor(PANEL);
        root.addView(songSearch, matchWrap());

        searchButton = makeButton("Search Songs Online");
        root.addView(searchButton, matchWrap());

        searchButton.setOnClickListener(v -> {
            String query = songSearch.getText().toString().trim();

            if (query.isEmpty()) {
                songSearch.setError("Enter a song title or artist");
                return;
            }

            searchCatalog(query);
        });

        searchStatus = new TextView(this);
        searchStatus.setText("Search the online music catalog.");
        searchStatus.setTextColor(Color.LTGRAY);
        searchStatus.setTextSize(14);
        searchStatus.setPadding(0, dp(12), 0, dp(8));
        root.addView(searchStatus);

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, matchWrap());

        Button lyricsButton = makeButton("Search Song Lyrics");
        root.addView(lyricsButton, matchWrap());

        lyricsButton.setOnClickListener(v -> {
            String query = songSearch.getText().toString().trim();

            if (query.isEmpty()) {
                songSearch.setError("Enter a song title or artist");
                return;
            }

            openWebSearch(query + " lyrics");
        });

        addSection(root, "2. SELECT YOUR MUSIC");

        TextView fileInfo = new TextView(this);
        fileInfo.setText(
                "Choose an audio file stored on your phone."
        );
        fileInfo.setTextColor(WHITE);
        fileInfo.setTextSize(14);
        root.addView(fileInfo);

        Button chooseButton = makeButton("Choose Audio File");
        root.addView(chooseButton, matchWrap());

        selectedSong = new TextView(this);
        selectedSong.setText("No audio file selected");
        selectedSong.setTextColor(Color.LTGRAY);
        selectedSong.setTextSize(14);
        selectedSong.setPadding(0, dp(8), 0, dp(8));
        root.addView(selectedSong);

        chooseButton.setOnClickListener(v -> chooseAudioFile());

        addSection(root, "3. INSTRUMENTAL / VOCAL REMOVAL");

        TextView vocalInfo = new TextView(this);
        vocalInfo.setText(
                "Find an online vocal-removal service. After processing, " +
                "return here and choose the instrumental audio file."
        );
        vocalInfo.setTextColor(WHITE);
        vocalInfo.setTextSize(14);
        root.addView(vocalInfo);

        Button vocalButton = makeButton("Find Vocal Remover");
        root.addView(vocalButton, matchWrap());

        vocalButton.setOnClickListener(v ->
                openWebSearch("online AI vocal remover audio instrumental")
        );

        addSection(root, "4. KARAOKE PLAYER");

        playbackStatus = new TextView(this);
        playbackStatus.setText("Ready to select music");
        playbackStatus.setTextColor(WHITE);
        playbackStatus.setTextSize(14);
        root.addView(playbackStatus);

        playButton = makeButton("Play Instrumental");
        root.addView(playButton, matchWrap());

        playButton.setOnClickListener(v -> togglePlayback());

        Button stopButton = makeButton("Stop Music");
        root.addView(stopButton, matchWrap());

        stopButton.setOnClickListener(v -> stopPlayback());

        Button backButton = makeButton("Back");
        root.addView(backButton, matchWrap());

        backButton.setOnClickListener(v -> finish());

        TextView note = new TextView(this);
        note.setText(
                "Online search displays catalog information. " +
                "Downloading music, creating an instrumental, and " +
                "synchronized lyrics are separate steps."
        );
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(12);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);
    }

    private void searchCatalog(String query) {
        searchButton.setEnabled(false);
        searchStatus.setText("Searching online music catalog...");
        resultsContainer.removeAllViews();

        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String encodedQuery = URLEncoder.encode(
                        query,
                        StandardCharsets.UTF_8.name()
                );

                URL url = new URL(
                        API_BASE + "/v1/catalog-search?q=" + encodedQuery
                );

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(25000);
                connection.setRequestProperty(
                        "Accept",
                        "application/json"
                );

                int responseCode = connection.getResponseCode();

                InputStream stream;

                if (responseCode >= 200 && responseCode < 300) {
                    stream = connection.getInputStream();
                } else {
                    stream = connection.getErrorStream();

                    String errorMessage = readStream(stream);

                    throw new IOException(
                            "Server returned HTTP " + responseCode
                    );
                }

                String response = readStream(stream);
                JSONObject json = new JSONObject(response);
                JSONArray results = json.optJSONArray("results");

                if (results == null) {
                    throw new IOException(
                            "The server returned an unexpected response."
                    );
                }

                final JSONArray foundTracks = results;

                mainHandler.post(() -> {
                    searchButton.setEnabled(true);

                    if (foundTracks.length() == 0) {
                        searchStatus.setText(
                                "No downloadable tracks found. Try another search."
                        );
                        return;
                    }

                    searchStatus.setText(
                            "Found " + foundTracks.length() + " songs."
                    );

                    displayResults(foundTracks);
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    searchButton.setEnabled(true);
                    searchStatus.setText(
                            "Search failed. Check your internet and try again."
                    );

                    Toast.makeText(
                            KaraokeStudioActivity.this,
                            "Unable to search songs. Please try again.",
                            Toast.LENGTH_LONG
                    ).show();
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream,
                        StandardCharsets.UTF_8
                ))) {

            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }

    private void displayResults(JSONArray results) {
        resultsContainer.removeAllViews();

        for (int i = 0; i < results.length(); i++) {
            JSONObject track = results.optJSONObject(i);

            if (track == null) {
                continue;
            }

            String trackId = track.optString("id", "");
            String trackName = track.optString("name", "Unknown title");
            String artist = track.optString("artist", "Unknown artist");
            int duration = track.optInt("duration", 0);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackgroundColor(PANEL);

            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = dp(10);
            resultsContainer.addView(card, cardParams);

            TextView nameView = new TextView(this);
            nameView.setText(trackName);
            nameView.setTextColor(GREEN);
            nameView.setTextSize(17);
            nameView.setTypeface(null, Typeface.BOLD);
            card.addView(nameView);

            TextView artistView = new TextView(this);
            artistView.setText("Artist: " + artist);
            artistView.setTextColor(WHITE);
            artistView.setTextSize(14);
            artistView.setPadding(0, dp(5), 0, 0);
            card.addView(artistView);

            TextView durationView = new TextView(this);
            durationView.setText(
                    "Duration: " + formatDuration(duration)
            );
            durationView.setTextColor(Color.LTGRAY);
            durationView.setTextSize(13);
            durationView.setPadding(0, dp(4), 0, 0);
            card.addView(durationView);

            TextView idView = new TextView(this);
            idView.setText("Catalog ID: " + trackId);
            idView.setTextColor(Color.LTGRAY);
            idView.setTextSize(12);
            idView.setPadding(0, dp(4), 0, 0);
            card.addView(idView);
        }
    }

    private String formatDuration(int seconds) {
        if (seconds <= 0) {
            return "Unknown";
        }

        return String.format(
                Locale.US,
                "%d:%02d",
                seconds / 60,
                seconds % 60
        );
    }

    private void addSection(LinearLayout root, String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextColor(GREEN);
        heading.setTextSize(17);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, dp(22), 0, dp(8));
        root.addView(heading);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(GREEN)
        );

        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        button.setLayoutParams(params);

        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private void openWebSearch(String query) {
        try {
            Uri uri = Uri.parse(
                    "https://www.google.com/search?q=" + Uri.encode(query)
            );

            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to open online search",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void chooseAudioFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(intent, REQUEST_AUDIO);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to open audio file picker",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_AUDIO
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {

            selectedAudioUri = data.getData();

            try {
                getContentResolver().takePersistableUriPermission(
                        selectedAudioUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (SecurityException ignored) {
                // Some document providers do not grant persistent access.
            }

            stopPlayback();

            selectedSong.setText(
                    "Selected audio:\n" +
                    selectedAudioUri.getLastPathSegment()
            );

            playbackStatus.setText("Audio selected and ready to play");
            playButton.setText("Play Instrumental");
        }
    }

    private void togglePlayback() {
        if (selectedAudioUri == null) {
            Toast.makeText(
                    this,
                    "Choose an audio file first",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playButton.setText("Resume Music");
                playbackStatus.setText("Music paused");
            } else {
                mediaPlayer.start();
                playButton.setText("Pause Music");
                playbackStatus.setText("Playing audio");
            }
            return;
        }

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, selectedAudioUri);

            mediaPlayer.setOnPreparedListener(player -> {
                player.start();
                playButton.setText("Pause Music");
                playbackStatus.setText("Playing audio");
            });

            mediaPlayer.setOnCompletionListener(player -> {
                playButton.setText("Play Instrumental");
                playbackStatus.setText("Playback finished");
                releasePlayer();
            });

            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                playbackStatus.setText(
                        "Unable to play this audio file"
                );
                releasePlayer();
                return true;
            });

            playbackStatus.setText("Loading audio...");
            mediaPlayer.prepareAsync();

        } catch (IOException | IllegalStateException e) {
            playbackStatus.setText("Unable to open this audio file");
            releasePlayer();

            Toast.makeText(
                    this,
                    "Could not play the selected audio",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void stopPlayback() {
        releasePlayer();

        if (playbackStatus != null) {
            playbackStatus.setText("Playback stopped");
        }

        if (playButton != null) {
            playButton.setText("Play Instrumental");
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        networkExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
