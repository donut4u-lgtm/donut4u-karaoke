
package com.donut4u.karaoke;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public class KaraokeStudioActivity extends Activity {

    private static final int REQUEST_AUDIO = 4101;

    private final int GREEN = Color.rgb(0, 190, 95);
    private final int DARK = Color.rgb(18, 22, 20);
    private final int WHITE = Color.WHITE;

    private MediaPlayer mediaPlayer;
    private TextView selectedSong;
    private TextView playbackStatus;
    private Button playButton;
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
                "Find songs, choose music, and prepare for karaoke."
        );
        subtitle.setTextColor(WHITE);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle);

        addSection(root, "1. SEARCH FOR A SONG");

        EditText songSearch = new EditText(this);
        songSearch.setSingleLine(true);
        songSearch.setHint("Enter song title or artist");
        songSearch.setHintTextColor(Color.LTGRAY);
        songSearch.setTextColor(WHITE);
        songSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        songSearch.setBackgroundColor(Color.rgb(43, 49, 45));
        root.addView(songSearch, matchWrap());

        Button searchButton = makeButton("Search Songs Online");
        root.addView(searchButton, matchWrap());

        searchButton.setOnClickListener(v -> {
            String query = songSearch.getText().toString().trim();

            if (query.isEmpty()) {
                songSearch.setError("Enter a song title or artist");
                return;
            }

            openWebSearch(query + " song");
        });

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
                "Find an audio vocal-removal service. After processing, " +
                "return here and select the instrumental audio file."
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
                "Note: Online searches open your browser. This version " +
                "does not download songs automatically, remove vocals " +
                "inside the app, or synchronize lyrics to the music yet."
        );
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(12);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);
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

            getContentResolver().takePersistableUriPermission(
                    selectedAudioUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            stopPlayback();

            selectedSong.setText(
                    "Selected audio:\n" + selectedAudioUri.getLastPathSegment()
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
                playbackStatus.setText("Unable to play this audio file");
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
        super.onDestroy();
    }
}
