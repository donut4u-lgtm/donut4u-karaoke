
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class OnlineSongActivity extends Activity {

    private final int GREEN = Color.rgb(20, 145, 75);
    private final int DARK = Color.rgb(22, 22, 22);
    private final int WHITE = Color.WHITE;
    private final int YELLOW = Color.rgb(255, 220, 50);

    private EditText searchBox;
    private LinearLayout resultsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(DARK);

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(GREEN);
        heading.setTextSize(25);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(10), 0, dp(18));
        root.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText("ONLINE KARAOKE SONGS");
        subtitle.setTextColor(YELLOW);
        subtitle.setTextSize(18);
        subtitle.setTypeface(null, Typeface.BOLD);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Enter song title or artist");
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(Color.LTGRAY);

        root.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        Button searchButton = makeButton("SEARCH KARAOKE SONGS");
        searchButton.setOnClickListener(v -> searchSongs());

        root.addView(searchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        TextView information = new TextView(this);
        information.setText(
                "Find karaoke videos online. Select a song to open " +
                "the available YouTube search results."
        );
        information.setTextColor(Color.LTGRAY);
        information.setTextSize(14);
        information.setGravity(Gravity.CENTER);
        information.setPadding(0, dp(12), 0, dp(12));
        root.addView(information);

        ScrollView scrollView = new ScrollView(this);

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(resultsContainer);

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button backButton = makeButton("BACK");
        backButton.setOnClickListener(v -> finish());

        root.addView(backButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        setContentView(root);

        addSuggestion("Popular Karaoke Songs");
        addSuggestion("OPM Karaoke");
        addSuggestion("Love Songs Karaoke");
        addSuggestion("Duet Karaoke");
        addSuggestion("Videoke Minus One");
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(WHITE);
        button.setTextSize(13);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private void searchSongs() {
        String query = searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            Toast.makeText(
                    this,
                    "Enter a song title or artist.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        openOnlineSearch(query);
    }

    private void addSuggestion(String title) {
        Button button = makeButton(title);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                );

        params.bottomMargin = dp(8);

        button.setOnClickListener(v -> {
            searchBox.setText(title);
            openOnlineSearch(title);
        });

        resultsContainer.addView(button, params);
    }

    private void openOnlineSearch(String query) {
        String searchQuery = query;

        String lower = query.toLowerCase(java.util.Locale.ROOT);

        if (!lower.contains("karaoke")
                && !lower.contains("instrumental")
                && !lower.contains("minus one")
                && !lower.contains("videoke")) {
            searchQuery += " karaoke";
        }

        Uri uri = Uri.parse(
                "https://m.youtube.com/results?search_query="
                        + Uri.encode(searchQuery)
        );

        Intent intent = new Intent(Intent.ACTION_VIEW, uri);

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to open online song search.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private int dp(int value) {
        return (int) (
                value *
                getResources().getDisplayMetrics().density + 0.5f
        );
    }
}
