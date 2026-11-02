
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
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    private ExoPlayer player;
    private ExoPlayer backgroundPlayer;

    private Stage3MidiEngine midiEngine;

    private FrameLayout stage;
    private TextureView backgroundTexture;

    private LinearLayout overlay;
    private TextView singerLabel;
    private TextView lyricsLabel;
    private ScrollView lyricsScroll;

    private boolean released;
    private boolean midiMode;
    private boolean midiReady;
    private boolean midiPausedByLifecycle;
    private boolean backgroundAvailable;

    private long midiDurationTicks;

    private int lastRenderedLyricStart = -2;
    private int lastRenderedLyricEnd = -2;
    private String lastRenderedLyricText;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private Stage3MidiLyrics currentMidiLyrics =
            Stage3MidiLyrics.empty();

    private Stage3SongQueue.QueuedSong currentSong;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (released) return;

            if (midiMode && midiReady && midiEngine != null) {
                updateMidiLyricsAtTick(
                        midiEngine.getCurrentTick());
            }

            handler.postDelayed(this, 100);
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
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {

        stage = new FrameLayout(this);
        stage.setBackgroundColor(DARK_GREEN);

        backgroundTexture = new TextureView(this);
        backgroundTexture.setVisibility(View.GONE);

        FrameLayout.LayoutParams videoParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);

        stage.addView(backgroundTexture, videoParams);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(
                dp(16), dp(12), dp(16), dp(12));
        overlay.setBackgroundColor(Color.TRANSPARENT);

        FrameLayout.LayoutParams overlayParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);

        stage.addView(overlay, overlayParams);

        singerLabel = new TextView(this);
        singerLabel.setText("");
        singerLabel.setTextColor(Color.WHITE);
        singerLabel.setTextSize(19);
        singerLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        singerLabel.setGravity(Gravity.CENTER);
        singerLabel.setPadding(
                dp(8), dp(8), dp(8), dp(14));
        singerLabel.setVisibility(View.GONE);

        overlay.addView(
                singerLabel,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setBackgroundColor(Color.TRANSPARENT);
        lyricsScroll.setOverScrollMode(
                ScrollView.OVER_SCROLL_NEVER);

        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f);

        overlay.addView(lyricsScroll, scrollParams);

        lyricsLabel = new TextView(this);
        lyricsLabel.setText("");
        lyricsLabel.setTextColor(Color.WHITE);
        lyricsLabel.setTextSize(31);
        lyricsLabel.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        lyricsLabel.setGravity(Gravity.CENTER);
        lyricsLabel.setPadding(
                dp(12), dp(24), dp(12), dp(24));
        lyricsLabel.setLineSpacing(dp(10), 1.05f);
        lyricsLabel.setIncludeFontPadding(true);
        lyricsLabel.setMaxLines(Integer.MAX_VALUE);

        lyricsScroll.addView(
                lyricsLabel,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT));

        setContentView(stage);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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
            return;
        }

        initializePlayer(currentSong);
    }

    private boolean isSongHubMidi(
            Stage3SongQueue.QueuedSong song) {

        if (song == null || song.uri == null) return false;

        try {
            return "songhub-midi".equalsIgnoreCase(
                    Uri.parse(song.uri).getScheme());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isMidiSong(
            Stage3SongQueue.QueuedSong song) {

        if (song == null) return false;

        if (isSongHubMidi(song)) return true;

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

        currentMidiLyrics = Stage3MidiLyrics.empty();

        lyricsLabel.setText("");
        singerLabel.setText("");
        singerLabel.setVisibility(View.GONE);

        resetLyricTracking();

        // Start the separate silent video, if a matching MP4 exists.
        startMatchingBackgroundVideo(song);

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
                    if (!released && midiMode) {
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

                if (released || !midiMode
                        || midiEngine == null) {
                    return;
                }

                midiEngine.play(
                        playableMidiUri,
                        selectedSoundFont,
                        new Stage3MidiEngine.Callback() {

                    @Override
                    public void onReady(long durationTicks) {

                        if (released || !midiMode) return;

                        midiReady = true;
                        midiDurationTicks =
                                Math.max(0L, durationTicks);

                        File midiFile = new File(
                                getFilesDir(),
                                "stage3_midi/song.mid");

                        currentMidiLyrics =
                                Stage3MidiLyrics.read(midiFile);

                        String singer =
                                currentMidiLyrics.getSinger();

                        if (singer != null
                                && !singer.trim().isEmpty()) {

                            singerLabel.setText(
                                    singer.trim());

                            singerLabel.setVisibility(
                                    View.VISIBLE);
                        } else {
                            singerLabel.setVisibility(
                                    View.GONE);
                        }

                        updateMidiLyricsAtTick(
                                midiEngine.getCurrentTick());
                    }

                    @Override
                    public void onError(String message) {

                        if (released || !midiMode) return;

                        midiReady = false;

                        lyricsLabel.setText(
                                "Unable to play this MIDI/KAR song.");

                        Toast.makeText(
                                Stage3PlayerActivity.this,
                                message,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onFinished() {

                        if (released || !midiMode) return;

                        finishCurrentSong();
                    }
                });
            });

        }, "Stage3MidiPrepare").start();
    }

    /*
     * Find an MP4 in the existing Songbook.
     * The MP4 is visual only. It never supplies the song audio.
     */
    private void startMatchingBackgroundVideo(
            Stage3SongQueue.QueuedSong midiSong) {

        String videoUri = findMatchingBackgroundVideo(midiSong);

        if (videoUri == null) {
            backgroundAvailable = false;
            backgroundTexture.setVisibility(View.GONE);
            stage.setBackgroundColor(DARK_GREEN);
            return;
        }

        try {
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
                public void onPlayerError(
                        PlaybackException error) {

                    backgroundAvailable = false;

                    if (backgroundPlayer != null) {
                        backgroundPlayer.release();
                        backgroundPlayer = null;
                    }

                    backgroundTexture.setVisibility(
                            View.GONE);

                    stage.setBackgroundColor(DARK_GREEN);
                }
            });

            backgroundTexture.setVisibility(View.VISIBLE);
            backgroundAvailable = true;

            backgroundPlayer.setMediaItem(
                    MediaItem.fromUri(Uri.parse(videoUri)));

            backgroundPlayer.prepare();
            backgroundPlayer.play();

        } catch (Exception e) {

            backgroundAvailable = false;

            if (backgroundPlayer != null) {
                backgroundPlayer.release();
                backgroundPlayer = null;
            }

            backgroundTexture.setVisibility(View.GONE);
            stage.setBackgroundColor(DARK_GREEN);
        }
    }

    private String findMatchingBackgroundVideo(
            Stage3SongQueue.QueuedSong midiSong) {

        if (midiSong == null) return null;

        String wanted = normalizeSongTitle(midiSong.title);

        if (wanted.isEmpty()) return null;

        SharedPreferences preferences =
                getSharedPreferences(
                        SONGBOOK_PREFS,
                        MODE_PRIVATE);

        String json = preferences.getString(
                SONGS_KEY, "[]");

        try {
            JSONArray songs = new JSONArray(json);

            String exactMatch = null;
            String bestMatch = null;
            int bestScore = 0;

            for (int i = 0; i < songs.length(); i++) {

                JSONObject item = songs.optJSONObject(i);

                if (item == null) continue;

                String uri = item.optString("uri", "");
                String type = item.optString("type", "");
                String title = item.optString("title", "");

                if (uri.isEmpty()) continue;

                String lowerUri =
                        uri.toLowerCase(Locale.ROOT);

                String lowerType =
                        type.toUpperCase(Locale.ROOT);

                boolean isMp4 =
                        lowerUri.endsWith(".mp4")
                        || lowerUri.contains(".mp4?")
                        || lowerType.contains("VIDEO")
                        && lowerUri.contains(".mp4");

                if (!isMp4) continue;

                String candidate =
                        normalizeSongTitle(title);

                if (candidate.isEmpty()) {
                    candidate = normalizeSongTitle(
                            Uri.parse(uri).getLastPathSegment());
                }

                if (candidate.isEmpty()) continue;

                if (wanted.equals(candidate)) {
                    exactMatch = uri;
                    break;
                }

                int score = similarityScore(
                        wanted, candidate);

                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = uri;
                }
            }

            if (exactMatch != null) {
                return exactMatch;
            }

            // Only accept a very close title match.
            if (bestScore >= 90) {
                return bestMatch;
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    private String normalizeSongTitle(String value) {

        if (value == null) return "";

        String s = value.trim();

        s = s.replaceAll(
                "(?i)\\.(mid|midi|kar|mp4)$", "");

        s = Normalizer.normalize(
                s, Normalizer.Form.NFD);

        s = s.replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT);

        s = s.replaceAll(
                "[^a-z0-9]+", " ");

        s = s.replaceAll("\\s+", " ").trim();

        return s;
    }

    private int similarityScore(
            String first,
            String second) {

        if (first.equals(second)) return 100;

        if (first.isEmpty() || second.isEmpty()) {
            return 0;
        }

        if (first.contains(second)
                || second.contains(first)) {
            return 92;
        }

        String[] a = first.split(" ");
        String[] b = second.split(" ");

        int common = 0;

        for (String word : a) {
            for (String other : b) {
                if (word.equals(other)) {
                    common++;
                    break;
                }
            }
        }

        int total = Math.max(a.length, b.length);

        return total == 0
                ? 0
                : common * 100 / total;
    }

    private void updateMidiLyricsAtTick(long tick) {

        if (lyricsLabel == null
                || currentMidiLyrics == null) {
            return;
        }

        if (!currentMidiLyrics.hasLyrics()) {

            if (!"No embedded lyrics found."
                    .contentEquals(lyricsLabel.getText())) {

                lyricsLabel.setText(
                        "No embedded lyrics found.");
            }

            return;
        }

        CharSequence candidate =
                currentMidiLyrics.styledFullTextAtTick(tick);

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

                if (span.getForegroundColor()
                        == ACTIVE_GREEN) {

                    activeStart =
                            spanned.getSpanStart(span);

                    activeEnd =
                            spanned.getSpanEnd(span);

                    break;
                }
            }
        }

        String candidateText = candidate.toString();

        boolean changed =
                !candidateText.equals(lastRenderedLyricText)
                || activeStart != lastRenderedLyricStart
                || activeEnd != lastRenderedLyricEnd;

        if (changed) {

            lyricsLabel.setText(candidate);

            lastRenderedLyricText = candidateText;
            lastRenderedLyricStart = activeStart;
            lastRenderedLyricEnd = activeEnd;
        }

        if (activeStart < 0) return;

        final int activeOffset = Math.max(
                0,
                currentMidiLyrics
                        .activeLineStartOffsetAtTick(tick));

        lyricsLabel.post(() -> {

            if (released || lyricsLabel == null
                    || lyricsScroll == null) {
                return;
            }

            Layout layout = lyricsLabel.getLayout();

            if (layout == null
                    || layout.getLineCount() == 0) {
                return;
            }

            int offset = Math.min(
                    activeOffset,
                    lyricsLabel.length());

            int line = layout.getLineForOffset(offset);

            int lineTop = layout.getLineTop(line);
            int lineBottom = layout.getLineBottom(line);

            int scrollY = lyricsScroll.getScrollY();
            int viewport = lyricsScroll.getHeight();

            int margin = dp(28);

            boolean outside =
                    lineTop < scrollY + margin
                    || lineBottom > scrollY + viewport - margin;

            if (outside) {

                int target =
                        lineTop - viewport / 3;

                int max = Math.max(
                        0,
                        lyricsLabel.getHeight() - viewport);

                target = Math.max(
                        0, Math.min(target, max));

                lyricsScroll.smoothScrollTo(0, target);
            }
        });
    }

    private void resetLyricTracking() {
        lastRenderedLyricStart = -2;
        lastRenderedLyricEnd = -2;
        lastRenderedLyricText = null;

        if (lyricsScroll != null) {
            lyricsScroll.scrollTo(0, 0);
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

    /*
     * Non-MIDI songs retain their normal media playback.
     */
    private void initializeMediaPlayer(
            Stage3SongQueue.QueuedSong song) {

        midiMode = false;
        midiReady = false;

        currentMidiLyrics = Stage3MidiLyrics.empty();

        backgroundTexture.setVisibility(View.GONE);
        stage.setBackgroundColor(DARK_GREEN);

        lyricsLabel.setText(
                "This file is not a MIDI/KAR karaoke song.");

        try {
            player = new ExoPlayer.Builder(this).build();

            player.setVideoTextureView(backgroundTexture);

            player.addListener(new Player.Listener() {

                @Override
                public void onPlaybackStateChanged(
                        int playbackState) {

                    if (player == null || midiMode) return;

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

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "Could not play this file.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void finishCurrentSong() {

        if (released) return;

        midiReady = false;
        midiMode = false;

        Stage3SongQueue.stopAndClear();

        currentSong = null;

        releasePlayback();

        lyricsLabel.setText(
                "SONG FINISHED\n\n"
                + "Return to the Songbook "
                + "to choose another song.");
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

        if (backgroundTexture != null) {
            backgroundTexture.setVisibility(View.GONE);
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

        resetLyricTracking();

        if (singerLabel != null) {
            singerLabel.setText("");
            singerLabel.setVisibility(View.GONE);
        }

        if (lyricsLabel != null) {
            lyricsLabel.setText("");
        }

        if (stage != null) {
            stage.setBackgroundColor(DARK_GREEN);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

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

        released = false;

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
    protected void onDestroy() {

        released = true;

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
