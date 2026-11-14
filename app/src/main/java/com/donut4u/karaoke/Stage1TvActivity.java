
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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

public class Stage1TvActivity extends Activity {

    private static final int BACKGROUND =
            Color.rgb(8, 18, 25);

    private static final int PANEL =
            Color.rgb(18, 42, 48);

    private static final int GREEN =
            Color.rgb(50, 220, 130);

    private static final int WHITE =
            Color.WHITE;

    private static final int MUTED =
            Color.rgb(180, 195, 195);

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final ArrayList<Video> videos =
            new ArrayList<>();

    private LinearLayout resultsContainer;
    private EditText searchInput;
    private Button searchButton;
    private TextView statusText;

    private ScrollView searchScroll;
    private View searchScreen;

    private LinearLayout playerScreen;
    private WebView player;

    private volatile boolean destroyed = false;

    private int searchGeneration = 0;

    private static final class Video {
        final String id;
        final String title;
        final String channel;

        Video(
                String id,
                String title,
                String channel
        ) {
            this.id = id;
            this.title = title;
            this.channel = channel;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        hideSystemUi();
        buildSearchScreen();
    }

    private int dp(float value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private GradientDrawable roundedBackground(
            int color,
            int strokeColor
    ) {
        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));

        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(2), strokeColor);
        }

        return drawable;
    }

    private LinearLayout.LayoutParams fullWidth(
            int height
    ) {
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

    private Button makeButton(String label) {
        Button button = new Button(this);

        button.setText(label);
        button.setTextColor(WHITE);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        button.setBackground(
                roundedBackground(PANEL, GREEN)
        );

        button.setPadding(
                dp(16),
                dp(10),
                dp(16),
                dp(10)
        );

        button.setFocusable(true);
        button.setFocusableInTouchMode(true);

        return button;
    }

    private void buildSearchScreen() {
        searchScroll = new ScrollView(this);
        searchScroll.setFillViewport(true);
        searchScroll.setBackgroundColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(
                dp(42),
                dp(22),
                dp(42),
                dp(24)
        );

        searchScroll.addView(root);

        TextView brand = new TextView(this);
        brand.setText("DONUT4U KARAOKE");
        brand.setTextColor(GREEN);
        brand.setTextSize(22);
        brand.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        brand.setGravity(Gravity.CENTER);

        root.addView(brand, fullWidthWrap());

        TextView heading = new TextView(this);
        heading.setText("STAGE 1 - YOUTUBE KARAOKE");
        heading.setTextColor(WHITE);
        heading.setTextSize(29);
        heading.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(
                0,
                dp(10),
                0,
                dp(8)
        );

        root.addView(heading, fullWidthWrap());

        TextView instructions = new TextView(this);
        instructions.setText(
                "Search for a song title or artist, then choose a video."
        );
        instructions.setTextColor(MUTED);
        instructions.setTextSize(16);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(
                0,
                0,
                0,
                dp(18)
        );

        root.addView(instructions, fullWidthWrap());

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(WHITE);
        searchInput.setHintTextColor(MUTED);
        searchInput.setHint("Enter song title or artist");
        searchInput.setTextSize(19);
        searchInput.setPadding(
                dp(16),
                0,
                dp(16),
                0
        );
        searchInput.setBackground(
                roundedBackground(PANEL, GREEN)
        );
        searchInput.setFocusable(true);
        searchInput.setFocusableInTouchMode(true);
        searchInput.setShowSoftInputOnFocus(true);
        searchInput.setImeOptions(
                EditorInfo.IME_ACTION_SEARCH
        );

        root.addView(
                searchInput,
                fullWidth(dp(58))
        );

        searchButton = makeButton("SEARCH YOUTUBE");

        LinearLayout.LayoutParams searchParams =
                fullWidth(dp(58));

        searchParams.topMargin = dp(12);

        root.addView(searchButton, searchParams);

        searchButton.setOnClickListener(
                v -> searchVideos()
        );

        searchInput.setOnEditorActionListener(
                (view, actionId, event) -> {
                    boolean enterPressed =
                            event != null
                                    && event.getAction()
                                    == KeyEvent.ACTION_UP
                                    && (event.getKeyCode()
                                    == KeyEvent.KEYCODE_ENTER
                                    || event.getKeyCode()
                                    == KeyEvent.KEYCODE_NUMPAD_ENTER);

                    if (actionId == EditorInfo.IME_ACTION_SEARCH
                            || enterPressed) {
                        searchVideos();
                        return true;
                    }

                    return false;
                }
        );

        statusText = new TextView(this);
        statusText.setText(
                "Ready to search YouTube."
        );
        statusText.setTextColor(MUTED);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(
                0,
                dp(14),
                0,
                dp(12)
        );

        root.addView(statusText, fullWidthWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(
                LinearLayout.VERTICAL
        );

        root.addView(
                resultsContainer,
                fullWidthWrap()
        );

        Button backButton = makeButton("BACK TO TV STAGE MENU");

        LinearLayout.LayoutParams backParams =
                fullWidth(dp(54));

        backParams.topMargin = dp(18);

        root.addView(backButton, backParams);

        backButton.setOnClickListener(v -> finish());

        searchScreen = searchScroll;

        setContentView(searchScreen);

        searchInput.requestFocus();
    }

    private String normalizedQuery(String query) {
        String result = query.trim();

        if (!result.toLowerCase().contains("karaoke")) {
            result += " karaoke";
        }

        return result;
    }

    private void searchVideos() {
        if (destroyed) {
            return;
        }

        String query = searchInput.getText()
                .toString()
                .trim();

        if (query.isEmpty()) {
            Toast.makeText(
                    this,
                    "Enter a song title or artist.",
                    Toast.LENGTH_SHORT
            ).show();

            searchInput.requestFocus();
            return;
        }

        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE
                );

        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(
                    searchInput.getWindowToken(),
                    0
            );
        }

        String apiKey = BuildConfig.YOUTUBE_API_KEY;

        if (apiKey == null || apiKey.trim().isEmpty()) {
            statusText.setText(
                    "YouTube API key is missing. Check the YOUTUBE_API_KEY GitHub secret."
            );
            return;
        }

        final int generation = ++searchGeneration;

        final String searchQuery =
                normalizedQuery(query);

        searchButton.setEnabled(false);
        resultsContainer.removeAllViews();
        videos.clear();

        statusText.setText("Searching YouTube...");

        executor.execute(() -> {
            ArrayList<Video> found =
                    new ArrayList<>();

            String error = null;

            HttpURLConnection connection = null;

            try {
                String encodedQuery =
                        URLEncoder.encode(
                                searchQuery,
                                "UTF-8"
                        );

                String encodedKey =
                        URLEncoder.encode(
                                apiKey,
                                "UTF-8"
                        );

                String requestUrl =
                        "https://www.googleapis.com/youtube/v3/search"
                                + "?part=snippet&type=video"
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

                int responseCode =
                        connection.getResponseCode();

                InputStream stream =
                        responseCode >= 200
                                && responseCode < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                String response = readAll(stream);

                if (responseCode < 200
                        || responseCode >= 300) {

                    JSONObject errorJson =
                            new JSONObject(response);

                    JSONObject errorObject =
                            errorJson.optJSONObject("error");

                    String message = errorObject == null
                            ? ""
                            : errorObject.optString(
                                    "message",
                                    ""
                            );

                    throw new Exception(
                            message.isEmpty()
                                    ? "YouTube API error: HTTP "
                                    + responseCode
                                    : message
                    );
                }

                JSONObject json =
                        new JSONObject(response);

                JSONArray items =
                        json.optJSONArray("items");

                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item =
                                items.optJSONObject(i);

                        if (item == null) {
                            continue;
                        }

                        JSONObject idObject =
                                item.optJSONObject("id");

                        JSONObject snippet =
                                item.optJSONObject("snippet");

                        if (idObject == null
                                || snippet == null) {
                            continue;
                        }

                        String videoId =
                                idObject.optString(
                                        "videoId",
                                        ""
                                );

                        if (!videoId.matches(
                                "[A-Za-z0-9_-]{6,20}")) {
                            continue;
                        }

                        String title =
                                snippet.optString(
                                        "title",
                                        "YouTube karaoke video"
                                );

                        String channel =
                                snippet.optString(
                                        "channelTitle",
                                        "YouTube channel"
                                );

                        found.add(
                                new Video(
                                        videoId,
                                        title,
                                        channel
                                )
                        );
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
            final ArrayList<Video> results = found;

            runOnUiThread(() -> {
                if (destroyed
                        || isFinishing()
                        || generation != searchGeneration) {
                    return;
                }

                searchButton.setEnabled(true);

                if (finalError != null) {
                    statusText.setText(
                            "YouTube search failed: "
                                    + finalError
                    );
                    return;
                }

                if (results.isEmpty()) {
                    statusText.setText(
                            "No embeddable videos found. Try another search."
                    );
                    return;
                }

                videos.clear();
                videos.addAll(results);

                statusText.setText(
                        "FOUND " + videos.size()
                                + " VIDEOS - SELECT ONE TO PLAY"
                );

                displayResults();
            });
        });
    }

    private String readAll(InputStream stream)
            throws Exception {

        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     stream,
                                     StandardCharsets.UTF_8
                             )
                     )) {

            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }

    private void displayResults() {
        resultsContainer.removeAllViews();

        for (int i = 0; i < videos.size(); i++) {
            Video video = videos.get(i);

            Button resultButton =
                    makeButton(
                            (i + 1) + ".  " + video.title
                                    + "\n"
                                    + video.channel
                    );

            resultButton.setTextSize(16);
            resultButton.setGravity(
                    Gravity.CENTER_VERTICAL
                            | Gravity.LEFT
            );
            resultButton.setMaxLines(3);
            resultButton.setMinHeight(dp(76));

            LinearLayout.LayoutParams params =
                    fullWidth(
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            params.bottomMargin = dp(10);

            resultsContainer.addView(
                    resultButton,
                    params
            );

            resultButton.setOnClickListener(
                    v -> playVideo(video)
            );
        }

        if (resultsContainer.getChildCount() > 0) {
            resultsContainer.getChildAt(0).requestFocus();
        }
    }

    private void playVideo(Video video) {
        if (destroyed || video == null) {
            return;
        }

        showPlayerScreen(video);
    }

    private void showPlayerScreen(Video video) {
        playerScreen = new LinearLayout(this);
        playerScreen.setOrientation(
                LinearLayout.VERTICAL
        );
        playerScreen.setGravity(Gravity.CENTER);
        playerScreen.setBackgroundColor(Color.BLACK);
        playerScreen.setPadding(
                dp(24),
                dp(18),
                dp(24),
                dp(18)
        );

        TextView title = new TextView(this);
        title.setText(video.title);
        title.setTextColor(WHITE);
        title.setTextSize(21);
        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);

        playerScreen.addView(
                title,
                fullWidth(dp(62))
        );

        TextView channel = new TextView(this);
        channel.setText(video.channel);
        channel.setTextColor(MUTED);
        channel.setTextSize(14);
        channel.setGravity(Gravity.CENTER);

        playerScreen.addView(
                channel,
                fullWidth(dp(32))
        );

        FrameLayout videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                );

        videoParams.topMargin = dp(8);
        videoParams.bottomMargin = dp(10);

        playerScreen.addView(
                videoFrame,
                videoParams
        );

        player = new WebView(this);

        WebSettings settings = player.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        player.setBackgroundColor(Color.BLACK);
        player.setWebChromeClient(new WebChromeClient());
        player.setWebViewClient(new WebViewClient());

        videoFrame.addView(
                player,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        Button backButton =
                makeButton("BACK TO SEARCH RESULTS");

        playerScreen.addView(
                backButton,
                fullWidth(dp(56))
        );

        backButton.setOnClickListener(
                v -> returnToSearch()
        );

        setContentView(playerScreen);

        String html =
                "<!DOCTYPE html>"
                        + "<html><head>"
                        + "<meta name='viewport' "
                        + "content='width=device-width,"
                        + "initial-scale=1,maximum-scale=1'>"
                        + "<style>"
                        + "html,body{margin:0;padding:0;"
                        + "width:100%;height:100%;"
                        + "background:#000;overflow:hidden;}"
                        + "iframe{width:100%;height:100%;"
                        + "border:0;}"
                        + "</style></head><body>"
                        + "<iframe "
                        + "src='https://www.youtube.com/embed/"
                        + video.id
                        + "?autoplay=1&playsinline=1"
                        + "&rel=0&modestbranding=1' "
                        + "allow='autoplay; encrypted-media; "
                        + "picture-in-picture' "
                        + "allowfullscreen>"
                        + "</iframe>"
                        + "</body></html>";

        player.loadDataWithBaseURL(
                "https://www.youtube.com/",
                html,
                "text/html",
                "UTF-8",
                null
        );
    }

    private void returnToSearch() {
        destroyPlayer();

        if (searchScreen != null) {
            setContentView(searchScreen);

            if (resultsContainer != null
                    && resultsContainer.getChildCount() > 0) {
                resultsContainer.getChildAt(0).requestFocus();
            } else if (searchInput != null) {
                searchInput.requestFocus();
            }
        }
    }

    private void destroyPlayer() {
        if (player != null) {
            try {
                player.stopLoading();
                player.loadUrl("about:blank");
                player.removeAllViews();
                player.destroy();
            } catch (Exception ignored) {
            }

            player = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (player != null) {
            returnToSearch();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();

        if (player != null) {
            player.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (player != null) {
            player.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        searchGeneration++;

        destroyPlayer();

        executor.shutdownNow();

        super.onDestroy();
    }
}
