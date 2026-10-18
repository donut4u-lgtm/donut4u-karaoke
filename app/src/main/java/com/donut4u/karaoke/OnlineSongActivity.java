
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OnlineSongActivity extends Activity {

    private static final int GREEN = Color.rgb(0, 190, 95);
    private static final int BG = Color.rgb(15, 18, 20);
    private static final int PANEL = Color.rgb(30, 35, 38);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.LTGRAY;

    private EditText searchBox;
    private Button searchButton;
    private TextView status;
    private LinearLayout resultsContainer;

    private View searchScreen;
    private LinearLayout playbackScreen;
    private FrameLayout playerHost;
    private WebView player;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private volatile boolean closing = false;
    private int searchGeneration = 0;

    private static class Video {
        String id;
        String title;
        String channel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        buildSearchScreen();

        String initialQuery =
                getIntent().getStringExtra("search_query");

        if (initialQuery != null && !initialQuery.trim().isEmpty()) {
            searchBox.setText(initialQuery);
            searchVideos();
        }
    }

    private void buildSearchScreen() {
        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        outer.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(20));

        outer.addView(root);

        TextView heading = new TextView(this);
        heading.setText("Donut4U Karaoke");
        heading.setTextColor(GREEN);
        heading.setTextSize(26);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(8), 0, dp(20));

        root.addView(heading, fullWidthWrap());

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Song title or artist");
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        styleEdit(searchBox);

        root.addView(searchBox, fullWidth(dp(52)));

        searchButton = makeButton("Search");

        LinearLayout.LayoutParams searchParams =
                fullWidth(dp(50));
        searchParams.topMargin = dp(10);

        root.addView(searchButton, searchParams);

        searchButton.setOnClickListener(v -> searchVideos());

        status = new TextView(this);
        status.setText("Search for a song to see karaoke videos.");
        status.setTextColor(MUTED);
        status.setTextSize(14);
        status.setPadding(0, dp(14), 0, dp(12));

        root.addView(status, fullWidthWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);

        root.addView(resultsContainer, fullWidthWrap());

        searchScreen = outer;
        setContentView(searchScreen);

        searchBox.setOnEditorActionListener(
                (view, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        searchVideos();
                        return true;
                    }
                    return false;
                }
        );
    }

    private void searchVideos() {
        String query = searchBox.getText().toString().trim();
        String apiKey = BuildConfig.YOUTUBE_API_KEY;

        if (query.isEmpty()) {
            toast("Enter a song title or artist.");
            return;
        }

        if (apiKey == null || apiKey.trim().isEmpty()) {
            status.setText(
                    "YouTube API key is missing. Check the "
                            + "YOUTUBE_API_KEY GitHub secret."
            );
            return;
        }

        Object service =
                getSystemService(Context.INPUT_METHOD_SERVICE);

        if (service instanceof InputMethodManager) {
            ((InputMethodManager) service)
                    .hideSoftInputFromWindow(
                            searchBox.getWindowToken(), 0);
        }

        final int generation = ++searchGeneration;

        String searchQuery =
                query.toLowerCase().contains("karaoke")
                        ? query
                        : query + " karaoke";

        searchButton.setEnabled(false);
        status.setText("Searching YouTube...");
        resultsContainer.removeAllViews();

        String encodedQuery;
        String encodedKey;

        try {
            encodedQuery = URLEncoder.encode(
                    searchQuery, "UTF-8");
            encodedKey = URLEncoder.encode(
                    apiKey, "UTF-8");
        } catch (Exception e) {
            searchButton.setEnabled(true);
            status.setText("Unable to prepare the search.");
            return;
        }

        executor.execute(() -> {
            ArrayList<Video> videos = new ArrayList<>();
            String error = null;
            HttpURLConnection connection = null;

            try {
                String requestUrl =
                        "https://www.googleapis.com/youtube/v3/search"
                        + "?part=snippet"
                        + "&type=video"
                        + "&videoEmbeddable=true"
                        + "&maxResults=25"
                        + "&regionCode=PH"
                        + "&q=" + encodedQuery
                        + "&key=" + encodedKey;

                connection = (HttpURLConnection)
                        new URL(requestUrl).openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();

                InputStream stream =
                        responseCode >= 200 && responseCode < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                String response = readAll(stream);

                if (responseCode < 200 || responseCode >= 300) {
                    JSONObject errorJson =
                            new JSONObject(response);

                    JSONObject errorObject =
                            errorJson.optJSONObject("error");

                    String message = errorObject == null
                            ? ""
                            : errorObject.optString("message", "");

                    throw new Exception(
                            message.isEmpty()
                                    ? "YouTube API error: HTTP "
                                            + responseCode
                                    : message
                    );
                }

                JSONObject json = new JSONObject(response);
                JSONArray items = json.optJSONArray("items");

                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);

                        if (item == null) {
                            continue;
                        }

                        JSONObject idObject =
                                item.optJSONObject("id");

                        JSONObject snippet =
                                item.optJSONObject("snippet");

                        if (idObject == null || snippet == null) {
                            continue;
                        }

                        String videoId =
                                idObject.optString("videoId", "");

                        if (videoId.isEmpty()) {
                            continue;
                        }

                        Video video = new Video();
                        video.id = videoId;
                        video.title = snippet.optString(
                                "title", "YouTube karaoke video");
                        video.channel = snippet.optString(
                                "channelTitle", "YouTube channel");

                        videos.add(video);
                    }
                }

            } catch (Exception e) {
                error = e.getMessage() == null
                        ? "Unable to search YouTube."
                        : e.getMessage();

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final String finalError = error;
            final ArrayList<Video> found = videos;

            runOnUiThread(() -> {
                if (closing || isFinishing()
                        || generation != searchGeneration) {
                    return;
                }

                searchButton.setEnabled(true);
                resultsContainer.removeAllViews();

                if (finalError != null) {
                    status.setText(
                            "YouTube search failed: " + finalError);
                    return;
                }

                if (found.isEmpty()) {
                    status.setText(
                            "No embeddable videos found. "
                                    + "Try another song title or artist.");
                    return;
                }

                status.setText(
                        "Found " + found.size()
                                + " videos. Tap a song to play.");

                for (Video video : found) {
                    addVideoResult(video);
                }
            });
        });
    }

    private void addVideoResult(Video video) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(round(PANEL, dp(10)));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams cardParams = fullWidthWrap();
        cardParams.bottomMargin = dp(10);

        resultsContainer.addView(card, cardParams);

        TextView title = new TextView(this);
        title.setText(video.title);
        title.setTextColor(WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);

        card.addView(title, fullWidthWrap());

        TextView channel = new TextView(this);
        channel.setText(video.channel);
        channel.setTextColor(MUTED);
        channel.setTextSize(12);
        channel.setPadding(0, dp(7), 0, 0);

        card.addView(channel, fullWidthWrap());

        TextView tapLabel = new TextView(this);
        tapLabel.setText("▶  Tap to play");
        tapLabel.setTextColor(GREEN);
        tapLabel.setTextSize(13);
        tapLabel.setTypeface(null, Typeface.BOLD);
        tapLabel.setPadding(0, dp(12), 0, 0);

        card.addView(tapLabel, fullWidthWrap());

        card.setOnClickListener(v -> playVideo(video));
    }

    private void playVideo(Video video) {
        destroyPlayer();
        hideSystemBars();

        playbackScreen = new LinearLayout(this);
        playbackScreen.setOrientation(LinearLayout.VERTICAL);
        playbackScreen.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText(video.title);
        title.setTextColor(WHITE);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), dp(8), dp(12), dp(8));
        title.setMaxLines(2);

        playbackScreen.addView(title, fullWidthWrap());

        playerHost = new FrameLayout(this);
        playerHost.setBackgroundColor(Color.BLACK);

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                );

        playbackScreen.addView(playerHost, videoParams);

        Button backButton = makeButton("Back");

        LinearLayout.LayoutParams backParams =
                fullWidth(dp(50));

        backParams.setMargins(
                dp(12), dp(8), dp(12), dp(12));

        playbackScreen.addView(backButton, backParams);

        backButton.setOnClickListener(v -> returnToResults());

        setContentView(playbackScreen);

        player = new WebView(this);
        player.setBackgroundColor(Color.BLACK);

        WebSettings settings = player.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        player.setWebChromeClient(new WebChromeClient());
        player.setWebViewClient(new WebViewClient());

        playerHost.addView(
                player,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        String html =
                "<!doctype html>"
                + "<html>"
                + "<head>"
                + "<meta name=\"viewport\" "
                + "content=\"width=device-width, initial-scale=1,"
                + " maximum-scale=1\">"
                + "<meta name=\"referrer\" "
                + "content=\"strict-origin-when-cross-origin\">"
                + "<style>"
                + "html,body{margin:0;padding:0;background:#000;"
                + "width:100%;height:100%;overflow:hidden;}"
                + "iframe{border:0;width:100%;height:100%;}"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<iframe "
                + "src=\"https://www.youtube.com/embed/"
                + video.id
                + "?playsinline=0&controls=1&rel=0\" "
                + "title=\"YouTube karaoke player\" "
                + "allow=\"accelerometer; autoplay; clipboard-write; "
                + "encrypted-media; gyroscope; picture-in-picture; "
                + "web-share\" "
                + "referrerpolicy=\"strict-origin-when-cross-origin\" "
                + "allowfullscreen>"
                + "</iframe>"
                + "</body>"
                + "</html>";

        player.loadDataWithBaseURL(
                "https://com.donut4u.karaoke/",
                html,
                "text/html",
                "UTF-8",
                null
        );
    }

    private void returnToResults() {
        destroyPlayer();
        playbackScreen = null;
        playerHost = null;

        showSystemBars();
        setContentView(searchScreen);
    }

    private void hideSystemBars() {
        Window window = getWindow();

        window.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showSystemBars() {
        Window window = getWindow();

        window.clearFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
    }

    private void destroyPlayer() {
        if (player != null) {
            player.stopLoading();
            player.loadUrl("about:blank");

            if (playerHost != null) {
                playerHost.removeView(player);
            }

            player.destroy();
            player = null;
        }
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        input, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }

    private void styleEdit(EditText edit) {
        edit.setTextColor(WHITE);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(15);
        edit.setPadding(dp(12), 0, dp(12), 0);
        edit.setBackground(round(PANEL, dp(8)));
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(15);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(round(GREEN, dp(8)));
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    private void toast(String message) {
        Toast.makeText(
                this, message, Toast.LENGTH_LONG
        ).show();
    }

    @Override
    public void onBackPressed() {
        if (player != null) {
            returnToResults();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        closing = true;
        searchGeneration++;

        destroyPlayer();
        executor.shutdownNow();

        super.onDestroy();
    }
}
