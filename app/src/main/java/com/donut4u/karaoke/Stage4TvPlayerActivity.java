
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.TextureView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;
import java.util.Locale;

public class Stage4TvPlayerActivity extends Activity {

    private static final String EXTRA_NUMBER = "stage4_song_number";
    private static final String EXTRA_TITLE = "stage4_song_title";
    private static final String EXTRA_URI = "stage4_song_uri";
    private static final String EXTRA_TYPE = "stage4_song_type";

    private static final int BACKGROUND = Color.rgb(8, 20, 25);
    private static final int GREEN = Color.rgb(0, 220, 115);
    private static final int WHITE = Color.rgb(245, 250, 247);
    private static final int MUTED = Color.rgb(175, 193, 187);

    private final Handler handler = new Handler();

    private ExoPlayer mediaPlayer;
    private Stage3MidiEngine midiEngine;
    private Stage3MidiLyrics midiLyrics = Stage3MidiLyrics.empty();

    private TextureView videoTexture;
    private ScrollView lyricsScroll;
    private TextView titleLabel;
    private TextView statusLabel;
    private TextView lyricsLabel;
    private TextView timeLabel;
    private Button playButton;

    private String songTitle = "";
    private String songUri = "";
    private String songType = "";

    private boolean midiMode;
    private boolean midiReady;
    private boolean released;
    private boolean pausedForLifecycle;

    private int lastLyricLine = Integer.MIN_VALUE;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (released) {
                return;
            }

            if (midiMode && midiReady && midiEngine != null) {
                updateMidiDisplay();
            } else if (!midiMode && mediaPlayer != null) {
                updateMediaDisplay();
            }

            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        songTitle = getIntent().getStringExtra(EXTRA_TITLE);
        songUri = getIntent().getStringExtra(EXTRA_URI);
        songType = getIntent().getStringExtra(EXTRA_TYPE);

        if (songTitle == null) songTitle = "Karaoke Song";
        if (songUri == null) songUri = "";
        if (songType == null) songType = "AUDIO";

        midiMode = songType.toUpperCase(Locale.ROOT)
                .contains("MIDI");

        buildScreen();

        if (songUri.trim().isEmpty()) {
            showError("The song file location is missing.");
            return;
        }

        if (midiMode) {
            startMidiSong();
        } else {
            startMediaSong();
        }

