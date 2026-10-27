
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class Stage3QueueActivity extends Activity {

    private LinearLayout queueList;
    private TextView nowPlayingLabel;
    private TextView countLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Stage3SongQueue.initialize(this);
        buildScreen();
        refreshQueue();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(10, 20, 15));

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(Color.rgb(80, 255, 150));
        heading.setTextSize(24);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading,
                new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • PLAY QUEUE");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        LinearLayout nowCard = new LinearLayout(this);
        nowCard.setOrientation(LinearLayout.VERTICAL);
        nowCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        nowCard.setBackgroundColor(Color.rgb(25, 45, 32));

        TextView nowTitle = new TextView(this);
        nowTitle.setText("NOW SELECTED");
        nowTitle.setTextColor(Color.rgb(80, 255, 150));
        nowTitle.setTextSize(13);
        nowTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        nowCard.addView(nowTitle);

        nowPlayingLabel = new TextView(this);
        nowPlayingLabel.setTextColor(Color.WHITE);
        nowPlayingLabel.setTextSize(16);
        nowPlayingLabel.setPadding(0, dp(6), 0, 0);
        nowCard.addView(nowPlayingLabel);

        root.addView(nowCard,
                new LinearLayout.LayoutParams(-1, -2));

        countLabel = new TextView(this);
        countLabel.setTextColor(Color.rgb(140, 255, 180));
        countLabel.setTextSize(14);
        countLabel.setPadding(0, dp(12), 0, dp(8));
        root.addView(countLabel);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        queueList = new LinearLayout(this);
        queueList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(queueList);

        root.addView(scroll,
                new LinearLayout.LayoutParams(-1, 0, 1f));

        Button nextButton = makeButton("PLAY NEXT SONG");
        nextButton.setOnClickListener(v -> playNextSong());
        root.addView(nextButton, buttonParams());

        Button clearButton = makeButton("CLEAR QUEUE");
        clearButton.setOnClickListener(v -> confirmClearQueue());
        root.addView(clearButton, buttonParams());

        Button backButton = makeButton("BACK TO SONGBOOK");
        backButton.setOnClickListener(v -> finish());
        root.addView(backButton, buttonParams());

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(20, 125, 65)));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(46));
        params.bottomMargin = dp(7);
        return params;
    }

    private void refreshQueue() {
        if (queueList == null) {
            return;
        }

        queueList.removeAllViews();

        Stage3SongQueue.QueuedSong current =
                Stage3SongQueue.getNowPlaying();

        if (current == null) {
            nowPlayingLabel.setText("No song selected");
        } else {
            nowPlayingLabel.setText(
                    current.number + " • " + current.title +
                    "\nType: " + current.type);
        }

        List<Stage3SongQueue.QueuedSong> songs =
                Stage3SongQueue.getQueue();

        countLabel.setText("QUEUED SONGS: " + songs.size());

        if (songs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(
                    "Your queue is empty.\n\n" +
                    "Return to the songbook and tap ADD TO QUEUE.");
            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            queueList.addView(empty);
            return;
        }

        for (int i = 0; i < songs.size(); i++) {
            final int position = i;
            Stage3SongQueue.QueuedSong song = songs.get(i);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackgroundColor(Color.rgb(25, 39, 30));

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(8);
            card.setLayoutParams(cardParams);

            TextView songTitle = new TextView(this);
            songTitle.setText(
                    (i + 1) + ". " + song.number + " • " + song.title);
            songTitle.setTextColor(Color.WHITE);
            songTitle.setTextSize(15);
            songTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(songTitle);

            TextView songType = new TextView(this);
            songType.setText("Type: " + song.type);
            songType.setTextColor(Color.rgb(140, 255, 180));
            songType.setTextSize(12);
            songType.setPadding(0, dp(4), 0, dp(8));
            card.addView(songType);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);

            Button selectButton = makeButton("PLAY");
            selectButton.setOnClickListener(v -> {
                Stage3SongQueue.setNowPlaying(song);
                openPlayer();
            });

            Button upButton = makeButton("UP");
            upButton.setEnabled(position > 0);
            upButton.setOnClickListener(v -> {
                Stage3SongQueue.move(position, position - 1);
                refreshQueue();
            });

            Button downButton = makeButton("DOWN");
            downButton.setEnabled(position < songs.size() - 1);
            downButton.setOnClickListener(v -> {
                Stage3SongQueue.move(position, position + 1);
                refreshQueue();
            });

            Button removeButton = makeButton("REMOVE");
            removeButton.setOnClickListener(v -> {
                Stage3SongQueue.removeAt(position);
                refreshQueue();
                Toast.makeText(this,
                        "Song removed.",
                        Toast.LENGTH_SHORT).show();
            });

            addAction(actions, selectButton, 1f);
            addAction(actions, upButton, 0.7f);
            addAction(actions, downButton, 0.8f);
            addAction(actions, removeButton, 1f);

            card.addView(actions);
            queueList.addView(card);
        }
    }

    private void playNextSong() {
        Stage3SongQueue.QueuedSong next =
                Stage3SongQueue.playNext();

        if (next == null) {
            Toast.makeText(this,
                    "The queue is empty.",
                    Toast.LENGTH_SHORT).show();
            refreshQueue();
            return;
        }

        openPlayer();
    }

    private void openPlayer() {
        try {
            startActivity(new Intent(
                    Stage3QueueActivity.this,
                    Stage3PlayerActivity.class));
        } catch (Exception e) {
            Toast.makeText(this,
                    "Could not open the player.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addAction(
            LinearLayout parent, Button button, float weight) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(42), weight);
        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, params);
    }

    private void confirmClearQueue() {
        new AlertDialog.Builder(this)
                .setTitle("Clear queue?")
                .setMessage(
                        "Remove all queued songs and the current selection?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CLEAR", (dialog, which) -> {
                    Stage3SongQueue.stopAndClear();
                    refreshQueue();
                    Toast.makeText(this,
                            "Queue cleared.",
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Stage3SongQueue.initialize(this);
        refreshQueue();
    }
}
