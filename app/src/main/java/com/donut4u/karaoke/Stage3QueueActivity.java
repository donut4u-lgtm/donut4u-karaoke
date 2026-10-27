
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class Stage3QueueActivity extends Activity {

    private static final int BG = Color.rgb(9, 22, 17);
    private static final int PANEL = Color.rgb(20, 43, 32);
    private static final int GREEN = Color.rgb(43, 190, 112);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(185, 204, 192);

    private LinearLayout songList;
    private TextView nowPlayingText;
    private TextView countText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(BG);

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(GREEN);
        heading.setTextSize(23);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3  •  SONG QUEUE");
        subtitle.setTextColor(WHITE);
        subtitle.setTextSize(16);
        subtitle.setPadding(0, dp(5), 0, dp(15));
        root.addView(subtitle);

        LinearLayout nowCard = new LinearLayout(this);
        nowCard.setOrientation(LinearLayout.VERTICAL);
        nowCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        nowCard.setBackground(round(PANEL, 14));

        TextView nowLabel = new TextView(this);
        nowLabel.setText("NOW PLAYING");
        nowLabel.setTextColor(GREEN);
        nowLabel.setTextSize(12);
        nowLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        nowCard.addView(nowLabel);

        nowPlayingText = new TextView(this);
        nowPlayingText.setTextColor(WHITE);
        nowPlayingText.setTextSize(17);
        nowPlayingText.setPadding(0, dp(5), 0, 0);
        nowCard.addView(nowPlayingText);

        root.addView(nowCard, new LinearLayout.LayoutParams(
                -1, -2
        ));

        countText = new TextView(this);
        countText.setTextColor(MUTED);
        countText.setTextSize(14);
        countText.setPadding(0, dp(16), 0, dp(8));
        root.addView(countText);

        ScrollView scroll = new ScrollView(this);
        songList = new LinearLayout(this);
        songList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(songList);

        root.addView(scroll, new LinearLayout.LayoutParams(
                -1, 0, 1
        ));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(12), 0, 0);

        Button nextButton = makeButton("PLAY NEXT", GREEN, Color.BLACK);
        nextButton.setOnClickListener(v -> playNext());

        Button clearButton = makeButton(
                "CLEAR QUEUE", Color.rgb(100, 45, 45), WHITE
        );
        clearButton.setOnClickListener(v -> {
            Stage3SongQueue.clearQueue();
            refresh();
            Toast.makeText(
                    this, "Queue cleared.", Toast.LENGTH_SHORT
            ).show();
        });

        controls.addView(nextButton, new LinearLayout.LayoutParams(
                0, dp(48), 1
        ));

        LinearLayout.LayoutParams clearParams =
                new LinearLayout.LayoutParams(0, dp(48), 1);
        clearParams.setMargins(dp(8), 0, 0, 0);
        controls.addView(clearButton, clearParams);

        root.addView(controls);

        Button backButton = makeButton(
                "BACK TO SONGS", PANEL, WHITE
        );
        backButton.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(-1, dp(46));
        backParams.setMargins(0, dp(8), 0, 0);
        root.addView(backButton, backParams);

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (songList == null) {
            return;
        }

        Stage3SongQueue.QueuedSong current =
                Stage3SongQueue.getNowPlaying();

        if (current == null) {
            nowPlayingText.setText("Nothing selected yet");
        } else {
            nowPlayingText.setText(
                    current.number + "  •  " + current.title
            );
        }

        List<Stage3SongQueue.QueuedSong> songs =
                Stage3SongQueue.getQueue();

        countText.setText("UP NEXT  •  " + songs.size() + " song(s)");
        songList.removeAllViews();

        if (songs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(
                    "Your queue is empty.\nAdd songs from the Stage 3 songbook."
            );
            empty.setTextColor(MUTED);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(35), dp(12), dp(35));
            songList.addView(empty);
            return;
        }

        for (int i = 0; i < songs.size(); i++) {
            final int position = i;
            Stage3SongQueue.QueuedSong song = songs.get(i);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(11), dp(12), dp(11));
            card.setBackground(round(PANEL, 12));

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(-1, -2);
            cardParams.setMargins(0, 0, 0, dp(9));
            songList.addView(card, cardParams);

            TextView title = new TextView(this);
            title.setText(
                    (i + 1) + ".  " + song.number + "  •  " + song.title
            );
            title.setTextColor(WHITE);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(title);

            TextView type = new TextView(this);
            type.setText(song.type.isEmpty() ? "Song file" : song.type);
            type.setTextColor(MUTED);
            type.setTextSize(12);
            type.setPadding(0, dp(4), 0, dp(8));
            card.addView(type);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);

            Button up = makeButton("↑ UP", Color.rgb(38, 75, 54), WHITE);
            up.setEnabled(position > 0);
            up.setOnClickListener(v -> {
                if (Stage3SongQueue.move(position, position - 1)) {
                    refresh();
                }
            });

            Button down = makeButton(
                    "↓ DOWN", Color.rgb(38, 75, 54), WHITE
            );
            down.setEnabled(position < songs.size() - 1);
            down.setOnClickListener(v -> {
                if (Stage3SongQueue.move(position, position + 1)) {
                    refresh();
                }
            });

            Button remove = makeButton(
                    "REMOVE", Color.rgb(100, 45, 45), WHITE
            );
            remove.setOnClickListener(v -> {
                if (Stage3SongQueue.removeAt(position)) {
                    refresh();
                }
            });

            actions.addView(up, new LinearLayout.LayoutParams(
                    0, dp(42), 1
            ));

            LinearLayout.LayoutParams downParams =
                    new LinearLayout.LayoutParams(0, dp(42), 1);
            downParams.setMargins(dp(5), 0, 0, 0);
            actions.addView(down, downParams);

            LinearLayout.LayoutParams removeParams =
                    new LinearLayout.LayoutParams(0, dp(42), 1);
            removeParams.setMargins(dp(5), 0, 0, 0);
            actions.addView(remove, removeParams);

            card.addView(actions);
        }
    }

    private void playNext() {
        Stage3SongQueue.QueuedSong next =
                Stage3SongQueue.playNext();

        refresh();

        if (next == null) {
            Toast.makeText(
                    this, "No songs in the queue.", Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Toast.makeText(
                this,
                "Selected: " + next.title
                        + ". Player integration is the next step.",
                Toast.LENGTH_LONG
        ).show();
    }

    private Button makeButton(
            String label,
            int backgroundColor,
            int textColor
    ) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(round(backgroundColor, 10));
        return button;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }
}
