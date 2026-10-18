
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
    private LinearLayout playerPanel;
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

        buildScreen();
    }

    private void buildScreen() {
        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        outer.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(20));

        outer.addView(root);
        setContentView(outer);

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(GREEN);
        heading.setTextSize(24);
        heading.setTypeface(null, Typeface.BOLD);
        root.addView(heading, fullWidthWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("YOUTUBE KARAOKE CATALOGUE");
        subtitle.setTextColor(WHITE);
        subtitle.setTextSize(16);
        subtitle.setTypeface(null, Typeface.BOLD);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle, fullWidthWrap());

        root.addView(
                label("Search by song title or artist"),
                fullWidthWrap()
        );

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Example: My Way karaoke");
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        styleEdit(searchBox);
        root.addView(searchBox, fullWidth(dp(50)));

        searchButton = makeButton("SEARCH YOUTUBE KARAOKE");
        searchButton.setOnClickListener(v -> searchVideos());
        root.addView(searchButton, fullWidth(dp(48)));

        status = new TextView(this);
        status.setText(
                "Search YouTube for karaoke videos. "
                + "Choose a result to play it."
        );
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setPadding(0, dp(12), 0, dp(10));
        root.addView(status, fullWidthWrap());

        playerPanel = new LinearLayout(this);
        playerPanel.setOrientation(LinearLayout.VERTICAL);
        playerPanel.setVisibility(View.GONE);
        root.addView(playerPanel, fullWidthWrap());

        Button backToResults =
                makeButton("BACK TO SEARCH RESULTS");

        backToResults.setOnClickListener(v -> {
            destroyPlayer();
            playerPanel.setVisibility(View.GONE);
            resultsContainer.setVisibility(View.VISIBLE);
            status.setText("Select a YouTube karaoke video.");
        });

        playerPanel.addView(
                backToResults,
                fullWidth(dp(44))
        );

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, fullWidthWrap());

        Button back = makeButton("BACK TO KARAOKE PLAYER");
        back.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams backParams = fullWidth(dp(46));
        backParams.topMargin = dp(18);
        root.addView(back, backParams);

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
                    "This app was built without a YouTube API key. "
                    + "Check the YOUTUBE_API_KEY GitHub secret."
            );
            return;
        }

        Object service = getSystemService(
                Context.INPUT_METHOD_SERVICE
        );

        if (service instanceof InputMethodManager) {
            ((InputMethodManager) service)
                    .hideSoftInputFromWindow(
                            searchBox.getWindowToken(), 0
                    );
        }

        final int generation = ++searchGeneration;

        String searchQuery = query.toLowerCase().contains("karaoke")
                ? query
                : query + " karaoke";

        searchButton.setEnabled(false);
        status.setText("Searching YouTube...");
        resultsContainer.removeAllViews();
        destroyPlayer();
        playerPanel.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.VISIBLE);

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
                        + "&q=" + URLEncoder.encode(
                                searchQuery, "UTF-8")
                        + "&key=" + URLEncoder.encode(
                                apiKey, "UTF-8");

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

                        JSONObject idObject = item.optJSONObject("id");
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
                            "YouTube search failed: " + finalError
                    );
                    return;
                }

                if (found.isEmpty()) {
                    status.setText(
                            "No embeddable videos found. "
                            + "Try another title or artist."
                    );
                    return;
                }

                status.setText(
                        "YOUTUBE RESULTS: " + found.size()
                        + ". Select a video to play."
                );

                for (Video video : found) {
                    addVideoResult(video);
                }
            });
        });
    }

    private void addVideoResult(Video video) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(round(PANEL, dp(10)));

        LinearLayout.LayoutParams cardParams = fullWidthWrap();
        cardParams.bottomMargin = dp(10);
        resultsContainer.addView(card, cardParams);

        TextView title = new TextView(this);
        title.setText(video.title);
        title.setTextColor(WHITE);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title, fullWidthWrap());

        TextView channel = new TextView(this);
        channel.setText("Channel: " + video.channel);
        channel.setTextColor(MUTED);
        channel.setTextSize(12);
        channel.setPadding(0, dp(5), 0, dp(8));
        card.addView(channel, fullWidthWrap());

        Button play = makeButton("▶ PLAY YOUTUBE KARAOKE");
        play.setOnClickListener(v -> playVideo(video));
        card.addView(play, fullWidth(dp(44)));
    }

    private void playVideo(Video video) {
        destroyPlayer();

        resultsContainer.setVisibility(View.GONE);
        playerPanel.setVisibility(View.VISIBLE);
        status.setText("Selected: " + video.title);

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

        LinearLayout.LayoutParams playerParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(270)
                );

        playerParams.topMargin = dp(8);
        playerPanel.addView(player, playerParams);

        String html =
                "<!doctype html>"
                + "<html>"
                + "<head>"
                + "<meta name=\"viewport\" "
                + "content=\"width=device-width, initial-scale=1\">"
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
                + "?playsinline=1&controls=1&rel=0\" "
                + "title=\"YouTube karaoke player\" "
                + "allow=\"accelerometer; autoplay; clipboard-write; "
                + "encrypted-media; gyroscope; picture-in-picture; "
                + "web-share\" "
                + "referrerpolicy=\"strict-origin-when-cross-origin\" "
                + "allowfullscreen>"
                + "</iframe>"
                + "</body>"
                + "</html>";

        String baseUrl = "https://com.donut4u.karaoke/";

        player.loadDataWithBaseURL(
                baseUrl,
                html,
                "text/html",
                "UTF-8",
                null
        );
    }

    private void destroyPlayer() {
        if (player != null) {
            player.stopLoading();
            player.loadUrl("about:blank");
            playerPanel.removeView(player);
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

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(WHITE);
        view.setTextSize(13);
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private void styleEdit(EditText edit) {
        edit.setTextColor(WHITE);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(14);
        edit.setPadding(dp(12), 0, dp(12), 0);
        edit.setBackground(round(PANEL, dp(8)));
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(12);
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
    protected void onDestroy() {
        closing = true;
        searchGeneration++;
        destroyPlayer();
        executor.shutdownNow();
        super.onDestroy();
    }
}
