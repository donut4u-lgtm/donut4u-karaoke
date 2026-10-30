package com.donut4u.karaoke;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spanned;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class Stage3PlayerActivity extends Activity {

    private static final String SONGBOOK_PREFS = "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";
    private static final String DEFAULT_SOUNDFONT = "builtin://default";
    private static final int SONGHUB_SECTOR_SIZE = 2048;
    private static final long MAX_SONGHUB_SLICE_BYTES = 64L * 1024L * 1024L;

    private ExoPlayer player;
    private Stage3MidiEngine midiEngine;

    private SurfaceView videoSurface;
    private TextView titleLabel;
    private TextView statusLabel;
    private TextView metadataLabel;
    private TextView preparationLabel;
    private TextView lyricsLabel;
    private TextView cueLabel;
    private TextView timeLabel;
    private ScrollView lyricsScroll;
    private SeekBar seekBar;
    private Button playPauseButton;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean userSeeking;
    private boolean released;
    private boolean midiMode;
    private boolean midiReady;
    private boolean midiPausedByLifecycle;

    private long midiDurationTicks;
    private int lastAutoScrolledLine = -1;
    private int lastAutoScrolledOffset = -1;
    private int lastRenderedLyricStart = -2;
    private int lastRenderedLyricEnd = -2;
    private int dotFrame;
    private String lastRenderedLyricText = null;
    private String lastPreparationText = null;

    private Stage3MidiLyrics currentMidiLyrics = Stage3MidiLyrics.empty();
    private Stage3SongQueue.QueuedSong currentSong;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (released) return;

            if (midiMode && midiEngine != null && midiReady) {
                updateMidiProgress();
            } else if (!midiMode && player != null) {
                updateMediaProgress();
            }

            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Stage3SongQueue.initialize(this);
        midiEngine = Stage3MidiEngine.create(this);
        buildScreen();
        startSelectedSong();
        handler.post(progressUpdater);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(8));
        root.setBackgroundColor(Color.rgb(8, 17, 12));

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(Color.rgb(80, 255, 150));
        heading.setTextSize(24);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(42)));

        titleLabel = new TextView(this);
        titleLabel.setText("Preparing song...");
        titleLabel.setTextColor(Color.WHITE);
        titleLabel.setTextSize(18);
        titleLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleLabel.setGravity(Gravity.CENTER);
        titleLabel.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(titleLabel, new LinearLayout.LayoutParams(-1, -2));

        statusLabel = new TextView(this);
        statusLabel.setText("Preparing playback...");
        statusLabel.setTextColor(Color.rgb(140, 255, 180));
        statusLabel.setTextSize(14);
        statusLabel.setGravity(Gravity.CENTER);
        root.addView(statusLabel, new LinearLayout.LayoutParams(-1, dp(28)));

        videoSurface = new SurfaceView(this);
        videoSurface.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(-1, 0, 1f);
        videoParams.setMargins(0, dp(4), 0, dp(4));
        root.addView(videoSurface, videoParams);

        metadataLabel = new TextView(this);
        metadataLabel.setTextColor(Color.rgb(255, 210, 100));
        metadataLabel.setTextSize(15);
        metadataLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        metadataLabel.setGravity(Gravity.CENTER);
        metadataLabel.setPadding(dp(8), dp(6), dp(8), dp(6));
        metadataLabel.setVisibility(View.GONE);
        root.addView(metadataLabel,
                new LinearLayout.LayoutParams(-1, -2));

        preparationLabel = new TextView(this);
        preparationLabel.setTextColor(Color.rgb(140, 255, 180));
        preparationLabel.setTextSize(17);
        preparationLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        preparationLabel.setGravity(Gravity.CENTER);
        preparationLabel.setPadding(dp(8), dp(5), dp(8), dp(5));
        preparationLabel.setMaxLines(2);
        preparationLabel.setVisibility(View.GONE);
        root.addView(preparationLabel,
                new LinearLayout.LayoutParams(-1, -2));

        cueLabel = new TextView(this);
        cueLabel.setText("1   2   3   4   5");
        cueLabel.setTextColor(Color.rgb(80, 255, 150));
        cueLabel.setTextSize(28);
        cueLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cueLabel.setGravity(Gravity.CENTER);
        cueLabel.setVisibility(View.GONE);
        root.addView(cueLabel,
                new LinearLayout.LayoutParams(-1, dp(44)));

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(false);
        lyricsScroll.setSmoothScrollingEnabled(true);
        lyricsScroll.setVerticalScrollBarEnabled(true);
        lyricsScroll.setBackgroundColor(Color.rgb(8, 17, 12));
        lyricsScroll.setOverScrollMode(
                ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS);

        lyricsLabel = new TextView(this);
        lyricsLabel.setText("Waiting for embedded lyrics...");
        lyricsLabel.setTextColor(Color.WHITE);
        lyricsLabel.setTextSize(25);
        lyricsLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lyricsLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        lyricsLabel.setPadding(dp(14), dp(20), dp(14), dp(28));
        lyricsLabel.setLineSpacing(dp(5), 1.0f);
        lyricsLabel.setIncludeFontPadding(true);
        lyricsLabel.setMaxLines(Integer.MAX_VALUE);
        lyricsLabel.setFocusable(false);
        lyricsLabel.setTextIsSelectable(false);

        lyricsScroll.addView(lyricsLabel,
                new ScrollView.LayoutParams(-1, -2));
        lyricsScroll.setVisibility(View.GONE);

        LinearLayout.LayoutParams lyricsParams =
                new LinearLayout.LayoutParams(-1, 0, 1f);
        lyricsParams.setMargins(0, dp(4), 0, dp(4));
        root.addView(lyricsScroll, lyricsParams);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        root.addView(seekBar,
                new LinearLayout.LayoutParams(-1, dp(38)));

        timeLabel = new TextView(this);
        timeLabel.setText("00:00 / 00:00");
        timeLabel.setTextColor(Color.LTGRAY);
        timeLabel.setTextSize(12);
        timeLabel.setGravity(Gravity.CENTER);
        root.addView(timeLabel,
                new LinearLayout.LayoutParams(-1, dp(22)));

        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(
                    SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;

                if (midiMode && midiReady && midiDurationTicks > 0) {
                    long target = midiDurationTicks * progress / 1000L;
                    long targetMs = currentMidiLyrics.tickToMillis(target);
                    long durationMs =
                            currentMidiLyrics.tickToMillis(midiDurationTicks);
                    timeLabel.setText(
                            formatTime(targetMs) + " / " + formatTime(durationMs));
                } else if (!midiMode && player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        long target = duration * progress / 1000L;
                        timeLabel.setText(
                                formatTime(target) + " / " + formatTime(duration));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int progress = bar.getProgress();

                if (midiMode && midiEngine != null
                        && midiReady && midiDurationTicks > 0) {
                    midiEngine.seekTo(midiDurationTicks * progress / 1000L);
                    updateMidiLyricsAtCurrentPosition();
                } else if (!midiMode && player != null
                        && player.getDuration() > 0) {
                    player.seekTo(player.getDuration() * progress / 1000L);
                }

                userSeeking = false;
            }
        });

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        playPauseButton = makeButton("PLAY");
        playPauseButton.setOnClickListener(v -> togglePlayback());

        Button nextButton = makeButton("NEXT SONG");
        nextButton.setOnClickListener(v -> playNextSong());

        Button stopButton = makeButton("STOP");
        stopButton.setOnClickListener(v -> stopPlayback());

        addControl(controls, playPauseButton, 1f);
        addControl(controls, nextButton, 1f);
        addControl(controls, stopButton, 0.8f);
        root.addView(controls,
                new LinearLayout.LayoutParams(-1, dp(48)));

        Button backButton = makeButton("BACK TO QUEUE");
        backButton.setOnClickListener(v -> finish());
        root.addView(backButton,
                new LinearLayout.LayoutParams(-1, dp(42)));

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(20, 125, 65)));
        return button;
    }

    private void addControl(
            LinearLayout parent, Button button, float weight) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, -1, weight);
        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, params);
    }

    private void showMidiLyricsArea() {
        videoSurface.setVisibility(View.GONE);
        lyricsScroll.setVisibility(View.VISIBLE);
        lyricsScroll.scrollTo(0, 0);
        resetLyricsScrollTracking();
    }

    private void showVideoArea() {
        metadataLabel.setVisibility(View.GONE);
        preparationLabel.setVisibility(View.GONE);
        lyricsScroll.setVisibility(View.GONE);
        cueLabel.setVisibility(View.GONE);
        lyricsScroll.scrollTo(0, 0);
        videoSurface.setVisibility(View.VISIBLE);
        resetLyricsScrollTracking();
    }

    private void resetLyricsScrollTracking() {
        lastAutoScrolledLine = -1;
        lastAutoScrolledOffset = -1;
        lastRenderedLyricStart = -2;
        lastRenderedLyricEnd = -2;
        lastRenderedLyricText = null;
        lastPreparationText = null;
    }

    private void startSelectedSong() {
        currentSong = Stage3SongQueue.getNowPlaying();
        if (currentSong == null) currentSong = Stage3SongQueue.playNext();

        if (currentSong == null) {
            titleLabel.setText("No song selected");
            statusLabel.setText(
                    "Return to the songbook and add songs to the queue.");
            lyricsLabel.setText("No song selected.");
            lyricsScroll.setVisibility(View.VISIBLE);
            videoSurface.setVisibility(View.GONE);
            playPauseButton.setEnabled(false);
            return;
        }

        titleLabel.setText(currentSong.number + " • " + currentSong.title);
        initializePlayer(currentSong);
    }

    private boolean isSongHubMidi(Stage3SongQueue.QueuedSong song) {
        if (song == null || song.uri == null) return false;
        try {
            return "songhub-midi".equalsIgnoreCase(
                    Uri.parse(song.uri).getScheme());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isMidiSong(Stage3SongQueue.QueuedSong song) {
        if (song == null) return false;
        if (isSongHubMidi(song)) return true;

        String type = song.type == null
                ? "" : song.type.toUpperCase(Locale.ROOT);
        String segment = "";

        try {
            String last = Uri.parse(song.uri).getLastPathSegment();
            if (last != null) segment = last.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
        }

        return type.contains("MIDI") || type.contains("KAR")
                || segment.endsWith(".mid")
                || segment.endsWith(".midi")
                || segment.endsWith(".kar");
    }

    /*
     * Donut4U automatically manages the instrument SoundFont.
     * Users do not need to select or import a SoundFont.
     * Stage3MidiEngine handles the built-in default SoundFont.
     */
    private String findSoundFontUri() {
        return DEFAULT_SOUNDFONT;
    }

    private void initializePlayer(Stage3SongQueue.QueuedSong song) {
        releasePlayback();
        if (isMidiSong(song)) {
            initializeMidiPlayer(song);
        } else {
            initializeMediaPlayer(song);
        }
    }

    private void initializeMidiPlayer(Stage3SongQueue.QueuedSong song) {
        midiMode = true;
        midiReady = false;
        midiDurationTicks = 0L;
        currentMidiLyrics = Stage3MidiLyrics.empty();
        resetLyricsScrollTracking();

        showMidiLyricsArea();
        seekBar.setProgress(0);
        timeLabel.setText("00:00 / 00:00");
        lyricsLabel.setText("Loading song lyrics...");
        lyricsScroll.scrollTo(0, 0);
        metadataLabel.setText("");
        metadataLabel.setVisibility(View.GONE);
        preparationLabel.setText("");
        preparationLabel.setVisibility(View.GONE);
        cueLabel.setVisibility(View.GONE);
        playPauseButton.setText("PLAY");
        playPauseButton.setEnabled(false);

        String soundFontUri = findSoundFontUri();
        if (soundFontUri == null || soundFontUri.trim().isEmpty()) {
            soundFontUri = DEFAULT_SOUNDFONT;
        }

        statusLabel.setText("Loading MIDI/KAR and instrument sounds...");
        final String selectedSoundFont = soundFontUri;

        new Thread(() -> {
            String playableMidiUri;

            try {
                playableMidiUri = isSongHubMidi(song)
                        ? extractSongHubRawMidi(song.uri).toURI().toString()
                        : song.uri;
            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Could not read the indexed MIDI data."
                        : e.getMessage();

                runOnUiThread(() -> {
                    if (!released && midiMode) {
                        statusLabel.setText("SongHub MIDI unavailable");
                        lyricsLabel.setText(
                                "Lyrics unavailable for this song.");
                        Toast.makeText(
                                this, message, Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            runOnUiThread(() -> {
                if (released || !midiMode || midiEngine == null) return;

                midiEngine.play(
                        playableMidiUri,
                        selectedSoundFont,
                        new Stage3MidiEngine.Callback() {
                    @Override
                    public void onReady(long durationTicks) {
                        if (released || !midiMode) return;

                        midiReady = true;
                        midiDurationTicks = Math.max(0L, durationTicks);

                        File midiFile = new File(
                                getFilesDir(), "stage3_midi/song.mid");
                        currentMidiLyrics = Stage3MidiLyrics.read(midiFile);

                        playPauseButton.setEnabled(true);
                        playPauseButton.setText("PAUSE");
                        statusLabel.setText("MIDI playing");
                        updateMidiLyricsAtCurrentPosition();
                        updateMidiProgress();
                    }

                    @Override
                    public void onError(String message) {
                        if (released || !midiMode) return;
                        midiReady = false;
                        playPauseButton.setEnabled(false);
                        statusLabel.setText("MIDI playback failed");
                        lyricsLabel.setText(
                                "Unable to load this song's lyrics.");
                        cueLabel.setVisibility(View.GONE);
                        preparationLabel.setVisibility(View.GONE);
                        Toast.makeText(
                                Stage3PlayerActivity.this,
                                message,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onFinished() {
                        if (released || !midiMode) return;
                        statusLabel.setText("MIDI finished");
                        cueLabel.setVisibility(View.GONE);
                        playNextSong();
                    }
                });
            });
        }, "Stage3SongHubPrepare").start();
    }

    private void renderCueDots(boolean visible) {
        if (cueLabel == null) return;

        if (!visible) {
            cueLabel.setVisibility(View.GONE);
            return;
        }

        int countdownNumber =
                currentMidiLyrics.getCountdownNumberAtTick(
                        midiEngine == null ? 0L : midiEngine.getCurrentTick());

        if (countdownNumber < 1 || countdownNumber > 5) {
            cueLabel.setVisibility(View.GONE);
            return;
        }

        String countdown = "1   2   3   4   5";
        SpannableString styled = new SpannableString(countdown);

        int[] positions = {0, 4, 8, 12, 16};

        for (int i = 0; i < 5; i++) {
            int displayedNumber = i + 1;

            int color = displayedNumber == countdownNumber
                    ? Color.rgb(0, 255, 100)
                    : Color.rgb(65, 100, 75);

            styled.setSpan(
                    new ForegroundColorSpan(color),
                    positions[i],
                    positions[i] + 1,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        cueLabel.setText(styled);
        cueLabel.setVisibility(View.VISIBLE);
    }

    private void updatePreparationLine(long tick) {
        if (preparationLabel == null) return;

        String upcoming = currentMidiLyrics.upcomingLineAtTick(tick);
        if (upcoming == null) upcoming = "";
        upcoming = upcoming.trim();

        if (upcoming.isEmpty()) {
            if (lastPreparationText != null) {
                preparationLabel.setText("");
                preparationLabel.setVisibility(View.GONE);
                lastPreparationText = null;
            }
            return;
        }

        String display = "GET READY: " + upcoming;

        if (!display.equals(lastPreparationText)) {
            preparationLabel.setText(display);
            lastPreparationText = display;
        }

        preparationLabel.setVisibility(View.VISIBLE);
    }

    private void updateMidiLyricsAtCurrentPosition() {
        if (lyricsLabel == null || currentMidiLyrics == null) return;

        String metadata = currentMidiLyrics.getMetadataText();
        if (metadataLabel != null) {
            metadataLabel.setText(metadata);
            metadataLabel.setVisibility(
                    metadata == null || metadata.trim().isEmpty()
                            ? View.GONE : View.VISIBLE);
        }

        if (!currentMidiLyrics.hasLyrics()) {
            if (cueLabel != null) cueLabel.setVisibility(View.GONE);
            if (preparationLabel != null) {
                preparationLabel.setVisibility(View.GONE);
            }

            if (lastRenderedLyricStart != -1) {
                lyricsLabel.setText(
                        "No embedded lyrics found in this MIDI/KAR file.");
                lastRenderedLyricStart = -1;
                lastRenderedLyricEnd = -1;
                lastRenderedLyricText =
                        "No embedded lyrics found in this MIDI/KAR file.";
            }

            resetLyricsScrollTracking();
            return;
        }

        long tick = midiEngine == null
                ? 0L : midiEngine.getCurrentTick();

        updatePreparationLine(tick);
        renderCueDots(currentMidiLyrics.shouldShowDotsAtTick(tick));

        CharSequence candidate =
                currentMidiLyrics.styledFullTextAtTick(tick);
        int activeStart = -1;
        int activeEnd = -1;

        if (candidate instanceof Spanned) {
            Spanned spanned = (Spanned) candidate;
            ForegroundColorSpan[] spans = spanned.getSpans(
                    0, spanned.length(), ForegroundColorSpan.class);

            for (ForegroundColorSpan span : spans) {
                if (span.getForegroundColor() == Color.rgb(0, 255, 100)) {
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
            int savedScrollY =
                    lyricsScroll == null ? 0 : lyricsScroll.getScrollY();

            lyricsLabel.setText(candidate);
            lastRenderedLyricText = candidateText;
            lastRenderedLyricStart = activeStart;
            lastRenderedLyricEnd = activeEnd;

            final int restoreY = savedScrollY;
            lyricsLabel.post(() -> {
                if (released || lyricsScroll == null || lyricsLabel == null
                        || lyricsScroll.getVisibility() != View.VISIBLE) {
                    return;
                }

                Layout layout = lyricsLabel.getLayout();
                if (layout == null || layout.getLineCount() == 0) return;

                int maxScroll = Math.max(
                        0, lyricsLabel.getHeight() - lyricsScroll.getHeight());

                lyricsScroll.scrollTo(0, Math.min(restoreY, maxScroll));
            });
        }

        if (activeStart < 0) return;

        final int activeOffset = Math.max(
                0, currentMidiLyrics.activeLineStartOffsetAtTick(tick));

        lyricsLabel.post(() -> {
            if (released || lyricsScroll == null || lyricsLabel == null
                    || lyricsScroll.getVisibility() != View.VISIBLE) {
                return;
            }

            Layout layout = lyricsLabel.getLayout();
            if (layout == null || layout.getLineCount() == 0) return;

            int offset = Math.min(activeOffset, lyricsLabel.length());
            int visualLine = layout.getLineForOffset(offset);
            int lineTop = layout.getLineTop(visualLine);
            int lineBottom = layout.getLineBottom(visualLine);

            int scrollY = lyricsScroll.getScrollY();
            int viewportHeight = lyricsScroll.getHeight();
            int margin = dp(20);

            boolean outsideViewport = lineTop < scrollY + margin
                    || lineBottom > scrollY + viewportHeight - margin;
            boolean newLyricLine = activeOffset != lastAutoScrolledOffset;

            if (outsideViewport || newLyricLine) {
                int targetY = lineTop - viewportHeight / 3;
                int maxScroll = Math.max(
                        0, lyricsLabel.getHeight() - viewportHeight);
                targetY = Math.max(0, Math.min(targetY, maxScroll));

                lyricsScroll.smoothScrollTo(0, targetY);
                lastAutoScrolledLine = visualLine;
                lastAutoScrolledOffset = activeOffset;
            }
        });
    }

    private File extractSongHubRawMidi(String songUri) throws IOException {
        Uri songHubUri = Uri.parse(songUri);
        String blobString = songHubUri.getQueryParameter("blob");
        String offsetString = songHubUri.getQueryParameter("offset");
        String lengthString = songHubUri.getQueryParameter("length");

        if (blobString == null || offsetString == null || lengthString == null) {
            throw new IOException(
                    "This catalog entry has no linked blob file. "
                    + "Reconnect the library after the songbook is updated "
                    + "to save blob references.");
        }

        long sectorOffset;
        long sectorLength;

        try {
            sectorOffset = Long.parseLong(offsetString);
            sectorLength = Long.parseLong(lengthString);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid SongHub sector reference.");
        }

        if (sectorOffset < 0 || sectorLength <= 0) {
            throw new IOException("Invalid SongHub data range.");
        }

        long byteOffset;
        long byteLength;

        try {
            byteOffset = Math.multiplyExact(
                    sectorOffset, (long) SONGHUB_SECTOR_SIZE);
            byteLength = Math.multiplyExact(
                    sectorLength, (long) SONGHUB_SECTOR_SIZE);
        } catch (ArithmeticException e) {
            throw new IOException("SongHub data range is too large.");
        }

        if (byteLength > MAX_SONGHUB_SLICE_BYTES) {
            throw new IOException(
                    "SongHub data range exceeds the supported file size.");
        }

        Uri blobUri = Uri.parse(blobString);
        File directory = new File(getCacheDir(), "stage3_songhub");

        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create the SongHub cache folder.");
        }

        String safeNumber = currentSong == null
                ? "song"
                : currentSong.number.replaceAll("[^A-Za-z0-9_-]", "_");

        File destination = new File(directory, safeNumber + ".mid");
        File temporary = new File(directory, safeNumber + ".tmp");

        try (InputStream input =
                     getContentResolver().openInputStream(blobUri)) {
            if (input == null) {
                throw new IOException("Cannot open the selected blob file.");
            }

            skipFully(input, byteOffset);

            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                long remaining = byteLength;
                int read;

                while (remaining > 0
                        && (read = input.read(
                                buffer, 0,
                                (int) Math.min(buffer.length, remaining))) != -1) {
                    output.write(buffer, 0, read);
                    remaining -= read;
                }

                if (remaining != 0) {
                    throw new IOException(
                            "The blob file is shorter than the indexed range.");
                }
            }
        } catch (Exception e) {
            temporary.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Could not read the SongHub blob range.", e);
        }

        if (temporary.length() < 14) {
            temporary.delete();
            throw new IOException("The selected data is too short for MIDI.");
        }

        byte[] header = new byte[4];

        try (InputStream check = new java.io.FileInputStream(temporary)) {
            if (check.read(header) != 4
                    || header[0] != 'M'
                    || header[1] != 'T'
                    || header[2] != 'h'
                    || header[3] != 'd') {
                temporary.delete();
                throw new IOException(
                        "This indexed data is not a standard, unencrypted "
                        + "MIDI file. Protected SongHub data cannot be played "
                        + "by this raw-MIDI reader.");
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace the cached MIDI file.");
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Cannot save the cached MIDI file.");
        }

        return destination;
    }

    private void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;

        while (remaining > 0) {
            long skipped = input.skip(remaining);

            if (skipped > 0) {
                remaining -= skipped;
            } else {
                if (input.read() == -1) {
                    throw new IOException(
                            "Blob file ended before the indexed song.");
                }
                remaining--;
            }
        }
    }

    private void initializeMediaPlayer(Stage3SongQueue.QueuedSong song) {
        midiMode = false;
        midiReady = false;
        currentMidiLyrics = Stage3MidiLyrics.empty();
        showVideoArea();

        try {
            player = new ExoPlayer.Builder(this).build();
            player.setVideoSurfaceView(videoSurface);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (player == null || midiMode) return;

                    if (playbackState == Player.STATE_BUFFERING) {
                        statusLabel.setText("Buffering...");
                    } else if (playbackState == Player.STATE_READY) {
                        statusLabel.setText(
                                player.isPlaying() ? "Playing" : "Ready");
                    } else if (playbackState == Player.STATE_ENDED) {
                        statusLabel.setText("Song finished");
                        playNextSong();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (player == null || midiMode) return;

                    playPauseButton.setText(isPlaying ? "PAUSE" : "PLAY");
                    statusLabel.setText(
                            isPlaying ? "Playing" : "Playback paused");
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    statusLabel.setText(
                            "Cannot play this file. Try another format.");
                    Toast.makeText(
                            Stage3PlayerActivity.this,
                            "Playback error: " + error.getErrorCodeName(),
                            Toast.LENGTH_LONG).show();
                }
            });

            player.setMediaItem(MediaItem.fromUri(Uri.parse(song.uri)));
            player.prepare();
            player.play();
            playPauseButton.setEnabled(true);
            playPauseButton.setText("PAUSE");

        } catch (Exception e) {
            statusLabel.setText("Unable to start playback.");
            Toast.makeText(
                    this,
                    "Could not play this song.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void togglePlayback() {
        if (midiMode) {
            if (midiEngine == null || !midiReady) return;

            if (midiEngine.isPlaying()) {
                midiEngine.pause();
                playPauseButton.setText("PLAY");
                statusLabel.setText("MIDI paused");
            } else {
                midiEngine.resume();
                playPauseButton.setText("PAUSE");
                statusLabel.setText("MIDI playing");
            }
            return;
        }

        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void playNextSong() {
        Stage3SongQueue.QueuedSong next = Stage3SongQueue.playNext();

        if (next == null) {
            releasePlayback();
            statusLabel.setText("Queue finished.");
            titleLabel.setText("No more queued songs");
            lyricsLabel.setText("Queue finished.");
            lyricsScroll.setVisibility(View.VISIBLE);
            videoSurface.setVisibility(View.GONE);
            playPauseButton.setEnabled(false);
            seekBar.setProgress(0);
            timeLabel.setText("00:00 / 00:00");
            return;
        }

        currentSong = next;
        titleLabel.setText(next.number + " • " + next.title);
        initializePlayer(next);
    }

    private void stopPlayback() {
        if (midiMode && midiEngine != null) {
            midiEngine.stop();
            midiReady = false;
            playPauseButton.setText("PLAY");
            playPauseButton.setEnabled(false);
            seekBar.setProgress(0);
            timeLabel.setText("00:00 / 00:00");
            statusLabel.setText("MIDI stopped");
            cueLabel.setVisibility(View.GONE);
            preparationLabel.setVisibility(View.GONE);
            resetLyricsScrollTracking();
            return;
        }

        if (player != null) {
            player.pause();
            player.seekTo(0);
        }

        statusLabel.setText("Stopped");
    }

    private void updateMidiProgress() {
        if (midiEngine == null || !midiReady) return;

        long position = midiEngine.getCurrentTick();
        updateMidiLyricsAtCurrentPosition();

        if (midiDurationTicks > 0) {
            int progress = (int) Math.min(
                    1000L, position * 1000L / midiDurationTicks);

            if (!userSeeking) seekBar.setProgress(progress);

            long positionMs = currentMidiLyrics.tickToMillis(position);
            long durationMs =
                    currentMidiLyrics.tickToMillis(midiDurationTicks);

            timeLabel.setText(
                    formatTime(positionMs) + " / " + formatTime(durationMs));
        }

        playPauseButton.setText(
                midiEngine.isPlaying() ? "PAUSE" : "PLAY");
    }

    private void updateMediaProgress() {
        if (player == null) return;

        long duration = player.getDuration();
        long position = player.getCurrentPosition();

        if (duration > 0) {
            int progress = (int) Math.min(
                    1000L, position * 1000L / duration);

            if (!userSeeking) seekBar.setProgress(progress);

            timeLabel.setText(
                    formatTime(position) + " / " + formatTime(duration));
        } else {
            if (!userSeeking) seekBar.setProgress(0);
            timeLabel.setText(formatTime(position) + " / 00:00");
        }

        playPauseButton.setText(
                player.isPlaying() ? "PAUSE" : "PLAY");
    }

    private String formatTime(long milliseconds) {
        milliseconds = Math.max(0L, milliseconds);

        long totalSeconds = milliseconds / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        return String.format(
                Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void releasePlayback() {
        if (player != null) {
            try {
                player.clearVideoSurfaceView(videoSurface);
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }

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
        resetLyricsScrollTracking();

        if (metadataLabel != null) {
            metadataLabel.setText("");
            metadataLabel.setVisibility(View.GONE);
        }

        if (preparationLabel != null) {
            preparationLabel.setText("");
            preparationLabel.setVisibility(View.GONE);
        }

        if (cueLabel != null) cueLabel.setVisibility(View.GONE);
        if (lyricsLabel != null) {
            lyricsLabel.setText("Loading song lyrics...");
        }

        if (lyricsScroll != null) {
            lyricsScroll.setVisibility(View.GONE);
            lyricsScroll.scrollTo(0, 0);
        }

        if (videoSurface != null) {
            videoSurface.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (midiMode && midiEngine != null && midiReady) {
            midiPausedByLifecycle = midiEngine.isPlaying();
            if (midiPausedByLifecycle) midiEngine.pause();
        } else if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        released = false;

        if (midiMode && midiEngine != null
                && midiReady && midiPausedByLifecycle) {
            midiEngine.resume();
            midiPausedByLifecycle = false;
        }

        handler.removeCallbacks(progressUpdater);
        handler.post(progressUpdater);
    }

    @Override
    protected void onDestroy() {
        released = true;
        handler.removeCallbacks(progressUpdater);

        if (player != null) {
            try {
                player.clearVideoSurfaceView(videoSurface);
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }

        if (midiEngine != null) {
            midiEngine.release();
            midiEngine = null;
        }

        super.onDestroy();
    }
}
