package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OnlineSongActivity extends Activity {

    private static final int GREEN = Color.rgb(20, 145, 75);
    private static final int DARK = Color.rgb(22, 22, 22);
    private static final int WHITE = Color.WHITE;
    private static final int YELLOW = Color.rgb(255, 220, 50);

    /*
     * UltraStar-Deluxe community song repository.
     * Songs use UltraStar text files containing metadata,
     * backing-track references and timed lyric information.
     */
    private static final String CATALOG_TREE =
            "https://api.github.com/repos/UltraStar-Deluxe/songs-stream/git/trees/main?recursive=1";

    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/UltraStar-Deluxe/songs-stream/main/";

    private EditText searchBox;
    private LinearLayout resultsContainer;
    private ProgressBar progressBar;
    private Button searchButton;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private volatile boolean closing = false;
    private int searchGeneration = 0;

    private static class KaraokeSong {
        String title;
        String artist;
        String audioUrl;
        String coverUrl;
        String lyricsLrc;
        String sourcePath;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(DARK);

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(GREEN);
        heading.setTextSize(24);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(8), 0, dp(8));
        root.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText("KARAOKE SONGBOOK");
        subtitle.setTextColor(YELLOW);
        subtitle.setTextSize(18);
        subtitle.setTypeface(null, Typeface.BOLD);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        TextView explanation = new TextView(this);
        explanation.setText(
                "Search karaoke song packages by title or artist. " +
                "Songs need a playable backing track and timed lyrics. " +
                "This community catalog is not a complete commercial song library."
        );
        explanation.setTextColor(Color.LTGRAY);
        explanation.setTextSize(12);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(0, dp(7), 0, dp(8));
        root.addView(explanation);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Search karaoke song or artist");
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(Color.LTGRAY);
        root.addView(searchBox, fullWidth(dp(50)));

        searchButton = makeButton("SEARCH KARAOKE SONGS");
        searchButton.setOnClickListener(v -> searchSongs());
        root.addView(searchButton, fullWidth(dp(48)));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);

        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
        ));

        ScrollView scroll = new ScrollView(this);

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);

        scroll.addView(resultsContainer);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        Button back = makeButton("BACK TO KARAOKE PLAYER");
        back.setOnClickListener(v -> finish());
        root.addView(back, fullWidth(dp(46)));

        setContentView(root);

        addMessage(
                "Enter a song title or artist to search the karaoke songbook."
        );
    }

    private void searchSongs() {
        String query = searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            toast("Enter a song title or artist.");
            return;
        }

        final int generation = ++searchGeneration;
        final String[] terms = normalizedTerms(query);

        searchButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        resultsContainer.removeAllViews();

        addMessage("Searching the karaoke songbook...");

        executor.execute(() -> {
            ArrayList<KaraokeSong> matches = new ArrayList<>();
            String error = null;

            HttpURLConnection connection = null;

            try {
                connection = openConnection(CATALOG_TREE);

                JSONObject treeJson = new JSONObject(
                        readAll(connection.getInputStream())
                );

                JSONArray tree = treeJson.optJSONArray("tree");

                if (tree == null) {
                    throw new Exception("The songbook index was not returned.");
                }

                ArrayList<String> candidatePaths = new ArrayList<>();

                for (int i = 0; i < tree.length(); i++) {
                    if (closing || generation != searchGeneration) {
                        return;
                    }

                    JSONObject entry = tree.optJSONObject(i);

                    if (entry == null) {
                        continue;
                    }

                    if (!"blob".equals(entry.optString("type"))) {
                        continue;
                    }

                    String path = entry.optString("path", "");
                    String lowerPath = path.toLowerCase(Locale.ROOT);

                    if (!lowerPath.endsWith(".txt")) {
                        continue;
                    }

                    if (lowerPath.endsWith("readme.txt")
                            || lowerPath.contains("/license")) {
                        continue;
                    }

                    if (matchesAllTerms(lowerPath, terms)) {
                        candidatePaths.add(path);
                    }
                }

                /*
                 * Avoid downloading hundreds of files for a single
                 * search. The catalog search can be expanded later
                 * with a dedicated cached index.
                 */
                int checked = 0;

                for (String path : candidatePaths) {
                    if (closing || generation != searchGeneration) {
                        return;
                    }

                    if (checked >= 40) {
                        break;
                    }

                    checked++;

                    try {
                        KaraokeSong song = fetchSong(path);

                        if (song != null
                                && matchesAllTerms(
                                (song.artist + " " + song.title)
                                        .toLowerCase(Locale.ROOT),
                                terms)) {
                            matches.add(song);
                        }
                    } catch (Exception ignored) {
                        // Ignore an invalid individual song package.
                    }
                }

                Collections.sort(
                        matches,
                        Comparator.comparing(
                                song -> (song.artist + " " + song.title)
                                        .toLowerCase(Locale.ROOT)
                        )
                );

            } catch (Exception e) {
                error = e.getMessage();

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final String searchError = error;
            final ArrayList<KaraokeSong> found = matches;

            runOnUiThread(() -> {
                if (closing || isFinishing()
                        || generation != searchGeneration) {
                    return;
                }

                resultsContainer.removeAllViews();

                if (searchError != null) {
                    addMessage(
                            "Could not load the songbook. " +
                            "Check your internet connection and try again."
                    );
                    finishSearch();
                    return;
                }

                if (found.isEmpty()) {
                    addMessage(
                            "No matching karaoke song was found. " +
                            "Try a shorter title or artist."
                    );
                    finishSearch();
                    return;
                }

                addMessage("KARAOKE SONGS FOUND: " + found.size());

                for (KaraokeSong song : found) {
                    addSongResult(song);
                }

                finishSearch();
            });
        });
    }

    private KaraokeSong fetchSong(String path) throws Exception {
        String encodedPath = Uri.encode(path, "/");

        HttpURLConnection connection = null;

        try {
            connection = openConnection(RAW_BASE + encodedPath);

            String source = readAll(connection.getInputStream());

            String title = header(source, "TITLE");
            String artist = header(source, "ARTIST");
            String audio = header(source, "MP3");

            if (title.isEmpty()
                    || artist.isEmpty()
                    || !isHttpUrl(audio)) {
                return null;
            }

            String lyrics = convertUltraStarToLrc(source);

            /*
             * A song without timed lyrics is not presented as
             * a synchronized karaoke result.
             */
            if (lyrics.isEmpty()) {
                return null;
            }

            KaraokeSong song = new KaraokeSong();

            song.title = title;
            song.artist = artist;
            song.audioUrl = audio;
            song.coverUrl = header(source, "COVER");
            song.lyricsLrc = lyrics;
            song.sourcePath = path;

            return song;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String convertUltraStarToLrc(String source) {
        double bpm = parseNumber(header(source, "BPM"), 0);
        double gap = parseNumber(header(source, "GAP"), 0);

        if (bpm <= 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        StringBuilder lyricLine = new StringBuilder();

        long lineStartBeat = -1;

        for (String rawLine : source.split("\\r?\\n")) {
            String line = rawLine.trim();

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("-")) {
                appendLrcLine(
                        result,
                        lyricLine,
                        lineStartBeat,
                        bpm,
                        gap
                );

                lyricLine.setLength(0);
                lineStartBeat = -1;
                continue;
            }

            if (line.startsWith("E")) {
                appendLrcLine(
                        result,
                        lyricLine,
                        lineStartBeat,
                        bpm,
                        gap
                );
                break;
            }

            if (line.startsWith(":")
                    || line.startsWith("F")
                    || line.startsWith("R")
                    || line.startsWith("G")) {

                String note = line.substring(1).trim();

                Matcher matcher = Pattern.compile(
                        "^(\\d+)\\s+\\d+\\s+-?\\d+\\s+(.*)$"
                ).matcher(note);

                if (!matcher.matches()) {
                    continue;
                }

                try {
                    long beat = Long.parseLong(matcher.group(1));
                    String words = matcher.group(2);

                    if (lineStartBeat < 0) {
                        lineStartBeat = beat;
                    }

                    lyricLine.append(words.replace("~", ""));

                } catch (Exception ignored) {
                    // Ignore malformed lyric lines.
                }
            }
        }

        appendLrcLine(
                result,
                lyricLine,
                lineStartBeat,
                bpm,
                gap
        );

        return result.toString();
    }

    private void appendLrcLine(
            StringBuilder result,
            StringBuilder lyricLine,
            long startBeat,
            double bpm,
            double gap) {

        String text = lyricLine.toString().trim();

        if (text.isEmpty() || startBeat < 0) {
            return;
        }

        long millis = Math.max(
                0,
                Math.round(gap + startBeat * 60000.0 / bpm)
        );

        long minutes = millis / 60000;
        long seconds = (millis % 60000) / 1000;
        long centiseconds = (millis % 1000) / 10;

        result.append(
                String.format(
                        Locale.ROOT,
                        "[%02d:%02d.%02d]",
                        minutes,
                        seconds,
                        centiseconds
                )
        );

        result.append(text).append('\n');
    }

    private double parseNumber(String value, double fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }

        try {
            return Double.parseDouble(
                    value.trim().replace(',', '.')
            );
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String header(String source, String key) {
        Pattern pattern = Pattern.compile(
                "(?im)^#" + Pattern.quote(key) + ":(.*)$"
        );

        Matcher matcher = pattern.matcher(source);

        return matcher.find()
                ? matcher.group(1).trim()
                : "";
    }

    private String[] normalizedTerms(String query) {
        String cleaned = query.toLowerCase(Locale.ROOT)
                .replace("karaoke", " ")
                .replace("instrumental", " ")
                .replace("minus one", " ")
                .replace("videoke", " ");

        String[] split = cleaned.split("[^a-z0-9]+");

        ArrayList<String> terms = new ArrayList<>();

        for (String term : split) {
            if (term.length() > 1) {
                terms.add(term);
            }
        }

        return terms.toArray(new String[0]);
    }

    private boolean matchesAllTerms(
            String value,
            String[] terms) {

        if (terms.length == 0) {
            return true;
        }

        for (String term : terms) {
            if (!value.contains(term)) {
                return false;
            }
        }

        return true;
    }

    private boolean isHttpUrl(String value) {
        try {
            Uri uri = Uri.parse(value);

            String scheme = uri.getScheme();

            return uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme));

        } catch (Exception ignored) {
            return false;
        }
    }

    private void addSongResult(KaraokeSong song) {
        Button play = makeButton("▶  " + song.title);

        play.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        play.setPadding(dp(8), 0, dp(8), 0);

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        buttonParams.bottomMargin = dp(2);

        resultsContainer.addView(play, buttonParams);

        TextView details = new TextView(this);

        details.setText(
                song.artist + "\n" +
                "Karaoke song • Timed lyrics"
        );

        details.setTextColor(Color.LTGRAY);
        details.setTextSize(12);
        details.setPadding(
                dp(8),
                0,
                dp(8),
                dp(10)
        );

        resultsContainer.addView(details);

        play.setOnClickListener(v -> {
            Intent result = new Intent();

            result.putExtra("audio_url", song.audioUrl);
            result.putExtra(
                    "song_title",
                    song.title + " — " + song.artist
            );
            result.putExtra("lyrics_lrc", song.lyricsLrc);

            setResult(RESULT_OK, result);
            finish();
        });
    }

    private HttpURLConnection openConnection(String url)
            throws Exception {

        HttpURLConnection connection =
                (HttpURLConnection) new URL(url).openConnection();

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);

        connection.setRequestProperty(
                "User-Agent",
                "Donut4U-Karaoke/1.0"
        );

        connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json, text/plain, */*"
        );

        int status = connection.getResponseCode();

        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new Exception("HTTP " + status);
        }

        return connection;
    }

    private String readAll(InputStream input) throws Exception {
        try (
                InputStream in = input;
                ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }

            return new String(
                    out.toByteArray(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private void addMessage(String message) {
        TextView text = new TextView(this);

        text.setText(message);
        text.setTextColor(WHITE);
        text.setTextSize(14);
        text.setGravity(Gravity.CENTER);

        text.setPadding(
                dp(10),
                dp(18),
                dp(10),
                dp(18)
        );

        resultsContainer.addView(
                text,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );
    }

    private void finishSearch() {
        searchButton.setEnabled(true);
        progressBar.setVisibility(View.GONE);
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private Button makeButton(String label) {
        Button button = new Button(this);

        button.setText(label);
        button.setTextColor(WHITE);
        button.setTextSize(12);
        button.setBackgroundColor(GREEN);

        return button;
    }

    private int dp(int value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    private void toast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        searchGeneration++;

        executor.shutdownNow();

        super.onDestroy();
    }
}