        handler.post(progressUpdater);
    }

    private int dp(float value) {
        return (int) (
                value * getResources().getDisplayMetrics().density
                        + 0.5f
        );
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(28), dp(12), dp(28), dp(12));

        titleLabel = new TextView(this);
        titleLabel.setText(songTitle);
        titleLabel.setTextColor(GREEN);
        titleLabel.setTextSize(25);
        titleLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleLabel.setGravity(Gravity.CENTER);
        titleLabel.setMaxLines(2);

        root.addView(
                titleLabel,
                new LinearLayout.LayoutParams(-1, dp(58))
        );

        statusLabel = new TextView(this);
        statusLabel.setText("Preparing song...");
        statusLabel.setTextColor(MUTED);
        statusLabel.setTextSize(15);
        statusLabel.setGravity(Gravity.CENTER);

        root.addView(
                statusLabel,
                new LinearLayout.LayoutParams(-1, dp(34))
        );

        videoTexture = new TextureView(this);
        videoTexture.setBackgroundColor(Color.BLACK);

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(-1, 0, 1f);

        root.addView(videoTexture, videoParams);

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setBackgroundColor(BACKGROUND);

        lyricsLabel = new TextView(this);
        lyricsLabel.setTextColor(WHITE);
        lyricsLabel.setTextSize(27);
        lyricsLabel.setGravity(Gravity.CENTER);
        lyricsLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lyricsLabel.setPadding(
                dp(24), dp(22), dp(24), dp(22)
        );
        lyricsLabel.setText("Get ready to sing!");

        lyricsScroll.addView(
                lyricsLabel,
                new ScrollView.LayoutParams(-1, -2)
        );

        LinearLayout.LayoutParams lyricsParams =
                new LinearLayout.LayoutParams(-1, 0, 2f);

        root.addView(lyricsScroll, lyricsParams);

        timeLabel = new TextView(this);
        timeLabel.setTextColor(MUTED);
        timeLabel.setTextSize(15);
        timeLabel.setGravity(Gravity.CENTER);

        root.addView(
                timeLabel,
                new LinearLayout.LayoutParams(-1, dp(30))
        );

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        playButton = createButton("PAUSE");
        playButton.setOnClickListener(v -> togglePlayback());

        Button stopButton = createButton("STOP");
        stopButton.setOnClickListener(v -> stopPlayback());

        Button backButton = createButton("BACK");
        backButton.setOnClickListener(v -> finish());

        controls.addView(
                playButton,
                new LinearLayout.LayoutParams(dp(180), dp(58))
        );

        controls.addView(
                stopButton,
                new LinearLayout.LayoutParams(dp(180), dp(58))
        );

        controls.addView(
                backButton,
                new LinearLayout.LayoutParams(dp(180), dp(58))
        );

        root.addView(
                controls,
                new LinearLayout.LayoutParams(-1, dp(72))
        );

        setContentView(root);

        if (!songType.toUpperCase(Locale.ROOT).contains("VIDEO")) {
            videoTexture.setVisibility(View.GONE);
        }
    }

    private Button createButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(Color.BLACK);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private void startMidiSong() {
        statusLabel.setText("Preparing MIDI / KAR playback...");
        lyricsLabel.setText("Loading timed karaoke lyrics...");

        try {
            midiEngine = Stage3MidiEngine.create(this);

            midiEngine.play(
                    songUri,
                    "builtin://default",
                    new Stage3MidiEngine.Callback() {

                        @Override
                        public void onReady(long durationTicks) {
                            if (released) return;

                            midiReady = true;

                            File midiFile = new File(
                                    getFilesDir(),
                                    "stage3_midi/song.mid"
                            );

                            midiLyrics = Stage3MidiLyrics.read(midiFile);

                            if (midiLyrics.hasLyrics()) {
                                lyricsLabel.setText(
                                        midiLyrics.styledFullTextAtTick(
                                                0L,
                                                durationTicks
                                        )
                                );
                                statusLabel.setText("MIDI / KAR PLAYING");
                            } else {
                                lyricsLabel.setText(
                                        "No timed lyrics were found in this MIDI/KAR file."
                                );
                                statusLabel.setText("MIDI / KAR PLAYING");
                            }

                            updateMidiDisplay();
                        }

                        @Override
                        public void onError(String message) {
                            if (released) return;

                            midiReady = false;

                            showError(
                                    "MIDI playback error: " + message
                            );
                        }

                        @Override
                        public void onFinished() {
                            if (released) return;

                            midiReady = false;
                            statusLabel.setText("SONG FINISHED");
                            playButton.setText("PLAY");
                        }
                    }
            );

        } catch (Exception e) {
            showError(
                    "Could not start MIDI playback: "
                            + e.getMessage()
            );
        }
    }

    private void startMediaSong() {
        try {
            statusLabel.setText("Loading audio / video...");

            mediaPlayer = new ExoPlayer.Builder(this).build();

            if (songType.toUpperCase(Locale.ROOT).contains("VIDEO")) {
                videoTexture.setVisibility(View.VISIBLE);
                mediaPlayer.setVideoTextureView(videoTexture);
            }

            mediaPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (released) return;

                    if (playbackState == Player.STATE_READY) {
                        statusLabel.setText("PLAYING");
                    } else if (playbackState == Player.STATE_ENDED) {
                        statusLabel.setText("SONG FINISHED");
                        playButton.setText("PLAY");
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        statusLabel.setText("BUFFERING...");
                    }
                }

                @Override
                public void onPlayerError(
                        androidx.media3.common.PlaybackException error
                ) {
                    showError(
                            "Media playback error: " + error.getMessage()
                    );
                }
            });

            mediaPlayer.setMediaItem(
                    MediaItem.fromUri(Uri.parse(songUri))
            );

            mediaPlayer.prepare();
            mediaPlayer.play();

        } catch (Exception e) {
            showError(
                    "Could not start audio/video: "
                            + e.getMessage()
            );
        }
    }

    private void togglePlayback() {
        if (midiMode) {
            if (!midiReady || midiEngine == null) return;

            if (midiEngine.isPlaying()) {
                midiEngine.pause();
                playButton.setText("PLAY");
                statusLabel.setText("PAUSED");
            } else {
                midiEngine.resume();
                playButton.setText("PAUSE");
                statusLabel.setText("PLAYING");
            }

        } else if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playButton.setText("PLAY");
                statusLabel.setText("PAUSED");
            } else {
                mediaPlayer.play();
                playButton.setText("PAUSE");
                statusLabel.setText("PLAYING");
            }
        }
    }

    private void stopPlayback() {
        if (midiMode) {
            if (midiEngine != null) {
                midiEngine.stop();
            }

            midiReady = false;
            playButton.setText("PLAY");
            statusLabel.setText("STOPPED");
            lyricsLabel.setText("Song stopped.");

        } else if (mediaPlayer != null) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0L);
            playButton.setText("PLAY");
            statusLabel.setText("STOPPED");
        }
    }

    private void updateMidiDisplay() {
        if (midiEngine == null || !midiReady) return;

        long tick = midiEngine.getCurrentTick();
        long duration = midiEngine.getDurationTicks();

        lyricsLabel.setText(
                midiLyrics.styledFullTextAtTick(tick, duration)
        );

        int line = midiLyrics.activeLineNumberAtTick(tick);

        if (line != lastLyricLine) {
            lastLyricLine = line;

            final int offset = Math.max(
                    0,
                    midiLyrics.activeLineStartOffsetAtTick(tick)
            );

            lyricsScroll.post(() -> {
                if (released) return;

                android.text.Layout layout =
                        lyricsLabel.getLayout();

                if (layout != null
                        && offset <= lyricsLabel.length()) {
                    int safeOffset = Math.min(
                            offset,
                            Math.max(0, lyricsLabel.length() - 1)
                    );

                    int lineNumber =
                            layout.getLineForOffset(safeOffset);

                    lyricsScroll.smoothScrollTo(
                            0,
                            layout.getLineTop(lineNumber)
                    );
                }
            });
        }

        timeLabel.setText(
                "MIDI TICK: " + tick
                        + " / " + duration
        );

        playButton.setText(
                midiEngine.isPlaying() ? "PAUSE" : "PLAY"
        );
    }

    private void updateMediaDisplay() {
        if (mediaPlayer == null) return;

        long position = mediaPlayer.getCurrentPosition();
        long duration = mediaPlayer.getDuration();

        timeLabel.setText(
                formatTime(position)
                        + " / "
                        + formatTime(duration)
        );

        playButton.setText(
                mediaPlayer.isPlaying() ? "PAUSE" : "PLAY"
        );
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        return String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds
        );
    }

    private void showError(String message) {
        statusLabel.setText("PLAYBACK ERROR");
        lyricsLabel.setText(message);
        playButton.setText("PLAY");
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (midiMode && midiReady && midiEngine != null
                && midiEngine.isPlaying()) {
            midiEngine.pause();
            pausedForLifecycle = true;
        } else if (!midiMode && mediaPlayer != null
                && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            pausedForLifecycle = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!pausedForLifecycle || released) return;

        pausedForLifecycle = false;

        if (midiMode && midiReady && midiEngine != null) {
            midiEngine.resume();
        } else if (!midiMode && mediaPlayer != null) {
            mediaPlayer.play();
        }
    }

    @Override
    protected void onDestroy() {
        released = true;
        handler.removeCallbacks(progressUpdater);

        if (midiEngine != null) {
            midiEngine.release();
            midiEngine = null;
        }

        if (mediaPlayer != null) {
            mediaPlayer.clearVideoTextureView(videoTexture);
            mediaPlayer.release();
            mediaPlayer = null;
        }

        super.onDestroy();
    }
}
