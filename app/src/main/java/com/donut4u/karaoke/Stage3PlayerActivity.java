
package com.donut4u.karaoke;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Locale;

public class Stage3PlayerActivity extends Activity {

    private static final String SONGBOOK_PREFS =
            "donut4u_stage3_songbook";

    private static final String SONGS_KEY = "songs_json";

    private static final String DEFAULT_SOUNDFONT =
            "builtin://default";

    private static final int SONGHUB_SECTOR_SIZE = 2048;

    private static final long MAX_SONGHUB_SLICE_BYTES =
            64L * 1024L * 1024L;

    private static final int DARK_GREEN =
            Color.rgb(5, 24, 15);

    private static final int ACTIVE_GREEN =
            Color.rgb(0, 255, 100);

    private static final int WHITE = Color.WHITE;

    private static final int SEEK_MAX = 1000;

    private static final int REQUEST_BACKGROUND_IMAGE = 7301;
    private static final int REQUEST_BACKGROUND_VIDEO = 7302;
    private static final int REQUEST_MIC_PERMISSION = 7401;

    private static final String PREF_BG_TYPE =
            "stage3_personal_background_type";

    private static final String PREF_BG_URI =
            "stage3_personal_background_uri";

    private static final String PREF_BG_DARKNESS =
            "stage3_personal_background_darkness";

    private static final String PREF_BG_FIT =
            "stage3_personal_background_fit";

    private static final String BG_DEFAULT = "DEFAULT";
    private static final String BG_IMAGE = "IMAGE";
    private static final String BG_VIDEO = "VIDEO";

    private static final String FIT_FILL = "FILL";
    private static final String FIT = "FIT";
    private static final String FIT_STRETCH = "STRETCH";

    // ---------------------------------------------------------
    // STAGE 3 LAUNCH DIAGNOSTICS
    // ---------------------------------------------------------

    private static final String DIAG_PREFS =
            "stage3_player_launch_diagnostics";

    private static final String DIAG_KEY =
            "last_checkpoint";

    private static final String DIAG_ERROR_KEY =
            "last_error";

