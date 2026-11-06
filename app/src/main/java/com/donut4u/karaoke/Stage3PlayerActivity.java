private void buildScreen() {

    stage = new FrameLayout(this);
    stage.setBackgroundColor(DARK_GREEN);

    backgroundImage = new ImageView(this);
    backgroundImage.setVisibility(View.GONE);
    backgroundImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
    backgroundImage.setBackgroundColor(DARK_GREEN);

    FrameLayout.LayoutParams imageParams =
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);

    stage.addView(backgroundImage, imageParams);

    backgroundTexture = new TextureView(this);
    backgroundTexture.setVisibility(View.GONE);
    backgroundTexture.setOpaque(false);

    FrameLayout.LayoutParams videoParams =
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);

    stage.addView(backgroundTexture, videoParams);

    backgroundTexture.addOnLayoutChangeListener(
            (v, left, top, right, bottom,
             oldLeft, oldTop, oldRight, oldBottom) -> {
                applyVideoFitMode();
            });

    overlay = new LinearLayout(this);
    overlay.setOrientation(LinearLayout.VERTICAL);
    overlay.setGravity(Gravity.CENTER);
    overlay.setPadding(
            dp(14), dp(8), dp(14), dp(8));

    FrameLayout.LayoutParams overlayParams =
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);

    stage.addView(overlay, overlayParams);

    applyBackgroundDarkness();

    songTitleLabel = new TextView(this);
    songTitleLabel.setText("");
    songTitleLabel.setTextColor(WHITE);
    songTitleLabel.setTextSize(19);
    songTitleLabel.setTypeface(
            Typeface.DEFAULT, Typeface.BOLD);
    songTitleLabel.setGravity(Gravity.CENTER);
    songTitleLabel.setMaxLines(2);
    songTitleLabel.setPadding(
            dp(8), dp(3), dp(8), dp(4));

    overlay.addView(
            songTitleLabel,
            new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

    singerLabel = new TextView(this);
    singerLabel.setText("");
    singerLabel.setTextColor(WHITE);
    singerLabel.setTextSize(15);
    singerLabel.setTypeface(
            Typeface.DEFAULT, Typeface.BOLD);
    singerLabel.setGravity(Gravity.CENTER);
    singerLabel.setPadding(
            dp(8), dp(1), dp(8), dp(3));
    singerLabel.setVisibility(View.GONE);

    overlay.addView(
            singerLabel,
            new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

    View lyricsTopGap = new View(this);

    overlay.addView(
            lyricsTopGap,
            new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(22)));

    FrameLayout lyricsContainer = new FrameLayout(this);

    LinearLayout.LayoutParams containerParams =
            new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f);

    lyricsScroll = new ScrollView(this);
    lyricsScroll.setFillViewport(true);
    lyricsScroll.setVerticalScrollBarEnabled(false);
    lyricsScroll.setHorizontalScrollBarEnabled(false);
    lyricsScroll.setBackgroundColor(Color.TRANSPARENT);
    lyricsScroll.setOverScrollMode(
            ScrollView.OVER_SCROLL_NEVER);
    lyricsScroll.setSmoothScrollingEnabled(true);
    lyricsScroll.setClipToPadding(false);
    lyricsScroll.setPadding(0, dp(4), 0, dp(4));

    lyricsContainer.addView(
            lyricsScroll,
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

    lyricsLabel = new TextView(this);
    lyricsLabel.setText("");
    lyricsLabel.setTextColor(WHITE);
    lyricsLabel.setTextSize(22);
    lyricsLabel.setTypeface(
            Typeface.DEFAULT, Typeface.BOLD);
    lyricsLabel.setGravity(Gravity.CENTER);
    lyricsLabel.setPadding(
            dp(10), dp(10), dp(10), dp(10));
    lyricsLabel.setLineSpacing(dp(6), 1.05f);
    lyricsLabel.setIncludeFontPadding(true);
    lyricsLabel.setMaxLines(Integer.MAX_VALUE);
    lyricsLabel.setBreakStrategy(
            Layout.BREAK_STRATEGY_HIGH_QUALITY);
    lyricsLabel.setHyphenationFrequency(
            Layout.HYPHENATION_FREQUENCY_NONE);

    lyricsScroll.addView(
            lyricsLabel,
            new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT));

    countdownLabel = new TextView(this);
    countdownLabel.setTextColor(ACTIVE_GREEN);
    countdownLabel.setTextSize(76);
    countdownLabel.setTypeface(
            Typeface.DEFAULT, Typeface.BOLD);
    countdownLabel.setGravity(Gravity.CENTER);
    countdownLabel.setShadowLayer(
            dp(12), 0, 0, Color.BLACK);
    countdownLabel.setVisibility(View.GONE);
    countdownLabel.setClickable(false);

    lyricsContainer.addView(
            countdownLabel,
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

    overlay.addView(lyricsContainer, containerParams);

    buildPlaybackControls();

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(DARK_GREEN);

    root.addView(
            stage,
            new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f));

    FrameLayout adHolder = new FrameLayout(this);
    adHolder.setBackgroundColor(DARK_GREEN);

    root.addView(
            adHolder,
            new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(250)));

    setContentView(root);

    loadStage3Ad(adHolder);

    getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
}
