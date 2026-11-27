
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.TextureView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Stage4TvPlayerActivity extends Activity {

    private static final String EXTRA_TITLE = "stage4_song_title";
    private static final String EXTRA_URI = "stage4_song_uri";
    private static final String EXTRA_TYPE = "stage4_song_type";
    private static final String EXTRA_LRC_URI = "stage4_song_lrc_uri";

    private static final int BACKGROUND = Color.rgb(8, 20, 25);
    private static final int PANEL = Color.rgb(20, 39, 42);
    private static final int GREEN = Color.rgb(0, 220, 115);
    private static final int WHITE = Color.rgb(245, 250, 247);
    private static final int MUTED = Color.rgb(175, 193, 187);

    private static final long MEDIA_SEEK_MS = 10000L;
    private static final long MEDIA_LONG_SEEK_MS = 30000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<LrcLine> lrcLines = new ArrayList<>();

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
    private String lrcUri = "";

    private boolean midiMode;
    private boolean midiReady;
    private boolean released;
    private boolean pausedForLifecycle;
    private boolean usingLrcFallback;

    private int lastLyricLine = Integer.MIN_VALUE;
    private int lastLrcLine = Integer.MIN_VALUE;

    private static final class LrcLine {
        final long timeMs;
        final String text;

        LrcLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (released) return;

            try {
                if (midiMode && midiReady && midiEngine != null) {
                    updateMidiDisplay();
                } else if (!midiMode && mediaPlayer != null) {
                    updateMediaDisplay();
                }
            } catch (Throwable error) {
                android.util.Log.e(
                        "Stage4TVPlayer",
                        "Playback display update failed.",
                        error
                );
                showError("Playback display error: " + errorText(error));
            }

            if (!released) {
                handler.postDelayed(this, 150);
            }
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
        lrcUri = getIntent().getStringExtra(EXTRA_LRC_URI);

        if (songTitle == null) songTitle = "Karaoke Song";
        if (songUri == null) songUri = "";
        if (songType == null) songType = "AUDIO";
        if (lrcUri == null) lrcUri = "";

        String normalizedType = songType.toUpperCase(Locale.ROOT);
        midiMode = normalizedType.contains("MIDI")
                || normalizedType.contains("KAR");

        try {
            buildScreen();

            if (!midiMode) {
                if (!lrcUri.trim().isEmpty()) {
                    loadLrcLyrics();
                } else {
                    lyricsLabel.setText(
                            "No matching timed LRC lyrics file was imported for this song."
                    );
                }
            }

            if (songUri.trim().isEmpty()) {
                showError("The song file location is missing.");
                return;
            }

            if (midiMode) {
                startMidiSong();
            } else {
                startMediaSong();
            }

            if (!released) {
                handler.post(progressUpdater);
            }

        } catch (Throwable error) {
            android.util.Log.e(
                    "Stage4TVPlayer",
                    "Fatal error while opening the selected song.",
                    error
            );

            showFatalStartupError(
                    "Could not open this song: " + errorText(error)
            );
        }
    }

    private String errorText(Throwable error) {
        if (error == null) return "Unknown error";

        String message = error.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }

        return error.getClass().getSimpleName() + ": " + message;
    }

    private void showFatalStartupError(String message) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(BACKGROUND);
            root.setPadding(dp(35), dp(25), dp(35), dp(25));

            TextView heading = new TextView(this);
            heading.setText("SONG PLAYER ERROR");
            heading.setTextColor(GREEN);
            heading.setTextSize(25);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setGravity(Gravity.CENTER);

            root.addView(
                    heading,
                    new LinearLayout.LayoutParams(-1, dp(55))
            );

            TextView details = new TextView(this);
            details.setText(message);
            details.setTextColor(WHITE);
            details.setTextSize(19);
            details.setGravity(Gravity.CENTER);
            details.setPadding(dp(15), dp(15), dp(15), dp(15));

            root.addView(
                    details,
                    new LinearLayout.LayoutParams(-1, 0, 1f)
            );

            Button back = createButton("BACK TO SONGBOOK");
            back.setOnClickListener(v -> finish());

            root.addView(
                    back,
                    new LinearLayout.LayoutParams(dp(280), dp(58))
            );

            setContentView(root);
            back.requestFocus();

        } catch (Throwable displayError) {
            android.util.Log.e(
                    "Stage4TVPlayer",
                    "Could not display startup diagnostic.",
                    displayError
            );
            finish();
        }
    }

    private int dp(float value) {
        return (int) (
                value * getResources().getDisplayMetrics().density + 0.5f
        );
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.FILL_VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(18), dp(5), dp(18), dp(5));

        titleLabel = new TextView(this);
        titleLabel.setText(songTitle);
        titleLabel.setTextColor(GREEN);
        titleLabel.setTextSize(23);
        titleLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleLabel.setGravity(Gravity.CENTER);
        titleLabel.setMaxLines(2);

        root.addView(
                titleLabel,
                new LinearLayout.LayoutParams(-1, dp(43))
        );

        statusLabel = new TextView(this);
        statusLabel.setText("Preparing song...");
        statusLabel.setTextColor(MUTED);
        statusLabel.setTextSize(13);
        statusLabel.setGravity(Gravity.CENTER);

        root.addView(
                statusLabel,
                new LinearLayout.LayoutParams(-1, dp(22))
        );

        videoTexture = new TextureView(this);
        videoTexture.setBackgroundColor(Color.BLACK);

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(-1, dp(105));
        videoParams.bottomMargin = dp(3);

        root.addView(videoTexture, videoParams);

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setHorizontalScrollBarEnabled(false);
        lyricsScroll.setOverScrollMode(
                View.OVER_SCROLL_NEVER
        );
        lyricsScroll.setBackgroundColor(PANEL);
        lyricsScroll.setClipToPadding(false);

        lyricsLabel = new TextView(this);
        lyricsLabel.setTextColor(WHITE);
        lyricsLabel.setTextSize(32);
        lyricsLabel.setGravity(Gravity.CENTER);
        lyricsLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lyricsLabel.setLineSpacing(dp(10), 1.0f);
        lyricsLabel.setIncludeFontPadding(true);
        lyricsLabel.setPadding(dp(24), dp(20), dp(24), dp(20));
        lyricsLabel.setText("Get ready to sing!");

        lyricsScroll.addView(
                lyricsLabel,
                new ScrollView.LayoutParams(-1, -2)
        );

        LinearLayout.LayoutParams lyricsParams =
                new LinearLayout.LayoutParams(-1, 0, 1f);
        lyricsParams.topMargin = dp(3);
        lyricsParams.bottomMargin = dp(3);

        root.addView(lyricsScroll, lyricsParams);

        timeLabel = new TextView(this);
        timeLabel.setTextColor(MUTED);
        timeLabel.setTextSize(13);
        timeLabel.setGravity(Gravity.CENTER);

        root.addView(
                timeLabel,
                new LinearLayout.LayoutParams(-1, dp(23))
        );

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

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(0, dp(52), 1f);
        buttonParams.setMargins(dp(4), dp(3), dp(4), dp(3));

        controls.addView(playButton, new LinearLayout.LayoutParams(
                buttonParams.width,
                buttonParams.height,
                buttonParams.weight
        ));

        controls.addView(pauseButton, new LinearLayout.LayoutParams(
                buttonParams.width,
                buttonParams.height,
                buttonParams.weight
        ));

        controls.addView(stopButton, new LinearLayout.LayoutParams(
                buttonParams.width,
                buttonParams.height,
                buttonParams.weight
        ));

        root.addView(
                controls,
                new LinearLayout.LayoutParams(-1, dp(58))
        );

        backButton = createButton("BACK TO SONGBOOK");
        backButton.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(dp(220), dp(38));
        backParams.gravity = Gravity.CENTER;
        backParams.topMargin = dp(1);

        root.addView(backButton, backParams);

        TextView remoteHelp = new TextView(this);
        remoteHelp.setText(
                "REMOTE: LEFT / RIGHT = SEEK     CENTER = SELECT"
        );
        remoteHelp.setTextColor(MUTED);
        remoteHelp.setTextSize(10);
        remoteHelp.setGravity(Gravity.CENTER);

        root.addView(
                remoteHelp,
                new LinearLayout.LayoutParams(-1, dp(18))
        );

        setContentView(root);

        if (!songType.toUpperCase(Locale.ROOT).contains("VIDEO")) {
            videoTexture.setVisibility(View.GONE);
        }

        playButton.requestFocus();
    }

    private Button createButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
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

        states.addState(new int[]{}, normal);

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

                            try {
                                midiReady = true;

                                File midiFile = new File(
                                        getFilesDir(),
                                        "stage3_midi/song.mid"
                                );

                                midiLyrics = Stage3MidiLyrics.read(midiFile);

                                if (midiLyrics.hasLyrics()) {
                                    usingLrcFallback = false;

                                    lyricsLabel.setText(
                                            midiLyrics.styledFullTextAtTick(
                                                    0L,
                                                    durationTicks
                                            )
                                    );

                                    statusLabel.setText("MIDI / KAR PLAYING");

                                } else if (!lrcUri.trim().isEmpty()) {
                                    usingLrcFallback = true;
                                    loadLrcLyrics();

                                    if (!lrcLines.isEmpty()) {
                                        statusLabel.setText(
                                                "MIDI / KAR PLAYING - LRC LYRICS"
                                        );
                                    } else {
                                        lyricsLabel.setText(
                                                "No embedded MIDI lyrics and the matching LRC file could not be loaded."
                                        );
                                    }

                                } else {
                                    usingLrcFallback = false;

                                    lyricsLabel.setText(
                                            "No embedded lyrics or matching LRC file were found."
                                    );

                                    statusLabel.setText("MIDI / KAR PLAYING");
                                }

                                lastLyricLine = Integer.MIN_VALUE;
                                lastLrcLine = Integer.MIN_VALUE;

                                updateMidiDisplay();

                            } catch (Throwable error) {
                                midiReady = false;

                                android.util.Log.e(
                                        "Stage4TVPlayer",
                                        "MIDI ready callback failed.",
                                        error
                                );

                                showError(
                                        "MIDI started, but lyrics could not be prepared: "
                                                + errorText(error)
                                );
                            }
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

        } catch (Throwable error) {
            midiReady = false;

            android.util.Log.e(
                    "Stage4TVPlayer",
                    "Could not start MIDI playback.",
                    error
            );

            showError(
                    "Could not start MIDI playback: " + errorText(error)
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

                    } else if (playbackState == Player.STATE_BUFFERING) {
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
                        androidx.media3.common.PlaybackException error) {
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

        } catch (Throwable error) {
            android.util.Log.e(
                    "Stage4TVPlayer",
                    "Could not start audio/video playback.",
                    error
            );

            showError(
                    "Could not start audio/video: " + errorText(error)
            );
        }
    }

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

            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
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
        try {
            if (midiMode) {
                if (!midiReady || midiEngine == null) return;

                midiEngine.resume();
                statusLabel.setText("PLAYING");

            } else if (mediaPlayer != null) {
                mediaPlayer.play();
                statusLabel.setText("PLAYING");
            }
        } catch (Throwable error) {
            showError("Could not resume playback: " + errorText(error));
        }
    }

    private void pausePlayback() {
        try {
            if (midiMode) {
                if (!midiReady || midiEngine == null) return;

                midiEngine.pause();
                statusLabel.setText("PAUSED");

            } else if (mediaPlayer != null) {
                mediaPlayer.pause();
                statusLabel.setText("PAUSED");
            }
        } catch (Throwable error) {
            showError("Could not pause playback: " + errorText(error));
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

        try {
            long duration = midiEngine.getDurationTicks();
            if (duration <= 0L) return;

            long current = midiEngine.getCurrentTick();
            long step = Math.max(1L, duration / 50L);

            if (Math.abs(direction) >= MEDIA_LONG_SEEK_MS) {
                step = Math.max(1L, duration / 10L);
            }

            long target = direction < 0L
                    ? Math.max(0L, current - step)
                    : Math.min(duration, current + step);

            midiEngine.seekTo(target);

            statusLabel.setText(
                    direction < 0L ? "REWIND" : "FAST FORWARD"
            );

            updateMidiDisplay();

        } catch (Throwable error) {
            showError("Could not seek MIDI song: " + errorText(error));
        }
    }

    private void seekMedia(long amountMs) {
        if (mediaPlayer == null) return;

        try {
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

        } catch (Throwable error) {
            showError("Could not seek media: " + errorText(error));
        }
    }

    private void stopPlayback() {
        try {
            if (midiMode) {
                if (midiEngine != null && midiReady) {
                    midiEngine.pause();
                    midiEngine.seekTo(0L);
                }

                statusLabel.setText("STOPPED");
                lastLyricLine = Integer.MIN_VALUE;
                lastLrcLine = Integer.MIN_VALUE;

                if (midiReady) {
                    updateMidiDisplay();
                }

            } else if (mediaPlayer != null) {
                mediaPlayer.pause();
                mediaPlayer.seekTo(0L);
                statusLabel.setText("STOPPED");
                updateMediaDisplay();
            }

        } catch (Throwable error) {
            showError("Could not stop playback: " + errorText(error));
        }
    }

    private void updateMidiDisplay() {
        if (midiEngine == null || !midiReady) return;

        long tick = midiEngine.getCurrentTick();
        long duration = midiEngine.getDurationTicks();

        if (usingLrcFallback && !lrcLines.isEmpty()) {
            long positionMs = midiLyrics.tickToMillis(tick);
            updateLrcDisplay(positionMs);

        } else if (midiLyrics.hasLyrics()) {
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
                            && offset < lyricsLabel.length()) {

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
        }

        timeLabel.setText(
                "MIDI TICK: " + tick + " / " + duration
        );
    }

    private void updateMediaDisplay() {
        if (mediaPlayer == null) return;

        long position = mediaPlayer.getCurrentPosition();
        long duration = mediaPlayer.getDuration();

        timeLabel.setText(
                formatTime(position) + " / " + formatTime(duration)
        );

        updateLrcDisplay(position);
    }

    private void loadLrcLyrics() {
        lrcLines.clear();
        lastLrcLine = Integer.MIN_VALUE;

        if (lrcUri.trim().isEmpty()) {
            showLrcUnavailable(
                    "No matching LRC lyrics file was linked."
            );
            return;
        }

        Pattern timestampPattern = Pattern.compile(
                "\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]"
        );

        try {
            InputStream input = getContentResolver().openInputStream(
                    Uri.parse(lrcUri)
            );

            if (input == null) {
                showLrcUnavailable(
                        "The linked LRC file could not be opened."
                );
                return;
            }

            try (
                    InputStream safeInput = input;
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    safeInput,
                                    StandardCharsets.UTF_8
                            )
                    )
            ) {
                String line;

                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()
                            && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }

                    Matcher matcher =
                            timestampPattern.matcher(line);

                    ArrayList<Long> timestamps = new ArrayList<>();

                    while (matcher.find()) {
                        long minutes =
                                Long.parseLong(matcher.group(1));

                        long seconds =
                                Long.parseLong(matcher.group(2));

                        String fraction = matcher.group(3);
                        long millis = 0L;

                        if (fraction != null) {
                            if (fraction.length() == 1) {
                                millis =
                                        Long.parseLong(fraction) * 100L;

                            } else if (fraction.length() == 2) {
                                millis =
                                        Long.parseLong(fraction) * 10L;

                            } else {
                                millis = Long.parseLong(fraction);
                            }
                        }

                        timestamps.add(
                                minutes * 60000L
                                        + seconds * 1000L
                                        + millis
                        );
                    }

                    String lyricText = matcher.replaceAll("").trim();

                    if (lyricText.isEmpty()
                            || timestamps.isEmpty()) {
                        continue;
                    }

                    for (Long timestamp : timestamps) {
                        lrcLines.add(
                                new LrcLine(timestamp, lyricText)
                        );
                    }
                }
            }

            Collections.sort(
                    lrcLines,
                    Comparator.comparingLong(a -> a.timeMs)
            );

            if (lrcLines.isEmpty()) {
                showLrcUnavailable(
                        "The linked LRC file contains no timed lyrics."
                );

            } else {
                lyricsLabel.setText("Lyrics ready...");

                statusLabel.setText(
                        midiMode
                                ? "MIDI / KAR PLAYING - LRC LYRICS"
                                : "AUDIO / VIDEO WITH TIMED LYRICS"
                );
            }

        } catch (Throwable error) {
            android.util.Log.e(
                    "Stage4TVPlayer",
                    "Could not read linked LRC lyrics.",
                    error
            );

            showLrcUnavailable(
                    "Could not read the linked LRC lyrics file: "
                            + errorText(error)
            );
        }
    }

    private void showLrcUnavailable(String message) {
        if (lyricsLabel != null && lrcLines.isEmpty()) {
            lyricsLabel.setText(message);
        }
    }

    private void updateLrcDisplay(long positionMs) {
        if (lrcLines.isEmpty() || lyricsLabel == null) return;

        int active = -1;

        for (int i = 0; i < lrcLines.size(); i++) {
            if (lrcLines.get(i).timeMs <= positionMs) {
                active = i;
            } else {
                break;
            }
        }

        if (active == lastLrcLine) return;
        lastLrcLine = active;

        if (active < 0) {
            lyricsLabel.setText("Get ready to sing!");
            lyricsScroll.scrollTo(0, 0);
            return;
        }

        int first = Math.max(0, active - 1);
        int last = Math.min(lrcLines.size() - 1, active + 2);

        SpannableStringBuilder display =
                new SpannableStringBuilder();

        int activeStart = 0;

        for (int i = first; i <= last; i++) {
            if (i > first) {
                display.append("\n\n");
            }

            int start = display.length();
            display.append(lrcLines.get(i).text);
            int end = display.length();

            display.setSpan(
                    new ForegroundColorSpan(
                            i == active ? GREEN : WHITE
                    ),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            if (i == active) {
                activeStart = start;
            }
        }

        lyricsLabel.setText(display);

        final int scrollOffset = activeStart;

        lyricsScroll.post(() -> {
            if (released) return;

            android.text.Layout layout =
                    lyricsLabel.getLayout();

            if (layout != null
                    && scrollOffset < lyricsLabel.length()) {

                int line = layout.getLineForOffset(scrollOffset);

                lyricsScroll.smoothScrollTo(
                        0,
                        layout.getLineTop(line)
                );
            }
        });
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
        } else {
            showFatalStartupError(message);
        }

        android.util.Log.e(
                "Stage4TVPlayer",
                message
        );
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            if (midiMode
                    && midiReady
                    && midiEngine != null
                    && midiEngine.isPlaying()) {

                midiEngine.pause();
                pausedForLifecycle = true;

            } else if (!midiMode
                    && mediaPlayer != null
                    && mediaPlayer.isPlaying()) {

                mediaPlayer.pause();
                pausedForLifecycle = true;
            }
        } catch (Throwable error) {
            android.util.Log.e(
                    "Stage4TVPlayer",
                    "Pause lifecycle error.",
                    error
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!pausedForLifecycle || released) return;

        pausedForLifecycle = false;

        try {
            if (midiMode && midiReady && midiEngine != null) {
                midiEngine.resume();

            } else if (!midiMode && mediaPlayer != null) {
                mediaPlayer.play();
            }
        } catch (Throwable error) {
            showError(
                    "Could not resume after returning to the player: "
                            + errorText(error)
            );
        }
    }

    @Override
    protected void onDestroy() {
        released = true;
        handler.removeCallbacks(progressUpdater);

        try {
            if (midiEngine != null) {
                midiEngine.release();
                midiEngine = null;
            }
        } catch (Throwable error) {
            android.util.Log.e(
                    "Stage4TVPlayer",
                    "MIDI release error.",
                    error
            );
        }

        try {
            if (mediaPlayer != null) {
                mediaPlayer.clearVideoTextureView(videoTexture);
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Throwable error) {
            android.util.Log.e(
                    "Stage4TVPlayer",
                    "Media player release error.",
                    error
            );
        }

        super.onDestroy();
    }
}
