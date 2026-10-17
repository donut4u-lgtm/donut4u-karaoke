package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private final int green = Color.rgb(25, 125, 75);
    private final int dark = Color.rgb(30, 30, 30);

    private EditText searchInput;
    private WebView karaokeWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(green);
        getWindow().setNavigationBarColor(Color.BLACK);

        showSearchScreen();
    }

    private void showSearchScreen() {

        if (karaokeWebView != null) {
            karaokeWebView.stopLoading();
            karaokeWebView.loadUrl("about:blank");
            karaokeWebView.destroy();
            karaokeWebView = null;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("🎤 donut4u karaoke");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(green);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, dp(10));

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText("Search YouTube and sing along!");
        subtitle.setTextSize(16);
        subtitle.setTextColor(dark);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(22));

        root.addView(subtitle);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(17);
        searchInput.setHint("Enter song title or artist...");
        searchInput.setPadding(
                dp(12), dp(12), dp(12), dp(12)
        );

        root.addView(searchInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button searchButton = new Button(this);
        searchButton.setText("FIND KARAOKE ON YOUTUBE");
        searchButton.setTextColor(Color.WHITE);
        searchButton.setTextSize(16);
        searchButton.setAllCaps(false);
        searchButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(green)
        );

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        buttonParams.topMargin = dp(14);
        root.addView(searchButton, buttonParams);

        TextView note = new TextView(this);
        note.setText(
                "No local music files are displayed.\n\n"
                + "Enter a song or artist above, then search "
                + "for karaoke videos on YouTube."
        );
        note.setTextSize(14);
        note.setTextColor(Color.DKGRAY);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), dp(20), dp(8), dp(8));

        root.addView(note);

        searchButton.setOnClickListener(v -> {

            String query = searchInput.getText()
                    .toString().trim();

            if (query.isEmpty()) {
                Toast.makeText(
                        this,
                        "Please enter a song or artist.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            openKaraokeSearch(query + " karaoke");
        });

        setContentView(root);
    }

    private void openKaraokeSearch(String query) {

        LinearLayout playerScreen = new LinearLayout(this);
        playerScreen.setOrientation(LinearLayout.VERTICAL);
        playerScreen.setBackgroundColor(Color.BLACK);

        Button backButton = new Button(this);
        backButton.setText("🏠 BACK TO SONG SEARCH");
        backButton.setTextSize(16);
        backButton.setAllCaps(false);
        backButton.setTextColor(Color.WHITE);
        backButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(green)
        );

        playerScreen.addView(backButton,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(56)
                )
        );

        TextView hint = new TextView(this);
        hint.setText(
                "Choose a karaoke video below."
        );
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(8), dp(8), dp(8), dp(8));

        playerScreen.addView(hint);

        karaokeWebView = new WebView(this);
        karaokeWebView.setBackgroundColor(Color.BLACK);

        WebSettings settings = karaokeWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        karaokeWebView.setWebChromeClient(
                new WebChromeClient()
        );

        karaokeWebView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                // Keep website navigation inside the app.
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                // Compatibility with older WebView versions.
                return false;
            }
        });

        playerScreen.addView(
                karaokeWebView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        backButton.setOnClickListener(v ->
                showSearchScreen()
        );

        setContentView(playerScreen);

        try {
            String encoded = URLEncoder.encode(
                    query,
                    StandardCharsets.UTF_8.name()
            );

            String url =
                    "https://www.youtube.com/results?search_query="
                    + encoded;

            karaokeWebView.loadUrl(url);

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not start YouTube search.",
                    Toast.LENGTH_LONG
            ).show();

            showSearchScreen();
        }
    }

    @Override
    public void onBackPressed() {

        if (karaokeWebView != null) {

            if (karaokeWebView.canGoBack()) {
                karaokeWebView.goBack();
            } else {
                showSearchScreen();
            }

            return;
        }

        super.onBackPressed();
    }

    private int dp(int value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    @Override
    protected void onPause() {
        if (karaokeWebView != null) {
            karaokeWebView.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (karaokeWebView != null) {
            karaokeWebView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (karaokeWebView != null) {
            karaokeWebView.stopLoading();
            karaokeWebView.destroy();
            karaokeWebView = null;
        }

        super.onDestroy();
    }
}
