
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

public class Stage3PostFileSearchActivity extends Activity {

    private static final String API_KEY_PREFS =
            "donut4u_postfile_secure";

    private static final String SONGBOOK_PREFS =
            "donut4u_stage3_songbook";

    private static final String SONGS_KEY = "songs_json";

    private static final String FILE_LIST_URL =
            "https://postfile.net/v1/files?page=1&per_page=200";

    private EditText searchInput;
    private LinearLayout resultsLayout;
    private TextView status;
    private ProgressBar progress;

    private final ArrayList<HostedSong> results =
            new ArrayList<>();

    private static class HostedSong {
        String id;
        String title;
        String url;

        HostedSong(String id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = url;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(5, 24, 15));

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(Color.rgb(80, 255, 150));
        heading.setTextSize(24);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading,
                new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • POSTFILE SONG SEARCH");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setHint("Enter song title or singer");
        searchInput.setTextSize(16);
        root.addView(searchInput,
                new LinearLayout.LayoutParams(-1, dp(52)));

        Button searchButton = makeButton("SEARCH POSTFILE SONGS");
        searchButton.setOnClickListener(v -> searchPostFile());
        root.addView(searchButton, buttonParams());

        status = new TextView(this);
        status.setText("Search your connected PostFile account.");
        status.setTextColor(Color.rgb(140, 255, 180));
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress,
                new LinearLayout.LayoutParams(-1, dp(5)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        resultsLayout = new LinearLayout(this);
        resultsLayout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(resultsLayout);

        root.addView(scroll,
                new LinearLayout.LayoutParams(-1, 0, 1f));

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

    private String getApiKey() {
        try {
            SharedPreferences prefs = getSharedPreferences(
                    API_KEY_PREFS, MODE_PRIVATE);

            String ivText = prefs.getString("iv", "");
            String encryptedText = prefs.getString("key", "");

            if (ivText.isEmpty() || encryptedText.isEmpty()) {
                return "";
            }

            byte[] iv = android.util.Base64.decode(
                    ivText, android.util.Base64.NO_WRAP);

            byte[] encrypted = android.util.Base64.decode(
                    encryptedText, android.util.Base64.NO_WRAP);

            java.security.KeyStore keyStore =
                    java.security.KeyStore.getInstance(
                            "AndroidKeyStore");

            keyStore.load(null);

            javax.crypto.SecretKey secretKey =
                    (javax.crypto.SecretKey) keyStore.getKey(
                            "Donut4UPostFileUploadKey", null);

            if (secretKey == null) return "";

            javax.crypto.Cipher cipher =
                    javax.crypto.Cipher.getInstance(
                            "AES/GCM/NoPadding");

            cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    secretKey,
                    new javax.crypto.spec.GCMParameterSpec(128, iv));

            return new String(
                    cipher.doFinal(encrypted),
                    java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            return "";
        }
    }

    private void searchPostFile() {
        String query = searchInput.getText()
                .toString().trim().toLowerCase(Locale.ROOT);

        if (query.isEmpty()) {
            searchInput.setError("Enter a song title or singer");
            return;
        }

        String apiKey = getApiKey();

        if (apiKey.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("POSTFILE API KEY")
                    .setMessage(
                            "Set your PostFile API key first using " +
                            "the existing Stage 3 PostFile uploader.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        results.clear();
        resultsLayout.removeAllViews();

        progress.setVisibility(View.VISIBLE);
        status.setText("Searching your PostFile account...");

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL address = new URL(FILE_LIST_URL);
                connection = (HttpURLConnection)
                        address.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty(
                        "X-API-Key", apiKey);

                int code = connection.getResponseCode();

                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                StringBuilder response = new StringBuilder();

                if (stream != null) {
                    byte[] buffer = new byte[8192];
                    int count;

                    while ((count = stream.read(buffer)) != -1) {
                        response.append(new String(
                                buffer, 0, count,
                                java.nio.charset.StandardCharsets.UTF_8));
                    }

                    stream.close();
                }

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "PostFile HTTP " + code + ": " + response);
                }

                JSONObject json =
                        new JSONObject(response.toString());

                JSONArray files = json.optJSONArray("files");

                if (files == null) {
                    throw new Exception(
                            "PostFile did not return a files list.");
                }

                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.getJSONObject(i);

                    String name = file.optString("name", "");
                    String id = file.optString("file_id", "");
                    String fileUrl = file.optString("url", "");

                    String lower = name.toLowerCase(Locale.ROOT);

                    boolean supported =
                            lower.endsWith(".mid")
                            || lower.endsWith(".midi")
                            || lower.endsWith(".kar");

                    if (!supported || !lower.contains(query)) {
                        continue;
                    }

                    if (id.isEmpty() || !fileUrl.startsWith("https://")) {
                        continue;
                    }

                    results.add(new HostedSong(id, name, fileUrl));
                }

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    displayResults();
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Search failed." : e.getMessage();

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Search failed: " + message);
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "PostFile-Search").start();
    }

