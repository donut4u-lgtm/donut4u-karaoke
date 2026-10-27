
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;

public class Stage3PlayerActivity extends Activity {

    private ExoPlayer player;
    private SurfaceView videoSurface;
    private TextView titleLabel;
    private TextView statusLabel;
    private TextView timeLabel;
    private SeekBar seekBar;
    private Button playPauseButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean userSeeking;
    private boolean released;
    private Stage3SongQueue.QueuedSong currentSong;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) {
                return;
            }

            long duration = player.getDuration();
            long position = player.getCurrentPosition();

            if (duration > 0) {
                seekBar.setMax((int) Math.min(duration, Integer.MAX_VALUE));

                if (!userSeeking) {
                    seekBar.setProgress(
                            (int) Math.min(position, Integer.MAX_VALUE));
                }

                timeLabel.setText(
                        formatTime(position) + " / " + formatTime(duration));
            } else {
                seekBar.setProgress(0);
                timeLabel.setText(formatTime(position) + " / LIVE");
            }

            playPauseButton.setText(
                    player.isPlaying() ? "PAUSE" : "PLAY");

            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Stage3SongQueue.initialize(this);
        buildScreen();
        startSelectedSong();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
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
        root.addView(heading,
                new LinearLayout.LayoutParams(-1, dp(44)));

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
        root.addView(seekBar,
                new LinearLayout.LayoutParams(-1, dp(40)));

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
                            SeekBar bar, int progress, boolean fromUser) {
                        if (fromUser && player != null) {
                            long duration = player.getDuration();

                            if (duration > 0) {
                                long target = (long) (
                                        duration * (progress / 1000.0));
                                timeLabel.setText(
                                        formatTime(target) + " / " +
                                        formatTime(duration));
                            }
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        userSeeking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        if (player != null && player.getDuration() > 0) {
                            long target = (long) (
                                    player.getDuration()
                                            * (bar.getProgress() / 1000.0));
                            player.seekTo(target);
                        }
                        userSeeking = false;
                    }
                });

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        playPauseButton = makeButton("PAUSE");
        playPauseButton.setOnClickListener(v -> togglePlayback());

        Button nextButton = makeButton("NEXT SONG");
        nextButton.setOnClickListener(v -> playNextSong());

        Button stopButton = makeButton("STOP");
        stopButton.setOnClickListener(v -> stopPlayback());

        addControl(controls, playPauseButton, 1f);
        addControl(controls, nextButton, 1f);
        addControl(controls, stopButton, 0.8f);

        root.addView(controls,
                new LinearLayout.LayoutParams(-1, dp(50)));

        Button backButton = makeButton("BACK TO QUEUE");
        backButton.setOnClickListener(v -> finish());
        root.addView(backButton,
                new LinearLayout.LayoutParams(-1, dp(46)));

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

    private void startSelectedSong() {
        currentSong = Stage3SongQueue.getNowPlaying();

        if (currentSong == null) {
            currentSong = Stage3SongQueue.playNext();
        }

        if (currentSong == null) {
            titleLabel.setText("No song selected");
            statusLabel.setText(
                    "Return to the songbook and add songs to the queue.");
            playPauseButton.setEnabled(false);
            return;
        }

        titleLabel.setText(
                currentSong.number + " • " + currentSong.title);

        initializePlayer(currentSong);
    }

    private void initializePlayer(
            Stage3SongQueue.QueuedSong song) {
        releasePlayer();

        try {
            player = new ExoPlayer.Builder(this).build();
            player.setVideoSurfaceView(videoSurface);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        statusLabel.setText("Buffering...");
                    } else if (playbackState == Player.STATE_READY) {
                        statusLabel.setText(
                                player != null && player.isPlaying()
                                        ? "Playing"
                                        : "Ready");
                    } else if (playbackState == Player.STATE_ENDED) {
                        statusLabel.setText("Song finished");
                        playNextSong();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (player != null) {
                        playPauseButton.setText(
                                isPlaying ? "PAUSE" : "PLAY");
                    }

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

            MediaItem mediaItem = MediaItem.fromUri(
                    Uri.parse(song.uri));

            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();

            playPauseButton.setEnabled(true);
            handler.removeCallbacks(progressUpdater);
            handler.post(progressUpdater);

        } catch (Exception e) {
            statusLabel.setText("Unable to start playback.");
            Toast.makeText(this,
                    "Could not play this song.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void togglePlayback() {
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
            if (player != null) {
                player.pause();
            }

            statusLabel.setText("Queue finished.");
            titleLabel.setText("No more queued songs");
            playPauseButton.setEnabled(false);
            return;
        }

        currentSong = next;
        titleLabel.setText(next.number + " • " + next.title);
        initializePlayer(next);
    }

    private void stopPlayback() {
        if (player != null) {
            player.pause();
            player.seekTo(0);
        }

        statusLabel.setText("Stopped");
    }

    private String formatTime(long milliseconds) {
        if (milliseconds < 0) {
            milliseconds = 0;
        }

        long totalSeconds = milliseconds / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        return String.format(
                Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void releasePlayer() {
        if (player != null) {
            player.clearVideoSurfaceView(videoSurface);
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(progressUpdater);

        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        released = true;
        handler.removeCallbacks(progressUpdater);
        releasePlayer();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        released = false;

        if (player != null) {
            handler.removeCallbacks(progressUpdater);
            handler.post(progressUpdater);
        }
    }
}