    private void markPlayerLaunchCheckpoint(String checkpoint) {
        try {
            getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(DIAG_KEY, checkpoint)
                    .putLong("last_checkpoint_time",
                            System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {
        }

        Log.e("Stage3Launch", "CHECKPOINT: " + checkpoint);
    }

    private String getPlayerLaunchCheckpoint() {
        try {
            return getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
                    .getString(DIAG_KEY, "unknown");
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private void showStartupDiagnostic(
            String message,
            Throwable error) {

        String details = message;

        if (error != null) {
            Log.e("Stage3Launch", message, error);

            String errorText =
                    error.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(error.getMessage());

            details += "\n\n" + errorText;

            try {
                getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(DIAG_ERROR_KEY,
                                Log.getStackTraceString(error))
                        .apply();
            } catch (Exception ignored) {
            }
        }

        try {
            LinearLayout diagnostic =
                    new LinearLayout(this);

            diagnostic.setOrientation(
                    LinearLayout.VERTICAL);

            diagnostic.setGravity(Gravity.CENTER_VERTICAL);

            diagnostic.setPadding(
                    dp(20), dp(20), dp(20), dp(20));

            diagnostic.setBackgroundColor(DARK_GREEN);

            TextView heading = new TextView(this);
            heading.setText("STAGE 3 STARTUP DIAGNOSTIC");
            heading.setTextColor(ACTIVE_GREEN);
            heading.setTextSize(22);
            heading.setTypeface(
                    Typeface.DEFAULT, Typeface.BOLD);

            TextView text = new TextView(this);
            text.setTextColor(WHITE);
            text.setTextSize(17);
            text.setText(
                    details
                            + "\n\nLast checkpoint: "
                            + getPlayerLaunchCheckpoint()
                            + "\n\nPlease note this checkpoint "
                            + "before returning to the Songbook.");

            Button back = new Button(this);
            back.setText("BACK TO SONGBOOK");
            back.setOnClickListener(v -> {
                returningToSongbook = true;
                finish();
            });

            diagnostic.addView(heading);
            diagnostic.addView(text);
            diagnostic.addView(back);

            setContentView(diagnostic);

        } catch (Throwable displayError) {
            Log.e("Stage3Launch",
                    "Could not display diagnostic screen",
                    displayError);

            Toast.makeText(
                    this,
                    details + "\nCheckpoint: "
                            + getPlayerLaunchCheckpoint(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private ExoPlayer player;
    private ExoPlayer backgroundPlayer;

    private Stage3MidiEngine midiEngine;

    private FrameLayout stage;
    private TextureView backgroundTexture;
    private ImageView backgroundImage;

    private LinearLayout overlay;
    private TextView songTitleLabel;
    private TextView singerLabel;
    private TextView lyricsLabel;
    private TextView countdownLabel;
    private ScrollView lyricsScroll;

    private TextView playbackTimeLabel;
    private TextView backButton;

    private Button playButton;
    private Button pauseButton;
    private Button stopButton;
    private Button backgroundButton;

    // Microphone monitor controls
    private Stage3MicrophoneMonitor microphoneMonitor;
    private Button microphoneButton;
    private TextView microphoneStatusLabel;

    private SeekBar seekBar;

    private Stage3MidiTiming currentMidiTiming;

    private boolean released;
    private boolean midiMode;
    private boolean midiReady;
    private boolean midiPausedByLifecycle;
    private boolean backgroundAvailable;
    private boolean userSeeking;
    private boolean updatingSeekBar;
    private boolean returningToSongbook;

    private long midiDurationTicks;

    private int lastRenderedLyricStart = -2;
    private int lastRenderedLyricEnd = -2;

    private int lastScrolledLyricLine = Integer.MIN_VALUE;

    private String lastRenderedLyricText;

    private VideoSize currentBackgroundVideoSize;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private Stage3MidiLyrics currentMidiLyrics =
            Stage3MidiLyrics.empty();

    private Stage3SongQueue.QueuedSong currentSong;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {

            if (released || returningToSongbook) {
                return;
            }

            if (midiMode && midiReady && midiEngine != null) {

                long tick = midiEngine.getCurrentTick();

                updateMidiLyricsAtTick(tick);
                updateMidiControls(tick);

            } else if (!midiMode && player != null) {

                updateMediaControls();
            }

            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        markPlayerLaunchCheckpoint("01_ACTIVITY_CREATED");

        try {
            Stage3SongQueue.initialize(this);
            markPlayerLaunchCheckpoint("02_QUEUE_INITIALIZED");

            midiEngine = Stage3MidiEngine.create(this);
            markPlayerLaunchCheckpoint("03_MIDI_ENGINE_CREATED");

            buildScreen();
            markPlayerLaunchCheckpoint("04_SCREEN_BUILT");

            startSelectedSong();
            markPlayerLaunchCheckpoint("05_SELECTED_SONG_STARTED");

            handler.post(progressUpdater);
            markPlayerLaunchCheckpoint("06_PROGRESS_UPDATER_STARTED");

            Toast.makeText(
                    this,
                    "STAGE 3 PLAYER OPENED",
                    Toast.LENGTH_SHORT).show();

        } catch (Throwable error) {
            markPlayerLaunchCheckpoint("ERROR_ON_CREATE");
            showStartupDiagnostic(
                    "Stage 3 player startup failed.",
                    error);
        }
    }

    private SharedPreferences backgroundPreferences() {
        return getSharedPreferences(
                SONGBOOK_PREFS,
                MODE_PRIVATE);
    }

    private int dp(float value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {

        stage = new FrameLayout(this);
        stage.setBackgroundColor(DARK_GREEN);

        backgroundImage = new ImageView(this);
        backgroundImage.setVisibility(View.GONE);
        backgroundImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backgroundImage.setBackgroundColor(DARK_GREEN);

        FrameLayout.LayoutParams imageParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);

        stage.addView(backgroundImage, imageParams);

        backgroundTexture = new TextureView(this);
        backgroundTexture.setVisibility(View.GONE);
        backgroundTexture.setOpaque(false);

        FrameLayout.LayoutParams videoParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);

        stage.addView(backgroundTexture, videoParams);

        backgroundTexture.addOnLayoutChangeListener(
                (v, left, top, right, bottom,
                 oldLeft, oldTop, oldRight, oldBottom) -> {
                    applyVideoFitMode();
                });

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(
                dp(14), dp(8), dp(14), dp(8));

        FrameLayout.LayoutParams overlayParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);

        stage.addView(overlay, overlayParams);

        applyBackgroundDarkness();

        songTitleLabel = new TextView(this);
        songTitleLabel.setText("");
        songTitleLabel.setTextColor(WHITE);
        songTitleLabel.setTextSize(19);
        songTitleLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        songTitleLabel.setGravity(Gravity.CENTER);
        songTitleLabel.setMaxLines(2);
        songTitleLabel.setPadding(
                dp(8), dp(3), dp(8), dp(4));

        overlay.addView(
                songTitleLabel,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        singerLabel = new TextView(this);
        singerLabel.setText("");
        singerLabel.setTextColor(WHITE);
        singerLabel.setTextSize(15);
        singerLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        singerLabel.setGravity(Gravity.CENTER);
        singerLabel.setPadding(
                dp(8), dp(1), dp(8), dp(3));
        singerLabel.setVisibility(View.GONE);

        overlay.addView(
                singerLabel,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        View lyricsTopGap = new View(this);

        overlay.addView(
                lyricsTopGap,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(22)));

        FrameLayout lyricsContainer = new FrameLayout(this);

        LinearLayout.LayoutParams containerParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f);

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setHorizontalScrollBarEnabled(false);
        lyricsScroll.setBackgroundColor(Color.TRANSPARENT);
        lyricsScroll.setOverScrollMode(
                ScrollView.OVER_SCROLL_NEVER);
        lyricsScroll.setSmoothScrollingEnabled(true);
        lyricsScroll.setClipToPadding(false);
        lyricsScroll.setPadding(0, dp(4), 0, dp(4));

        lyricsContainer.addView(
                lyricsScroll,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        lyricsLabel = new TextView(this);
        lyricsLabel.setText("");
        lyricsLabel.setTextColor(WHITE);
        lyricsLabel.setTextSize(22);
        lyricsLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        lyricsLabel.setGravity(Gravity.CENTER);
        lyricsLabel.setPadding(
                dp(10), dp(10), dp(10), dp(10));
        lyricsLabel.setLineSpacing(dp(6), 1.05f);
        lyricsLabel.setIncludeFontPadding(true);
        lyricsLabel.setMaxLines(Integer.MAX_VALUE);
        lyricsLabel.setBreakStrategy(
                Layout.BREAK_STRATEGY_HIGH_QUALITY);
        lyricsLabel.setHyphenationFrequency(
                Layout.HYPHENATION_FREQUENCY_NONE);

        lyricsScroll.addView(
                lyricsLabel,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT));

        countdownLabel = new TextView(this);
        countdownLabel.setTextColor(ACTIVE_GREEN);
        countdownLabel.setTextSize(76);
        countdownLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        countdownLabel.setGravity(Gravity.CENTER);
        countdownLabel.setShadowLayer(
                dp(12), 0, 0, Color.BLACK);
        countdownLabel.setVisibility(View.GONE);
        countdownLabel.setClickable(false);

        lyricsContainer.addView(
                countdownLabel,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        overlay.addView(lyricsContainer, containerParams);

        buildPlaybackControls();

        setContentView(stage);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void buildPlaybackControls() {

        LinearLayout controls = new LinearLayout(this);

        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(
                dp(8), dp(4), dp(8), dp(2));
        controls.setBackgroundColor(Color.TRANSPARENT);

        playbackTimeLabel = new TextView(this);
        playbackTimeLabel.setText("00:00 / 00:00");
        playbackTimeLabel.setTextColor(WHITE);
        playbackTimeLabel.setTextSize(14);
        playbackTimeLabel.setGravity(Gravity.CENTER);
        playbackTimeLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        playbackTimeLabel.setPadding(
                dp(4), dp(3), dp(4), dp(3));

        controls.addView(
                playbackTimeLabel,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(this);
        seekBar.setMax(SEEK_MAX);
        seekBar.setProgress(0);
        seekBar.setEnabled(false);
        seekBar.setVisibility(View.VISIBLE);
        seekBar.setMinimumHeight(dp(48));
        seekBar.setPadding(dp(8), 0, dp(8), 0);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            seekBar.setProgressTintList(
                    ColorStateList.valueOf(ACTIVE_GREEN));
            seekBar.setProgressBackgroundTintList(
                    ColorStateList.valueOf(WHITE));
            seekBar.setThumbTintList(
                    ColorStateList.valueOf(ACTIVE_GREEN));
        }

        LinearLayout.LayoutParams seekParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52));

        controls.addView(seekBar, seekParams);

        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(
                    SeekBar bar,
                    int progress,
                    boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                seekToProgress(bar.getProgress());
            }
        });

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, dp(3), 0, dp(3));

        playButton = createControlButton("PLAY");
        pauseButton = createControlButton("PAUSE");
        stopButton = createControlButton("STOP");

        buttonRow.addView(
                playButton,
                new LinearLayout.LayoutParams(0, dp(50), 1f));

        buttonRow.addView(
                pauseButton,
                new LinearLayout.LayoutParams(0, dp(50), 1f));

        buttonRow.addView(
                stopButton,
                new LinearLayout.LayoutParams(0, dp(50), 1f));

        controls.addView(buttonRow);

        playButton.setOnClickListener(v -> playCurrentSong());
        pauseButton.setOnClickListener(v -> pauseCurrentSong());
        stopButton.setOnClickListener(v -> stopCurrentSong());

        // -----------------------------------------------------
        // MICROPHONE MONITOR ROW
        // -----------------------------------------------------

        LinearLayout microphoneRow = new LinearLayout(this);
        microphoneRow.setOrientation(LinearLayout.HORIZONTAL);
        microphoneRow.setGravity(Gravity.CENTER_VERTICAL);
        microphoneRow.setPadding(
                dp(4), dp(2), dp(4), dp(2));

        microphoneStatusLabel = new TextView(this);
        microphoneStatusLabel.setText(
                "MIC OFF — singer monitor");
        microphoneStatusLabel.setTextColor(WHITE);
        microphoneStatusLabel.setTextSize(12);
        microphoneStatusLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        microphoneStatusLabel.setGravity(Gravity.CENTER_VERTICAL);
        microphoneStatusLabel.setMaxLines(2);

        microphoneRow.addView(
                microphoneStatusLabel,
                new LinearLayout.LayoutParams(
                        0,
                        dp(42),
                        1f));

        microphoneButton = createControlButton("MIC: OFF");
        microphoneButton.setTextSize(12);
        microphoneButton.setMinHeight(dp(40));
        microphoneButton.setMinimumHeight(dp(40));

        microphoneRow.addView(
                microphoneButton,
                new LinearLayout.LayoutParams(
                        dp(112),
                        dp(42)));

        microphoneButton.setOnClickListener(
                v -> toggleMicrophoneMonitoring());

        controls.addView(microphoneRow);

        // -----------------------------------------------------
        // BACKGROUND AND SONGBOOK CONTROLS
        // -----------------------------------------------------

        backgroundButton = createControlButton("BACKGROUND");
        backgroundButton.setTextSize(14);
        backgroundButton.setOnClickListener(v -> showBackgroundMenu());

        controls.addView(
                backgroundButton,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(46)));

        backButton = new TextView(this);
        backButton.setText("←  BACK TO SONGBOOK");
        backButton.setTextColor(WHITE);
        backButton.setTextSize(15);
        backButton.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        backButton.setGravity(Gravity.CENTER);
        backButton.setPadding(
                dp(20), dp(8), dp(20), dp(8));
        backButton.setBackgroundColor(Color.TRANSPARENT);
        backButton.setContentDescription("Back to Songbook");

        backButton.setOnClickListener(v -> returnToSongbook());

        controls.addView(
                backButton,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(44)));

        overlay.addView(
                controls,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private Button createControlButton(String text) {

        Button button = new Button(this);

        button.setText(text);
        button.setTextColor(WHITE);
        button.setTextSize(15);
        button.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setPadding(
                dp(8), dp(5), dp(8), dp(5));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setClickable(true);
        button.setFocusable(true);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            button.setBackgroundTintList(
                    ColorStateList.valueOf(Color.rgb(0, 115, 55)));
        } else {
            button.setBackgroundColor(Color.rgb(0, 115, 55));
        }

        button.setEnabled(true);
        button.setAlpha(1f);

        return button;
    }

    // ---------------------------------------------------------
    // MICROPHONE MONITOR
    // ---------------------------------------------------------

    private void toggleMicrophoneMonitoring() {

        if (microphoneMonitor != null
                && microphoneMonitor.isRunning()) {

            stopMicrophoneMonitoring();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO
                    },
                    REQUEST_MIC_PERMISSION);

            return;
        }

        startMicrophoneMonitoring();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults);

