
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
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

    private static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final int CHUNK_SIZE = 100;
    private static final int MAX_CHUNKS = 100;

    private static final int PAGE_SIZE = 50;

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
    private Button loadMoreButton;

    private boolean tvMode = false;
    private volatile boolean activityDestroyed = false;

    private TvKeyboardReceiver tvKeyboardReceiver;
    private TextView tvKeyboardCodeText;
    private TextView tvKeyboardStatusText;

    private volatile boolean loading = false;
    private volatile boolean downloading = false;

    private int visibleLimit = PAGE_SIZE;

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

        tvMode = getIntent().getBooleanExtra(
                EXTRA_TV_MODE, false);

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

        if (tvMode) {
            buildTvPairingPanel(root);
        }

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

        loadMoreButton = makeButton("LOAD MORE SONGS");
        loadMoreButton.setVisibility(View.GONE);
        loadMoreButton.setOnClickListener(v -> {
            visibleLimit += PAGE_SIZE;
            renderSongs();
        });

        root.addView(loadMoreButton,
                new LinearLayout.LayoutParams(-1, dp(44)));

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
                        visibleLimit = PAGE_SIZE;
                        renderSongs();
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable s) {
                    }
                });

        renderSongs();
    }

    private void buildTvPairingPanel(LinearLayout root) {

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(10), dp(7), dp(10), dp(7));
        panel.setBackgroundColor(Color.rgb(18, 55, 35));

        TextView heading = new TextView(this);
        heading.setText("PHONE KEYBOARD CONNECTION");
        heading.setTextColor(Color.rgb(100, 255, 160));
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);

        panel.addView(heading,
                new LinearLayout.LayoutParams(-1, dp(25)));

        TextView instruction = new TextView(this);
        instruction.setText(
                "Open Donut4U Phone Remote and enter this TV code:");
        instruction.setTextColor(Color.WHITE);
        instruction.setTextSize(11);
        instruction.setGravity(Gravity.CENTER);

        panel.addView(instruction);

        tvKeyboardCodeText = new TextView(this);
        tvKeyboardCodeText.setText("------");
        tvKeyboardCodeText.setTextColor(Color.rgb(100, 255, 160));
        tvKeyboardCodeText.setTextSize(28);
        tvKeyboardCodeText.setTypeface(
                Typeface.DEFAULT, Typeface.BOLD);
        tvKeyboardCodeText.setGravity(Gravity.CENTER);
        tvKeyboardCodeText.setPadding(0, dp(3), 0, dp(3));

        panel.addView(tvKeyboardCodeText,
                new LinearLayout.LayoutParams(-1, dp(42)));

        tvKeyboardStatusText = new TextView(this);
        tvKeyboardStatusText.setText(
                "Preparing phone keyboard connection...");
        tvKeyboardStatusText.setTextColor(Color.WHITE);
        tvKeyboardStatusText.setTextSize(11);
        tvKeyboardStatusText.setGravity(Gravity.CENTER);

        panel.addView(tvKeyboardStatusText);

        LinearLayout.LayoutParams panelParams =
                new LinearLayout.LayoutParams(-1, -2);

        panelParams.bottomMargin = dp(7);

        root.addView(panel, panelParams);
    }

    private void startTvKeyboardReceiver() {

        if (!tvMode || activityDestroyed
                || tvKeyboardReceiver != null
                || tvKeyboardCodeText == null
                || tvKeyboardStatusText == null) {
            return;
        }

        tvKeyboardStatusText.setText(
                "Starting phone keyboard connection...");

        tvKeyboardReceiver = new TvKeyboardReceiver(
                this,
                new TvKeyboardReceiver.Listener() {

                    @Override
                    public void onPairingCodeReady(String code) {
                        if (activityDestroyed
                                || tvKeyboardCodeText == null) {
                            return;
                        }

                        tvKeyboardCodeText.setText(
                                code == null || code.isEmpty()
                                        ? "------" : code);
                    }

                    @Override
                    public void onSearchRequested(String songTitle) {
                        if (activityDestroyed || searchInput == null) {
                            return;
                        }

                        String text = songTitle == null
                                ? "" : songTitle.trim();

                        searchInput.setText(text);
                        searchInput.setSelection(
                                searchInput.getText().length());
                    }

                    @Override
                    public void onTextInput(
                            String target,
                            String text,
                            String action
                    ) {
                        if (activityDestroyed) {
                            return;
                        }

                        applyTvTextInput(target, text, action);
                    }

                    @Override
                    public void onStatusChanged(String message) {
                        if (activityDestroyed
                                || tvKeyboardStatusText == null) {
                            return;
                        }

                        tvKeyboardStatusText.setText(message);
                    }

                    @Override
                    public void onError(String message) {
                        if (activityDestroyed
                                || tvKeyboardStatusText == null) {
                            return;
                        }

                        tvKeyboardStatusText.setText(
                                "Connection error: " + message);

                        android.util.Log.e(
                                "Stage3MediaFire",
                                "Phone keyboard receiver: " + message);
                    }
                });

        try {
            tvKeyboardReceiver.start();

            String currentCode =
                    TvKeyboardReceiver.getCurrentPairingCode();

            if (currentCode != null && !currentCode.isEmpty()) {
                tvKeyboardCodeText.setText(currentCode);
            }

        } catch (Exception e) {
            android.util.Log.e(
                    "Stage3MediaFire",
                    "Could not start phone keyboard receiver.", e);

            tvKeyboardStatusText.setText(
                    "Could not start phone keyboard connection.");

            tvKeyboardReceiver = null;
        }
    }

    private void applyTvTextInput(
            String target,
            String text,
            String action
    ) {
        if (activityDestroyed || !tvMode || searchInput == null) {
            return;
        }

        String inputTarget = target == null
                ? "" : target.trim().toLowerCase(Locale.ROOT);

        if (!inputTarget.isEmpty()
                && !"active".equals(inputTarget)
                && !"search".equals(inputTarget)
                && !"searchinput".equals(inputTarget)) {
            return;
        }

        String inputAction = action == null
                ? "" : action.trim().toLowerCase(Locale.ROOT);

        String value = text == null ? "" : text;

        searchInput.requestFocus();

        android.text.Editable editable = searchInput.getText();

        int start = searchInput.getSelectionStart();
        int end = searchInput.getSelectionEnd();

        if (start < 0) {
            start = editable.length();
        }

        if (end < 0) {
            end = start;
        }

        start = Math.min(start, editable.length());
        end = Math.min(end, editable.length());

        if (start > end) {
            int temporary = start;
            start = end;
            end = temporary;
        }

        switch (inputAction) {
            case "replace":
                searchInput.setText(value);
                searchInput.setSelection(
                        searchInput.getText().length());
                break;

            case "append":
                editable.replace(start, end, value);
                searchInput.setSelection(start + value.length());
                break;

            case "backspace":
                if (start != end) {
                    editable.delete(start, end);
                    searchInput.setSelection(start);
                } else if (start > 0) {
                    int deleteStart = start - 1;

                    if (deleteStart > 0
                            && Character.isLowSurrogate(
                                    editable.charAt(deleteStart))
                            && Character.isHighSurrogate(
                                    editable.charAt(deleteStart - 1))) {
                        deleteStart--;
                    }

                    editable.delete(deleteStart, start);
                    searchInput.setSelection(deleteStart);
                }
                break;

            case "clear":
                searchInput.setText("");
                searchInput.setSelection(0);
                break;

            case "submit":
                renderSongs();
                searchInput.setSelection(
                        searchInput.getText().length());
                break;

            default:
                android.util.Log.w(
                        "Stage3MediaFire",
                        "Ignored unknown phone keyboard action: "
                                + inputAction);
                return;
        }
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
     * LOAD MEDIAFIRE FOLDER CHUNKS.
     * Song data is collected in the background.
     * The UI list is rendered only after loading finishes.
     */

    private void loadFolder() {

        if (loading || downloading) {
            return;
        }

        loading = true;
        visibleLimit = PAGE_SIZE;

        allSongs.clear();
        seenSongs.clear();

        songList.removeAllViews();
        loadMoreButton.setVisibility(View.GONE);

        loadButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);

        status.setText("Connecting to MediaFire folder API...");
        countLabel.setText("Loading MIDI/KAR song list...");

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

                        if (isFinishing() || activityDestroyed) {
                            return;
                        }

                        status.setText(
                                "Reading MediaFire chunk "
                                        + currentChunk
                                        + "...\nMIDI/KAR found: "
                                        + found);

                        countLabel.setText(
                                "LOADING SONGS: " + found
                                        + "\nThe song list will appear "
                                        + "when loading finishes.");
                    });

                    if (!result.moreChunks) {
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

                if (activityDestroyed) {
                    return;
                }

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
                    "User-Agent", userAgent());

            connection.setRequestProperty(
                    "Referer", FOLDER_URL);

            connection.setRequestProperty(
                    "Accept", "application/json");

            connection.setRequestProperty(
                    "Accept-Encoding", "identity");

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
                cleanUrl(downloadUrl),
                quickKey);
    }

    /*
     * PAGINATED RENDERING:
     * At most PAGE_SIZE song cards are added per render.
     * Search still checks every loaded song.
     */

    private void renderSongs() {

        if (songList == null || activityDestroyed) {
            return;
        }

        songList.removeAllViews();

        String query = searchInput == null
                ? ""
                : searchInput.getText().toString()
                .trim().toLowerCase(Locale.ROOT);

        int totalMatches = 0;
        int rendered = 0;

        for (MediaSong song : allSongs) {

            if (!query.isEmpty()
                    && !song.title.toLowerCase(Locale.ROOT)
                    .contains(query)) {
                continue;
            }

            totalMatches++;

            if (rendered >= visibleLimit) {
                continue;
            }

            rendered++;

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
                    "FOUND: " + totalMatches
                            + "  |  SHOWING: " + rendered);
        }

        if (loadMoreButton != null) {

            if (totalMatches > rendered) {
                loadMoreButton.setVisibility(View.VISIBLE);
                loadMoreButton.setText(
                        "LOAD MORE SONGS ("
                                + (totalMatches - rendered)
                                + " REMAINING)");
            } else {
                loadMoreButton.setVisibility(View.GONE);
            }
        }

        if (rendered == 0) {

            TextView empty = new TextView(this);

            empty.setText(allSongs.isEmpty()
                    ? (loading
                        ? "Loading MediaFire song list..."
                        : "No MIDI/KAR songs loaded yet. Tap LOAD / RELOAD.")
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

                    if (activityDestroyed) {
                        return;
                    }

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

                    startActivity(new Intent(
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

                    if (activityDestroyed) {
                        return;
                    }

                    progress.setIndeterminate(false);
                    progress.setVisibility(View.GONE);

                    status.setText(
                            "DOWNLOAD FAILED:\n" + message);
                });
            }

        }, "Donut4U-MediaFire-Download").start();
    }

    private File downloadFile(MediaSong song) throws Exception {

        String directUrl =
                resolveMediaFireDownloadUrl(song.downloadUrl);

        URL url = new URL(directUrl);

        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new Exception(
                    "Resolved download link is not HTTPS.");
        }

        if (!isDirectDownloadHost(url.getHost())) {
            throw new Exception(
                    "MediaFire did not provide a direct download server.");
        }

        HttpURLConnection connection = null;
        File outputFile = null;

        try {

            connection = (HttpURLConnection) url.openConnection();

            connection.setConnectTimeout(25000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);

            connection.setRequestProperty(
                    "User-Agent", userAgent());

            connection.setRequestProperty(
                    "Referer", song.downloadUrl);

            connection.setRequestProperty(
                    "Accept-Encoding", "identity");

            connection.connect();

            int response = connection.getResponseCode();

            if (response < 200 || response >= 300) {
                throw new Exception(
                        "File download returned HTTP " + response);
            }

            URL finalUrl = connection.getURL();

            if (!isMediaFireHost(finalUrl.getHost())) {
                throw new Exception(
                        "Download redirected to an unexpected host.");
            }

            String contentType = connection.getContentType();

            if (contentType != null
                    && contentType.toLowerCase(Locale.ROOT)
                    .contains("text/html")) {

                throw new Exception(
                        "MediaFire returned a webpage instead of MIDI/KAR.");
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

        return resolveMediaFireDownloadUrl(filePageUrl, 0);
    }

    private String resolveMediaFireDownloadUrl(
            String filePageUrl, int depth) throws Exception {

        if (depth > 3) {
            throw new Exception(
                    "MediaFire download link resolution exceeded its limit.");
        }

        URL pageUrl = new URL(filePageUrl);

        if (!"https".equalsIgnoreCase(pageUrl.getProtocol())
                || !isMediaFireHost(pageUrl.getHost())) {

            throw new Exception(
                    "Invalid MediaFire file page link.");
        }

        if (isDirectDownloadHost(pageUrl.getHost())) {
            return pageUrl.toString();
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

            connection.setRequestProperty(
                    "Accept-Encoding", "identity");

            int response = connection.getResponseCode();

            if (response < 200 || response >= 400) {
                throw new Exception(
                        "Could not open MediaFire file page. HTTP "
                                + response);
            }

            String finalUrl = connection.getURL().toString();
            URL finalPage = new URL(finalUrl);

            if (isDirectDownloadHost(finalPage.getHost())) {
                return finalUrl;
            }

            String contentType = connection.getContentType();

            if (contentType != null
                    && !contentType.toLowerCase(Locale.ROOT)
                    .contains("text/html")) {

                throw new Exception(
                        "MediaFire response was not a download page. "
                                + "Content type: " + contentType);
            }

            String html = readTextResponse(
                    connection.getInputStream(),
                    5 * 1024 * 1024);

            String resolved = findDownloadButtonUrl(html);

            if (resolved == null || resolved.trim().isEmpty()) {

                throw new Exception(
                        "MediaFire download link not found. "
                                + "Page host: " + finalPage.getHost()
                                + "; HTML size: " + html.length()
                                + "; HTTP: " + response);
            }

            URL resolvedUrl = new URL(finalPage, resolved);

            if (!"https".equalsIgnoreCase(
                    resolvedUrl.getProtocol())) {

                throw new Exception(
                        "MediaFire supplied a non-HTTPS download address.");
            }

            if (!isMediaFireHost(resolvedUrl.getHost())) {
                throw new Exception(
                        "MediaFire supplied an unexpected download host.");
            }

            if (isDirectDownloadHost(resolvedUrl.getHost())) {
                return resolvedUrl.toString();
            }

            return resolveMediaFireDownloadUrl(
                    resolvedUrl.toString(),
                    depth + 1);

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

        String source = html
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\x26", "&")
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&#x26;", "&");

        Pattern directPattern = Pattern.compile(
                "(?i)(?:https?:)?//download[0-9]+\\.mediafire\\.com/[^\\s\"'<>\\\\]+");

        Matcher directMatcher = directPattern.matcher(source);

        if (directMatcher.find()) {

            String found = directMatcher.group();

            if (found.startsWith("//")) {
                found = "https:" + found;
            }

            return cleanUrl(found);
        }

        Pattern prePattern = Pattern.compile(
                "(?i)(?:https?:)?//(?:www\\.)?mediafire\\.com/"
                        + "(?:file|view|download)/[^\\s\"'<>\\\\]+"
                        + "[?&][^\\s\"'<>\\\\]*dkey=[^\\s\"'<>\\\\]+");

        Matcher preMatcher = prePattern.matcher(source);

        if (preMatcher.find()) {

            String found = preMatcher.group();

            if (found.startsWith("//")) {
                found = "https:" + found;
            }

            return cleanUrl(found);
        }

        Pattern anchorPattern = Pattern.compile(
                "(?is)<a\\b[^>]*>");

        Matcher anchorMatcher = anchorPattern.matcher(source);

        while (anchorMatcher.find()) {

            String tag = anchorMatcher.group();

            boolean isDownloadButton =
                    Pattern.compile(
                            "(?i)\\bid\\s*=\\s*['\"]downloadButton['\"]")
                            .matcher(tag).find();

            if (!isDownloadButton) {
                continue;
            }

            String href = getHtmlAttribute(tag, "href");

            if (href != null && !href.isEmpty()) {
                return cleanUrl(href);
            }

            String scrambled =
                    getHtmlAttribute(tag, "data-scrambled-url");

            String decoded = decodeScrambledUrl(scrambled);

            if (decoded != null) {
                return decoded;
            }
        }

        Pattern scrambledPattern = Pattern.compile(
                "(?is)data-scrambled-url\\s*=\\s*['\"]([^'\"]+)['\"]");

        Matcher scrambledMatcher =
                scrambledPattern.matcher(source);

        while (scrambledMatcher.find()) {

            String decoded = decodeScrambledUrl(
                    scrambledMatcher.group(1));

            if (decoded != null) {
                return decoded;
            }
        }

        Pattern fallback = Pattern.compile(
                "(?is)<a\\b(?=[^>]*downloadButton)[^>]*"
                        + "href\\s*=\\s*['\"]([^'\"]+)['\"]");

        Matcher fallbackMatcher = fallback.matcher(source);

        if (fallbackMatcher.find()) {
            return cleanUrl(fallbackMatcher.group(1));
        }

        return null;
    }

    private String decodeScrambledUrl(String scrambled) {

        if (scrambled == null || scrambled.trim().isEmpty()) {
            return null;
        }

        try {

            byte[] decodedBytes = Base64.decode(
                    scrambled,
                    Base64.DEFAULT);

            String decoded = new String(
                    decodedBytes, "UTF-8").trim();

            decoded = cleanUrl(decoded);

            if (decoded.startsWith("https://")
                    || decoded.startsWith("//")) {

                if (decoded.startsWith("//")) {
                    decoded = "https:" + decoded;
                }

                return decoded;
            }

        } catch (Exception ignored) {
        }

        try {

            byte[] decodedBytes = Base64.decode(
                    scrambled,
                    Base64.URL_SAFE | Base64.NO_WRAP);

            String decoded = new String(
                    decodedBytes, "UTF-8").trim();

            decoded = cleanUrl(decoded);

            if (decoded.startsWith("https://")
                    || decoded.startsWith("//")) {

                if (decoded.startsWith("//")) {
                    decoded = "https:" + decoded;
                }

                return decoded;
            }

        } catch (Exception ignored) {
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
        result = result.replace("&#x26;", "&");
        result = result.replace("&quot;", "\"");
        result = result.replace("&#x2F;", "/");
        result = result.replace("\\/", "/");
        result = result.replace("\\u0026", "&");
        result = result.replace("\\x26", "&");

        while (result.endsWith(")")
                || result.endsWith(",")
                || result.endsWith(";")) {

            result = result.substring(0, result.length() - 1);
        }

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

        return value.matches(
                "download[0-9]+\\.mediafire\\.com");
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
    protected void onResume() {
        super.onResume();

        if (tvMode) {
            startTvKeyboardReceiver();
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;

        // TvKeyboardReceiver is application-wide.
        // Do not disable the shared TV session when changing screens.
        tvKeyboardReceiver = null;

        super.onDestroy();
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
