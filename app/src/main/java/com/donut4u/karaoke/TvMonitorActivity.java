
package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TvMonitorActivity extends Activity
        implements TvSessionController.Observer {

    public static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final int BLACK = Color.BLACK;
    private static final int WHITE = Color.WHITE;
    private static final int GREEN = Color.rgb(50, 220, 130);
    private static final int MUTED = Color.rgb(180, 190, 190);

    private static final int HIGHLIGHT_BACKGROUND =
            Color.argb(75, 50, 220, 130);

    private static final String MIME_MIDI = "audio/midi";

    private static final String DEFAULT_SOUNDFONT =
            "builtin://default";

    /*
     * This must match the application identifier used by Stage 1.
     * YouTube requires the embedding application to identify itself
     * through the WebView Referer.
     */
    private static final String YOUTUBE_APP_ORIGIN =
            "https://com.donut4u.karaoke";

    private static final String YOUTUBE_APP_BASE_URL =
            YOUTUBE_APP_ORIGIN + "/";

    private TvSessionController sessionController;

    private FrameLayout root;
    private FrameLayout videoContainer;
    private ImageView backgroundImageView;
    private WebView videoView;

    private LinearLayout informationPanel;
    private FrameLayout.LayoutParams informationPanelParams;
    private LinearLayout.LayoutParams lyricsLayoutParams;

    private TextView titleView;
    private TextView singerView;
    private TextView composerView;
    private TextView lyricsView;
    private ScrollView lyricsScroll;
    private TextView statusView;

    private final ExecutorService imageExecutor =
            Executors.newSingleThreadExecutor();

    private volatile String currentBackgroundTransferId = "";

    private Stage3MidiEngine tvMidiEngine;

    private volatile String currentMidiTransferId = "";

    private String currentVideoId = "";
    private String currentMediaUrl = "";
    private String currentLyrics = "";

    private int currentActiveLine = -1;
    private int currentActiveLineOffset = -1;
    private int currentStage = 0;

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

        sessionController.startSession(this);
        sessionController.setObserver(this);

        tvMidiEngine = Stage3MidiEngine.create(this);

        JSONObject previousState =
                sessionController.getLastPhoneState();

        if (previousState != null) {
            applyPhoneState(previousState);
        }
    }

    // ------------------------------------------------
    // SCREEN
    // ------------------------------------------------

    private void buildScreen() {

        root = new FrameLayout(this);
        root.setBackgroundColor(BLACK);

        videoContainer = new FrameLayout(this);
        videoContainer.setBackgroundColor(BLACK);

        /*
         * The transferred phone image is the bottom layer.
         * Video playback is added above this ImageView.
         */
        backgroundImageView = new ImageView(this);
        backgroundImageView.setBackgroundColor(BLACK);
        backgroundImageView.setScaleType(
                ImageView.ScaleType.CENTER_CROP
        );
        backgroundImageView.setAdjustViewBounds(false);
        backgroundImageView.setVisibility(View.GONE);

        videoContainer.addView(
                backgroundImageView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

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
        informationPanel.setGravity(
                Gravity.CENTER_HORIZONTAL
        );
        informationPanel.setPadding(
                dp(35),
                dp(16),
                dp(35),
                dp(14)
        );
        informationPanel.setBackgroundColor(
                Color.argb(225, 5, 12, 12)
        );

        informationPanelParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                );

        root.addView(
                informationPanel,
                informationPanelParams
        );

        titleView = new TextView(this);
        titleView.setText("DONUT4U KARAOKE");
        titleView.setTextColor(GREEN);
        titleView.setTextSize(23);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
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
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setHorizontalScrollBarEnabled(false);
        lyricsScroll.setBackgroundColor(Color.TRANSPARENT);
        lyricsScroll.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8)
        );
        lyricsScroll.setClipToPadding(false);
        lyricsScroll.setOverScrollMode(
                ScrollView.OVER_SCROLL_NEVER
        );

        lyricsView = new TextView(this);
        lyricsView.setText(
                "Waiting for a song from your phone..."
        );
        lyricsView.setTextColor(WHITE);
        lyricsView.setTextSize(30);
        lyricsView.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setLineSpacing(dp(10), 1.08f);
        lyricsView.setIncludeFontPadding(true);
        lyricsView.setMaxLines(Integer.MAX_VALUE);
        lyricsView.setBreakStrategy(
                Layout.BREAK_STRATEGY_HIGH_QUALITY
        );

        lyricsScroll.addView(
                lyricsView,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.MATCH_PARENT
                )
        );

        /*
         * Stage 1 and Stage 2 retain the compact bottom information
         * panel. Stage 3 changes this to a full-screen lyrics area.
         */
        lyricsLayoutParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(190)
                );

        lyricsLayoutParams.topMargin = dp(8);

        informationPanel.addView(
                lyricsScroll,
                lyricsLayoutParams
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

    // ------------------------------------------------
    // STAGE LAYOUT
    // ------------------------------------------------

    private void applyStageLayout(int stage) {

        if (informationPanel == null
                || informationPanelParams == null
                || lyricsLayoutParams == null
                || lyricsView == null
                || lyricsScroll == null) {
            return;
        }

        if (stage == 3) {

            /*
             * Stage 3 uses the entire TV screen.
             * The lyrics are large and centered vertically
             * and horizontally instead of sitting at the bottom.
             */
            informationPanelParams.width =
                    FrameLayout.LayoutParams.MATCH_PARENT;

            informationPanelParams.height =
                    FrameLayout.LayoutParams.MATCH_PARENT;

            informationPanelParams.gravity = Gravity.FILL;

            informationPanel.setPadding(
                    dp(45),
                    dp(16),
                    dp(45),
                    dp(12)
            );

            informationPanel.setGravity(
                    Gravity.CENTER_HORIZONTAL
            );

            /*
             * Keep the background image visible behind the lyrics.
             */
            informationPanel.setBackgroundColor(
                    Color.argb(90, 5, 12, 12)
            );

            lyricsLayoutParams.height = 0;
            lyricsLayoutParams.weight = 1f;
            lyricsLayoutParams.topMargin = dp(2);

            lyricsView.setTextSize(36);
            lyricsView.setLineSpacing(dp(14), 1.08f);
            lyricsView.setGravity(Gravity.CENTER);

        } else {

            /*
             * Stages 1 and 2 retain their video-first layout.
             */
            informationPanelParams.width =
                    FrameLayout.LayoutParams.MATCH_PARENT;

            informationPanelParams.height =
                    FrameLayout.LayoutParams.WRAP_CONTENT;

            informationPanelParams.gravity = Gravity.BOTTOM;

            informationPanel.setPadding(
                    dp(30),
                    dp(14),
                    dp(30),
                    dp(18)
            );

            informationPanel.setGravity(
                    Gravity.CENTER_HORIZONTAL
            );

            informationPanel.setBackgroundColor(
                    Color.argb(225, 5, 12, 12)
            );

            lyricsLayoutParams.height = dp(190);
            lyricsLayoutParams.weight = 0f;
            lyricsLayoutParams.topMargin = dp(8);

            lyricsView.setTextSize(30);
            lyricsView.setLineSpacing(dp(9), 1.05f);
            lyricsView.setGravity(Gravity.CENTER);
        }

        informationPanel.setLayoutParams(
                informationPanelParams
        );

        lyricsScroll.setLayoutParams(
                lyricsLayoutParams
        );

        lyricsScroll.setFillViewport(true);
        lyricsView.requestLayout();
        lyricsScroll.requestLayout();
        informationPanel.requestLayout();
    }

    // ------------------------------------------------
    // PHONE STATE
    // ------------------------------------------------

    private void applyPhoneState(JSONObject state) {

        if (state == null || destroyed) {
            return;
        }

        int stage = state.optInt("stage", 0);

        if (stage < 1 || stage > 3) {
            return;
        }

        currentStage = stage;
        applyStageLayout(stage);

        if (stage != 3 && tvMidiEngine != null) {
            tvMidiEngine.stop();
            currentMidiTransferId = "";
        }

        String action = state.optString("action", "");

        if ("back".equalsIgnoreCase(action)) {
            stopCurrentPlayback();
            return;
        }

        if ("stop".equalsIgnoreCase(action)) {
            if (stage == 3) {
                pauseAndRewindTvMidi();
            } else {
                stopCurrentPlayback();
                return;
            }
        }

        String title = firstNonEmpty(
                state.optString("songTitle", ""),
                state.optString("title", ""),
                "DONUT4U KARAOKE"
        );

        String singer = state.optString("singer", "");
        String composer = state.optString("composer", "");

        titleView.setText(title);

        setMetadata(
                singerView,
                singer,
                "Singer: "
        );

        setMetadata(
                composerView,
                composer,
                "Composer: "
        );

        String lyrics = firstNonEmpty(
                state.optString("lyrics", ""),
                state.optString("lyricText", "")
        );

        int activeLine = state.optInt("activeLine", -1);

        int activeLineOffset =
                state.optInt("activeLineOffset", -1);

        if (stage == 3) {
            currentActiveLine = activeLine;
            currentActiveLineOffset = activeLineOffset;
        } else {
            currentActiveLine = -1;
            currentActiveLineOffset = -1;
        }

        if (!lyrics.equals(currentLyrics)) {
            currentLyrics = lyrics;

            if (lyrics.isEmpty()) {
                lyricsView.setText("");
            }
        }

        if (stage == 3) {

            displayHighlightedLyrics(
                    lyrics,
                    currentActiveLine,
                    currentActiveLineOffset
            );

        } else if (!lyrics.isEmpty()
                && !lyrics.equals(
                        lyricsView.getText().toString())) {

            lyricsView.setText(lyrics);
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

        if ("pause".equalsIgnoreCase(playback)
                || "paused".equalsIgnoreCase(playback)) {

            pauseVideo();

            if (stage == 3) {
                pauseTvMidi();
            }

        } else if ("play".equalsIgnoreCase(playback)
                || "playing".equalsIgnoreCase(playback)) {

            resumeVideo();

            if (stage == 3) {
                resumeTvMidi();
            }

        } else if ("stopped".equalsIgnoreCase(playback)
                || "stop".equalsIgnoreCase(playback)) {

            stopVideoOnly();

            if (stage == 3) {
                pauseAndRewindTvMidi();
            }
        }

        statusView.setText(
                "PHONE CONNECTED  •  STAGE " + stage
        );
    }

    // ------------------------------------------------
    // KARAOKE LYRICS
    // ------------------------------------------------

    private void displayHighlightedLyrics(
            String lyrics,
            int activeLine,
            int activeLineOffset
    ) {

        if (lyrics == null || lyrics.isEmpty()) {
            lyricsView.setText("");
            return;
        }

        SpannableStringBuilder builder =
                new SpannableStringBuilder(lyrics);

        int targetStart = -1;
        int targetEnd = -1;

        if (activeLineOffset >= 0
                && activeLineOffset < lyrics.length()) {

            targetStart = lyrics.lastIndexOf(
                    "\n",
                    Math.max(0, activeLineOffset - 1)
            ) + 1;

            targetEnd = lyrics.indexOf(
                    "\n",
                    activeLineOffset
            );

            if (targetEnd < 0) {
                targetEnd = lyrics.length();
            }

        } else {

            String[] lines = lyrics.split("\n", -1);

            int lineStart = 0;

            for (int i = 0; i < lines.length; i++) {

                int lineEnd =
                        lineStart + lines[i].length();

                if (i == activeLine && activeLine >= 0) {
                    targetStart = lineStart;
                    targetEnd = lineEnd;
                    break;
                }

                lineStart = lineEnd + 1;
            }
        }

        if (targetStart >= 0
                && targetEnd > targetStart
                && targetEnd <= builder.length()) {

            builder.setSpan(
                    new ForegroundColorSpan(GREEN),
                    targetStart,
                    targetEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            builder.setSpan(
                    new BackgroundColorSpan(
                            HIGHLIGHT_BACKGROUND
                    ),
                    targetStart,
                    targetEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            builder.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    targetStart,
                    targetEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        lyricsView.setText(builder);

        if (targetStart >= 0) {

            final int scrollOffset = targetStart;

            lyricsView.post(() -> {

                if (destroyed
                        || lyricsView == null
                        || lyricsScroll == null) {
                    return;
                }

                Layout layout = lyricsView.getLayout();

                if (layout == null) {
                    return;
                }

                int lineNumber =
                        layout.getLineForOffset(scrollOffset);

                int lineTop =
                        layout.getLineTop(lineNumber);

                int lineBottom =
                        layout.getLineBottom(lineNumber);

                /*
                 * Keep the currently active karaoke line near
                 * the vertical center of the TV screen.
                 */
                int lineCenter =
                        lineTop + ((lineBottom - lineTop) / 2);

                int viewportCenter =
                        lyricsScroll.getHeight() / 2;

                int targetScroll =
                        lineCenter - viewportCenter;

                lyricsScroll.smoothScrollTo(
                        0,
                        Math.max(0, targetScroll)
                );
            });
        }
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

    // ------------------------------------------------
    // TV BACKGROUND IMAGE AND MIDI RECEIVING
    // ------------------------------------------------

    @Override
    public void onPhoneMediaFileReceived(
            File file,
            String mimeType,
            String transferId
    ) {

        if (destroyed
                || file == null
                || !file.isFile()
                || !file.exists()
                || transferId == null
                || transferId.trim().isEmpty()) {
            return;
        }

        if (MIME_MIDI.equalsIgnoreCase(mimeType)) {
            startTvMidiPlayback(file, transferId);
            return;
        }

        if (!"image/jpeg".equalsIgnoreCase(mimeType)
                && !"image/png".equalsIgnoreCase(mimeType)
                && !"image/webp".equalsIgnoreCase(mimeType)) {
            return;
        }

        /*
         * Ignore duplicate delivery of the same transfer.
         */
        if (transferId.equals(currentBackgroundTransferId)) {
            return;
        }

        currentBackgroundTransferId = transferId;

        final String receivedTransferId = transferId;
        final String receivedMimeType = mimeType;
        final String receivedPath = file.getAbsolutePath();

        imageExecutor.execute(() -> {

            Bitmap bitmap = null;

            try {

                File receivedFile = new File(receivedPath);

                if (!receivedFile.isFile()
                        || !receivedFile.exists()
                        || receivedFile.length() <= 0L
                        || receivedFile.length()
                        > 12L * 1024L * 1024L) {

                    throw new IllegalArgumentException(
                            "Invalid TV background image file."
                    );
                }

                BitmapFactory.Options options =
                        new BitmapFactory.Options();

                options.inPreferredConfig =
                        Bitmap.Config.RGB_565;

                bitmap = BitmapFactory.decodeFile(
                        receivedFile.getAbsolutePath(),
                        options
                );

                if (bitmap == null) {
                    throw new IllegalArgumentException(
                            "TV could not decode the received image."
                    );
                }

            } catch (Exception e) {

                android.util.Log.e(
                        "TvMonitorActivity",
                        "Could not load transferred TV background.",
                        e
                );

                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }

                runOnUiThread(() -> {

                    if (!destroyed
                            && statusView != null
                            && receivedTransferId.equals(
                                    currentBackgroundTransferId
                            )) {

                        statusView.setText(
                                "TV BACKGROUND IMAGE COULD NOT LOAD"
                        );
                    }
                });

                return;
            }

            final Bitmap loadedBitmap = bitmap;

            runOnUiThread(() -> {

                if (destroyed
                        || backgroundImageView == null
                        || !receivedTransferId.equals(
                                currentBackgroundTransferId
                        )) {

                    if (!loadedBitmap.isRecycled()) {
                        loadedBitmap.recycle();
                    }

                    return;
                }

                backgroundImageView.setImageBitmap(
                        loadedBitmap
                );

                backgroundImageView.setVisibility(
                        View.VISIBLE
                );

                if (statusView != null) {
                    statusView.setText(
                            "PHONE BACKGROUND RECEIVED  •  "
                                    + receivedMimeType.toUpperCase()
                    );
                }
            });
        });
    }

    // ------------------------------------------------
    // STAGE 3 MIDI PLAYBACK ON TV
    // ------------------------------------------------

    private void startTvMidiPlayback(
            File midiFile,
            String transferId
    ) {

        if (destroyed
                || midiFile == null
                || !midiFile.isFile()
                || !midiFile.exists()
                || midiFile.length() <= 0L
                || midiFile.length() > 64L * 1024L * 1024L
                || transferId == null
                || transferId.trim().isEmpty()) {
            return;
        }

        if (transferId.equals(currentMidiTransferId)) {
            return;
        }

        currentMidiTransferId = transferId;

        if (tvMidiEngine == null) {
            tvMidiEngine = Stage3MidiEngine.create(this);
        }

        statusView.setText("LOADING STAGE 3 AUDIO ON TV...");

        try {
            tvMidiEngine.play(
                    midiFile.toURI().toString(),
                    DEFAULT_SOUNDFONT,
                    new Stage3MidiEngine.Callback() {

                @Override
                public void onReady(long durationTicks) {
                    if (destroyed) {
                        return;
                    }

                    statusView.setText(
                            "STAGE 3 AUDIO PLAYING ON TV"
                    );
                }

                @Override
                public void onError(String message) {
                    if (destroyed) {
                        return;
                    }

                    statusView.setText(
                            "TV MIDI ERROR: " +
                                    (message == null
                                            ? "Playback failed"
                                            : message)
                    );
                }

                @Override
                public void onFinished() {
                    if (destroyed) {
                        return;
                    }

                    statusView.setText(
                            "STAGE 3 SONG FINISHED"
                    );
                }
            });

        } catch (Exception e) {
            statusView.setText(
                    "TV MIDI ERROR: " + e.getMessage()
            );
        }
    }

    private void pauseTvMidi() {
        if (tvMidiEngine == null) {
            return;
        }

        try {
            tvMidiEngine.pause();
        } catch (Exception ignored) {
        }
    }

    private void resumeTvMidi() {
        if (tvMidiEngine == null) {
            return;
        }

        try {
            tvMidiEngine.resume();
        } catch (Exception ignored) {
        }
    }

    private void pauseAndRewindTvMidi() {
        if (tvMidiEngine == null) {
            return;
        }

        try {
            tvMidiEngine.pause();
            tvMidiEngine.seekTo(0L);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------
    // YOUTUBE VIDEO PLAYBACK
    // ------------------------------------------------

    private void playYouTube(String videoId) {

        if (videoId == null || videoId.trim().isEmpty()) {
            return;
        }

        String safeVideoId = videoId.trim();

        if (!safeVideoId.matches("[A-Za-z0-9_-]{6,20}")) {
            return;
        }

        currentVideoId = safeVideoId;
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

        videoView.setWebChromeClient(
                new WebChromeClient()
        );

        videoView.setWebViewClient(
                new WebViewClient()
        );

        videoContainer.addView(
                videoView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        /*
         * The base URL supplies the Android WebView Referer.
         * The iframe origin identifies the same Donut4U package.
         *
         * referrerpolicy is explicitly included so the browser
         * sends the required origin information to YouTube.
         */
        String html =
                "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,"
                + "initial-scale=1,maximum-scale=1'>"
                + "<meta name='referrer' "
                + "content='strict-origin-when-cross-origin'>"
                + "<style>"
                + "html,body{margin:0;padding:0;background:#000;"
                + "width:100%;height:100%;overflow:hidden;}"
                + "iframe{position:absolute;inset:0;width:100%;"
                + "height:100%;border:0;}"
                + "</style></head><body>"
                + "<iframe id='player' "
                + "src='https://www.youtube.com/embed/"
                + safeVideoId
                + "?enablejsapi=1&playsinline=1&controls=1"
                + "&rel=0&autoplay=1"
                + "&origin=https://com.donut4u.karaoke' "
                + "referrerpolicy='strict-origin' "
                + "allow='autoplay; encrypted-media; picture-in-picture' "
                + "allowfullscreen></iframe>"
                + "</body></html>";

        videoView.loadDataWithBaseURL(
                YOUTUBE_APP_BASE_URL,
                html,
                "text/html",
                "UTF-8",
                null
        );

        videoLoaded = true;
    }

    // ------------------------------------------------
    // DIRECT MEDIA PLAYBACK
    // ------------------------------------------------

    private void playMediaUrl(String mediaUrl) {

        if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
            return;
        }

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

        videoView.setWebChromeClient(
                new WebChromeClient()
        );

        videoView.setWebViewClient(
                new WebViewClient()
        );

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

    // ------------------------------------------------
    // PLAYBACK CONTROLS
    // ------------------------------------------------

    private void pauseVideo() {

        if (videoView == null || !videoLoaded) {
            return;
        }

        videoView.evaluateJavascript(
                "document.querySelectorAll('video').forEach(v=>v.pause());"
                + "var f=document.getElementById('player');"
                + "if(f){f.contentWindow.postMessage("
                + "'{\\\"event\\\":\\\"command\\\",\\\"func\\\":\\\"pauseVideo\\\","
                + "\\\"args\\\":\\\"\\\"}','*');}",
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
                + "'{\\\"event\\\":\\\"command\\\",\\\"func\\\":\\\"playVideo\\\","
                + "\\\"args\\\":\\\"\\\"}','*');}",
                null
        );
    }

    private void stopVideoOnly() {
        pauseVideo();
    }

    private void stopCurrentPlayback() {

        if (tvMidiEngine != null) {
            try {
                tvMidiEngine.stop();
            } catch (Exception ignored) {
            }
        }

        currentMidiTransferId = "";

        destroyVideoView();

        currentVideoId = "";
        currentMediaUrl = "";
        currentLyrics = "";

        currentActiveLine = -1;
        currentActiveLineOffset = -1;
        currentStage = 0;

        videoLoaded = false;

        titleView.setText("DONUT4U KARAOKE");

        singerView.setVisibility(View.GONE);
        composerView.setVisibility(View.GONE);

        lyricsView.setText(
                "Waiting for a song from your phone..."
        );

        lyricsScroll.scrollTo(0, 0);

        applyStageLayout(0);

        statusView.setText("PHONE CONNECTED");

        /*
         * Keep the transferred background image.
         * Stopping a song must not remove the user's image.
         */
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

    // ------------------------------------------------
    // GENERAL HELPERS
    // ------------------------------------------------

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

    // ------------------------------------------------
    // SESSION CALLBACKS
    // ------------------------------------------------

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

            if (!destroyed
                    && statusView != null
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

        imageExecutor.shutdownNow();

        if (tvMidiEngine != null) {
            try {
                tvMidiEngine.release();
            } catch (Exception ignored) {
            }
            tvMidiEngine = null;
        }

        destroyVideoView();

        if (backgroundImageView != null) {
            backgroundImageView.setImageDrawable(null);
        }

        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        super.onDestroy();
    }
}
