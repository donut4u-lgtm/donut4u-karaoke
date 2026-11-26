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
        @Override
        public void run() {
            if (destroyed || currentStage != 3 || tvMidiEngine == null) {
                return;
            }

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

            if (tvMidiEngine != null) {
                tvMidiEngine.stop();
            }

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
            }

            // Do not process the default "playing" state below.
            // A STOP command must remain stopped.
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
                displayHighlightedLyrics(
                        lyrics,
                        currentActiveLine,
                        currentActiveLineOffset
                );
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

        SpannableStringBuilder builder =
                new SpannableStringBuilder(lyrics);

        int lineStart = -1;
        int lineEnd = -1;

        if (activeLineOffset >= 0
                && activeLineOffset < lyrics.length()) {

            lineStart = lyrics.lastIndexOf(
                    "\n",
                    Math.max(0, activeLineOffset - 1)
            ) + 1;

            lineEnd = lyrics.indexOf("\n", activeLineOffset);

            if (lineEnd < 0) {
                lineEnd = lyrics.length();
            }

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

                lineEnd = newline < 0
                        ? lyrics.length()
                        : newline;
            }
        }

        if (lineStart >= 0
                && lineEnd > lineStart
                && lineEnd <= builder.length()) {

            builder.setSpan(
                    new ForegroundColorSpan(GREEN),
                    lineStart,
                    lineEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            builder.setSpan(
                    new BackgroundColorSpan(HIGHLIGHT_BACKGROUND),
                    lineStart,
                    lineEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            builder.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    lineStart,
                    lineEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        lyricsView.setText(builder);

        if (lineStart >= 0) {
            final int offset = lineStart;
            lyricsView.post(
                    () -> scrollTvLyricsToOffset(offset)
            );
        }
    }

    /* Render active word and line from the exact MIDI timing on TV. */
    private void updateTvMidiLyrics() {

        Stage3MidiLyrics parsed = tvMidiLyrics;

        if (destroyed
                || currentStage != 3
                || parsed == null
                || tvMidiEngine == null) {
            return;
        }

        if (!parsed.hasLyrics()) {

            /*
             * The phone already parses the selected Stage 3 MIDI/KAR
             * and sends its current lyric lines in the TV state.
             *
             * If the TV-side copy cannot expose embedded lyric events,
             * keep using those phone-supplied lyrics instead of replacing
             * them with an error message. This fallback affects TV only.
             */
            if (currentLyrics != null
                    && !currentLyrics.trim().isEmpty()) {

                displayHighlightedLyrics(
                        currentLyrics,
                        currentActiveLine,
                        currentActiveLineOffset
                );

                return;
            }

            String message =
                    "Waiting for Stage 3 lyrics from phone...";

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

        CharSequence rendered =
                parsed.styledFullTextAtTick(
                        tick,
                        tvMidiDurationTicks
                );

        /*
         * IMPORTANT TV-ONLY FALLBACK:
         * Some MIDI/KAR files are playable on the TV but the TV parser
         * can return an empty lyric result. Do not replace valid lyrics
         * already received from the phone with that empty result.
         */
        if (rendered == null
                || rendered.toString().trim().isEmpty()) {

            if (currentLyrics != null
                    && !currentLyrics.trim().isEmpty()) {

                displayHighlightedLyrics(
                        currentLyrics,
                        currentActiveLine,
                        currentActiveLineOffset
                );

            } else {

                String waitingMessage =
                        "Waiting for Stage 3 lyrics from phone...";

                if (!waitingMessage.contentEquals(lyricsView.getText())) {
                    lyricsView.setText(waitingMessage);
                }
            }

            return;
        }

        String plainText = rendered.toString();

        int wordStart = -1;
        int wordEnd = -1;

        if (rendered instanceof Spanned) {

            Spanned spanned = (Spanned) rendered;

            ForegroundColorSpan[] spans =
                    spanned.getSpans(
                            0,
                            spanned.length(),
                            ForegroundColorSpan.class
                    );

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

        int activeLine =
                parsed.activeLineNumberAtTick(tick);

        if (activeLine >= 0
                && activeLine != lastTvScrolledLine) {

            lastTvScrolledLine = activeLine;

            int offset = Math.max(
                    0,
                    parsed.activeLineStartOffsetAtTick(tick)
            );

            lyricsView.post(
                    () -> scrollTvLyricsToOffset(offset)
            );
        }
    }

    private void scrollTvLyricsToOffset(int requestedOffset) {

        if (destroyed
                || lyricsView == null
                || lyricsScroll == null) {
            return;
        }

        Layout layout = lyricsView.getLayout();

        if (layout == null || layout.getLineCount() == 0) {
            return;
        }

        int offset = Math.max(
                0,
                Math.min(requestedOffset, lyricsView.length())
        );

        int lineNumber = layout.getLineForOffset(offset);

        int lineTop = layout.getLineTop(lineNumber);
        int lineBottom = layout.getLineBottom(lineNumber);

        int viewportHeight = lyricsScroll.getHeight()
                - lyricsScroll.getPaddingTop()
                - lyricsScroll.getPaddingBottom();

        if (viewportHeight <= 0) {
            return;
        }

        int lineHeight = lineBottom - lineTop;

        int target = lineTop
                - (viewportHeight - lineHeight) / 2;

        int maxScroll = Math.max(
                0,
                lyricsView.getHeight() - viewportHeight
        );

        target = Math.max(
                0,
                Math.min(target, maxScroll)
        );

        if (Math.abs(target - lyricsScroll.getScrollY()) > dp(12)) {
            lyricsScroll.smoothScrollTo(0, target);
        }
    }

    private void startTvLyricUpdates() {

        lyricHandler.removeCallbacks(tvLyricUpdater);

        if (!destroyed && currentStage == 3) {
            lyricHandler.post(tvLyricUpdater);
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

        tvMidiLyrics = null;
        tvMidiDurationTicks = 0L;

        lastTvLyricText = "";
        lastTvWordStart = -1;
        lastTvWordEnd = -1;
        lastTvScrolledLine = Integer.MIN_VALUE;

        lyricHandler.removeCallbacks(tvLyricUpdater);

        final String parsedTransferId = transferId;

        imageExecutor.execute(() -> {

            Stage3MidiLyrics parsed =
                    Stage3MidiLyrics.read(midiFile);

            runOnUiThread(() -> {

                if (destroyed
                        || !parsedTransferId.equals(currentMidiTransferId)) {
                    return;
                }

                tvMidiLyrics = parsed;

                setMetadata(
                        singerView,
                        parsed.getSinger(),
                        "Singer: "
                );

                setMetadata(
                        composerView,
                        parsed.getComposer(),
                        "Composer: "
                );

                lastTvLyricText = "";
                lastTvWordStart = -1;
                lastTvWordEnd = -1;
                lastTvScrolledLine = Integer.MIN_VALUE;

                updateTvMidiLyrics();
            });
        });

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

                    tvMidiDurationTicks =
                            Math.max(0L, durationTicks);

                    statusView.setText(
                            "STAGE 3 AUDIO PLAYING ON TV"
                    );

                    startTvLyricUpdates();
                }

                @Override
                public void onError(String message) {

                    if (destroyed) {
                        return;
                    }

                    statusView.setText(
                            "TV MIDI ERROR: "
                                    + (message == null
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

        lyricHandler.removeCallbacks(tvLyricUpdater);

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
            startTvLyricUpdates();
        } catch (Exception ignored) {
        }
    }

    private void pauseAndRewindTvMidi() {

        if (tvMidiEngine == null) {
            return;
        }

        lyricHandler.removeCallbacks(tvLyricUpdater);

        try {

            tvMidiEngine.pause();
            tvMidiEngine.seekTo(0L);

            lastTvScrolledLine = Integer.MIN_VALUE;

            updateTvMidiLyrics();

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

        String html;

        if (currentStage == 1) {

            // Stage 1 only: use the same YouTube IFrame API
            // playback identity and startup method as Stage1TvActivity.

            html =
                    "<!DOCTYPE html>"
                            + "<html><head>"
                            + "<meta name='viewport' "
                            + "content='width=device-width,"
                            + "initial-scale=1,maximum-scale=1'>"
                            + "<meta name='referrer' "
                            + "content='strict-origin-when-cross-origin'>"
                            + "<style>"
                            + "html,body{margin:0;padding:0;"
                            + "width:100%;height:100%;"
                            + "background:#000;overflow:hidden;}"
                            + "#player{position:absolute;top:0;left:0;"
                            + "width:100%;height:100%;}"
                            + "</style>"
                            + "</head><body>"
                            + "<div id='player'></div>"
                            + "<script>"
                            + "var donutPlayer;"
                            + "function onYouTubeIframeAPIReady(){"
                            + "donutPlayer=new YT.Player('player',{"
                            + "width:'100%',height:'100%',"
                            + "videoId:'" + safeVideoId + "',"
                            + "playerVars:{"
                            + "playsinline:1,"
                            + "autoplay:1,"
                            + "controls:1,"
                            + "rel:0,"
                            + "modestbranding:1,"
                            + "enablejsapi:1,"
                            + "origin:'https://com.donut4u.karaoke'"
                            + "},"
                            + "events:{"
                            + "onReady:function(event){"
                            + "event.target.playVideo();"
                            + "},"
                            + "onError:function(event){"
                            + "document.title='YOUTUBE_ERROR_' + event.data;"
                            + "}"
                            + "}"
                            + "});"
                            + "}"
                            + "</script>"
                            + "<script "
                            + "src='https://www.youtube.com/iframe_api'>"
                            + "</script>"
                            + "</body></html>";

        } else {

            // Stage 2 and Stage 3 retain the previous iframe method.

            html =
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
        }

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

        lyricHandler.removeCallbacks(tvLyricUpdater);

        if (tvMidiEngine != null) {
            try {
                tvMidiEngine.stop();
            } catch (Exception ignored) {
            }
        }

        currentMidiTransferId = "";
        tvMidiLyrics = null;
        tvMidiDurationTicks = 0L;

        lastTvLyricText = "";
        lastTvWordStart = -1;
        lastTvWordEnd = -1;
        lastTvScrolledLine = Integer.MIN_VALUE;

        destroyVideoView();

        currentVideoId = "";
        currentMediaUrl = "";
        currentLyrics = "";

        currentActiveLine = -1;
        currentActiveLineOffset = -1;
        currentStage = 0;

        videoLoaded = false;

        titleView.setText("DONUT4U KARAOKE");
        stage12TitleView.setText("DONUT4U KARAOKE");

        singerView.setText("");
        singerView.setVisibility(View.GONE);

        composerView.setText("");
        composerView.setVisibility(View.GONE);

        lyricsView.setText("");

        lyricsScroll.scrollTo(0, 0);

        applyStageLayout(0);

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
                        .getDisplayMetrics().density
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

        lyricHandler.removeCallbacks(tvLyricUpdater);
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
