
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private TextView lyricView;
    private TextView timeView;
    private SeekBar seekBar;
    private LinearLayout songCard;
    private boolean isSeeking = false;

    private static final int GREEN = Color.rgb(24, 135, 76);
    private static final int DARK = Color.rgb(24, 32, 28);
    private static final int GREY = Color.rgb(105, 115, 109);

    private static final String[] WORDS = {
        "Sa", "bawat", "araw,", "may", "bagong", "simula.",
        "Awit", "ng", "puso,", "sabay", "sa", "kanta.",
        "Hawak", "ang", "pangarap,", "tuloy", "lang", "tayo.",
        "Sa", "bawat", "himig,", "liwanag", "ang", "taglay.",
        "Sabay", "nating", "awitin,", "hanggang", "dulo", "ng", "araw."
    };

    private static final float[] WORD_START = {
        0.0f, 0.45f, 0.95f, 1.8f, 2.2f, 2.85f,
        4.2f, 4.75f, 5.0f, 6.0f, 6.6f, 6.85f,
        8.2f, 8.8f, 9.05f, 10.3f, 10.8f, 11.2f,
        12.6f, 13.0f, 13.5f, 14.5f, 15.2f, 15.5f,
        16.9f, 17.5f, 18.1f, 19.2f, 19.9f, 20.4f, 20.6f
    };

    private static final float[] WORD_END = {
        0.45f, 0.95f, 1.6f, 2.2f, 2.85f, 4.0f,
        4.75f, 5.0f, 5.8f, 6.6f, 6.85f, 8.0f,
        8.8f, 9.05f, 10.1f, 10.8f, 11.2f, 12.4f,
        13.0f, 13.5f, 14.3f, 15.2f, 15.5f, 16.7f,
        17.5f, 18.1f, 19.0f, 19.9f, 20.4f, 20.6f, 22.0f
    };

    private static final int[] WORD_LINE = {
        0,0,0,0,0,0,
        1,1,1,1,1,1,
        2,2,2,2,2,2,
        3,3,3,3,3,3,
        4,4,4,4,4,4,4
    };

    private static final String[] LINES = {
        "Sa bawat araw, may bagong simula.",
        "Awit ng puso, sabay sa kanta.",
        "Hawak ang pangarap, tuloy lang tayo.",
        "Sa bawat himig, liwanag ang taglay.",
        "Sabay nating awitin, hanggang dulo ng araw."
    };

    private final Runnable updateTask = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                try {
                    int position = player.getCurrentPosition();

                    if (!isSeeking) {
                        seekBar.setProgress(position);
                    }

                    timeView.setText(
                        formatTime(position) + " / " +
                        formatTime(player.getDuration())
                    );

                    updateLyrics(position / 1000.0f);

                } catch (IllegalStateException ignored) {
                }
            }

            handler.postDelayed(this, 60);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildScreen();

        handler.post(updateTask);
    }

    private void buildScreen() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackgroundColor(Color.rgb(246, 249, 247));

        TextView appTitle = new TextView(this);
        appTitle.setText("donut4u karaoke");
        appTitle.setTextSize(28);
        appTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appTitle.setTextColor(GREEN);
        appTitle.setGravity(Gravity.CENTER);

        root.addView(appTitle, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Sing along with moving lyrics");
        subtitle.setTextSize(15);
        subtitle.setTextColor(GREY);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(18));

        root.addView(subtitle, matchWrap());

        EditText searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(16);
        searchBox.setHint("Search song or artist");
        searchBox.setPadding(dp(14), dp(10), dp(14), dp(10));

        root.addView(searchBox, matchWrap());

        songCard = new LinearLayout(this);
        songCard.setOrientation(LinearLayout.VERTICAL);
        songCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        songCard.setBackgroundColor(Color.WHITE);

        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(14);

        root.addView(songCard, cardParams);

        TextView songTitle = new TextView(this);
        songTitle.setText("Sa Bawat Araw");
        songTitle.setTextSize(20);
        songTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        songTitle.setTextColor(DARK);

        songCard.addView(songTitle, matchWrap());

        TextView songInfo = new TextView(this);
        songInfo.setText("Original demo song • 32 seconds");
        songInfo.setTextSize(13);
        songInfo.setTextColor(GREY);
        songInfo.setPadding(0, dp(4), 0, dp(10));

        songCard.addView(songInfo, matchWrap());

        Button playButton = new Button(this);
        playButton.setText("PLAY DEMO SONG");
        playButton.setTextColor(Color.WHITE);
        playButton.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(GREEN)
        );

        songCard.addView(playButton, matchWrap());

        playButton.setOnClickListener(v -> togglePlayback());

        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(
                CharSequence s, int start, int count, int after
            ) {}

            @Override
            public void onTextChanged(
                CharSequence s, int start, int before, int count
            ) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);

                boolean found =
                    query.isEmpty()
                    || "sa bawat araw".contains(query)
                    || "original demo song".contains(query)
                    || "donut4u".contains(query);

                songCard.setVisibility(
                    found ? View.VISIBLE : View.GONE
                );
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        TextView lyricsLabel = new TextView(this);
        lyricsLabel.setText("KARAOKE LYRICS");
        lyricsLabel.setTextSize(13);
        lyricsLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lyricsLabel.setTextColor(GREEN);
        lyricsLabel.setPadding(0, dp(24), 0, dp(8));

        root.addView(lyricsLabel, matchWrap());

        lyricView = new TextView(this);
        lyricView.setText("Press PLAY to start singing");
        lyricView.setTextSize(25);
        lyricView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lyricView.setTextColor(DARK);
        lyricView.setGravity(Gravity.CENTER);
        lyricView.setMinHeight(dp(125));
        lyricView.setPadding(dp(10), dp(12), dp(10), dp(12));
        lyricView.setBackgroundColor(Color.WHITE);

        root.addView(lyricView, matchWrap());

        seekBar = new SeekBar(this);
        seekBar.setMax(32000);

        root.addView(seekBar, matchWrap());

        seekBar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {

                @Override
                public void onProgressChanged(
                    SeekBar bar, int progress, boolean fromUser
                ) {
                    if (fromUser && player != null) {
                        updateLyrics(progress / 1000.0f);

                        timeView.setText(
                            formatTime(progress) + " / " +
                            formatTime(player.getDuration())
                        );
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    isSeeking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    if (player != null) {
                        player.seekTo(bar.getProgress());
                    }

                    isSeeking = false;
                }
            }
        );

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        root.addView(controls, matchWrap());

        Button restartButton = new Button(this);
        restartButton.setText("RESTART");

        controls.addView(
            restartButton,
            new LinearLayout.LayoutParams(0, dp(52), 1)
        );

        restartButton.setOnClickListener(v -> {
            if (player == null) {
                startSong();
            } else {
                player.seekTo(0);
                player.start();
            }
        });

        Button pauseButton = new Button(this);
        pauseButton.setText("PLAY / PAUSE");

        controls.addView(
            pauseButton,
            new LinearLayout.LayoutParams(0, dp(52), 1)
        );

        pauseButton.setOnClickListener(v -> togglePlayback());

        timeView = new TextView(this);
        timeView.setText("00:00 / 00:32");
        timeView.setTextSize(13);
        timeView.setTextColor(GREY);
        timeView.setGravity(Gravity.CENTER);
        timeView.setPadding(0, dp(4), 0, dp(10));

        root.addView(timeView, matchWrap());

        TextView note = new TextView(this);
        note.setText(
            "Demo only: original synthesized melody. " +
            "More songs need their own licensed music and timed lyrics."
        );
        note.setTextSize(12);
        note.setTextColor(GREY);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(4), dp(8), dp(4), 0);

        root.addView(note, matchWrap());

        setContentView(root);
    }

    private void startSong() {
        try {
            player = MediaPlayer.create(
                this,
                R.raw.donut4u_demo_song
            );

            if (player == null) {
                Toast.makeText(
                    this,
                    "Could not load demo song.",
                    Toast.LENGTH_LONG
                ).show();
                return;
            }

            player.setOnCompletionListener(mp -> {
                updateLyrics(0);
                lyricView.setText("Song finished! Press PLAY to sing again.");
            });

            seekBar.setMax(player.getDuration());
            player.start();

        } catch (Exception e) {
            Toast.makeText(
                this,
                "Playback error: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void togglePlayback() {
        try {
            if (player == null) {
                startSong();
                return;
            }

            if (player.isPlaying()) {
                player.pause();
            } else {
                if (player.getCurrentPosition() >= player.getDuration() - 100) {
                    player.seekTo(0);
                }

                player.start();
            }

        } catch (Exception e) {
            Toast.makeText(
                this,
                "Playback error: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateLyrics(float seconds) {

        int active = -1;

        for (int i = 0; i < WORDS.length; i++) {
            if (seconds >= WORD_START[i] && seconds < WORD_END[i]) {
                active = i;
                break;
            }
        }

        if (active < 0) {
            if (seconds < WORD_START[0]) {
                lyricView.setText("Press PLAY to start singing");
            } else if (seconds >= WORD_END[WORDS.length - 1]) {
                lyricView.setText("♪ Sing it again! ♪");
            }

            return;
        }

        int lineIndex = WORD_LINE[active];
        String line = LINES[lineIndex];

        SpannableString styled = new SpannableString(line);

        int charStart = 0;
        int activeStart = -1;
        int activeEnd = -1;

        for (int i = 0; i < WORDS.length; i++) {

            if (WORD_LINE[i] != lineIndex) {
                continue;
            }

            String word = WORDS[i];
            int found = line.indexOf(word, charStart);

            if (found < 0) {
                found = line.indexOf(word);
            }

            if (found >= 0) {

                if (i == active) {
                    activeStart = found;
                    activeEnd = found + word.length();
                }

                charStart = found + word.length();
            }
        }

        if (activeStart >= 0 && activeEnd <= styled.length()) {

            styled.setSpan(
                new ForegroundColorSpan(GREEN),
                activeStart,
                activeEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            styled.setSpan(
                new StyleSpan(Typeface.BOLD),
                activeStart,
                activeEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        lyricView.setText(styled);
    }

    private String formatTime(int millis) {
        int seconds = Math.max(0, millis / 1000);

        return String.format(
            Locale.getDefault(),
            "%02d:%02d",
            seconds / 60,
            seconds % 60
        );
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (
            value * getResources().getDisplayMetrics().density + 0.5f
        );
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(updateTask);

        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (Exception ignored) {
            }

            player.release();
            player = null;
        }

        super.onDestroy();
    }
}