        if (requestCode != REQUEST_MIC_PERMISSION) {
            return;
        }

        if (grantResults.length > 0
                && grantResults[0]
                == PackageManager.PERMISSION_GRANTED) {

            startMicrophoneMonitoring();

        } else {

            if (microphoneButton != null) {
                microphoneButton.setText("MIC: OFF");
            }

            if (microphoneStatusLabel != null) {
                microphoneStatusLabel.setText(
                        "MIC PERMISSION DENIED");
            }

            Toast.makeText(
                    this,
                    "Microphone permission is required "
                            + "to use the singer monitor.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startMicrophoneMonitoring() {

        if (released || returningToSongbook) {
            return;
        }

        if (microphoneMonitor != null
                && microphoneMonitor.isRunning()) {
            return;
        }

        microphoneMonitor =
                new Stage3MicrophoneMonitor(
                        new Stage3MicrophoneMonitor.Listener() {

            @Override
            public void onUpdate(
                    int levelPercent,
                    int midiNote,
                    double confidence) {

                if (released
                        || returningToSongbook
                        || microphoneStatusLabel == null) {
                    return;
                }

                String pitch = "Pitch: —";

                if (midiNote >= 0 && confidence >= 0.72) {
                    pitch = "Pitch: " + midiNoteName(midiNote);
                }

                microphoneStatusLabel.setText(
                        "MIC " + levelPercent + "%  |  " + pitch);
            }

            @Override
            public void onError(String message) {

                if (released || returningToSongbook) {
                    return;
                }

                Stage3MicrophoneMonitor old =
                        microphoneMonitor;

                microphoneMonitor = null;

                if (old != null) {
                    old.stop();
                }

                if (microphoneButton != null) {
                    microphoneButton.setText("MIC: OFF");
                }

                if (microphoneStatusLabel != null) {
                    microphoneStatusLabel.setText(
                            "MIC UNAVAILABLE");
                }

                Toast.makeText(
                        Stage3PlayerActivity.this,
                        message,
                        Toast.LENGTH_LONG).show();
            }
        });

        boolean started = microphoneMonitor.start();

        if (started) {

            if (microphoneButton != null) {
                microphoneButton.setText("MIC: ON");
            }

            if (microphoneStatusLabel != null) {
                microphoneStatusLabel.setText(
                        "MIC ON — sing to test");
            }

        } else {

            microphoneMonitor = null;

            if (microphoneButton != null) {
                microphoneButton.setText("MIC: OFF");
            }

            if (microphoneStatusLabel != null) {
                microphoneStatusLabel.setText(
                        "MIC UNAVAILABLE");
            }
        }
    }

    private void stopMicrophoneMonitoring() {

        Stage3MicrophoneMonitor old =
                microphoneMonitor;

        microphoneMonitor = null;

        if (old != null) {
            old.stop();
        }

        if (microphoneButton != null) {
            microphoneButton.setText("MIC: OFF");
        }

        if (microphoneStatusLabel != null) {
            microphoneStatusLabel.setText(
                    "MIC OFF — singer monitor");
        }
    }

    private String midiNoteName(int midiNote) {

        String[] names = {
                "C", "C#", "D", "D#", "E", "F",
                "F#", "G", "G#", "A", "A#", "B"
        };

        int safeNote = Math.max(0, Math.min(127, midiNote));

        int noteIndex = safeNote % 12;
        int octave = safeNote / 12 - 1;

        return names[noteIndex] + octave;
    }

    // ---------------------------------------------------------
    // PERSONAL BACKGROUND MENU
    // ---------------------------------------------------------

    private void showBackgroundMenu() {

        String[] choices = {
                "Choose picture from phone",
                "Choose MP4 video from phone",
                "Adjust background darkness",
                "Change display mode: Fill / Fit / Stretch",
                "Use original dark-green background"
        };

        new AlertDialog.Builder(this)
                .setTitle("PERSONAL KARAOKE BACKGROUND")
                .setItems(choices, (dialog, which) -> {

                    if (which == 0) {
                        openBackgroundPicker(false);

                    } else if (which == 1) {
                        openBackgroundPicker(true);

                    } else if (which == 2) {
                        showDarknessDialog();

                    } else if (which == 3) {
                        showFitModeDialog();

                    } else if (which == 4) {
                        useDefaultBackground();
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void openBackgroundPicker(boolean video) {

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);

        intent.setType(video ? "video/mp4" : "image/*");

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(
                    intent,
                    video
                            ? REQUEST_BACKGROUND_VIDEO
                            : REQUEST_BACKGROUND_IMAGE);

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "Cannot open the phone file picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode, resultCode, data);

        if (resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        if (requestCode != REQUEST_BACKGROUND_IMAGE
                && requestCode != REQUEST_BACKGROUND_VIDEO) {
            return;
        }

        Uri uri = data.getData();

        try {
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            getContentResolver().takePersistableUriPermission(
                    uri,
                    flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);

        } catch (Exception ignored) {
            // Some document providers do not offer persistable access.
        }

        boolean video =
                requestCode == REQUEST_BACKGROUND_VIDEO;

        SharedPreferences.Editor editor =
                backgroundPreferences().edit();

        editor.putString(
                PREF_BG_TYPE,
                video ? BG_VIDEO : BG_IMAGE);

        editor.putString(PREF_BG_URI, uri.toString());

        editor.apply();

        applySavedPersonalBackground();

        Toast.makeText(
                this,
                video
                        ? "Your personal MP4 background is selected."
                        : "Your personal picture background is selected.",
                Toast.LENGTH_SHORT).show();
    }

    private void useDefaultBackground() {

        backgroundPreferences()
                .edit()
                .putString(PREF_BG_TYPE, BG_DEFAULT)
                .remove(PREF_BG_URI)
                .apply();

        applySavedPersonalBackground();

        Toast.makeText(
                this,
                "Original dark-green background restored.",
                Toast.LENGTH_SHORT).show();
    }

    private void applyBackgroundDarkness() {

        if (overlay == null) {
            return;
        }

        int darkness = backgroundPreferences().getInt(
                PREF_BG_DARKNESS, 57);

        darkness = Math.max(0, Math.min(90, darkness));

        int alpha = Math.round(
                darkness * 255f / 100f);

        overlay.setBackgroundColor(
                Color.argb(alpha, 0, 12, 7));
    }

    private void showDarknessDialog() {

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(
                dp(22), dp(12), dp(22), dp(8));

        TextView valueLabel = new TextView(this);
        valueLabel.setTextColor(WHITE);
        valueLabel.setTextSize(16);
        valueLabel.setGravity(Gravity.CENTER);

        SeekBar darknessBar = new SeekBar(this);
        darknessBar.setMax(90);

        int saved = backgroundPreferences().getInt(
                PREF_BG_DARKNESS, 57);

        darknessBar.setProgress(saved);

        valueLabel.setText("Darkness: " + saved + "%");

        darknessBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(
                    SeekBar bar,
                    int progress,
                    boolean fromUser) {

                valueLabel.setText(
                        "Darkness: " + progress + "%");

                backgroundPreferences()
                        .edit()
                        .putInt(PREF_BG_DARKNESS, progress)
                        .apply();

                applyBackgroundDarkness();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        layout.addView(valueLabel);
        layout.addView(darknessBar);

        new AlertDialog.Builder(this)
                .setTitle("BACKGROUND DARKNESS")
                .setView(layout)
                .setPositiveButton("DONE", null)
                .setNegativeButton("RESET", (dialog, which) -> {
                    backgroundPreferences()
                            .edit()
                            .putInt(PREF_BG_DARKNESS, 57)
                            .apply();
                    applyBackgroundDarkness();
                })
                .show();
    }

    private void showFitModeDialog() {

        String[] modes = {
                "Fill — cover screen, crop edges",
                "Fit — show whole picture/video",
                "Stretch — fill screen, may distort"
        };

        String current = backgroundPreferences().getString(
                PREF_BG_FIT, FIT_FILL);

        int selected = FIT_FILL.equals(current)
                ? 0
                : FIT.equals(current) ? 1 : 2;

        new AlertDialog.Builder(this)
                .setTitle("BACKGROUND DISPLAY MODE")
                .setSingleChoiceItems(
                        modes,
                        selected,
                        (dialog, which) -> {

                            String mode = which == 0
                                    ? FIT_FILL
                                    : which == 1
                                    ? FIT
                                    : FIT_STRETCH;

                            backgroundPreferences()
                                    .edit()
                                    .putString(PREF_BG_FIT, mode)
                                    .apply();

                            applyImageFitMode();
                            applyVideoFitMode();

                            dialog.dismiss();
                        })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void applyImageFitMode() {

        if (backgroundImage == null) {
            return;
        }

        String mode = backgroundPreferences().getString(
                PREF_BG_FIT, FIT_FILL);

        if (FIT.equals(mode)) {
            backgroundImage.setScaleType(
                    ImageView.ScaleType.FIT_CENTER);

        } else if (FIT_STRETCH.equals(mode)) {
            backgroundImage.setScaleType(
                    ImageView.ScaleType.FIT_XY);

        } else {
            backgroundImage.setScaleType(
                    ImageView.ScaleType.CENTER_CROP);
        }
    }

    private void applyVideoFitMode() {

        if (backgroundTexture == null) {
            return;
        }

        VideoSize size = currentBackgroundVideoSize;

        if (size == null
                || size.width <= 0
                || size.height <= 0) {
            return;
        }

        int viewWidth = backgroundTexture.getWidth();
        int viewHeight = backgroundTexture.getHeight();

        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        String mode = backgroundPreferences().getString(
                PREF_BG_FIT, FIT_FILL);

        Matrix matrix = new Matrix();

        if (FIT_STRETCH.equals(mode)) {
            backgroundTexture.setTransform(matrix);
            return;
        }

        float videoAspect =
                (size.width * size.pixelWidthHeightRatio)
                        / (float) size.height;

        float viewAspect =
                viewWidth / (float) viewHeight;

        float scaleX = 1f;
        float scaleY = 1f;

        if (FIT.equals(mode)) {

            if (videoAspect > viewAspect) {
                scaleY = viewAspect / videoAspect;
            } else {
                scaleX = videoAspect / viewAspect;
            }

        } else {

            if (videoAspect > viewAspect) {
                scaleX = videoAspect / viewAspect;
            } else {
                scaleY = viewAspect / videoAspect;
            }
        }

        matrix.setScale(
                scaleX,
                scaleY,
                viewWidth / 2f,
                viewHeight / 2f);

        backgroundTexture.setTransform(matrix);
    }

    private void applySavedPersonalBackground() {

        if (stage == null || backgroundTexture == null
                || backgroundImage == null) {
            return;
        }

        releaseBackgroundPlayer();

        backgroundImage.setImageDrawable(null);
        backgroundImage.setVisibility(View.GONE);

        backgroundTexture.setVisibility(View.GONE);
        currentBackgroundVideoSize = null;

        stage.setBackgroundColor(DARK_GREEN);

        applyBackgroundDarkness();

        SharedPreferences preferences =
                backgroundPreferences();

        String type = preferences.getString(
                PREF_BG_TYPE, BG_DEFAULT);

        String savedUri = preferences.getString(
                PREF_BG_URI, "");

        if (BG_IMAGE.equals(type)) {

            if (savedUri == null || savedUri.trim().isEmpty()) {
                return;
            }

            try {
                Uri uri = Uri.parse(savedUri);

                backgroundImage.setImageURI(uri);
                applyImageFitMode();

                backgroundImage.setVisibility(View.VISIBLE);

            } catch (Exception e) {

                backgroundImage.setVisibility(View.GONE);

                Toast.makeText(
                        this,
                        "Cannot open the saved background picture.",
                        Toast.LENGTH_LONG).show();
            }

        } else if (BG_VIDEO.equals(type)) {

            if (savedUri == null || savedUri.trim().isEmpty()) {
                return;
            }

            startPersonalBackgroundVideo(savedUri);

        } else {

            backgroundImage.setVisibility(View.GONE);
            backgroundTexture.setVisibility(View.GONE);
            stage.setBackgroundColor(DARK_GREEN);
        }
    }

    private void startPersonalBackgroundVideo(String uriString) {

        try {
            Uri uri = Uri.parse(uriString);

            backgroundPlayer =
                    new ExoPlayer.Builder(this).build();

            backgroundPlayer.setVideoTextureView(
                    backgroundTexture);

            backgroundPlayer.setVolume(0f);

            backgroundPlayer.setRepeatMode(
                    Player.REPEAT_MODE_ONE);

            backgroundPlayer.addListener(
                    new Player.Listener() {

                @Override
                public void onVideoSizeChanged(VideoSize videoSize) {

                    currentBackgroundVideoSize = videoSize;

                    applyVideoFitMode();
                }

                @Override
                public void onPlayerError(
                        PlaybackException error) {

                    backgroundAvailable = false;

                    releaseBackgroundPlayer();

                    Toast.makeText(
                            Stage3PlayerActivity.this,
                            "The selected background video cannot be played.",
                            Toast.LENGTH_LONG).show();
                }
            });

            backgroundTexture.setVisibility(View.VISIBLE);
            backgroundAvailable = true;

            backgroundPlayer.setMediaItem(
                    MediaItem.fromUri(uri));

            backgroundPlayer.prepare();

            if (!midiMode || (midiReady
                    && midiEngine != null
                    && midiEngine.isPlaying())) {
                backgroundPlayer.play();
            } else {
                backgroundPlayer.pause();
            }

        } catch (Exception e) {

            backgroundAvailable = false;

            releaseBackgroundPlayer();

            Toast.makeText(
                    this,
                    "Cannot load the selected MP4 background.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------
    // PLAYBACK
    // ---------------------------------------------------------

    private void playCurrentSong() {

        if (released || returningToSongbook) {
            return;
        }

        if (midiMode) {

            if (!midiReady || midiEngine == null) {
                Toast.makeText(
                        this,
                        "MIDI song is still loading.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                midiEngine.resume();
                midiPausedByLifecycle = false;

                if (backgroundPlayer != null) {
                    backgroundPlayer.play();
                }

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "Cannot resume MIDI song.",
                        Toast.LENGTH_SHORT).show();
            }

        } else if (player != null) {

            player.play();
        }
    }

    private void pauseCurrentSong() {

        if (midiMode) {

            if (!midiReady || midiEngine == null) {
                Toast.makeText(
                        this,
                        "MIDI song is still loading.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                midiEngine.pause();

                if (backgroundPlayer != null) {
                    backgroundPlayer.pause();
                }

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "Cannot pause MIDI song.",
                        Toast.LENGTH_SHORT).show();
            }

        } else if (player != null) {

            player.pause();
        }
    }

    private void stopCurrentSong() {

        if (midiMode) {

            if (!midiReady || midiEngine == null) {
                Toast.makeText(
                        this,
                        "MIDI song is still loading.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                midiEngine.pause();
                midiEngine.seekTo(0L);

                midiPausedByLifecycle = false;

                if (backgroundPlayer != null) {
                    backgroundPlayer.seekTo(0L);
                    backgroundPlayer.pause();
                }

                lastScrolledLyricLine = Integer.MIN_VALUE;

                updateMidiLyricsAtTick(0L);
                updateMidiControls(0L);

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "Cannot stop MIDI song.",
                        Toast.LENGTH_SHORT).show();
            }

        } else if (player != null) {

            player.pause();
            player.seekTo(0L);
            updateMediaControls();
        }
    }

    private String formatPlaybackTime(long millis) {

        long safeMillis = Math.max(0L, millis);
        long totalSeconds = safeMillis / 1000L;

        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            return String.format(
                    Locale.ROOT,
                    "%d:%02d:%02d",
                    hours, minutes, seconds);
        }

        return String.format(
                Locale.ROOT,
                "%02d:%02d",
                minutes, seconds);
    }

    private void updatePlaybackTime(
            long elapsedMillis,
            long totalMillis) {

        if (playbackTimeLabel == null) {
            return;
        }

        playbackTimeLabel.setText(
                formatPlaybackTime(elapsedMillis)
                        + " / "
                        + formatPlaybackTime(totalMillis));
    }

    private void startSelectedSong() {

        currentSong = Stage3SongQueue.getNowPlaying();

        if (currentSong == null) {
            currentSong = Stage3SongQueue.playNext();
        }

        if (currentSong == null) {
            lyricsLabel.setText(
                    "No song selected.\n\n"
                            + "Return to the Songbook.");
            songTitleLabel.setText("Donut4U Karaoke");
            updatePlaybackTime(0L, 0L);
            setControlsEnabled(false);
            return;
        }

        initializePlayer(currentSong);
    }

    private boolean isSongHubMidi(
            Stage3SongQueue.QueuedSong song) {

        if (song == null || song.uri == null) {
            return false;
        }

        try {
            return "songhub-midi".equalsIgnoreCase(
                    Uri.parse(song.uri).getScheme());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isMidiSong(
            Stage3SongQueue.QueuedSong song) {

        if (song == null) {
            return false;
        }

        if (isSongHubMidi(song)) {
            return true;
        }

        String type = song.type == null
                ? ""
                : song.type.toUpperCase(Locale.ROOT);

        String segment = "";

        try {
            String last =
                    Uri.parse(song.uri).getLastPathSegment();

            if (last != null) {
                segment = last.toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
        }

        return type.contains("MIDI")
                || type.contains("KAR")
                || segment.endsWith(".mid")
                || segment.endsWith(".midi")
                || segment.endsWith(".kar");
    }

    private void initializePlayer(
            Stage3SongQueue.QueuedSong song) {

        releasePlayback();

        currentSong = song;

        String title = song.title == null
                ? ""
                : song.title.trim();

        songTitleLabel.setText(
                title.isEmpty()
                        ? "Donut4U Karaoke"
                        : title);

        updatePlaybackTime(0L, 0L);

        if (isMidiSong(song)) {
            initializeMidiPlayer(song);
        } else {
            initializeMediaPlayer(song);
        }
    }

    private String findSoundFontUri() {

        SharedPreferences preferences =
                getSharedPreferences(
                        SONGBOOK_PREFS,
                        MODE_PRIVATE);

        String selected = preferences.getString(
                "selected_soundfont_uri", "");

        if (selected == null || selected.trim().isEmpty()) {
            return DEFAULT_SOUNDFONT;
        }

        return selected.trim();
    }

    private void initializeMidiPlayer(
            Stage3SongQueue.QueuedSong song) {

        midiMode = true;
        midiReady = false;
        midiDurationTicks = 0L;
        currentMidiTiming = null;

        currentMidiLyrics = Stage3MidiLyrics.empty();

        lyricsLabel.setText("");
        countdownLabel.setVisibility(View.GONE);
        singerLabel.setText("");
        singerLabel.setVisibility(View.GONE);

        resetLyricTracking();

        setControlsEnabled(true);

        updatePlaybackTime(0L, 0L);

        applySavedPersonalBackground();

        String soundFontUri = findSoundFontUri();

        if (soundFontUri == null
                || soundFontUri.trim().isEmpty()) {
            soundFontUri = DEFAULT_SOUNDFONT;
        }

        final String selectedSoundFont = soundFontUri;

        new Thread(() -> {

            String playableMidiUri;

            try {
                playableMidiUri = isSongHubMidi(song)
                        ? extractSongHubRawMidi(song.uri)
                                .toURI().toString()
                        : song.uri;

            } catch (Exception e) {

                String message = e.getMessage() == null
                        ? "Could not read the indexed MIDI data."
                        : e.getMessage();

                runOnUiThread(() -> {

                    if (!released && !returningToSongbook
                            && midiMode) {

                        lyricsLabel.setText(
                                "Unable to load this MIDI/KAR song.");

                        Toast.makeText(
                                this,
                                message,
                                Toast.LENGTH_LONG).show();
                    }
                });

                return;
            }

            runOnUiThread(() -> {

                if (released || returningToSongbook
                        || !midiMode || midiEngine == null) {
                    return;
                }

                midiEngine.play(
                        playableMidiUri,
                        selectedSoundFont,
                        new Stage3MidiEngine.Callback() {

                    @Override
                    public void onReady(long durationTicks) {

                        if (released || returningToSongbook
                                || !midiMode) {
                            return;
                        }

                        midiReady = true;

                        midiDurationTicks =
                                Math.max(0L, durationTicks);

                        File midiFile = new File(
                                getFilesDir(),
                                "stage3_midi/song.mid");

                        currentMidiLyrics =
                                Stage3MidiLyrics.read(midiFile);

                        try {
                            currentMidiTiming =
                                    Stage3MidiTiming.read(midiFile);

                        } catch (Exception timingError) {

                            currentMidiTiming = null;

                            Toast.makeText(
                                    Stage3PlayerActivity.this,
                                    "MIDI timer could not read timing: "
                                            + timingError.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }

                        if (currentMidiTiming != null
                                && midiDurationTicks <= 0L) {

                            midiDurationTicks =
                                    currentMidiTiming.getDurationTicks();
                        }

                        String singer =
                                currentMidiLyrics.getSinger();

                        if (singer != null
                                && !singer.trim().isEmpty()) {

                            singerLabel.setText(singer.trim());
                            singerLabel.setVisibility(View.VISIBLE);

                        } else {
                            singerLabel.setVisibility(View.GONE);
                        }

                        setControlsEnabled(true);

                        if (backgroundPlayer != null) {
                            backgroundPlayer.play();
                        }

                        updateMidiLyricsAtTick(
                                midiEngine.getCurrentTick());

                        updateMidiControls(
                                midiEngine.getCurrentTick());
                    }

                    @Override
                    public void onError(String message) {

                        if (released || returningToSongbook
                                || !midiMode) {
                            return;
                        }

                        midiReady = false;

                        if (backgroundPlayer != null) {
                            backgroundPlayer.pause();
                        }

                        setControlsEnabled(false);

                        lyricsLabel.setText(
                                "Unable to play this MIDI/KAR song.");

                        countdownLabel.setVisibility(View.GONE);

                        Toast.makeText(
                                Stage3PlayerActivity.this,
                                message,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onFinished() {

                        if (released || returningToSongbook
                                || !midiMode) {
                            return;
                        }

                        finishCurrentSong();
                    }
                });
            });

        }, "Stage3MidiPrepare").start();
    }

    private void setControlsEnabled(boolean enabled) {

        if (playButton != null) {
            playButton.setEnabled(enabled);
            playButton.setAlpha(enabled ? 1f : 0.45f);
        }

        if (pauseButton != null) {
            pauseButton.setEnabled(enabled);
            pauseButton.setAlpha(enabled ? 1f : 0.45f);
        }

        if (stopButton != null) {
            stopButton.setEnabled(enabled);
            stopButton.setAlpha(enabled ? 1f : 0.45f);
        }

        if (seekBar != null) {

            boolean canSeek = enabled;

            if (midiMode) {
                canSeek = enabled
                        && getEffectiveMidiDurationTicks() > 0L;
            }

            seekBar.setEnabled(canSeek);
            seekBar.setAlpha(canSeek ? 1f : 0.55f);
        }
    }

    private void returnToSongbook() {

        if (returningToSongbook || released) {
            return;
        }

        returningToSongbook = true;

        markPlayerLaunchCheckpoint("RETURN_TO_SONGBOOK");

        handler.removeCallbacks(progressUpdater);

        stopMicrophoneMonitoring();

        if (backButton != null) {
            backButton.setEnabled(false);
            backButton.setAlpha(0.5f);
        }

        releasePlayback();

        if (midiEngine != null) {
            try {
                midiEngine.release();
            } catch (Exception ignored) {
            }
            midiEngine = null;
        }

        released = true;

        finish();
    }

    private long getEffectiveMidiDurationTicks() {

        if (midiDurationTicks > 0L) {
            return midiDurationTicks;
        }

        if (currentMidiTiming != null) {
            return currentMidiTiming.getDurationTicks();
        }

        return 0L;
    }

    private long progressToMidiTick(int progress) {

        long duration = getEffectiveMidiDurationTicks();

        if (duration <= 0L) {
            return 0L;
        }

        int safeProgress = Math.max(
                0, Math.min(SEEK_MAX, progress));

        double fraction =
                safeProgress / (double) SEEK_MAX;

        long target = (long) (
                duration * fraction);

        return Math.max(
                0L,
                Math.min(target, duration));
    }

    private void seekToProgress(int progress) {

        int safeProgress = Math.max(
                0, Math.min(SEEK_MAX, progress));

        if (midiMode) {

            long duration = getEffectiveMidiDurationTicks();

            if (!midiReady
                    || midiEngine == null
                    || duration <= 0L) {
                return;
            }

            long targetTick =
                    progressToMidiTick(safeProgress);

            try {
                boolean wasPlaying = midiEngine.isPlaying();

                midiEngine.seekTo(targetTick);

                lastScrolledLyricLine = Integer.MIN_VALUE;

                updateMidiLyricsAtTick(targetTick);

                if (backgroundPlayer != null) {
                    if (wasPlaying) {
                        backgroundPlayer.play();
                    } else {
                        backgroundPlayer.pause();
                    }
                }

                updateMidiControls(
                        midiEngine.getCurrentTick());

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "Could not seek in this MIDI song.",
                        Toast.LENGTH_SHORT).show();
            }

            return;
        }

        if (player != null) {

            long duration = player.getDuration();

            if (duration > 0L) {

                long target =
                        (long) (duration
                                * (safeProgress / (double) SEEK_MAX));

                player.seekTo(Math.max(
                        0L, Math.min(target, duration)));

                updateMediaControls();
            }
        }
    }

    private void updateMidiControls(long tick) {

        if (!midiReady) {
            updatePlaybackTime(0L, 0L);
            return;
        }

        long durationTicks = getEffectiveMidiDurationTicks();

        long safeTick = Math.max(0L, tick);

        if (durationTicks > 0L) {
            safeTick = Math.min(safeTick, durationTicks);
        }

        if (durationTicks > 0L
                && !userSeeking
                && seekBar != null) {

            int progress = (int) Math.max(
                    0,
                    Math.min(
                            SEEK_MAX,
                            (long) (safeTick
                                    * (double) SEEK_MAX
                                    / durationTicks)));

            updatingSeekBar = true;
            seekBar.setProgress(progress);
            updatingSeekBar = false;
        }

        long elapsedMillis = 0L;
        long totalMillis = 0L;

        if (currentMidiTiming != null) {

            elapsedMillis =
                    currentMidiTiming.tickToMillis(safeTick);

            totalMillis =
                    currentMidiTiming.getDurationMillis();
        }

        updatePlaybackTime(
                elapsedMillis,
                Math.max(elapsedMillis, totalMillis));
    }

    private void updateMediaControls() {

        if (player == null) {
            return;
        }

        long duration = player.getDuration();
        long position = player.getCurrentPosition();

        if (duration > 0L && !userSeeking
                && seekBar != null) {

            int progress = (int) Math.max(
                    0,
                    Math.min(
                            SEEK_MAX,
                            position * SEEK_MAX / duration));

            updatingSeekBar = true;
            seekBar.setProgress(progress);
            updatingSeekBar = false;
        }

        updatePlaybackTime(
                Math.max(0L, position),
                duration > 0L ? duration : 0L);
    }

    private void updateMidiLyricsAtTick(long tick) {

        if (lyricsLabel == null || currentMidiLyrics == null) {
            return;
        }

        if (!currentMidiLyrics.hasLyrics()) {

            countdownLabel.setVisibility(View.GONE);

            if (!"No embedded lyrics found."
                    .contentEquals(lyricsLabel.getText())) {

                lyricsLabel.setText(
                        "No embedded lyrics found.");

                resetLyricTracking();
            }

            return;
        }

        int countdown = currentMidiLyrics
                .getCountdownNumberAtTick(tick);

        if (countdown >= 1 && countdown <= 5) {

            countdownLabel.setText(
                    String.valueOf(countdown));

            countdownLabel.setVisibility(View.VISIBLE);

        } else {

            countdownLabel.setVisibility(View.GONE);
        }

        CharSequence candidate =
                currentMidiLyrics.styledFullTextAtTick(
                        tick, getEffectiveMidiDurationTicks());

        int activeStart = -1;
        int activeEnd = -1;

        if (candidate instanceof Spanned) {

            Spanned spanned = (Spanned) candidate;

            ForegroundColorSpan[] spans =
                    spanned.getSpans(
                            0,
                            spanned.length(),
                            ForegroundColorSpan.class);

            for (ForegroundColorSpan span : spans) {

                if (span.getForegroundColor() == ACTIVE_GREEN) {

                    activeStart = spanned.getSpanStart(span);
                    activeEnd = spanned.getSpanEnd(span);
                    break;
                }
            }
        }

        String candidateText = candidate.toString();

        boolean textChanged =
                !candidateText.equals(lastRenderedLyricText);

        boolean highlightChanged =
                activeStart != lastRenderedLyricStart
                || activeEnd != lastRenderedLyricEnd;

        if (textChanged || highlightChanged) {

            lyricsLabel.setText(candidate);

            lastRenderedLyricText = candidateText;
            lastRenderedLyricStart = activeStart;
            lastRenderedLyricEnd = activeEnd;
        }

        final int activeLine =
                currentMidiLyrics.activeLineNumberAtTick(tick);

        if (activeLine < 0) {
            return;
        }

        final int activeOffset = Math.max(
                0,
                currentMidiLyrics.activeLineStartOffsetAtTick(tick));

        if (activeLine == lastScrolledLyricLine) {
            return;
        }

        lastScrolledLyricLine = activeLine;

        lyricsLabel.post(() -> {

            if (released
                    || returningToSongbook
                    || lyricsLabel == null
                    || lyricsScroll == null) {
                return;
            }

            Layout layout = lyricsLabel.getLayout();

            if (layout == null || layout.getLineCount() == 0) {
                return;
            }

            int offset = Math.min(
                    activeOffset,
                    lyricsLabel.length());

            int textLine = layout.getLineForOffset(offset);

            int lineTop = layout.getLineTop(textLine);
            int lineBottom = layout.getLineBottom(textLine);

            int viewportHeight =
                    lyricsScroll.getHeight()
                            - lyricsScroll.getPaddingTop()
                            - lyricsScroll.getPaddingBottom();

            if (viewportHeight <= 0) {
                return;
            }

            int lineHeight = lineBottom - lineTop;

            int target = lineTop
                    - (viewportHeight - lineHeight) / 2;

            int maxScroll = Math.max(
                    0,
                    lyricsLabel.getHeight()
                            - viewportHeight);

            target = Math.max(
                    0,
                    Math.min(target, maxScroll));

            int currentScroll = lyricsScroll.getScrollY();

            if (Math.abs(target - currentScroll) > dp(18)) {
                lyricsScroll.smoothScrollTo(0, target);
            }
        });
    }

    private void resetLyricTracking() {

        lastRenderedLyricStart = -2;
        lastRenderedLyricEnd = -2;
        lastRenderedLyricText = null;

        lastScrolledLyricLine = Integer.MIN_VALUE;

        if (countdownLabel != null) {
            countdownLabel.setVisibility(View.GONE);
        }

        if (lyricsScroll != null) {
            lyricsScroll.stopNestedScroll();
            lyricsScroll.smoothScrollTo(0, 0);
        }
    }

    private File extractSongHubRawMidi(
            String songUri) throws IOException {

        Uri songHubUri = Uri.parse(songUri);

        String blobString =
                songHubUri.getQueryParameter("blob");

        String offsetString =
                songHubUri.getQueryParameter("offset");

        String lengthString =
                songHubUri.getQueryParameter("length");

        if (blobString == null
                || offsetString == null
                || lengthString == null) {

            throw new IOException(
                    "This catalog entry has no linked blob file. "
                    + "Reconnect the SongHub library.");
        }

        long sectorOffset;
        long sectorLength;

        try {
            sectorOffset = Long.parseLong(offsetString);
            sectorLength = Long.parseLong(lengthString);

        } catch (NumberFormatException e) {

            throw new IOException(
                    "Invalid SongHub sector reference.");
        }

        if (sectorOffset < 0 || sectorLength <= 0) {
            throw new IOException(
                    "Invalid SongHub data range.");
        }

        long byteOffset;
        long byteLength;

        try {
            byteOffset = Math.multiplyExact(
                    sectorOffset,
                    (long) SONGHUB_SECTOR_SIZE);

            byteLength = Math.multiplyExact(
                    sectorLength,
                    (long) SONGHUB_SECTOR_SIZE);

        } catch (ArithmeticException e) {

            throw new IOException(
                    "SongHub data range is too large.");
        }

        if (byteLength > MAX_SONGHUB_SLICE_BYTES) {
            throw new IOException(
                    "SongHub data range exceeds the supported size.");
        }

        Uri blobUri = Uri.parse(blobString);

        File directory = new File(
                getCacheDir(),
                "stage3_songhub");

        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException(
                    "Cannot create the SongHub cache folder.");
        }

        String safeNumber = currentSong == null
                ? "song"
                : currentSong.number.replaceAll(
                        "[^A-Za-z0-9_-]", "_");

        File destination =
                new File(directory, safeNumber + ".mid");

        File temporary =
                new File(directory, safeNumber + ".tmp");

        try (InputStream input =
                     getContentResolver().openInputStream(blobUri)) {

            if (input == null) {
                throw new IOException(
                        "Cannot open the SongHub blob file.");
            }

            skipFully(input, byteOffset);

            try (FileOutputStream output =
                         new FileOutputStream(temporary)) {

                byte[] buffer = new byte[8192];
                long remaining = byteLength;
                int read;

                while (remaining > 0
                        && (read = input.read(
                                buffer,
                                0,
                                (int) Math.min(
                                        buffer.length,
                                        remaining))) != -1) {

                    output.write(buffer, 0, read);
                    remaining -= read;
                }

                if (remaining != 0) {
                    throw new IOException(
                            "The blob ended before the indexed song.");
                }
            }

        } catch (Exception e) {

            temporary.delete();

            if (e instanceof IOException) {
                throw (IOException) e;
            }

            throw new IOException(
                    "Could not read the SongHub blob range.", e);
        }

        if (temporary.length() < 14) {

            temporary.delete();

            throw new IOException(
                    "The selected data is too short for MIDI.");
        }

        byte[] header = new byte[4];

        try (InputStream check =
                     new java.io.FileInputStream(temporary)) {

            if (check.read(header) != 4
                    || header[0] != 'M'
                    || header[1] != 'T'
                    || header[2] != 'h'
                    || header[3] != 'd') {

                temporary.delete();

                throw new IOException(
                        "This indexed data is not a standard "
                        + "unencrypted MIDI file.");
            }
        }

        if (destination.exists() && !destination.delete()) {

            temporary.delete();

            throw new IOException(
                    "Cannot replace the cached MIDI file.");
        }

        if (!temporary.renameTo(destination)) {

            temporary.delete();

            throw new IOException(
                    "Cannot save the cached MIDI file.");
        }

        return destination;
    }

    private void skipFully(
            InputStream input,
            long bytes) throws IOException {

        long remaining = bytes;

        while (remaining > 0) {

            long skipped = input.skip(remaining);

            if (skipped > 0) {
                remaining -= skipped;

            } else {

                if (input.read() == -1) {
                    throw new IOException(
                            "Blob file ended before the song data.");
                }

                remaining--;
            }
        }
    }

    private void initializeMediaPlayer(
            Stage3SongQueue.QueuedSong song) {

        midiMode = false;
        midiReady = false;
        currentMidiTiming = null;

        currentMidiLyrics = Stage3MidiLyrics.empty();

        countdownLabel.setVisibility(View.GONE);
        backgroundTexture.setVisibility(View.GONE);
        backgroundImage.setVisibility(View.GONE);
        stage.setBackgroundColor(DARK_GREEN);

        lyricsLabel.setText(
                "This file is not a MIDI/KAR karaoke song.");

        resetLyricTracking();

        try {
            player = new ExoPlayer.Builder(this).build();

            player.setVideoTextureView(backgroundTexture);

            player.addListener(new Player.Listener() {

                @Override
                public void onPlaybackStateChanged(
                        int playbackState) {

                    if (player == null || midiMode) {
                        return;
                    }

                    if (playbackState == Player.STATE_ENDED) {
                        finishCurrentSong();
                    }
                }

                @Override
                public void onPlayerError(
                        PlaybackException error) {

                    Toast.makeText(
                            Stage3PlayerActivity.this,
                            "Playback error: "
                                    + error.getErrorCodeName(),
                            Toast.LENGTH_LONG).show();
                }
            });

            player.setMediaItem(
                    MediaItem.fromUri(Uri.parse(song.uri)));

            player.prepare();
            player.play();

            setControlsEnabled(true);

        } catch (Exception e) {

            setControlsEnabled(false);

            Toast.makeText(
                    this,
                    "Could not play this file.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void finishCurrentSong() {

        if (released || returningToSongbook) {
            return;
        }

        midiReady = false;
        midiMode = false;

        Stage3SongQueue.stopAndClear();

        currentSong = null;

        releasePlayback();

        songTitleLabel.setText("SONG FINISHED");

        lyricsLabel.setText(
                "SONG FINISHED\n\n"
                + "Return to the Songbook "
                + "to choose another song.");

        countdownLabel.setVisibility(View.GONE);

        updatePlaybackTime(0L, 0L);

        setControlsEnabled(false);
    }

    private void releaseBackgroundPlayer() {

        if (backgroundPlayer != null) {

            try {
                backgroundPlayer.clearVideoTextureView(
                        backgroundTexture);

                backgroundPlayer.release();

            } catch (Exception ignored) {
            }

            backgroundPlayer = null;
        }

        backgroundAvailable = false;
        currentBackgroundVideoSize = null;

        if (backgroundTexture != null) {
            backgroundTexture.setVisibility(View.GONE);
            backgroundTexture.setTransform(new Matrix());
        }
    }

    private void releasePlayback() {

        if (player != null) {

            try {
                player.clearVideoTextureView(
                        backgroundTexture);

                player.release();

            } catch (Exception ignored) {
            }

            player = null;
        }

        releaseBackgroundPlayer();

        if (midiEngine != null) {

            try {
                midiEngine.stop();

            } catch (Exception ignored) {
            }
        }

        midiMode = false;
        midiReady = false;
        midiDurationTicks = 0L;
        midiPausedByLifecycle = false;

        currentMidiLyrics = Stage3MidiLyrics.empty();
        currentMidiTiming = null;

        resetLyricTracking();

        if (singerLabel != null) {
            singerLabel.setText("");
            singerLabel.setVisibility(View.GONE);
        }

        if (lyricsLabel != null) {
            lyricsLabel.setText("");
        }

        if (backgroundImage != null) {
            backgroundImage.setImageDrawable(null);
            backgroundImage.setVisibility(View.GONE);
        }

        if (backgroundTexture != null) {
            backgroundTexture.setVisibility(View.GONE);
        }

        setControlsEnabled(false);

        updatePlaybackTime(0L, 0L);

        if (seekBar != null) {
            updatingSeekBar = true;
            seekBar.setProgress(0);
            updatingSeekBar = false;
        }

        if (stage != null) {
            stage.setBackgroundColor(DARK_GREEN);
        }
    }

    @Override
    protected void onStop() {
        markPlayerLaunchCheckpoint(
                "LIFECYCLE_ON_STOP_finishing_" + isFinishing());

        super.onStop();

        // Stop microphone capture whenever Stage 3 leaves foreground.
        stopMicrophoneMonitoring();

        if (returningToSongbook || released) {
            return;
        }

        if (midiMode && midiEngine != null && midiReady) {

            midiPausedByLifecycle =
                    midiEngine.isPlaying();

            if (midiPausedByLifecycle) {
                midiEngine.pause();
            }

            if (backgroundPlayer != null) {
                backgroundPlayer.pause();
            }

        } else if (player != null) {

            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        markPlayerLaunchCheckpoint("LIFECYCLE_ON_RESUME");

        if (returningToSongbook || released) {
            return;
        }

        if (midiMode && midiEngine != null
                && midiReady && midiPausedByLifecycle) {

            midiEngine.resume();

            midiPausedByLifecycle = false;

            if (backgroundPlayer != null) {
                backgroundPlayer.play();
            }
        }

        handler.removeCallbacks(progressUpdater);
        handler.post(progressUpdater);
    }

    @Override
    public void onBackPressed() {
        markPlayerLaunchCheckpoint("ON_BACK_PRESSED");
        returnToSongbook();
    }

    @Override
    protected void onDestroy() {

        markPlayerLaunchCheckpoint(
                "LIFECYCLE_ON_DESTROY_finishing_" + isFinishing());

        released = true;

        stopMicrophoneMonitoring();

        handler.removeCallbacks(progressUpdater);

        if (player != null) {

            try {
                player.release();

            } catch (Exception ignored) {
            }

            player = null;
        }

        releaseBackgroundPlayer();

        if (midiEngine != null) {

            midiEngine.release();
            midiEngine = null;
        }

        super.onDestroy();
    }
}
