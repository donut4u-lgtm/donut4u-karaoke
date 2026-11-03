.
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Stage3MediaFireActivity extends Activity {

    private static final String PREFS =
            "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";

    private static final String TEST_LINK =
            "https://www.mediafire.com/file/vx2ly4u4j1ky6tc/A-Ha_-_Take_On_Me.mid/file";

    private EditText searchInput;
    private EditText linkInput;
    private TextView status;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(5, 24, 15));

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(Color.rgb(80, 255, 150));
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • MEDIAFIRE MIDI/KAR");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        TextView searchLabel = label("1. SEARCH FOR A KARAOKE SONG");
        root.addView(searchLabel);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setHint("Song title or singer");
        root.addView(searchInput, new LinearLayout.LayoutParams(-1, dp(50)));

        Button searchButton = makeButton("SEARCH MEDIAFIRE SONGS");
        searchButton.setOnClickListener(v -> openSearch());
        root.addView(searchButton, buttonParams());

        TextView linkLabel = label("2. MEDIAFIRE FILE LINK");
        root.addView(linkLabel);

        linkInput = new EditText(this);
        linkInput.setTextColor(Color.WHITE);
        linkInput.setHintTextColor(Color.LTGRAY);
        linkInput.setTextSize(13);
        linkInput.setHint("Paste a public MediaFire file link");
        linkInput.setMinLines(2);
        linkInput.setMaxLines(3);
        linkInput.setGravity(Gravity.TOP);
        linkInput.setText(TEST_LINK);
        root.addView(linkInput,
                new LinearLayout.LayoutParams(-1, dp(82)));

        Button downloadButton =
                makeButton("DOWNLOAD MIDI/KAR & ADD TO SONGBOOK");
        downloadButton.setOnClickListener(v -> downloadSong());
        root.addView(downloadButton, buttonParams());

        progress = new ProgressBar(this);
        progress.setVisibility(android.view.View.GONE);
        root.addView(progress,
                new LinearLayout.LayoutParams(-1, dp(5)));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setPadding(0, dp(12), 0, dp(12));
        status.setText(
                "Search for a public MIDI/KAR file, paste its link, " +
                "and download it into your Stage 3 songbook.");
        root.addView(status);

        android.view.View spacer = new android.view.View(this);
        root.addView(spacer,
                new LinearLayout.LayoutParams(1, 0, 1f));

        Button back = makeButton("BACK TO SONGBOOK");
        back.setOnClickListener(v -> finish());
        root.addView(back, buttonParams());

        setContentView(root);
    }

    private TextView label(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.rgb(140, 255, 180));
        view.setTextSize(14);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private Button makeButton(String value) {
        Button button = new Button(this);
        button.setText(value);
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
                new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(8);
        return params;
    }

    private void openSearch() {
        String query = searchInput.getText().toString().trim();

        if (query.isEmpty()) {
            searchInput.setError("Enter a song title");
            return;
        }

        try {
            String text = URLEncoder.encode(
                    "site:mediafire.com/file MIDI KAR " + query,
                    "UTF-8");

            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + text));

            startActivity(intent);

            status.setText(
                    "Choose a public MediaFire MIDI/KAR result. " +
                    "Copy its individual file link and return here.");

        } catch (Exception e) {
            status.setText("Could not open web search.");
        }
    }

    private void downloadSong() {
        String link = linkInput.getText().toString().trim();

        if (!isMediaFireLink(link)) {
            linkInput.setError(
                    "Enter an HTTPS MediaFire file link");
            return;
        }

        progress.setVisibility(android.view.View.VISIBLE);
        status.setText("Resolving MediaFire download link...");

        new Thread(() -> {
            File downloaded = null;

            try {
                String directUrl = resolveDownloadUrl(link);

                runOnUiThread(() ->
                        status.setText("Downloading MIDI/KAR file..."));

                downloaded = downloadFile(directUrl);

                String fileName = downloaded.getName();
                String title = removeExtension(fileName);
                String localUri = Uri.fromFile(downloaded).toString();

                String number = registerSong(title, localUri);

                File finalFile = downloaded;

                runOnUiThread(() -> {
                    progress.setVisibility(
                            android.view.View.GONE);

                    Stage3SongQueue.initialize(this);

                    Stage3SongQueue.setNowPlaying(
                            new Stage3SongQueue.QueuedSong(
                                    number,
                                    title,
                                    Uri.fromFile(finalFile).toString(),
                                    "MIDI/KAR"));

                    status.setText(
                            "Downloaded and registered:\n" + title);

                    Toast.makeText(
                            this,
                            "Opening Stage 3 player",
                            Toast.LENGTH_LONG).show();

                    startActivity(new Intent(
                            this,
                            Stage3PlayerActivity.class));
                });

            } catch (Exception e) {
                if (downloaded != null && downloaded.exists()) {
                    downloaded.delete();
                }

                String message = e.getMessage() == null
                        ? "Unknown download error" : e.getMessage();

                runOnUiThread(() -> {
                    progress.setVisibility(
                            android.view.View.GONE);
                    status.setText("DOWNLOAD FAILED:\n" + message);
                });
            }
        }, "Donut4U-MediaFire").start();
    }

    private boolean isMediaFireLink(String value) {
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();

            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("mediafire.com")
                    || host.toLowerCase(Locale.ROOT)
                    .endsWith(".mediafire.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveDownloadUrl(String pageUrl)
            throws Exception {

        HttpURLConnection connection = null;

        try {
            connection = openConnection(pageUrl);
            connection.connect();

            int response = connection.getResponseCode();

            if (response < 200 || response >= 400) {
                throw new Exception(
                        "MediaFire page returned HTTP " + response);
            }

            String finalUrl = connection.getURL().toString();
            String contentType = connection.getContentType();

            // Some links redirect directly to the file.
            if (contentType != null
                    && !contentType.toLowerCase(Locale.ROOT)
                    .contains("text/html")
                    && hasMidiExtension(finalUrl)) {
                return finalUrl;
            }

            String html = readPage(connection.getInputStream());

            // Locate the MediaFire download button, regardless
            // of the order of its HTML attributes.
            Pattern anchorPattern = Pattern.compile(
                    "<a\\b[^>]*id\\s*=\\s*[\"']downloadButton[\"'][^>]*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

            Matcher anchor = anchorPattern.matcher(html);

            if (anchor.find()) {
                Matcher href = Pattern.compile(
                        "href\\s*=\\s*[\"']([^\"']+)[\"']",
                        Pattern.CASE_INSENSITIVE)
                        .matcher(anchor.group());

                if (href.find()) {
                    String candidate =
                            cleanHtmlUrl(href.group(1));

                    if (isAllowedDownloadHost(candidate)) {
                        return candidate;
                    }
                }
            }

            // Fallback: look for a direct MediaFire download host.
            Pattern directPattern = Pattern.compile(
                    "https?://download[0-9]+\\.mediafire\\.com/[^\"'\\s<>]+",
                    Pattern.CASE_INSENSITIVE);

            Matcher direct = directPattern.matcher(html);

            if (direct.find()) {
                String candidate =
                        cleanHtmlUrl(direct.group());

                if (isAllowedDownloadHost(candidate)) {
                    return candidate;
                }
            }

            throw new Exception(
                    "MediaFire did not expose a direct download link. " +
                    "It may require browser verification. Open the link " +
                    "in your browser and try again with a direct file link.");

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String address)
            throws Exception {

        URL url = new URL(address);

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setConnectTimeout(20000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) " +
                "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");

        return connection;
    }

    private String readPage(InputStream input)
            throws Exception {

        try (InputStream in = new BufferedInputStream(input);
             ByteArrayOutputStream out =
                     new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int count;
            int total = 0;

            while ((count = in.read(buffer)) != -1) {
                total += count;

                if (total > 4 * 1024 * 1024) {
                    break;
                }

                out.write(buffer, 0, count);
            }

            return out.toString("UTF-8");
        }
    }

    private String cleanHtmlUrl(String value) {
        return value.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("\\/", "/")
                .trim();
    }

    private boolean isAllowedDownloadHost(String value) {
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();

            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && host.toLowerCase(Locale.ROOT)
                    .matches("download[0-9]+\\.mediafire\\.com");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasMidiExtension(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains(".mid")
                || lower.contains(".midi")
                || lower.contains(".kar");
    }

    private File downloadFile(String address)
            throws Exception {

        if (!isAllowedDownloadHost(address)) {
            throw new Exception(
                    "The resolved download host is not a MediaFire file host.");
        }

        HttpURLConnection connection = null;
        File outputFile = null;

        try {
            connection = openConnection(address);
            connection.connect();

            int response = connection.getResponseCode();

            if (response < 200 || response >= 300) {
                throw new Exception(
                        "File download returned HTTP " + response);
            }

            String contentType = connection.getContentType();

            if (contentType != null
                    && contentType.toLowerCase(Locale.ROOT)
                    .contains("text/html")) {
                throw new Exception(
                        "MediaFire returned a web page instead of a file.");
            }

            String fileName = getFileName(connection, address);
            String extension = getMidiExtension(fileName);

            if (extension.isEmpty()) {
                throw new Exception(
                        "The file is not identified as MID, MIDI or KAR.");
            }

            File folder = new File(
                    getFilesDir(), "stage3_mediafire");

            if (!folder.exists() && !folder.mkdirs()) {
                throw new Exception(
                        "Could not create the Stage 3 download folder.");
            }

            String safeName = fileName.replaceAll(
                    "[^A-Za-z0-9._-]", "_");

            if (!safeName.toLowerCase(Locale.ROOT)
                    .endsWith(extension)) {
                safeName += extension;
            }

            outputFile = new File(
                    folder,
                    System.currentTimeMillis() + "_" + safeName);

            long total = 0;

            try (InputStream input =
                         new BufferedInputStream(
                                 connection.getInputStream());
                 FileOutputStream output =
                         new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    total += count;

                    if (total > 100L * 1024L * 1024L) {
                        throw new Exception(
                                "File exceeds the 100 MB download limit.");
                    }

                    output.write(buffer, 0, count);
                }
            }

            if (total < 14 || !hasMidiHeader(outputFile)) {
                throw new Exception(
                        "Downloaded data is not a valid MIDI/KAR file.");
            }

            return outputFile;

        } catch (Exception e) {
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
            throw e;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean hasMidiHeader(File file) {
        try (InputStream input =
                     new java.io.FileInputStream(file)) {

            byte[] header = new byte[4];

            if (input.read(header) != 4) {
                return false;
            }

            return header[0] == 'M'
                    && header[1] == 'T'
                    && header[2] == 'h'
                    && header[3] == 'd';

        } catch (Exception e) {
            return false;
        }
    }

    private String getMidiExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".midi")) return ".midi";
        if (lower.endsWith(".mid")) return ".mid";
        if (lower.endsWith(".kar")) return ".kar";

        return "";
    }

    private String getFileName(
            HttpURLConnection connection,
            String address) {

        String disposition =
                connection.getHeaderField("Content-Disposition");

        if (disposition != null) {
            Matcher matcher = Pattern.compile(
                    "filename\\*?=(?:UTF-8''|\"?)([^\";]+)",
                    Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);

            if (matcher.find()) {
                return Uri.decode(matcher.group(1).trim());
            }
        }

        Uri uri = Uri.parse(address);
        String path = uri.getLastPathSegment();

        if (path != null && path.contains(".")) {
            return Uri.decode(path);
        }

        return "mediafire_song.mid";
    }

    private String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String registerSong(
            String title,
            String uri) throws Exception {

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        JSONArray songs = new JSONArray(
                prefs.getString(SONGS_KEY, "[]"));

        int nextNumber = 10000;

        for (int i = 0; i < songs.length(); i++) {
            JSONObject item = songs.getJSONObject(i);

            if (uri.equals(item.optString("uri", ""))) {
                return item.optString("number", "10000");
            }

            try {
                int oldNumber = Integer.parseInt(
                        item.optString("number", "0"));

                nextNumber = Math.max(
                        nextNumber, oldNumber + 1);

            } catch (NumberFormatException ignored) {
            }
        }

        String number = String.format(
                Locale.US, "%05d", nextNumber);

        JSONObject song = new JSONObject();
        song.put("number", number);
        song.put("title", title);
        song.put("uri", uri);
        song.put("type", "MIDI/KAR");

        songs.put(song);

        prefs.edit()
                .putString(SONGS_KEY, songs.toString())
                .apply();

        return number;
    }
}
