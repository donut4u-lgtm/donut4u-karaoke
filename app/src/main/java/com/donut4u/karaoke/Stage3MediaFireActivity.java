
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Stage3MediaFireActivity extends Activity {

    private static final String FOLDER_KEY = "rmpdn8g24drnm";

    private static final String FOLDER_URL =
            "https://www.mediafire.com/folder/rmpdn8g24drnm/File_1";

    private static final String API_URL =
            "https://www.mediafire.com/api/1.4/folder/get_content.php";

    private static final String PREFS =
            "donut4u_stage3_songbook";

    private static final String SONGS_KEY = "songs_json";

    private static final int CHUNK_SIZE = 100;
    private static final int MAX_CHUNKS = 100;

    private static final long MAX_DOWNLOAD_SIZE =
            100L * 1024L * 1024L;

    private final ArrayList<MediaSong> allSongs =
            new ArrayList<>();

    private final Set<String> seenSongs =
            new HashSet<>();

    private LinearLayout songList;
    private EditText searchInput;
    private TextView status;
    private TextView countLabel;
    private ProgressBar progress;
    private Button loadButton;

    private volatile boolean loading = false;
    private volatile boolean downloading = false;

    private static class MediaSong {
        String title;
        String downloadUrl;
        String quickKey;

        MediaSong(String title, String downloadUrl,
                  String quickKey) {
            this.title = title;
            this.downloadUrl = downloadUrl;
            this.quickKey = quickKey;
        }
    }

    private static class ChunkResult {
        JSONArray files;
        boolean moreChunks;

        ChunkResult(JSONArray files, boolean moreChunks) {
            this.files = files;
            this.moreChunks = moreChunks;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
        loadFolder();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(8));
        root.setBackgroundColor(Color.rgb(5, 24, 15));

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(Color.rgb(80, 255, 150));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        root.addView(title,
                new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • MEDIAFIRE MIDI/KAR SONGBOOK");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER);

        root.addView(subtitle,
                new LinearLayout.LayoutParams(-1, dp(28)));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search song title...");
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(15);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackgroundColor(Color.rgb(35, 48, 40));

        root.addView(searchInput,
                new LinearLayout.LayoutParams(-1, dp(46)));

        loadButton = makeButton("LOAD / RELOAD ALL MEDIAFIRE SONGS");
        loadButton.setOnClickListener(v -> loadFolder());

        root.addView(loadButton,
                new LinearLayout.LayoutParams(-1, dp(46)));

        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);

        root.addView(progress,
                new LinearLayout.LayoutParams(-1, dp(4)));

        status = new TextView(this);
        status.setTextColor(Color.rgb(150, 255, 180));
        status.setTextSize(12);
        status.setPadding(dp(4), dp(7), dp(4), dp(5));
        status.setText("Ready to load MediaFire songs.");

        root.addView(status);

        countLabel = new TextView(this);
        countLabel.setTextColor(Color.WHITE);
        countLabel.setTextSize(12);
        countLabel.setPadding(dp(4), dp(4), dp(4), dp(8));

        root.addView(countLabel);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        songList = new LinearLayout(this);
        songList.setOrientation(LinearLayout.VERTICAL);

        scroll.addView(songList);

        root.addView(scroll,
                new LinearLayout.LayoutParams(-1, 0, 1f));

        Button back = makeButton("BACK TO STAGE 3 SONGBOOK");
        back.setOnClickListener(v -> finish());

        root.addView(back,
                new LinearLayout.LayoutParams(-1, dp(46)));

        setContentView(root);

        searchInput.addTextChangedListener(
                new android.text.TextWatcher() {

                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start,
                            int count, int after) {
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence s, int start,
                            int before, int count) {
                        renderSongs();
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable s) {
                    }
                });

        renderSongs();
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(20, 125, 65)));
        return button;
    }

    /*
     * LOAD ALL MEDIAFIRE CHUNKS.
     *
     * Important: a chunk with no MIDI/KAR songs does not
     * automatically mean the folder has ended.
     * Use MediaFire's more_chunks value when available.
     */

    private void loadFolder() {

        if (loading || downloading) {
            return;
        }

        loading = true;

        allSongs.clear();
        seenSongs.clear();

        renderSongs();

        loadButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);

        status.setText("Connecting to MediaFire folder API...");

        new Thread(() -> {

            int totalChunks = 0;
            String error = null;

            try {

                for (int chunk = 1;
                     chunk <= MAX_CHUNKS;
                     chunk++) {

                    ChunkResult result = requestChunk(chunk);
                    JSONArray entries = result.files;

                    for (int i = 0; i < entries.length(); i++) {

                        JSONObject item = entries.optJSONObject(i);

                        if (item == null) {
                            continue;
                        }

                        MediaSong song = parseSong(item);

                        if (song != null) {

                            String key = song.quickKey.isEmpty()
                                    ? song.downloadUrl
                                    : song.quickKey;

                            if (seenSongs.add(key)) {
                                allSongs.add(song);
                            }
                        }
                    }

                    totalChunks = chunk;

                    final int currentChunk = chunk;
                    final int found = allSongs.size();

                    runOnUiThread(() -> {

                        status.setText(
                                "Reading MediaFire chunk "
                                        + currentChunk
                                        + "...\nMIDI/KAR found: "
                                        + found);

                        countLabel.setText(
                                "MIDI/KAR SONGS FOUND: " + found);

                        renderSongs();
                    });

                    if (!result.moreChunks || entries.length() == 0) {
                        break;
                    }
                }

            } catch (Exception e) {

                error = e.getMessage() == null
                        ? "Unknown MediaFire API error"
                        : e.getMessage();
            }

            final String finalError = error;
            final int chunksRead = totalChunks;
            final int found = allSongs.size();

            runOnUiThread(() -> {

                loading = false;

                progress.setIndeterminate(false);
                progress.setVisibility(View.GONE);

                loadButton.setEnabled(true);

                if (finalError != null) {

                    status.setText(
                            "MediaFire loading stopped:\n"
                                    + finalError
                                    + "\nSongs found: " + found);

                } else {

                    status.setText(
                            "Folder loading finished.\n"
                                    + "Chunks checked: " + chunksRead
                                    + "\nMIDI/KAR songs: " + found);
                }

                countLabel.setText(
                        "TOTAL MIDI/KAR SONGS: " + found);

                renderSongs();
            });

        }, "Donut4U-MediaFire-API").start();
    }

    private ChunkResult requestChunk(int chunk) throws Exception {

        String query =
                "r=utga"
                + "&content_type=files"
                + "&filter=all"
                + "&order_by=name"
                + "&order_direction=asc"
                + "&chunk=" + chunk
                + "&chunk_size=" + CHUNK_SIZE
                + "&version=1.5"
                + "&folder_key="
                + URLEncoder.encode(FOLDER_KEY, "UTF-8")
                + "&response_format=json";

        URL url = new URL(API_URL + "?" + query);

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        try {

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(45000);
            connection.setInstanceFollowRedirects(true);

            connection.setRequestProperty(
                    "User-Agent",
                    userAgent());

            connection.setRequestProperty(
                    "Referer", FOLDER_URL);

            connection.setRequestProperty(
                    "Accept", "application/json");

            int response = connection.getResponseCode();

            if (response < 200 || response >= 300) {
                throw new Exception(
                        "MediaFire API HTTP " + response
                                + " on chunk " + chunk);
            }

            StringBuilder text = new StringBuilder();

            try (InputStream input =
                         new BufferedInputStream(
                                 connection.getInputStream())) {

                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {

                    text.append(new String(
                            buffer, 0, count, "UTF-8"));

                    if (text.length() > 10 * 1024 * 1024) {
                        throw new Exception(
                                "MediaFire API response is too large.");
                    }
                }
            }

            JSONObject root = new JSONObject(text.toString());

            JSONObject responseObject =
                    root.optJSONObject("response");

            if (responseObject != null) {

                String apiResult = responseObject.optString(
                        "result", "");

                if (!apiResult.isEmpty()
                        && !"Success".equalsIgnoreCase(apiResult)) {

                    throw new Exception(
                            "MediaFire API: " + apiResult);
                }
            }

            JSONArray files = findFileArray(root);

            if (files == null) {
                throw new Exception(
                        "MediaFire returned an unexpected folder response "
                                + "on chunk " + chunk);
            }

            Boolean more = findMoreChunks(root);

            boolean hasMore;

            if (more != null) {
                hasMore = more;
            } else {
                // Fallback if MediaFire omits more_chunks.
                hasMore = files.length() >= CHUNK_SIZE;
            }

            return new ChunkResult(files, hasMore);

        } finally {
            connection.disconnect();
        }
    }

    private JSONArray findFileArray(JSONObject object) {

        if (object == null) {
            return null;
        }

        JSONArray direct = object.optJSONArray("files");

        if (direct != null) {
            return direct;
        }

        for (String key : new String[]{
                "folder_content",
                "response",
                "content",
                "data"
        }) {

            JSONObject nested = object.optJSONObject(key);

            if (nested != null) {

                JSONArray found = findFileArray(nested);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private Boolean findMoreChunks(JSONObject object) {

        if (object == null) {
            return null;
        }

        if (object.has("more_chunks")) {

            Object value = object.opt("more_chunks");

            if (value instanceof Boolean) {
                return (Boolean) value;
            }

            if (value instanceof Number) {
                return ((Number) value).intValue() != 0;
            }

            if (value != null) {

                String text = String.valueOf(value)
                        .trim()
                        .toLowerCase(Locale.ROOT);

                if (text.equals("yes")
                        || text.equals("true")
                        || text.equals("1")) {
                    return true;
                }

                if (text.equals("no")
                        || text.equals("false")
                        || text.equals("0")) {
                    return false;
                }
            }
        }

        for (String key : new String[]{
                "folder_content",
                "response",
                "content",
                "data"
        }) {

            JSONObject nested = object.optJSONObject(key);

            if (nested != null) {

                Boolean found = findMoreChunks(nested);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private MediaSong parseSong(JSONObject item) {

        String fileName = item.optString(
                "filename",
                item.optString("name", ""));

        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        String lower = fileName.toLowerCase(Locale.ROOT);

        if (!(lower.endsWith(".mid")
                || lower.endsWith(".midi")
                || lower.endsWith(".kar"))) {
            return null;
        }

        JSONObject links = item.optJSONObject("links");

        String downloadUrl = "";

        if (links != null) {
            downloadUrl = links.optString(
                    "normal_download",
                    links.optString("download", ""));
        }

        if (downloadUrl.isEmpty()) {
            downloadUrl = item.optString(
                    "normal_download",
                    item.optString("download_url", ""));
        }

        String quickKey = item.optString("quickkey", "");

        if (downloadUrl.isEmpty()) {
            return null;
        }

        return new MediaSong(
                fileName,
                downloadUrl,
                quickKey);
    }

    private void renderSongs() {

        if (songList == null) {
            return;
        }

        songList.removeAllViews();

        String query = searchInput == null
                ? ""
                : searchInput.getText().toString()
                .trim().toLowerCase(Locale.ROOT);

        int shown = 0;

        for (MediaSong song : allSongs) {

            if (!query.isEmpty()
                    && !song.title.toLowerCase(Locale.ROOT)
                    .contains(query)) {
                continue;
            }

            shown++;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackgroundColor(Color.rgb(25, 39, 30));

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(-1, -2);

            cardParams.bottomMargin = dp(7);
            songList.addView(card, cardParams);

            TextView name = new TextView(this);
            name.setText(song.title);
            name.setTextColor(Color.WHITE);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

            card.addView(name);

            TextView type = new TextView(this);
            type.setText("MEDIAFIRE • MIDI/KAR");
            type.setTextColor(Color.rgb(100, 255, 160));
            type.setTextSize(11);
            type.setPadding(0, dp(4), 0, dp(7));

            card.addView(type);

            Button download =
                    makeButton("DOWNLOAD AND ADD TO SONGBOOK");

            download.setOnClickListener(v ->
                    startDownload(song));

            card.addView(download,
                    new LinearLayout.LayoutParams(-1, dp(44)));
        }

        if (countLabel != null) {
            countLabel.setText(
                    "FOUND: " + allSongs.size()
                            + "  |  SHOWING: " + shown);
        }

        if (shown == 0) {

            TextView empty = new TextView(this);

            empty.setText(allSongs.isEmpty()
                    ? "No MIDI/KAR songs loaded yet. Tap LOAD / RELOAD."
                    : "No matching song found.");

            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(25), dp(12), dp(25));

            songList.addView(empty);
        }
    }

    private void startDownload(MediaSong song) {

        if (downloading || loading) {
            Toast.makeText(this,
                    "Please wait for the current operation.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        downloading = true;

        status.setText("Resolving MediaFire download:\n" + song.title);

        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);

        new Thread(() -> {

            File downloaded = null;

            try {

                downloaded = downloadFile(song);

                File finalFile = downloaded;

                String title = removeExtension(song.title);

                String localUri =
                        Uri.fromFile(finalFile).toString();

                String number = registerSong(title, localUri);

                runOnUiThread(() -> {

                    downloading = false;

                    progress.setIndeterminate(false);
                    progress.setVisibility(View.GONE);

                    Stage3SongQueue.initialize(this);

                    Stage3SongQueue.setNowPlaying(
                            new Stage3SongQueue.QueuedSong(
                                    number,
                                    title,
                                    localUri,
                                    "MIDI/KAR"));

                    status.setText(
                            "Downloaded and added:\n" + title);

                    Toast.makeText(
                            this,
                            "Song added to Stage 3 songbook.",
                            Toast.LENGTH_LONG).show();

                    startActivity(new android.content.Intent(
                            this,
                            Stage3PlayerActivity.class));
                });

            } catch (Exception e) {

                if (downloaded != null && downloaded.exists()) {
                    downloaded.delete();
                }

                String message = e.getMessage() == null
                        ? "Unknown download error"
                        : e.getMessage();

                runOnUiThread(() -> {

                    downloading = false;

                    progress.setIndeterminate(false);
                    progress.setVisibility(View.GONE);

                    status.setText(
                            "DOWNLOAD FAILED:\n" + message);
                });
            }

        }, "Donut4U-MediaFire-Download").start();
    }

    /*
     * STEP 1:
     * Open the MediaFire file page.
     *
     * STEP 2:
     * Resolve the actual download URL from:
     * - a redirect to downloadNN.mediafire.com
     * - the downloadButton href
     * - the data-scrambled-url attribute
     *
     * STEP 3:
     * Download and verify the MIDI/KAR bytes.
     */

    private File downloadFile(MediaSong song) throws Exception {

        String directUrl =
                resolveMediaFireDownloadUrl(song.downloadUrl);

        HttpURLConnection connection = null;
        File outputFile = null;

        try {

            URL url = new URL(directUrl);

            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new Exception(
                        "Resolved download link is not HTTPS.");
            }

            if (!isMediaFireHost(url.getHost())) {
                throw new Exception(
                        "Resolved download host is not MediaFire.");
            }

            connection = (HttpURLConnection) url.openConnection();

            connection.setConnectTimeout(25000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);

            connection.setRequestProperty(
                    "User-Agent", userAgent());

            connection.setRequestProperty(
                    "Referer", song.downloadUrl);

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
                        "MediaFire still returned a webpage. "
                                + "The actual file link could not be resolved.");
            }

            File folder = new File(
                    getFilesDir(), "stage3_mediafire");

            if (!folder.exists() && !folder.mkdirs()) {
                throw new Exception(
                        "Could not create Stage 3 download folder.");
            }

            String safeName = song.title.replaceAll(
                    "[^A-Za-z0-9._-]", "_");

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

                    if (total > MAX_DOWNLOAD_SIZE) {
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

    private String resolveMediaFireDownloadUrl(
            String filePageUrl) throws Exception {

        URL pageUrl = new URL(filePageUrl);

        if (!"https".equalsIgnoreCase(pageUrl.getProtocol())
                || !isMediaFireHost(pageUrl.getHost())) {

            throw new Exception(
                    "Invalid MediaFire file page link.");
        }

        HttpURLConnection connection = null;

        try {

            connection =
                    (HttpURLConnection) pageUrl.openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(45000);
            connection.setInstanceFollowRedirects(true);

            connection.setRequestProperty(
                    "User-Agent", userAgent());

            connection.setRequestProperty(
                    "Referer", FOLDER_URL);

            connection.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            int response = connection.getResponseCode();

            if (response < 200 || response >= 400) {
                throw new Exception(
                        "Could not open MediaFire file page. HTTP "
                                + response);
            }

            String finalUrl = connection.getURL().toString();

            // Some MediaFire links redirect straight to the file.
            if (!finalUrl.equals(filePageUrl)
                    && isDirectDownloadHost(new URL(finalUrl).getHost())) {

                return finalUrl;
            }

            String contentType = connection.getContentType();

            if (contentType != null
                    && !contentType.toLowerCase(Locale.ROOT)
                    .contains("text/html")) {

                if (isDirectDownloadHost(new URL(finalUrl).getHost())) {
                    return finalUrl;
                }
            }

            String html = readTextResponse(
                    connection.getInputStream(), 5 * 1024 * 1024);

            String resolved = findDownloadButtonUrl(html);

            if (resolved == null || resolved.trim().isEmpty()) {
                throw new Exception(
                        "MediaFire file page opened, but its actual "
                                + "download button link was not found.");
            }

            URL resolvedUrl = new URL(resolved);

            if (!"https".equalsIgnoreCase(resolvedUrl.getProtocol())
                    || !isMediaFireHost(resolvedUrl.getHost())) {

                throw new Exception(
                        "MediaFire supplied an invalid download address.");
            }

            return resolvedUrl.toString();

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String findDownloadButtonUrl(String html) {

        if (html == null || html.isEmpty()) {
            return null;
        }

        Pattern anchorPattern = Pattern.compile(
                "(?is)<a\\b[^>]*>");

        Matcher anchorMatcher =
                anchorPattern.matcher(html);

        while (anchorMatcher.find()) {

            String tag = anchorMatcher.group();

            if (!Pattern.compile(
                    "(?i)\\bid\\s*=\\s*['\"]downloadButton['\"]")
                    .matcher(tag).find()) {
                continue;
            }

            String href = getHtmlAttribute(tag, "href");

            if (href != null && !href.isEmpty()) {
                return cleanUrl(href);
            }

            String scrambled =
                    getHtmlAttribute(tag, "data-scrambled-url");

            if (scrambled != null && !scrambled.isEmpty()) {

                try {

                    byte[] decoded = Base64.decode(
                            scrambled,
                            Base64.DEFAULT);

                    String decodedUrl =
                            new String(decoded, "UTF-8").trim();

                    if (decodedUrl.startsWith("http")) {
                        return cleanUrl(decodedUrl);
                    }

                } catch (Exception ignored) {
                }
            }
        }

        // Fallback: look for the download button URL
        // even if MediaFire changes the order of its attributes.
        Pattern fallback = Pattern.compile(
                "(?is)<a\\b(?=[^>]*\\bdownloadButton\\b)[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"]");

        Matcher fallbackMatcher = fallback.matcher(html);

        if (fallbackMatcher.find()) {
            return cleanUrl(fallbackMatcher.group(1));
        }

        // Last fallback for MediaFire's encoded URL attribute.
        Pattern scrambledPattern = Pattern.compile(
                "(?is)data-scrambled-url\\s*=\\s*['\"]([^'\"]+)['\"]");

        Matcher scrambledMatcher =
                scrambledPattern.matcher(html);

        while (scrambledMatcher.find()) {

            try {

                byte[] decoded = Base64.decode(
                        scrambledMatcher.group(1),
                        Base64.DEFAULT);

                String decodedUrl =
                        new String(decoded, "UTF-8").trim();

                if (decodedUrl.startsWith("https://")) {
                    return cleanUrl(decodedUrl);
                }

            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String getHtmlAttribute(String tag, String attribute) {

        Pattern pattern = Pattern.compile(
                "(?is)\\b" + Pattern.quote(attribute)
                        + "\\s*=\\s*(['\"])(.*?)\\1");

        Matcher matcher = pattern.matcher(tag);

        if (matcher.find()) {
            return matcher.group(2);
        }

        return null;
    }

    private String cleanUrl(String value) {

        if (value == null) {
            return null;
        }

        String result = value.trim();

        result = result.replace("&amp;", "&");
        result = result.replace("&#38;", "&");
        result = result.replace("&quot;", "\"");
        result = result.replace("&#x2F;", "/");
        result = result.replace("\\/", "/");

        return result;
    }

    private String readTextResponse(
            InputStream input, int maxBytes) throws Exception {

        try (InputStream in = new BufferedInputStream(input);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int count;
            int total = 0;

            while ((count = in.read(buffer)) != -1) {

                total += count;

                if (total > maxBytes) {
                    throw new Exception(
                            "MediaFire download page is too large.");
                }

                output.write(buffer, 0, count);
            }

            return output.toString("UTF-8");
        }
    }

    private boolean isMediaFireHost(String host) {

        if (host == null) {
            return false;
        }

        String value = host.toLowerCase(Locale.ROOT);

        return value.equals("mediafire.com")
                || value.endsWith(".mediafire.com");
    }

    private boolean isDirectDownloadHost(String host) {

        if (host == null) {
            return false;
        }

        String value = host.toLowerCase(Locale.ROOT);

        return value.matches("download[0-9]+\\.mediafire\\.com")
                || value.endsWith(".mediafire.com");
    }

    private String userAgent() {
        return "Mozilla/5.0 (Linux; Android 13) "
                + "AppleWebKit/537.36 "
                + "(KHTML, like Gecko) "
                + "Chrome/120.0 Mobile Safari/537.36";
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

    private String removeExtension(String name) {

        int dot = name.lastIndexOf('.');

        return dot > 0
                ? name.substring(0, dot)
                : name;
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

    @Override
    public void onBackPressed() {

        if (loading || downloading) {

            Toast.makeText(this,
                    "Please wait for the current operation.",
                    Toast.LENGTH_SHORT).show();

            return;
        }

        super.onBackPressed();
    }
}
