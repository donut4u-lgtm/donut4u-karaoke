
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OnlineSongActivity extends Activity {

    private static final int GREEN = Color.rgb(20, 145, 75);
    private static final int DARK = Color.rgb(22, 22, 22);
    private static final int WHITE = Color.WHITE;
    private static final int YELLOW = Color.rgb(255, 220, 50);

    private EditText searchBox;
    private LinearLayout resultsContainer;
    private ProgressBar progressBar;
    private Button searchButton;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private volatile boolean closing = false;

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
        subtitle.setText("SEARCH FREE-LICENSED AUDIO");
        subtitle.setTextColor(YELLOW);
        subtitle.setTextSize(17);
        subtitle.setTypeface(null, Typeface.BOLD);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        TextView explanation = new TextView(this);
        explanation.setText(
                "Search audio available from Internet Archive. " +
                "Only results with a recognized public-domain or " +
                "Creative Commons BY licence and a playable audio file " +
                "are offered. Popular commercial karaoke tracks may " +
                "not be available."
        );
        explanation.setTextColor(Color.LTGRAY);
        explanation.setTextSize(13);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(0, dp(8), 0, dp(8));
        root.addView(explanation);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Song title or artist");
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(Color.LTGRAY);
        root.addView(searchBox, fullWidth(dp(50)));

        searchButton = makeButton("SEARCH AUDIO");
        searchButton.setOnClickListener(v -> searchSongs());
        root.addView(searchButton, fullWidth(dp(48)));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

        ScrollView scroll = new ScrollView(this);
        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(resultsContainer);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button back = makeButton("BACK TO KARAOKE PLAYER");
        back.setOnClickListener(v -> finish());
        root.addView(back, fullWidth(dp(46)));

        setContentView(root);

        addMessage("Enter a song title or artist, then tap SEARCH AUDIO.");
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(WHITE);
        button.setTextSize(12);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private void searchSongs() {
        String query = searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a song title or artist.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        searchButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        resultsContainer.removeAllViews();
        addMessage("Searching audio catalogue...");

        executor.execute(() -> {
            JSONArray documents = new JSONArray();
            String error = null;
            HttpURLConnection connection = null;

            try {
                String clean = query
                        .replace("\\", " ")
                        .replace("\"", " ")
                        .replace(":", " ")
                        .replace("(", " ")
                        .replace(")", " ");

                String expression = "mediatype:audio AND (title:(\""
                        + clean + "\") OR creator:(\"" + clean + "\"))";

                String api = "https://archive.org/advancedsearch.php?q="
                        + URLEncoder.encode(expression, "UTF-8")
                        + "&fl[]=identifier&fl[]=title&fl[]=creator"
                        + "&rows=20&page=1&output=json";

                connection = (HttpURLConnection)
                        new URL(api).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty(
                        "User-Agent", "Donut4U-Karaoke/1.0");

                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + status);
                }

                JSONObject response = new JSONObject(
                        readAll(connection.getInputStream()));

                JSONObject responseObject =
                        response.optJSONObject("response");

                if (responseObject != null) {
                    JSONArray docs = responseObject.optJSONArray("docs");
                    if (docs != null) documents = docs;
                }
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                if (connection != null) connection.disconnect();
            }

            final JSONArray found = documents;
            final String searchError = error;

            runOnUiThread(() -> {
                if (closing || isFinishing()) return;

                resultsContainer.removeAllViews();

                if (searchError != null) {
                    addMessage(
                            "Search failed. Check your internet connection.");
                    finishSearch();
                    return;
                }

                if (found.length() == 0) {
                    addMessage(
                            "No results found. Try a shorter title or artist.");
                    finishSearch();
                    return;
                }

                addMessage("Checking licences and playable files...");

                for (int i = 0; i < found.length(); i++) {
                    JSONObject doc = found.optJSONObject(i);
                    if (doc == null) continue;

                    String identifier =
                            doc.optString("identifier", "");
                    String title = doc.optString("title", identifier);
                    String creator = doc.optString("creator", "");

                    if (!identifier.isEmpty()) {
                        checkItem(identifier, title, creator);
                    }
                }
            });
        });
    }

    private void checkItem(
            String identifier, String title, String creator) {

        executor.execute(() -> {
            HttpURLConnection connection = null;
            String audioUrl = null;
            String licence = "";

            try {
                URL metadataUrl = new URL(
                        "https://archive.org/metadata/"
                                + Uri.encode(identifier));

                connection = (HttpURLConnection)
                        metadataUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty(
                        "User-Agent", "Donut4U-Karaoke/1.0");

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    JSONObject metadataResponse = new JSONObject(
                            readAll(connection.getInputStream()));

                    JSONObject metadata =
                            metadataResponse.optJSONObject("metadata");

                    if (metadata != null) {
                        licence = metadata.optString("licenseurl", "");
                    }

                    if (isAllowedLicence(licence)) {
                        JSONArray files =
                                metadataResponse.optJSONArray("files");

                        if (files != null) {
                            for (int i = 0; i < files.length(); i++) {
                                JSONObject file = files.optJSONObject(i);
                                if (file == null) continue;

                                String name = file.optString("name", "");
                                String format = file.optString("format", "");

                                if (isPlayableAudio(name, format)) {
                                    audioUrl = "https://archive.org/download/"
                                            + Uri.encode(identifier) + "/"
                                            + Uri.encode(name);
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }

            final String playableUrl = audioUrl;
            final String actualLicence = licence;

            runOnUiThread(() -> {
                if (closing || isFinishing()) return;

                if (playableUrl != null
                        && isAllowedLicence(actualLicence)) {
                    addSongResult(title, creator,
                            actualLicence, playableUrl);
                }

                finishSearchIfIdle();
            });
        });
    }

    private boolean isAllowedLicence(String licence) {
        if (licence == null) return false;

        String value = licence.toLowerCase(Locale.ROOT);

        return value.contains("creativecommons.org/licenses/by/")
                || value.contains("creativecommons.org/publicdomain/");
    }

    private boolean isPlayableAudio(String name, String format) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerFormat = format.toLowerCase(Locale.ROOT);

        boolean extension = lowerName.endsWith(".mp3")
                || lowerName.endsWith(".wav")
                || lowerName.endsWith(".m4a")
                || lowerName.endsWith(".aac")
                || lowerName.endsWith(".ogg")
                || lowerName.endsWith(".opus");

        boolean audioFormat = lowerFormat.contains("mp3")
                || lowerFormat.contains("mpeg")
                || lowerFormat.contains("wave")
                || lowerFormat.contains("wav")
                || lowerFormat.contains("aac")
                || lowerFormat.contains("ogg")
                || lowerFormat.contains("audio");

        return extension && audioFormat
                && !lowerName.contains("sample")
                && !lowerName.endsWith("_thumb.mp3");
    }

    private void addSongResult(
            String title, String creator,
            String licence, String audioUrl) {

        String cleanTitle = title == null || title.trim().isEmpty()
                ? "Untitled audio" : title.trim();

        Button play = makeButton("▶ PLAY: " + cleanTitle);
        play.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        play.setPadding(dp(8), 0, dp(8), 0);

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.bottomMargin = dp(3);
        resultsContainer.addView(play, buttonParams);

        String credit = creator == null || creator.trim().isEmpty()
                ? "Creator not listed" : creator.trim();

        TextView details = new TextView(this);
        details.setText(credit + "\nLicence: " + licence);
        details.setTextColor(Color.LTGRAY);
        details.setTextSize(11);
        details.setPadding(dp(8), 0, dp(8), dp(10));
        resultsContainer.addView(details);

        play.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra("audio_url", audioUrl);
            result.putExtra("song_title", cleanTitle);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void addMessage(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(WHITE);
        text.setTextSize(14);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(10), dp(18), dp(10), dp(18));

        resultsContainer.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void finishSearchIfIdle() {
        // Keep the search button available after result checks.
        // The user can perform another search at any time.
        finishSearch();
    }

    private void finishSearch() {
        searchButton.setEnabled(true);
        progressBar.setVisibility(View.GONE);
    }

    private String readAll(InputStream input) throws Exception {
        try (InputStream in = input;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int count;

            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }

            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int dp(int value) {
        return (int) (value
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        closing = true;
        executor.shutdownNow();
        super.onDestroy();
    }
}
