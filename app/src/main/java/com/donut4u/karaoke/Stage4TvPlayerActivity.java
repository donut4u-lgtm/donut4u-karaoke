
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
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
    private static final int PANEL = Color.rgb(20, 39, 42);
    private static final int GREEN = Color.rgb(0, 220, 115);
    private static final int WHITE = Color.rgb(245, 250, 247);
    private static final int MUTED = Color.rgb(175, 193, 187);

    private static final long MEDIA_SEEK_MS = 10000L;
    private static final long MEDIA_LONG_SEEK_MS = 30000L;

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
    private Button pauseButton;
    private Button stopButton;
    private Button backButton;

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

            handler.postDelayed(this, 150);
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

    /*
     * Main screen arrangement:
     *
     * SONG TITLE
     * STATUS
     * VIDEO PREVIEW (video files only)
     * LARGE LYRICS AREA
     * SONG TIME
     * PLAY | PAUSE | STOP
     * BACK
     * REMOTE HELP
     */
    private void buildScreen() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        root.setPadding(
                dp(28),
                dp(8),
                dp(28),
                dp(8)
        );

        titleLabel = new TextView(this);
        titleLabel.setText(songTitle);
        titleLabel.setTextColor(GREEN);
        titleLabel.setTextSize(24);
        titleLabel.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        titleLabel.setGravity(Gravity.CENTER);
        titleLabel.setMaxLines(2);

        root.addView(
                titleLabel,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(48)
                )
        );

        statusLabel = new TextView(this);
        statusLabel.setText("Preparing song...");
        statusLabel.setTextColor(MUTED);
        statusLabel.setTextSize(14);
        statusLabel.setGravity(Gravity.CENTER);

        root.addView(
                statusLabel,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(26)
                )
        );

        videoTexture = new TextureView(this);
        videoTexture.setBackgroundColor(Color.BLACK);

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(105)
                );

        videoParams.bottomMargin = dp(5);

        root.addView(
                videoTexture,
                videoParams
        );

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setBackgroundColor(PANEL);

        lyricsLabel = new TextView(this);
        lyricsLabel.setTextColor(WHITE);
        lyricsLabel.setTextSize(30);
        lyricsLabel.setGravity(Gravity.CENTER);
        lyricsLabel.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        lyricsLabel.setLineSpacing(
                dp(9),
                1.0f
        );

        lyricsLabel.setPadding(
                dp(28),
                dp(24),
                dp(28),
                dp(24)
        );

        lyricsLabel.setText("Get ready to sing!");

        lyricsScroll.addView(
                lyricsLabel,
                new ScrollView.LayoutParams(
                        -1,
                        -2
                )
        );

        LinearLayout.LayoutParams lyricsParams =
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1f
                );

        lyricsParams.topMargin = dp(5);

        root.addView(
                lyricsScroll,
                lyricsParams
        );

        timeLabel = new TextView(this);
        timeLabel.setTextColor(MUTED);
        timeLabel.setTextSize(14);
        timeLabel.setGravity(Gravity.CENTER);

        root.addView(
                timeLabel,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(26)
                )
        );

        /*
         * Three separate horizontal playback buttons.
         */
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setWeightSum(3f);

        playButton = createButton("PLAY");
        playButton.setOnClickListener(v -> startPlayback());

        pauseButton = createButton("PAUSE");
        pauseButton.setOnClickListener(v -> pausePlayback());

        stopButton = createButton("STOP");
        stopButton.setOnClickListener(v -> stopPlayback());

        LinearLayout.LayoutParams playParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(54),
                        1f
                );

        playParams.setMargins(
                dp(4),
                dp(3),
                dp(4),
                dp(3)
        );

        LinearLayout.LayoutParams pauseParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(54),
                        1f
                );

        pauseParams.setMargins(
                dp(4),
                dp(3),
                dp(4),
                dp(3)
        );

        LinearLayout.LayoutParams stopParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(54),
                        1f
                );

        stopParams.setMargins(
                dp(4),
                dp(3),
                dp(4),
                dp(3)
        );

        controls.addView(playButton, playParams);
        controls.addView(pauseButton, pauseParams);
        controls.addView(stopButton, stopParams);

        root.addView(
                controls,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(62)
                )
        );

        /*
         * BACK stays separate from playback controls.
         */
        backButton = createButton("BACK");
        backButton.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(
                        dp(180),
                        dp(42)
                );

        backParams.gravity = Gravity.CENTER;
        backParams.topMargin = dp(2);

        root.addView(
                backButton,
                backParams
        );

        TextView remoteHelp = new TextView(this);
        remoteHelp.setText(
                "REMOTE: LEFT / RIGHT = SEEK     "
                        + "CENTER = SELECT"
        );
        remoteHelp.setTextColor(MUTED);
        remoteHelp.setTextSize(11);
        remoteHelp.setGravity(Gravity.CENTER);

        root.addView(
                remoteHelp,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(22)
                )
        );

        setContentView(root);

        if (!songType.toUpperCase(Locale.ROOT).contains("VIDEO")) {
            videoTexture.setVisibility(View.GONE);
        }

        /*
         * Begin with PLAY selected for the TV remote.
         */
        playButton.requestFocus();
    }

    private Button createButton(String label) {

        Button button = new Button(this);

        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(WHITE);

        button.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        button.setAllCaps(false);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        button.setBackground(createButtonBackground());

        return button;
    }

    private StateListDrawable createButtonBackground() {

        StateListDrawable states = new StateListDrawable();

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.rgb(24, 75, 53));
        focused.setCornerRadius(dp(10));
        focused.setStroke(dp(3), GREEN);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.rgb(35, 95, 65));
        pressed.setCornerRadius(dp(10));
        pressed.setStroke(dp(3), WHITE);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(PANEL);
        normal.setCornerRadius(dp(10));
        normal.setStroke(dp(2), GREEN);

        states.addState(
                new int[]{android.R.attr.state_pressed},
                pressed
        );

        states.addState(
                new int[]{android.R.attr.state_focused},
                focused
        );

        states.addState(
                new int[]{},
                normal
        );

        return states;
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

                            midiLyrics = Stage3MidiLyrics.read(
                                    midiFile
                            );

                            if (midiLyrics.hasLyrics()) {

                                lyricsLabel.setText(
                                        midiLyrics.styledFullTextAtTick(
                                                0L,
                                                durationTicks
                                        )
                                );

                                statusLabel.setText(
                                        "MIDI / KAR PLAYING"
                                );

                            } else {

                                lyricsLabel.setText(
                                        "No timed lyrics were found "
                                                + "in this MIDI/KAR file."
                                );

                                statusLabel.setText(
                                        "MIDI / KAR PLAYING"
                                );
                            }

                            lastLyricLine = Integer.MIN_VALUE;

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

                    } else if (
                            playbackState == Player.STATE_BUFFERING
                    ) {

                        statusLabel.setText("BUFFERING...");
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {

                    if (released) return;

                    if (isPlaying) {
                        statusLabel.setText("PLAYING");
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

    /*
     * TV remote handling.
     *
     * LEFT / RIGHT:
     *   When a playback button has focus, allow normal
     *   horizontal button navigation.
     *
     *   Otherwise, rewind / fast-forward the song.
     *
     * CENTER / ENTER:
     *   Activates the focused button normally.
     *
     * MEDIA keys:
     *   Continue to control playback directly.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        if (event.getAction() == KeyEvent.ACTION_DOWN) {

            int keyCode = event.getKeyCode();

            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                togglePlayback();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                startPlayback();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                pausePlayback();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seekBackward(MEDIA_LONG_SEEK_MS);
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seekForward(MEDIA_LONG_SEEK_MS);
                return true;
            }

            View focused = getCurrentFocus();

            boolean buttonHasFocus = focused instanceof Button;

            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {

                if (buttonHasFocus) {
                    return super.dispatchKeyEvent(event);
                }

                seekBackward(MEDIA_SEEK_MS);
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {

                if (buttonHasFocus) {
                    return super.dispatchKeyEvent(event);
                }

                seekForward(MEDIA_SEEK_MS);
                return true;
            }

            if (
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
            ) {

                if (!buttonHasFocus) {
                    togglePlayback();
                    return true;
                }
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private void togglePlayback() {

        if (isPlaybackRunning()) {
            pausePlayback();
        } else {
            startPlayback();
        }
    }

    private boolean isPlaybackRunning() {

        if (midiMode) {

            return midiReady
                    && midiEngine != null
                    && midiEngine.isPlaying();
        }

        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    private void startPlayback() {

        if (midiMode) {

            if (!midiReady || midiEngine == null) return;

            midiEngine.resume();

            statusLabel.setText("PLAYING");

        } else if (mediaPlayer != null) {

            mediaPlayer.play();

            statusLabel.setText("PLAYING");
        }
    }

    private void pausePlayback() {

        if (midiMode) {

            if (!midiReady || midiEngine == null) return;

            midiEngine.pause();

            statusLabel.setText("PAUSED");

        } else if (mediaPlayer != null) {

            mediaPlayer.pause();

            statusLabel.setText("PAUSED");
        }
    }

    private void seekBackward(long amountMs) {
        seekBy(-Math.max(1L, amountMs));
    }

    private void seekForward(long amountMs) {
        seekBy(Math.max(1L, amountMs));
    }

    private void seekBy(long amount) {

        if (midiMode) {
            seekMidi(amount);
        } else {
            seekMedia(amount);
        }
    }

    private void seekMidi(long direction) {

        if (!midiReady || midiEngine == null) return;

        long duration = midiEngine.getDurationTicks();

        if (duration <= 0L) return;

        long current = midiEngine.getCurrentTick();

        long step = Math.max(1L, duration / 50L);

        if (Math.abs(direction) >= MEDIA_LONG_SEEK_MS) {
            step = Math.max(1L, duration / 10L);
        }

        long target;

        if (direction < 0L) {

            target = Math.max(0L, current - step);

        } else {

            target = Math.min(duration, current + step);
        }

        midiEngine.seekTo(target);

        statusLabel.setText(
                direction < 0L ? "REWIND" : "FAST FORWARD"
        );

        updateMidiDisplay();
    }

    private void seekMedia(long amountMs) {

        if (mediaPlayer == null) return;

        long duration = mediaPlayer.getDuration();

        if (duration <= 0L) return;

        long current = mediaPlayer.getCurrentPosition();

        long target = Math.max(
                0L,
                Math.min(duration, current + amountMs)
        );

        mediaPlayer.seekTo(target);

        statusLabel.setText(
                amountMs < 0L ? "REWIND" : "FAST FORWARD"
        );

        updateMediaDisplay();
    }

    /*
     * STOP pauses and returns MIDI playback to the beginning.
     * The loaded MIDI engine remains available for PLAY.
     */
    private void stopPlayback() {

        if (midiMode) {

            if (midiEngine != null && midiReady) {

                midiEngine.pause();
                midiEngine.seekTo(0L);
            }

            statusLabel.setText("STOPPED");

            lastLyricLine = Integer.MIN_VALUE;

            if (midiReady) {
                updateMidiDisplay();
            }

        } else if (mediaPlayer != null) {

            mediaPlayer.pause();
            mediaPlayer.seekTo(0L);

            statusLabel.setText("STOPPED");
        }
    }

    private void updateMidiDisplay() {

        if (midiEngine == null || !midiReady) return;

        long tick = midiEngine.getCurrentTick();

        long duration = midiEngine.getDurationTicks();

        lyricsLabel.setText(
                midiLyrics.styledFullTextAtTick(
                        tick,
                        duration
                )
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

                if (
                        layout != null
                                && offset <= lyricsLabel.length()
                ) {

                    int safeOffset = Math.min(
                            offset,
                            Math.max(
                                    0,
                                    lyricsLabel.length() - 1
                            )
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

        if (statusLabel != null) {
            statusLabel.setText("PLAYBACK ERROR");
        }

        if (lyricsLabel != null) {
            lyricsLabel.setText(message);
        }
    }

    @Override
    protected void onPause() {

        super.onPause();

        if (
                midiMode
                        && midiReady
                        && midiEngine != null
                        && midiEngine.isPlaying()
        ) {

            midiEngine.pause();

            pausedForLifecycle = true;

        } else if (
                !midiMode
                        && mediaPlayer != null
                        && mediaPlayer.isPlaying()
        ) {

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
