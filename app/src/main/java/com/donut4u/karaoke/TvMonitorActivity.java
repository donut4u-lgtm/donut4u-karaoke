package com.donut4u.karaoke;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spanned;
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

    private static final int TV_ACTIVE_GREEN =
            Color.rgb(0, 255, 100);

    private static final String MIME_MIDI = "audio/midi";

    private static final String DEFAULT_SOUNDFONT =
            "builtin://default";

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
    private TextView stage12TitleView;
    private TextView singerView;
    private TextView composerView;
    private TextView lyricsView;
    private ScrollView lyricsScroll;
    private TextView statusView;

    private final ExecutorService imageExecutor =
            Executors.newSingleThreadExecutor();

    private final Handler lyricHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable tvLyricUpdater = new Runnable() {
        @Override public void run() {
            if (destroyed || currentStage != 3 || tvMidiEngine == null) return;
            updateTvMidiLyrics();
            lyricHandler.postDelayed(this, 33L);
        }
    };

    private volatile String currentBackgroundTransferId = "";

    private Stage3MidiEngine tvMidiEngine;
    private volatile Stage3MidiLyrics tvMidiLyrics;
    private volatile long tvMidiDurationTicks;
    private String lastTvLyricText = "";
    private int lastTvWordStart = -1;
    private int lastTvWordEnd = -1;
    private int lastTvScrolledLine = Integer.MIN_VALUE;

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

        stage12TitleView = new TextView(this);
        stage12TitleView.setText("DONUT4U KARAOKE");
        stage12TitleView.setTextColor(GREEN);
        stage12TitleView.setTextSize(23);
        stage12TitleView.setGravity(Gravity.CENTER);
        stage12TitleView.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        stage12TitleView.setMaxLines(2);
        stage12TitleView.setPadding(
                dp(22),
                dp(8),
                dp(22),
                dp(8)
        );
        stage12TitleView.setBackgroundColor(
                Color.argb(175, 5, 12, 12)
        );
        stage12TitleView.setVisibility(View.GONE);
        stage12TitleView.setElevation(dp(8));

        FrameLayout.LayoutParams stage12TitleParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );

        stage12TitleParams.leftMargin = dp(30);
        stage12TitleParams.rightMargin = dp(30);
        stage12TitleParams.topMargin = dp(24);

        root.addView(
                stage12TitleView,
                stage12TitleParams
        );

        informationPanel.setVisibility(View.GONE);

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
        lyricsView.setText("");
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
                || lyricsScroll == null
                || stage12TitleView == null) {
            return;
        }

        if (stage == 3) {

            informationPanel.setVisibility(View.VISIBLE);
            stage12TitleView.setVisibility(View.GONE);

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

            informationPanel.setBackgroundColor(
                    Color.argb(90, 5, 12, 12)
            );

            lyricsLayoutParams.height = 0;
            lyricsLayoutParams.weight = 1f;
            lyricsLayoutParams.topMargin = dp(2);

            lyricsView.setTextSize(36);

            lyricsView.setLineSpacing(dp(8), 1.08f);
            lyricsView.setGravity(Gravity.CENTER);

        } else {

            informationPanel.setVisibility(View.GONE);

            if (stage == 1 || stage == 2) {
                stage12TitleView.setVisibility(View.VISIBLE);
            } else {
                stage12TitleView.setVisibility(View.GONE);
            }

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

        if (stage != 3) {
            lyricHandler.removeCallbacks(tvLyricUpdater);
            tvMidiLyrics = null;
            tvMidiDurationTicks = 0L;
            lastTvLyricText = "";
            lastTvWordStart = -1;
            lastTvWordEnd = -1;
            lastTvScrolledLine = Integer.MIN_VALUE;
            if (tvMidiEngine != null) tvMidiEngine.stop();
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
        stage12TitleView.setText(title);

        if (stage == 3) {

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

        } else {

            singerView.setText("");
            singerView.setVisibility(View.GONE);

            composerView.setText("");
            composerView.setVisibility(View.GONE);
        }

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

        currentLyrics = lyrics;

        if (stage == 3) {
            if (tvMidiLyrics != null && tvMidiLyrics.hasLyrics()) {
                updateTvMidiLyrics();
            } else {
                displayHighlightedLyrics(lyrics, currentActiveLine,
                        currentActiveLineOffset);
            }
        } else {

            lyricsView.setText("");
            lyricsScroll.scrollTo(0, 0);
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

    /*
     * Fallback while the TV MIDI file is arriving. The definitive
     * lyrics are parsed from that MIDI file and timed by the TV player.
     * No display-only newline is inserted, so source offsets stay valid.
     */
    private void displayHighlightedLyrics(
            String lyrics,
            int activeLine,
            int activeLineOffset
    ) {
        if (lyrics == null || lyrics.isEmpty()) {
            lyricsView.setText("");
            lyricsScroll.scrollTo(0, 0);
            return;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(lyrics);
        int lineStart = -1;
        int lineEnd = -1;
        if (activeLineOffset >= 0 && activeLineOffset < lyrics.length()) {
            lineStart = lyrics.lastIndexOf("\n",
                    Math.max(0, activeLineOffset - 1)) + 1;
            lineEnd = lyrics.indexOf("\n", activeLineOffset);
            if (lineEnd < 0) lineEnd = lyrics.length();
        } else if (activeLine >= 0) {
            int start = 0;
            int line = 0;
            while (line < activeLine && start < lyrics.length()) {
                int newline = lyrics.indexOf('\n', start);
                if (newline < 0) {
                    start = lyrics.length();
                    break;
                }
                start = newline + 1;
                line++;
            }
            if (line == activeLine && start <= lyrics.length()) {
                lineStart = start;
                int newline = lyrics.indexOf('\n', start);
                lineEnd = newline < 0 ? lyrics.length() : newline;
            }
        }
        if (lineStart >= 0 && lineEnd > lineStart
                && lineEnd <= builder.length()) {
            builder.setSpan(new ForegroundColorSpan(GREEN), lineStart, lineEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new BackgroundColorSpan(HIGHLIGHT_BACKGROUND),
                    lineStart, lineEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        lyricsView.setText(builder);
        if (lineStart >= 0) {
            final int offset = lineStart;
            lyricsView.post(() -> scrollTvLyricsToOffset(offset));
        }
    }

    /* Render active word and line from the exact MIDI timing on TV. */
    private void updateTvMidiLyrics() {
        Stage3MidiLyrics parsed = tvMidiLyrics;
        if (destroyed || currentStage != 3 || parsed == null
                || tvMidiEngine == null) return;
        if (!parsed.hasLyrics()) {
            String message = "No embedded lyrics found in this MIDI/KAR file.";
            if (!message.contentEquals(lyricsView.getText())) {
                lyricsView.setText(message);
            }
            return;
        }
        long tick;
        try {
            tick = tvMidiEngine.getCurrentTick();
        } catch (Exception error) {
            return;
        }
        CharSequence rendered = parsed.styledFullTextAtTick(
                tick, tvMidiDurationTicks);
        if (rendered == null) return;
        String plainText = rendered.toString();
        int wordStart = -1;
        int wordEnd = -1;
        if (rendered instanceof Spanned) {
            Spanned spanned = (Spanned) rendered;
            ForegroundColorSpan[] spans = spanned.getSpans(
                    0, spanned.length(), ForegroundColorSpan.class);
            for (ForegroundColorSpan span : spans) {
                if (span.getForegroundColor() == TV_ACTIVE_GREEN) {
                    wordStart = spanned.getSpanStart(span);
                    wordEnd = spanned.getSpanEnd(span);
                    break;
                }
            }
        }
        if (!plainText.equals(lastTvLyricText)
                || wordStart != lastTvWordStart
                || wordEnd != lastTvWordEnd) {
            lyricsView.setText(rendered);
            lastTvLyricText = plainText;
            lastTvWordStart = wordStart;
            lastTvWordEnd = wordEnd;
        }
        int activeLine = parsed.activeLineNumberAtTick(tick);
        if (activeLine >= 0 && activeLine != lastTvScrolledLine) {
            lastTvScrolledLine = activeLine;
            int offset = Math.max(0,
                    parsed.activeLineStartOffsetAtTick(tick));
            lyricsView.post(() -> scrollTvLyricsToOffset(offset));
        }
