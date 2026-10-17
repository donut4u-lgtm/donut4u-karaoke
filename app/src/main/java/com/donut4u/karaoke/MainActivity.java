
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private LinearLayout root;
    private EditText searchBox;
    private WebView searchWebView;
    private WebView playerWebView;
    private LinearLayout lyricsContainer;
    private ScrollView lyricsScroll;

    private final ArrayList<LyricLine> lyrics = new ArrayList<>();
    private final ArrayList<TextView> lyricViews = new ArrayList<>();

    private int activeLyric = -1;
    private int lyricRequestId = 0;
    private String currentVideoId = "";
    private boolean playerReady = false;

    private final int GREEN = Color.rgb(20, 145, 75);
    private final int DARK = Color.rgb(25, 25, 25);
    private final int WHITE = Color.WHITE;
    private final int YELLOW = Color.rgb(255, 220, 50);

    private static class LyricLine {
        long time;
        String text;

        LyricLine(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildLayout();
        setupSearchWebView();
        setupPlayerWebView();
        loadLyrics();
    }

    private void buildLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(WHITE);
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(12));
        root.addView(title);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Search song on YouTube");
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(Color.LTGRAY);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);

        searchRow.addView(searchBox,
                new LinearLayout.LayoutParams(0, dp(50), 1));

        Button searchButton = new Button(this);
        searchButton.setText("SEARCH");
        searchButton.setTextColor(WHITE);
        searchButton.setBackgroundColor(GREEN);

        searchRow.addView(searchButton,
                new LinearLayout.LayoutParams(dp(105), dp(50)));

        root.addView(searchRow);

        searchButton.setOnClickListener(v -> searchSongs());

        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null &&
                            event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                searchSongs();
                return true;
            }
            return false;
        });

        TextView searchLabel = new TextView(this);
        searchLabel.setText("SONG SEARCH — tap a video to play");
        searchLabel.setTextColor(YELLOW);
        searchLabel.setTextSize(13);
        searchLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(searchLabel);

        searchWebView = new WebView(this);
        root.addView(searchWebView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        TextView playerLabel = new TextView(this);
        playerLabel.setText("KARAOKE PLAYER");
        playerLabel.setTextColor(YELLOW);
        playerLabel.setTextSize(15);
        playerLabel.setTypeface(null, Typeface.BOLD);
        playerLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(playerLabel);

        playerWebView = new WebView(this);
        root.addView(playerWebView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        TextView lyricsLabel = new TextView(this);
        lyricsLabel.setText("SYNCHRONIZED LYRICS");
        lyricsLabel.setTextColor(YELLOW);
        lyricsLabel.setTextSize(16);
        lyricsLabel.setTypeface(null, Typeface.BOLD);
        lyricsLabel.setGravity(Gravity.CENTER);
        lyricsLabel.setPadding(0, dp(10), 0, dp(6));
        root.addView(lyricsLabel);

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setBackgroundColor(Color.rgb(15, 15, 15));

        lyricsContainer = new LinearLayout(this);
        lyricsContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsContainer.setPadding(dp(8), dp(10), dp(8), dp(10));

        lyricsScroll.addView(lyricsContainer);

        root.addView(lyricsScroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void setupSearchWebView() {
        WebSettings settings = searchWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);

        searchWebView.setWebChromeClient(new WebChromeClient());

        searchWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, String url) {

                String videoId = extractVideoId(url);

                if (!videoId.isEmpty()) {
                    playVideo(videoId);
                    return true;
                }

                if (url.contains("youtube.com/results") ||
                        url.contains("m.youtube.com/results")) {
                    view.loadUrl(url);
                    return true;
                }

                return false;
            }
        });

        searchWebView.loadUrl("https://m.youtube.com");
    }

    private void setupPlayerWebView() {
        WebSettings settings = playerWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        playerWebView.setWebChromeClient(new WebChromeClient());
        playerWebView.addJavascriptInterface(
                new PlayerBridge(), "AndroidPlayer");
    }

    private void searchSongs() {
        String query = searchBox.getText().toString().trim();

        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a song title first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            InputMethodManager imm =
                    (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);

            if (imm != null) {
                imm.hideSoftInputFromWindow(
                        searchBox.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }

        String url = "https://m.youtube.com/results?search_query="
                + android.net.Uri.encode(query);

        searchWebView.loadUrl(url);
    }

    private String extractVideoId(String url) {
        if (url == null) return "";

        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();

            if (host == null) return "";

            if (host.equalsIgnoreCase("youtu.be")) {
                String path = uri.getLastPathSegment();
                return validVideoId(path) ? path : "";
            }

            if (host.contains("youtube.com")) {
                String id = uri.getQueryParameter("v");

                if (validVideoId(id)) return id;

                String path = uri.getPath();

                if (path != null && path.startsWith("/shorts/")) {
                    String[] parts = path.split("/");
                    if (parts.length > 2 && validVideoId(parts[2])) {
                        return parts[2];
                    }
                }

                if (path != null && path.startsWith("/embed/")) {
                    String[] parts = path.split("/");
                    if (parts.length > 2 && validVideoId(parts[2])) {
                        return parts[2];
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    private boolean validVideoId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }

    private void playVideo(String videoId) {
        if (!validVideoId(videoId)) {
            Toast.makeText(this, "Unable to identify this video.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        currentVideoId = videoId;
        playerReady = false;

        String query = searchBox.getText().toString().trim();
        if (!query.isEmpty()) {
            searchTimedLyrics(query);
        }

        String html =
                "<!DOCTYPE html><html><head>" +
                "<meta name='viewport' content='width=device-width," +
                " initial-scale=1'>" +
                "<style>html,body{margin:0;padding:0;background:#000;}" +
                "#player{width:100%;height:100%;}</style>" +
                "</head><body>" +
                "<div id='player'></div>" +
                "<script>" +
                "var player;" +
                "function onYouTubeIframeAPIReady(){" +
                "player=new YT.Player('player',{" +
                "videoId:'" + videoId + "'," +
                "playerVars:{playsinline:1,rel:0}," +
                "events:{" +
                "onReady:function(e){" +
                "AndroidPlayer.onReady();" +
                "setInterval(function(){" +
                "try{" +
                "AndroidPlayer.onTime(e.target.getCurrentTime());" +
                "}catch(err){}" +
                "},250);" +
                "}" +
                "}" +
                "});" +
                "}" +
                "</script>" +
                "<script src='https://www.youtube.com/iframe_api'></script>" +
                "</body></html>";

        playerWebView.loadDataWithBaseURL(
                "https://www.youtube.com",
                html, "text/html", "UTF-8", null);
    }

    private class PlayerBridge {
        @JavascriptInterface
        public void onReady() {
            runOnUiThread(() -> playerReady = true);
        }

        @JavascriptInterface
        public void onTime(double seconds) {
            runOnUiThread(() -> updateLyrics(seconds));
        }
    }

    private void searchTimedLyrics(String query) {
        final int requestId = ++lyricRequestId;

        lyrics.clear();
        displayMessage("Searching for synchronized lyrics...");

        new Thread(() -> {
            ArrayList<LyricLine> foundLyrics = new ArrayList<>();

            HttpURLConnection connection = null;

            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                URL url = new URL(
                        "https://lrclib.net/api/search?q=" + encoded);

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty(
                        "User-Agent",
                        "Donut4U-Karaoke/1.0");

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream();

                    byte[] buffer = new byte[4096];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }

                    input.close();

                    String response = new String(
                            output.toByteArray(), StandardCharsets.UTF_8);

                    JSONArray results = new JSONArray(response);

                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        String synced = item.optString("syncedLyrics", "");

                        if (synced != null && !synced.trim().isEmpty()) {
                            foundLyrics = parseLrcText(synced);

                            if (!foundLyrics.isEmpty()) {
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // A network failure is handled by the message below.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final ArrayList<LyricLine> result = foundLyrics;

            runOnUiThread(() -> {
                if (requestId != lyricRequestId) return;

                lyrics.clear();
                lyrics.addAll(result);

                if (lyrics.isEmpty()) {
                    displayMessage(
                            "No synchronized lyrics found for this search.\n\n"
                            + "Try searching with the song title and artist.\n\n"
                            + "Not every song has timed lyrics available.");
                } else {
                    displayLyrics();
                    Toast.makeText(MainActivity.this,
                            "Synchronized lyrics loaded.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private ArrayList<LyricLine> parseLrcText(String lrcText) {
        ArrayList<LyricLine> parsed = new ArrayList<>();

        Pattern pattern = Pattern.compile(
                "\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\](.*)");

        String[] lines = lrcText.split("\\r?\\n");

        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);

            if (!matcher.matches()) continue;

            try {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));

                String fraction = matcher.group(3);
                long milliseconds = 0;

                if (fraction != null) {
                    if (fraction.length() == 1) {
                        milliseconds = Long.parseLong(fraction) * 100;
                    } else if (fraction.length() == 2) {
                        milliseconds = Long.parseLong(fraction) * 10;
                    } else {
                        milliseconds = Long.parseLong(fraction);
                    }
                }

                long time = minutes * 60000 + seconds * 1000
                        + milliseconds;

                String text = matcher.group(4).trim();

                if (!text.isEmpty()) {
                    parsed.add(new LyricLine(time, text));
                }
            } catch (Exception ignored) {
            }
        }

        Collections.sort(parsed,
                Comparator.comparingLong(item -> item.time));

        return parsed;
    }

    private void loadLyrics() {
        lyrics.clear();

        try {
            InputStream input =
                    getAssets().open("lyrics/default.lrc");

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));

            StringBuilder content = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }

            reader.close();
            lyrics.addAll(parseLrcText(content.toString()));

        } catch (Exception ignored) {
            lyrics.clear();
        }

        displayLyrics();
    }

    private void displayMessage(String message) {
        lyricsContainer.removeAllViews();
        lyricViews.clear();
        activeLyric = -1;

        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(WHITE);
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(12), dp(25), dp(12), dp(25));

        lyricsContainer.addView(text,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void displayLyrics() {
        lyricsContainer.removeAllViews();
        lyricViews.clear();
        activeLyric = -1;

        if (lyrics.isEmpty()) {
            displayMessage(
                    "No timed lyrics found.\n\n"
                    + "Play a song to search online for synchronized lyrics.");
            return;
        }

        for (LyricLine lyric : lyrics) {
            TextView text = new TextView(this);
            text.setText(lyric.text);
            text.setTextColor(Color.LTGRAY);
            text.setTextSize(18);
            text.setGravity(Gravity.CENTER);
            text.setPadding(dp(5), dp(12), dp(5), dp(12));

            lyricsContainer.addView(text,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            lyricViews.add(text);
        }
    }

    private void updateLyrics(double seconds) {
        if (lyrics.isEmpty() || lyricViews.isEmpty()) return;

        long currentTime = (long) (seconds * 1000);
        int found = -1;

        for (int i = 0; i < lyrics.size(); i++) {
            if (currentTime >= lyrics.get(i).time) {
                found = i;
            } else {
                break;
            }
        }

        if (found == activeLyric) return;

        activeLyric = found;

        for (int i = 0; i < lyricViews.size(); i++) {
            TextView text = lyricViews.get(i);

            if (i == activeLyric) {
                text.setTextColor(YELLOW);
                text.setTextSize(23);
                text.setTypeface(null, Typeface.BOLD);
            } else {
                text.setTextColor(Color.LTGRAY);
                text.setTextSize(18);
                text.setTypeface(null, Typeface.NORMAL);
            }
        }

        if (activeLyric >= 0) {
            final View activeView = lyricViews.get(activeLyric);

            lyricsScroll.post(() -> {
                int target = activeView.getTop()
                        - lyricsScroll.getHeight() / 2
                        + activeView.getHeight() / 2;

                lyricsScroll.smoothScrollTo(0, Math.max(0, target));
            });
        }
    }

    private int dp(int value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (searchWebView != null && searchWebView.canGoBack()) {
            searchWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        lyricRequestId++;

        if (searchWebView != null) {
            searchWebView.destroy();
        }

        if (playerWebView != null) {
            playerWebView.destroy();
        }

        super.onDestroy();
    }
}
