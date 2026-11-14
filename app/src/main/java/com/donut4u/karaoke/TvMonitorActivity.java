
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Dedicated TV karaoke monitor.
 *
 * This Activity displays karaoke content only.
 * Phone controls, microphone, scoring and advertisements
 * remain on the phone.
 *
 * Incoming updates use the existing TvSessionController.
 */
public class TvMonitorActivity extends Activity
        implements TvSessionController.Observer {

    public static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final int BLACK = Color.BLACK;
    private static final int WHITE = Color.WHITE;
    private static final int GREEN = Color.rgb(50, 220, 130);
    private static final int MUTED = Color.rgb(180, 190, 190);

    private TvSessionController sessionController;

    private FrameLayout root;
    private FrameLayout videoContainer;
    private WebView videoView;

    private LinearLayout informationPanel;
    private TextView titleView;
    private TextView singerView;
    private TextView composerView;
    private TextView lyricsView;
    private ScrollView lyricsScroll;
    private TextView statusView;

    private String currentVideoId = "";
    private String currentMediaUrl = "";
    private String currentLyrics = "";

    private boolean destroyed;
    private boolean videoLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        hideSystemUi();
        buildScreen();

        sessionController =
                TvSessionController.getInstance(
                        getApplicationContext()
                );

        /*
         * Reuse the existing application-wide receiver.
         * Do not create a second socket or receiver here.
         */
        sessionController.startSession(this);
        sessionController.setObserver(this);

        JSONObject previousState =
                sessionController.getLastPhoneState();

        if (previousState != null) {
            applyPhoneState(previousState);
        }
    }

    private void buildScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BLACK);

        videoContainer = new FrameLayout(this);
        videoContainer.setBackgroundColor(BLACK);

        root.addView(
                videoContainer,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        informationPanel = new LinearLayout(this);
        informationPanel.setOrientation(
                LinearLayout.VERTICAL
        );
        informationPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        informationPanel.setPadding(
                dp(30),
                dp(14),
                dp(30),
                dp(18)
        );
        informationPanel.setBackgroundColor(
                Color.argb(225, 5, 12, 12)
        );

        FrameLayout.LayoutParams panelParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                );

        root.addView(informationPanel, panelParams);

        titleView = new TextView(this);
        titleView.setText("DONUT4U KARAOKE");
        titleView.setTextColor(GREEN);
        titleView.setTextSize(23);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        informationPanel.addView(
                titleView,
                matchWrap()
        );

        singerView = makeMetadataText();
        composerView = makeMetadataText();

        informationPanel.addView(
                singerView,
                matchWrap()
        );

        informationPanel.addView(
                composerView,
                matchWrap()
        );

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(false);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setBackgroundColor(Color.TRANSPARENT);
        lyricsScroll.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8)
        );

        lyricsView = new TextView(this);
        lyricsView.setText(
                "Waiting for a song from your phone..."
        );
        lyricsView.setTextColor(WHITE);
        lyricsView.setTextSize(27);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setLineSpacing(dp(7), 1.05f);
        lyricsView.setIncludeFontPadding(true);

        lyricsScroll.addView(
                lyricsView,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );

        LinearLayout.LayoutParams lyricsParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(190)
                );

        lyricsParams.topMargin = dp(8);

        informationPanel.addView(
                lyricsScroll,
                lyricsParams
        );

        statusView = new TextView(this);
        statusView.setText("PHONE + TV READY");
        statusView.setTextColor(MUTED);
        statusView.setTextSize(12);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(8), 0, 0);

        informationPanel.addView(
                statusView,
                matchWrap()
        );

        setContentView(root);
    }

    private TextView makeMetadataText() {
        TextView view = new TextView(this);
        view.setTextColor(MUTED);
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(3), 0, 0);
        view.setVisibility(View.GONE);
        return view;
    }

    private void applyPhoneState(JSONObject state) {
        if (state == null || destroyed) {
            return;
        }

        int stage = state.optInt("stage", 0);

        if (stage < 1 || stage > 3) {
            return;
        }

        String action = state.optString("action", "");

        if ("stop".equalsIgnoreCase(action)
                || "back".equalsIgnoreCase(action)) {
            stopCurrentPlayback();
            return;
        }

        String title = firstNonEmpty(
                state.optString("songTitle", ""),
                state.optString("title", ""),
                "DONUT4U KARAOKE"
        );

        String singer = state.optString("singer", "");
        String composer = state.optString("composer", "");

        titleView.setText(title);
        setMetadata(singerView, singer, "Singer: ");
        setMetadata(composerView, composer, "Composer: ");

        String lyrics = firstNonEmpty(
                state.optString("lyrics", ""),
                state.optString("lyricText", "")
        );

        if (!lyrics.isEmpty() && !lyrics.equals(currentLyrics)) {
            currentLyrics = lyrics;
            lyricsView.setText(lyrics);
            lyricsScroll.post(
                    () -> lyricsScroll.scrollTo(0, 0)
            );
        }

        String videoId = state.optString("videoId", "");
        String mediaUrl = state.optString("mediaUrl", "");

        if (!videoId.isEmpty()
                && !videoId.equals(currentVideoId)) {
            playYouTube(videoId);
        } else if (!mediaUrl.isEmpty()
                && !mediaUrl.equals(currentMediaUrl)) {
            playMediaUrl(mediaUrl);
        }

        String playback = state.optString(
                "playback",
                "playing"
        );

        if ("pause".equalsIgnoreCase(playback)) {
            pauseVideo();
        } else if ("play".equalsIgnoreCase(playback)
                || "playing".equalsIgnoreCase(playback)) {
            resumeVideo();
        }

        statusView.setText(
                "PHONE CONNECTED  •  STAGE " + stage
        );
    }

    private void setMetadata(
            TextView view,
            String value,
            String prefix
    ) {
        if (value == null || value.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }

        view.setText(prefix + value.trim());
        view.setVisibility(View.VISIBLE);
    }

    private void playYouTube(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) {
            return;
        }

        currentVideoId = videoId.trim();
        currentMediaUrl = "";
        videoLoaded = false;

        destroyVideoView();

        videoView = new WebView(this);
        videoView.setBackgroundColor(BLACK);

        WebSettings settings = videoView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        videoView.setWebChromeClient(new WebChromeClient());
        videoView.setWebViewClient(new WebViewClient());

        videoContainer.addView(
                videoView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        String html =
                "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,"
                + "initial-scale=1,maximum-scale=1'>"
                + "<style>"
                + "html,body{margin:0;padding:0;background:#000;"
                + "width:100%;height:100%;overflow:hidden;}"
                + "iframe{position:absolute;inset:0;width:100%;"
                + "height:100%;border:0;}"
                + "</style></head><body>"
                + "<iframe id='player' "
                + "src='https://www.youtube.com/embed/"
                + currentVideoId
                + "?playsinline=1&controls=1&rel=0&autoplay=1' "
                + "allow='autoplay; encrypted-media; picture-in-picture' "
                + "allowfullscreen></iframe>"
                + "</body></html>";

        videoView.loadDataWithBaseURL(
                "https://www.youtube.com/",
                html,
                "text/html",
                "UTF-8",
                null
        );

        videoLoaded = true;
    }

    private void playMediaUrl(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
            return;
        }

        /*
         * A phone content:// URI cannot be opened by the TV.
         * The phone must provide a reachable HTTP media URL
         * before this playback path can be used.
         */
        if (!mediaUrl.startsWith("http://")
                && !mediaUrl.startsWith("https://")) {
            statusView.setText(
                    "Waiting for a TV-accessible media link."
            );
            return;
        }

        currentMediaUrl = mediaUrl.trim();
        currentVideoId = "";
        videoLoaded = false;

        destroyVideoView();

        videoView = new WebView(this);
        videoView.setBackgroundColor(BLACK);

        WebSettings settings = videoView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        videoView.setWebChromeClient(new WebChromeClient());
        videoView.setWebViewClient(new WebViewClient());

        videoContainer.addView(
                videoView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        String escapedUrl = currentMediaUrl
                .replace("&", "&amp;")
                .replace("\"", "&quot;");

        String html =
                "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,"
                + "initial-scale=1'>"
                + "<style>html,body{margin:0;padding:0;background:#000;"
                + "width:100%;height:100%;}video{width:100%;height:100%;"
                + "object-fit:contain;background:#000;}</style>"
                + "</head><body>"
                + "<video controls autoplay playsinline>"
                + "<source src=\"" + escapedUrl + "\">"
                + "</video></body></html>";

        videoView.loadDataWithBaseURL(
                currentMediaUrl,
                html,
                "text/html",
                "UTF-8",
                null
        );

        videoLoaded = true;
    }

    private void pauseVideo() {
        if (videoView == null || !videoLoaded) {
            return;
        }

        videoView.evaluateJavascript(
                "document.querySelectorAll('video').forEach(v=>v.pause());"
                + "var f=document.getElementById('player');"
                + "if(f){f.contentWindow.postMessage("
                + "'{\"event\":\"command\",\"func\":\"pauseVideo\","
                + "\"args\":\"\"}','*');}",
                null
        );
    }

    private void resumeVideo() {
        if (videoView == null || !videoLoaded) {
            return;
        }

        videoView.evaluateJavascript(
                "document.querySelectorAll('video').forEach(v=>v.play());"
                + "var f=document.getElementById('player');"
                + "if(f){f.contentWindow.postMessage("
                + "'{\"event\":\"command\",\"func\":\"playVideo\","
                + "\"args\":\"\"}','*');}",
                null
        );
    }

    private void stopCurrentPlayback() {
        destroyVideoView();

        currentVideoId = "";
        currentMediaUrl = "";
        currentLyrics = "";
        videoLoaded = false;

        titleView.setText("DONUT4U KARAOKE");
        singerView.setVisibility(View.GONE);
        composerView.setVisibility(View.GONE);
        lyricsView.setText(
                "Waiting for a song from your phone..."
        );

        statusView.setText("PHONE CONNECTED");
    }

    private void destroyVideoView() {
        if (videoView != null) {
            try {
                videoContainer.removeView(videoView);
                videoView.stopLoading();
                videoView.loadUrl("about:blank");
                videoView.removeAllViews();
                videoView.destroy();
            } catch (Exception ignored) {
            }

            videoView = null;
        }
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(
                value * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onPhoneStateReceived(
            JSONObject state,
            long sequence
    ) {
        runOnUiThread(() -> applyPhoneState(state));
    }

    @Override
    public void onStatusChanged(String message) {
        runOnUiThread(() -> {
            if (!destroyed && statusView != null
                    && message != null
                    && message.toLowerCase().contains("disconnect")) {
                statusView.setText("PHONE DISCONNECTED");
            }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            if (!destroyed && statusView != null) {
                statusView.setText("TV CONNECTION ERROR");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();

        if (sessionController != null) {
            sessionController.setObserver(this);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;

        if (sessionController != null) {
            sessionController.setObserver(null);
        }

        destroyVideoView();

        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        super.onDestroy();
    }
}
