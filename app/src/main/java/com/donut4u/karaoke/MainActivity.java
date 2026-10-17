
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {

    private LinearLayout songList;
    private EditText searchInput;

    private final ArrayList<String> allSongs = new ArrayList<>();

    private final int green = Color.rgb(25, 125, 75);
    private final int dark = Color.rgb(30, 30, 30);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        loadSongs();
        buildScreen();
        showSongs("");
    }

    private void loadSongs() {
        allSongs.add("A Whole New World - Peabo Bryson");
        allSongs.add("Bakit Ngayon Ka Lang - Ogie Alcasid");
        allSongs.add("Dancing Queen - ABBA");
        allSongs.add("Flowers - Miley Cyrus");
        allSongs.add("Hello - Adele");
        allSongs.add("Imagine - John Lennon");
        allSongs.add("My Way - Frank Sinatra");
        allSongs.add("Perfect - Ed Sheeran");
        allSongs.add("Queen of the Night - Whitney Houston");
        allSongs.add("Yesterday - The Beatles");

        Collections.sort(allSongs, String.CASE_INSENSITIVE_ORDER);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(20, 20, 20, 12);

        TextView title = new TextView(this);
        title.setText("🎤 donut4u karaoke");
        title.setTextSize(27);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(green);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 15, 0, 8);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "Your songs. Your voice. The world of music."
        );
        subtitle.setTextSize(14);
        subtitle.setTextColor(dark);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 18);
        root.addView(subtitle);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(16);
        searchInput.setHint("Search song or artist...");
        searchInput.setPadding(12, 8, 12, 8);
        root.addView(
                searchInput,
                new LinearLayout.LayoutParams(-1, -2)
        );

        Button searchButton = new Button(this);
        searchButton.setText("SEARCH SONGS");
        searchButton.setBackgroundTintList(
                ColorStateList.valueOf(green)
        );
        searchButton.setTextColor(Color.WHITE);
        root.addView(searchButton);

        Button youtubeButton = new Button(this);
        youtubeButton.setText("FIND KARAOKE ON YOUTUBE");
        root.addView(youtubeButton);

        TextView heading = new TextView(this);
        heading.setText("SONGS A–Z");
        heading.setTextSize(19);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextColor(dark);
        heading.setPadding(0, 15, 0, 10);
        root.addView(heading);

        ScrollView scroll = new ScrollView(this);
        songList = new LinearLayout(this);
        songList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(songList);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        searchButton.setOnClickListener(v -> {
            String query = searchInput.getText()
                    .toString().trim();
            showSongs(query);
        });

        youtubeButton.setOnClickListener(v -> {
            String query = searchInput.getText()
                    .toString().trim();

            if (query.isEmpty()) {
                Toast.makeText(
                        this,
                        "Enter a song or artist first.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            openYouTube(query + " karaoke");
        });

        setContentView(root);
    }

    private void showSongs(String query) {
        songList.removeAllViews();

        String search = query.toLowerCase(Locale.ROOT);
        int count = 0;

        for (String song : allSongs) {
            if (song.toLowerCase(Locale.ROOT)
                    .contains(search)) {
                addSongRow(song);
                count++;
            }
        }

        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(
                    "No matching sample songs.\n"
                    + "Enter your song or artist above, "
                    + "then tap FIND KARAOKE ON YOUTUBE."
            );
            empty.setTextSize(16);
            empty.setPadding(12, 20, 12, 20);
            empty.setTextColor(dark);
            songList.addView(empty);
        }
    }

    private void addSongRow(String song) {
        TextView row = new TextView(this);
        row.setText("♫  " + song + "   ▶");
        row.setTextSize(16);
        row.setTextColor(dark);
        row.setPadding(12, 18, 12, 18);
        row.setBackgroundColor(Color.rgb(245, 248, 245));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, 6);
        songList.addView(row, params);

        row.setOnClickListener(v ->
                openYouTube(song + " karaoke")
        );
    }

    private void openYouTube(String query) {
        String url =
                "https://www.youtube.com/results?search_query="
                + Uri.encode(query);

        Intent intent = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
        );

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to open YouTube.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}