    private void displayResults() {
        resultsLayout.removeAllViews();

        status.setText("MATCHING MIDI/KAR FILES: " + results.size());

        if (results.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(
                    "No matching MIDI/KAR files found.\n\n" +
                    "Try another song title or singer.");
            empty.setTextColor(Color.WHITE);
            empty.setTextSize(16);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(30), dp(12), dp(30));
            resultsLayout.addView(empty);
            return;
        }

        for (HostedSong song : results) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(Color.rgb(22, 48, 32));

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(9);
            card.setLayoutParams(cardParams);

            TextView title = new TextView(this);
            title.setText(song.title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(title);

            TextView format = new TextView(this);
            format.setText("PostFile • MIDI / KAR");
            format.setTextColor(Color.rgb(140, 255, 180));
            format.setTextSize(12);
            format.setPadding(0, dp(5), 0, dp(8));
            card.addView(format);

            Button playButton = makeButton("DOWNLOAD & PLAY");
            playButton.setOnClickListener(v -> downloadAndPlay(song));
            card.addView(playButton);

            resultsLayout.addView(card);
        }
    }

    private void downloadAndPlay(HostedSong song) {
        progress.setVisibility(View.VISIBLE);
        status.setText("Downloading " + song.title + "...");

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL address = new URL(song.url);
                connection = (HttpURLConnection)
                        address.openConnection();

                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);

                int code = connection.getResponseCode();

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            "Download HTTP " + code);
                }

                File directory = new File(
                        getFilesDir(), "stage3_hosted");

                if (!directory.exists() && !directory.mkdirs()) {
                    throw new Exception(
                            "Cannot create Stage 3 download folder.");
                }

                String extension = song.title.toLowerCase(Locale.ROOT);

                if (extension.endsWith(".midi")) {
                    extension = ".midi";
                } else if (extension.endsWith(".kar")) {
                    extension = ".kar";
                } else {
                    extension = ".mid";
                }

                File outputFile = new File(
                        directory,
                        "postfile_" + song.id.replaceAll(
                                "[^A-Za-z0-9_-]", "_") + extension);

                try (InputStream input = new BufferedInputStream(
                        connection.getInputStream());
                     FileOutputStream output =
                             new FileOutputStream(outputFile)) {

                    byte[] buffer = new byte[8192];
                    int count;
                    long total = 0;

                    while ((count = input.read(buffer)) != -1) {
                        total += count;

                        if (total > 100L * 1024L * 1024L) {
                            throw new Exception(
                                    "File exceeds the 100 MB download limit.");
                        }

                        output.write(buffer, 0, count);
                    }

                    if (total == 0) {
                        throw new Exception("Downloaded file is empty.");
                    }
                }

                String localUri = Uri.fromFile(outputFile).toString();

                String number = registerSongbook(
                        song.title, localUri);

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);

                    Stage3SongQueue.initialize(this);

                    Stage3SongQueue.QueuedSong queued =
                            new Stage3SongQueue.QueuedSong(
                                    number,
                                    song.title,
                                    localUri,
                                    "MIDI/KAR");

                    Stage3SongQueue.setNowPlaying(queued);

                    status.setText("Downloaded: " + song.title);

                    Toast.makeText(this,
                            "Song downloaded. Opening karaoke player.",
                            Toast.LENGTH_LONG).show();

                    startActivity(new Intent(
                            this, Stage3PlayerActivity.class));
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Download failed." : e.getMessage();

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Download failed: " + message);
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "PostFile-Download").start();
    }

    private String registerSongbook(String title, String uri)
            throws Exception {

        SharedPreferences prefs = getSharedPreferences(
                SONGBOOK_PREFS, MODE_PRIVATE);

        JSONArray songs = new JSONArray(
                prefs.getString(SONGS_KEY, "[]"));

        int nextNumber = 10000;

        for (int i = 0; i < songs.length(); i++) {
            JSONObject existing = songs.getJSONObject(i);

            if (uri.equals(existing.optString("uri", ""))) {
                return existing.optString("number", "10000");
            }

            try {
                int current = Integer.parseInt(
                        existing.optString("number", "0"));
                nextNumber = Math.max(nextNumber, current + 1);
            } catch (Exception ignored) {
            }
        }

        String number = String.format(
                Locale.US, "%05d", nextNumber);

        JSONObject item = new JSONObject();
        item.put("number", number);
        item.put("title", title);
        item.put("uri", uri);
        item.put("type", "MIDI/KAR");

        songs.put(item);

        prefs.edit()
                .putString(SONGS_KEY, songs.toString())
                .apply();

        return number;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
