package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private final int GREEN = Color.rgb(20, 145, 75);
    private final int DARK = Color.rgb(22, 22, 22);
    private final int WHITE = Color.WHITE;
    private final int YELLOW = Color.rgb(255, 220, 50);

    private EditText songBox;
    private EditText audioUrlBox;
    private TextView nowPlaying;
    private TextView queueStatus;
    private TextView timeLabel;
    private SeekBar seekBar;
    private LinearLayout lyricsContainer;
    private ScrollView lyricsScroll;

    private MediaPlayer mediaPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ArrayList<LyricLine> lyrics = new ArrayList<>();
    private final ArrayList<TextView> lyricViews = new ArrayList<>();
    private final ArrayList<QueueItem> queue = new ArrayList<>();

    private int activeLyric = -1;
    private int lyricRequestId = 0;
    private boolean userSeeking = false;
    private String currentTitle = "";

    private static class LyricLine {
        final long timeMs;
        final String text;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static class QueueItem {
        final String title;
        final String url;

        QueueItem(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null) {
                try {
                    int position = mediaPlayer.getCurrentPosition();
                    int duration = mediaPlayer.getDuration();

                    if (!userSeeking && duration > 0) {
                        seekBar.setMax(duration);
                        seekBar.setProgress(position);
                    }

                    timeLabel.setText(
                            formatTime(position) + " / " +
                            formatTime(duration)
                    );

                    updateLyrics(position);

                } catch (IllegalStateException ignored) {
                }
            }

            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildLayout();
        handler.post(progressRunnable);

        showLyricsMessage(
                "Search for lyrics, then load an authorized audio URL."
        );
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(GREEN);
        heading.setTextSize(24);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(8), 0, dp(10));

        root.addView(heading);

        songBox = new EditText(this);
        songBox.setSingleLine(true);
        songBox.setHint("Song title and artist");
        songBox.setTextColor(WHITE);
        songBox.setHintTextColor(Color.LTGRAY);

        root.addView(songBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout searchRow = new LinearLayout(this);

        Button lyricsButton = makeButton("FIND LYRICS");
        lyricsButton.setOnClickListener(v -> {
            String query = songBox.getText().toString().trim();

            if (query.isEmpty()) {
                toast("Enter the song title and artist.");
            } else {
                hideKeyboard();
                searchTimedLyrics(query);
            }
        });

        searchRow.addView(lyricsButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));

        Button youtubeButton = makeButton("YOUTUBE SEARCH");
        youtubeButton.setOnClickListener(v -> openYouTubeSearch());

        searchRow.addView(youtubeButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));

        root.addView(searchRow);

        TextView urlLabel = new TextView(this);
        urlLabel.setText("AUTHORIZED AUDIO URL");
        urlLabel.setTextColor(YELLOW);
        urlLabel.setTypeface(null, Typeface.BOLD);
        urlLabel.setPadding(0, dp(8), 0, dp(3));

        root.addView(urlLabel);

        audioUrlBox = new EditText(this);
        audioUrlBox.setSingleLine(true);
        audioUrlBox.setHint("Direct MP3 / WAV / AAC URL");
        audioUrlBox.setTextColor(WHITE);
        audioUrlBox.setHintTextColor(Color.LTGRAY);
        audioUrlBox.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        );

        root.addView(audioUrlBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button loadButton = makeButton("LOAD AND PLAY AUDIO");
        loadButton.setOnClickListener(v -> loadAudioFromBox());

        root.addView(loadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        nowPlaying = new TextView(this);
        nowPlaying.setText("Nothing playing");
        nowPlaying.setTextColor(WHITE);
        nowPlaying.setPadding(0, dp(5), 0, dp(2));

        root.addView(nowPlaying);

        seekBar = new SeekBar(this);
        root.addView(seekBar);

        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        userSeeking = true;
                    }

                    @Override
                    public void onProgressChanged(
                            SeekBar bar, int progress, boolean fromUser) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        if (mediaPlayer != null) {
                            try {
                                mediaPlayer.seekTo(bar.getProgress());
                            } catch (IllegalStateException ignored) {
                            }
                        }

                        userSeeking = false;
                    }
                }
        );

        timeLabel = new TextView(this);
        timeLabel.setText("00:00 / 00:00");
        timeLabel.setTextColor(Color.LTGRAY);
        timeLabel.setGravity(Gravity.CENTER);

        root.addView(timeLabel);

        LinearLayout controls = new LinearLayout(this);

        Button playPause = makeButton("PLAY / PAUSE");
        playPause.setOnClickListener(v -> togglePlayback());
        controls.addView(playPause,
                new LinearLayout.LayoutParams(0, dp(46), 1));

        Button restart = makeButton("RESTART");
        restart.setOnClickListener(v -> restartPlayback());
        controls.addView(restart,
                new LinearLayout.LayoutParams(0, dp(46), 1));

        Button addQueue = makeButton("ADD QUEUE");
        addQueue.setOnClickListener(v -> addToQueue());
        controls.addView(addQueue,
                new LinearLayout.LayoutParams(0, dp(46), 1));

        Button next = makeButton("NEXT");
        next.setOnClickListener(v -> playNext());
        controls.addView(next,
                new LinearLayout.LayoutParams(0, dp(46), 1));

        root.addView(controls);

        queueStatus = new TextView(this);
        queueStatus.setText("Queue is empty");
        queueStatus.setTextColor(Color.LTGRAY);
        queueStatus.setTextSize(12);

        root.addView(queueStatus);

        TextView lyricHeading = new TextView(this);
        lyricHeading.setText("SYNCHRONIZED LYRICS");
        lyricHeading.setTextColor(YELLOW);
        lyricHeading.setTextSize(16);
        lyricHeading.setTypeface(null, Typeface.BOLD);
        lyricHeading.setGravity(Gravity.CENTER);
        lyricHeading.setPadding(0, dp(7), 0, dp(5));

        root.addView(lyricHeading);

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setBackgroundColor(Color.rgb(12, 12, 12));

        lyricsContainer = new LinearLayout(this);
        lyricsContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsContainer.setPadding(dp(8), dp(8), dp(8), dp(8));

        lyricsScroll.addView(lyricsContainer);

        root.addView(lyricsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10);
        button.setTextColor(WHITE);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private void openYouTubeSearch() {
        String query = songBox.getText().toString().trim();

        if (query.isEmpty()) {
            toast("Enter a song title first.");
            return;
        }

        String lower = query.toLowerCase(Locale.ROOT);

        if (!lower.contains("karaoke") &&
                !lower.contains("instrumental") &&
                !lower.contains("minus one") &&
                !lower.contains("sing along")) {
            query += " karaoke";
        }

        Intent intent = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                        "https://m.youtube.com/results?search_query=" +
                        Uri.encode(query)
                )
        );

        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("Could not open YouTube.");
        }
    }

    private void loadAudioFromBox() {
        String url = audioUrlBox.getText().toString().trim();

        if (!url.startsWith("https://") &&
                !url.startsWith("http://")) {
            toast("Enter a valid authorized audio URL.");
            return;
        }

        String title = songBox.getText().toString().trim();

        if (title.isEmpty()) {
            title = "Karaoke audio";
        }

        playAudio(new QueueItem(title, url));
    }

    private void playAudio(QueueItem item) {
        releasePlayer();

        currentTitle = item.title;
        nowPlaying.setText("Loading: " + currentTitle);

        mediaPlayer = new MediaPlayer();

        try {
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
            );

            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();

                    nowPlaying.setText(
                            "NOW PLAYING: " + currentTitle
                    );

                    seekBar.setMax(Math.max(1, mp.getDuration()));

                    String query = songBox.getText().toString().trim();

                    if (!query.isEmpty()) {
                        searchTimedLyrics(query);
                    }

                } catch (IllegalStateException e) {
                    toast("Audio could not start.");
                }
            });

            mediaPlayer.setOnCompletionListener(mp -> playNext());

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                nowPlaying.setText("Audio playback failed.");
                toast("Check the audio URL and file format.");
                releasePlayer();
                return true;
            });

            mediaPlayer.setDataSource(item.url);
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            releasePlayer();
            nowPlaying.setText("Audio load failed");
            toast("Could not load the audio URL.");
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null) {
            toast("Load an audio URL first.");
            return;
        }

        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
            }
        } catch (IllegalStateException e) {
            toast("Audio is not ready yet.");
        }
    }

    private void restartPlayback() {
        if (mediaPlayer == null) {
            toast("Load audio first.");
            return;
        }

        try {
            mediaPlayer.seekTo(0);
            mediaPlayer.start();
        } catch (IllegalStateException e) {
            toast("Audio is not ready yet.");
        }
    }

    private void addToQueue() {
        String url = audioUrlBox.getText().toString().trim();

        if (!url.startsWith("https://") &&
                !url.startsWith("http://")) {
            toast("Enter an authorized audio URL first.");
            return;
        }

        String title = songBox.getText().toString().trim();

        if (title.isEmpty()) {
            title = "Karaoke audio";
        }

        queue.add(new QueueItem(title, url));
        updateQueueStatus();
        toast("Added to queue.");
    }

    private void playNext() {
        if (queue.isEmpty()) {
            updateQueueStatus();
            return;
        }

        QueueItem next = queue.remove(0);
        updateQueueStatus();

        songBox.setText(next.title);
        audioUrlBox.setText(next.url);

        playAudio(next);
    }

    private void updateQueueStatus() {
        if (queueStatus == null) {
            return;
        }

        if (queue.isEmpty()) {
            queueStatus.setText("Queue is empty");
            return;
        }

        StringBuilder text = new StringBuilder(
                "UP NEXT (" + queue.size() + "): "
        );

        int count = Math.min(3, queue.size());

        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(" • ");
            }

            text.append(queue.get(i).title);
        }

        if (queue.size() > count) {
            text.append(" • …");
        }

        queueStatus.setText(text.toString());
    }

    private void searchTimedLyrics(String query) {
        final int requestId = ++lyricRequestId;

        showLyricsMessage("Searching for synchronized lyrics...");

        new Thread(() -> {
            ArrayList<LyricLine> found = new ArrayList<>();
            HttpURLConnection connection = null;

            try {
                URL url = new URL(
                        "https://lrclib.net/api/search?q=" +
                        URLEncoder.encode(query, "UTF-8")
                );

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty(
                        "User-Agent", "Donut4U-Karaoke/1.0"
                );

                if (connection.getResponseCode() ==
                        HttpURLConnection.HTTP_OK) {

                    InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out =
                            new ByteArrayOutputStream();

                    byte[] buffer = new byte[4096];
                    int n;

                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }

                    in.close();

                    JSONArray results = new JSONArray(
                            new String(
                                    out.toByteArray(),
                                    StandardCharsets.UTF_8
                            )
                    );

                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        String synced = item.optString(
                                "syncedLyrics", ""
                        );

                        if (!synced.trim().isEmpty()) {
                            found = parseLrc(synced);

                            if (!found.isEmpty()) {
                                break;
                            }
                        }
                    }
                }

            } catch (Exception ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final ArrayList<LyricLine> result = found;

            runOnUiThread(() -> {
                if (requestId != lyricRequestId) {
                    return;
                }

                lyrics.clear();
                lyrics.addAll(result);

                if (lyrics.isEmpty()) {
                    showLyricsMessage(
                            "No synchronized lyrics found. " +
                            "Try song title and artist."
                    );
                } else {
                    displayLyrics();
                }
            });

        }).start();
    }

    private ArrayList<LyricLine> parseLrc(String text) {
        ArrayList<LyricLine> parsed = new ArrayList<>();

        Pattern pattern = Pattern.compile(
                "\\\\[(\\\\d{1,2}):(\\\\d{2})(?:[.:](\\\\d{1,3}))?\\\\](.*)"
        );

        for (String line : text.split("\\\\r?\\\\n")) {
            Matcher m = pattern.matcher(line);

            if (!m.matches()) {
                continue;
            }

            try {
                long minutes = Long.parseLong(m.group(1));
                long seconds = Long.parseLong(m.group(2));
                String fraction = m.group(3);
                long milliseconds = 0;

                if (fraction != null) {
                    if (fraction.length() == 1) {
                        milliseconds =
                                Long.parseLong(fraction) * 100;
                    } else if (fraction.length() == 2) {
                        milliseconds =
                                Long.parseLong(fraction) * 10;
                    } else {
                        milliseconds = Long.parseLong(fraction);
                    }
                }

                String lyric = m.group(4).trim();

                if (!lyric.isEmpty()) {
                    parsed.add(new LyricLine(
                            minutes * 60000 +
                            seconds * 1000 +
                            milliseconds,
                            lyric
                    ));
                }

            } catch (Exception ignored) {
            }
        }

        Collections.sort(
                parsed,
                Comparator.comparingLong(line -> line.timeMs)
        );

        return parsed;
    }

    private void displayLyrics() {
        lyricsContainer.removeAllViews();
        lyricViews.clear();
        activeLyric = -1;

        if (lyrics.isEmpty()) {
            showLyricsMessage(
                    "Timed lyrics will appear here when available."
            );
            return;
        }

        for (LyricLine lyric : lyrics) {
            TextView line = new TextView(this);
            line.setText(lyric.text);
            line.setTextColor(Color.LTGRAY);
            line.setTextSize(18);
            line.setGravity(Gravity.CENTER);
            line.setPadding(dp(5), dp(10), dp(5), dp(10));

            lyricsContainer.addView(
                    line,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            );

            lyricViews.add(line);
        }
    }

    private void showLyricsMessage(String message) {
        if (lyricsContainer == null) {
            return;
        }

        lyricsContainer.removeAllViews();
        lyricViews.clear();
        activeLyric = -1;

        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(WHITE);
        text.setTextSize(15);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(12), dp(20), dp(12), dp(20));

        lyricsContainer.addView(
                text,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );
    }

    private void updateLyrics(long currentMs) {
        if (lyrics.isEmpty() || lyricViews.isEmpty()) {
            return;
        }

        int found = -1;

        for (int i = 0; i < lyrics.size(); i++) {
            if (currentMs >= lyrics.get(i).timeMs) {
                found = i;
            } else {
                break;
            }
        }

        if (found == activeLyric) {
            return;
        }

        activeLyric = found;

        for (int i = 0; i < lyricViews.size(); i++) {
            TextView line = lyricViews.get(i);

            if (i == activeLyric) {
                line.setTextColor(YELLOW);
                line.setTextSize(23);
                line.setTypeface(null, Typeface.BOLD);
            } else {
                line.setTextColor(Color.LTGRAY);
                line.setTextSize(18);
                line.setTypeface(null, Typeface.NORMAL);
            }
        }

        if (activeLyric >= 0) {
            View active = lyricViews.get(activeLyric);

            lyricsScroll.post(() -> lyricsScroll.smoothScrollTo(
                    0,
                    Math.max(
                            0,
                            active.getTop() -
                            lyricsScroll.getHeight() / 2 +
                            active.getHeight() / 2
                    )
            ));
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE
                    );

            if (imm != null && songBox != null) {
                imm.hideSoftInputFromWindow(
                        songBox.getWindowToken(), 0
                );
            }
        } catch (Exception ignored) {
        }
    }

    private String formatTime(long millis) {
        if (millis < 0) {
            millis = 0;
        }

        long seconds = millis / 1000;

        return String.format(
                Locale.ROOT,
                "%02d:%02d",
                seconds / 60,
                seconds % 60
        );
    }

    private int dp(int value) {
        return (int) (
                value *
                getResources().getDisplayMetrics().density + 0.5f
        );
    }

    private void toast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setOnPreparedListener(null);
                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnErrorListener(null);
                mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        lyricRequestId++;
        handler.removeCallbacks(progressRunnable);
        releasePlayer();
        super.onDestroy();
    }
}
