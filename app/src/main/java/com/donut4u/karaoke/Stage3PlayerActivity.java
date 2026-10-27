
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class Stage3PlayerActivity extends Activity {

    private static final String SONGBOOK_PREFS =
            "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";

    private ExoPlayer player;
    private Stage3MidiEngine midiEngine;

    private SurfaceView videoSurface;
    private TextView titleLabel;
    private TextView statusLabel;
    private TextView timeLabel;
    private SeekBar seekBar;
    private Button playPauseButton;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean userSeeking;
    private boolean released;
    private boolean midiMode;
    private boolean midiReady;
    private boolean midiPausedByLifecycle;

    private long midiDurationTicks;
    private Stage3SongQueue.QueuedSong currentSong;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (released) {
                return;
            }

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
        return (int) (value
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackgroundColor(Color.rgb(8, 17, 12));

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(Color.rgb(80, 255, 150));
        heading.setTextSize(24);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);

        root.addView(
                heading,
                new LinearLayout.LayoutParams(-1, dp(44))
        );

        titleLabel = new TextView(this);
        titleLabel.setText("Preparing song...");
        titleLabel.setTextColor(Color.WHITE);
        titleLabel.setTextSize(18);
        titleLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleLabel.setGravity(Gravity.CENTER);
        titleLabel.setPadding(dp(4), dp(8), dp(4), dp(8));

        root.addView(titleLabel);

        videoSurface = new SurfaceView(this);
        videoSurface.setBackgroundColor(Color.BLACK);

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(-1, 0, 1f);
        videoParams.setMargins(0, dp(4), 0, dp(8));

        root.addView(videoSurface, videoParams);

        statusLabel = new TextView(this);
        statusLabel.setText("Preparing playback...");
        statusLabel.setTextColor(Color.rgb(140, 255, 180));
        statusLabel.setTextSize(14);
        statusLabel.setGravity(Gravity.CENTER);

        root.addView(statusLabel);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);

        root.addView(
                seekBar,
                new LinearLayout.LayoutParams(-1, dp(40))
        );

        timeLabel = new TextView(this);
        timeLabel.setText("00:00 / 00:00");
        timeLabel.setTextColor(Color.LTGRAY);
        timeLabel.setTextSize(12);
        timeLabel.setGravity(Gravity.CENTER);

        root.addView(timeLabel);

        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar bar,
                            int progress,
                            boolean fromUser) {

                        if (!fromUser) {
                            return;
                        }

                        if (midiMode && midiReady
                                && midiDurationTicks > 0) {

                            long target =
                                    midiDurationTicks * progress / 1000L;

                            timeLabel.setText(
                                    formatTicks(target) + " / "
                                            + formatTicks(midiDurationTicks)
                            );

                        } else if (!midiMode && player != null) {

                            long duration = player.getDuration();

                            if (duration > 0) {
                                long target =
                                        duration * progress / 1000L;

                                timeLabel.setText(
                                        formatTime(target) + " / "
                                                + formatTime(duration)
                                );
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
                                && midiReady
                                && midiDurationTicks > 0) {

                            long target =
                                    midiDurationTicks * progress / 1000L;

                            midiEngine.seekTo(target);

                        } else if (!midiMode && player != null
                                && player.getDuration() > 0) {

                            long target =
                                    player.getDuration()
                                            * progress / 1000L;

                            player.seekTo(target);
                        }

                        userSeeking = false;
                    }
                }
        );

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

        root.addView(
                controls,
                new LinearLayout.LayoutParams(-1, dp(50))
        );

        Button backButton = makeButton("BACK TO QUEUE");
        backButton.setOnClickListener(v -> finish());

        root.addView(
                backButton,
                new LinearLayout.LayoutParams(-1, dp(46))
        );

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
                        Color.rgb(20, 125, 65)
                )
        );
        return button;
    }

    private void addControl(
            LinearLayout parent,
            Button button,
            float weight) {

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, -1, weight);

        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, params);
    }

    private void startSelectedSong() {
        currentSong = Stage3SongQueue.getNowPlaying();

        if (currentSong == null) {
            currentSong = Stage3SongQueue.playNext();
        }

        if (currentSong == null) {
            titleLabel.setText("No song selected");
            statusLabel.setText(
                    "Return to the songbook and add songs to the queue."
            );
            playPauseButton.setEnabled(false);
            return;
        }

        titleLabel.setText(
                currentSong.number + " • " + currentSong.title
        );

        initializePlayer(currentSong);
    }

    private boolean isMidiSong(
            Stage3SongQueue.QueuedSong song) {

        if (song == null) {
            return false;
        }

        String type = song.type == null
                ? ""
                : song.type.toUpperCase(Locale.ROOT);

        String uri = song.uri == null
                ? ""
                : Uri.parse(song.uri).getLastPathSegment();

        String name = uri == null
                ? ""
                : uri.toLowerCase(Locale.ROOT);

        return type.contains("MIDI")
                || type.contains("KAR")
                || name.endsWith(".mid")
                || name.endsWith(".midi")
                || name.endsWith(".kar");
    }

    private String findSoundFontUri() {
        SharedPreferences preferences =
                getSharedPreferences(SONGBOOK_PREFS, MODE_PRIVATE);

        String saved = preferences.getString(SONGS_KEY, "[]");

        try {
            JSONArray songs = new JSONArray(saved);

            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.getJSONObject(i);

                String type = item.optString("type", "");
                String uri = item.optString("uri", "");

                if ("SOUNDFONT".equalsIgnoreCase(type)
                        && !uri.trim().isEmpty()) {
                    return uri;
                }

                String title = item.optString("title", "")
                        .toLowerCase(Locale.ROOT);

                if (!uri.trim().isEmpty()
                        && title.endsWith(".sf2")) {
                    return uri;
                }
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    private void initializePlayer(
            Stage3SongQueue.QueuedSong song) {

        releasePlayback();

        if (isMidiSong(song)) {
            initializeMidiPlayer(song);
        } else {
            initializeMediaPlayer(song);
        }
    }

    private void initializeMidiPlayer(
            Stage3SongQueue.QueuedSong song) {

        midiMode = true;
        midiReady = false;
        midiDurationTicks = 0L;

        seekBar.setProgress(0);
        timeLabel.setText("00:00 / 00:00");
        playPauseButton.setText("PLAY");
        playPauseButton.setEnabled(false);

        String soundFontUri = findSoundFontUri();

        if (soundFontUri.isEmpty()) {
            statusLabel.setText(
                    "Import a SoundFont (.sf2) in the songbook first."
            );

            Toast.makeText(
                    this,
                    "No SoundFont found. Import an .sf2 file, then try again.",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        statusLabel.setText("Loading MIDI/KAR and SoundFont...");

        midiEngine.play(
                song.uri,
                soundFontUri,
                new Stage3MidiEngine.Callback() {

                    @Override
                    public void onReady(long durationTicks) {
                        if (released || !midiMode) {
                            return;
                        }

                        midiReady = true;
                        midiDurationTicks = Math.max(0L, durationTicks);

                        playPauseButton.setEnabled(true);
                        playPauseButton.setText("PAUSE");

                        statusLabel.setText("MIDI playing");
                        updateMidiProgress();
                    }

                    @Override
                    public void onError(String message) {
                        if (released || !midiMode) {
                            return;
                        }

                        midiReady = false;
                        playPauseButton.setEnabled(false);
                        statusLabel.setText("MIDI playback failed");

                        Toast.makeText(
                                Stage3PlayerActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    }

                    @Override
                    public void onFinished() {
                        if (released || !midiMode) {
                            return;
                        }

                        statusLabel.setText("MIDI finished");
                        playNextSong();
                    }
                }
        );
    }

    private void initializeMediaPlayer(
            Stage3SongQueue.QueuedSong song) {

        midiMode = false;
        midiReady = false;

        try {
            player = new ExoPlayer.Builder(this).build();
            player.setVideoSurfaceView(videoSurface);

            player.addListener(new Player.Listener() {

                @Override
                public void onPlaybackStateChanged(int playbackState) {

                    if (player == null || midiMode) {
                        return;
                    }

                    if (playbackState == Player.STATE_BUFFERING) {
                        statusLabel.setText("Buffering...");

                    } else if (playbackState == Player.STATE_READY) {
                        statusLabel.setText(
                                player.isPlaying() ? "Playing" : "Ready"
                        );

                    } else if (playbackState == Player.STATE_ENDED) {
                        statusLabel.setText("Song finished");
                        playNextSong();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {

                    if (player == null || midiMode) {
                        return;
                    }

                    playPauseButton.setText(
                            isPlaying ? "PAUSE" : "PLAY"
                    );

                    statusLabel.setText(
                            isPlaying ? "Playing" : "Playback paused"
                    );
                }

                @Override
                public void onPlayerError(PlaybackException error) {

                    statusLabel.setText(
                            "Cannot play this file. Try another format."
                    );

                    Toast.makeText(
                            Stage3PlayerActivity.this,
                            "Playback error: "
                                    + error.getErrorCodeName(),
                            Toast.LENGTH_LONG
                    ).show();
                }
            });

            MediaItem mediaItem =
                    MediaItem.fromUri(Uri.parse(song.uri));

            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();

            playPauseButton.setEnabled(true);
            playPauseButton.setText("PAUSE");

        } catch (Exception e) {
            statusLabel.setText("Unable to start playback.");

            Toast.makeText(
                    this,
                    "Could not play this song.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void togglePlayback() {

        if (midiMode) {
            if (midiEngine == null || !midiReady) {
                return;
            }

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

        if (player == null) {
            return;
        }

        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void playNextSong() {
        Stage3SongQueue.QueuedSong next =
                Stage3SongQueue.playNext();

        if (next == null) {
            releasePlayback();

            statusLabel.setText("Queue finished.");
            titleLabel.setText("No more queued songs");
            playPauseButton.setEnabled(false);
            seekBar.setProgress(0);
            timeLabel.setText("00:00 / 00:00");
            return;
        }

        currentSong = next;

        titleLabel.setText(
                next.number + " • " + next.title
        );

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
            return;
        }

        if (player != null) {
            player.pause();
            player.seekTo(0);
        }

        statusLabel.setText("Stopped");
    }

    private void updateMidiProgress() {
        if (midiEngine == null || !midiReady) {
            return;
        }

        long position = midiEngine.getCurrentTick();

        if (midiDurationTicks > 0) {
            int progress = (int) Math.min(
                    1000L,
                    position * 1000L / midiDurationTicks
            );

            if (!userSeeking) {
                seekBar.setProgress(progress);
            }

            timeLabel.setText(
                    formatTicks(position) + " / "
                            + formatTicks(midiDurationTicks)
            );
        }

        playPauseButton.setText(
                midiEngine.isPlaying() ? "PAUSE" : "PLAY"
        );
    }

    private void updateMediaProgress() {
        if (player == null) {
            return;
        }

        long duration = player.getDuration();
        long position = player.getCurrentPosition();

        if (duration > 0) {
            int progress = (int) Math.min(
                    1000L,
                    position * 1000L / duration
            );

            if (!userSeeking) {
                seekBar.setProgress(progress);
            }

            timeLabel.setText(
                    formatTime(position) + " / " + formatTime(duration)
            );
        } else {
            if (!userSeeking) {
                seekBar.setProgress(0);
            }

            timeLabel.setText(formatTime(position) + " / 00:00");
        }

        playPauseButton.setText(
                player.isPlaying() ? "PAUSE" : "PLAY"
        );
    }

    private String formatTicks(long ticks) {
        // MIDI tick timing varies by tempo. Until the engine exposes
        // tempo-aware milliseconds, show the position as a tick counter.
        return String.format(Locale.US, "%,d ticks", Math.max(0L, ticks));
    }

    private String formatTime(long milliseconds) {
        milliseconds = Math.max(0L, milliseconds);

        long totalSeconds = milliseconds / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        return String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds
        );
    }

    private void releasePlayback() {
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
            try {
                midiEngine.stop();
            } catch (Exception ignored) {
            }
        }

        midiMode = false;
        midiReady = false;
        midiDurationTicks = 0L;

        if (videoSurface != null) {
            videoSurface.setVisibility(SurfaceView.VISIBLE);
        }

        handler.post(progressUpdater);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (midiMode && midiEngine != null && midiReady) {
            midiPausedByLifecycle = midiEngine.isPlaying();

            if (midiPausedByLifecycle) {
                midiEngine.pause();
            }
        } else if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        released = false;

        if (midiMode && midiEngine != null && midiReady
                && midiPausedByLifecycle) {
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
