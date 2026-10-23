package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class KaraokeStudioActivity extends Activity {

    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/7288125709";

    private static final int BG = Color.rgb(18, 18, 24);
    private static final int PANEL = Color.rgb(35, 35, 46);
    private static final int ACCENT = Color.rgb(255, 185, 70);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(195, 195, 205);

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout resultsContainer;
    private EditText searchInput;
    private TextView statusText;
    private TextView nowPlayingText;
    private TextView lyricsText;
    private ProgressBar progressBar;
    private Button playButton;
    private Button pauseButton;
    private Button stopButton;
    private SurfaceView videoSurfaceView;

    private ExoPlayer player;
    private AdView adView;

    private final List<SongResult> currentResults =
            new ArrayList<>();

    private String currentTitle = "";
    private String currentArtist = "";
    private String currentVideoUrl = "";

    private boolean extractorInitialized = false;
    private boolean searching = false;
    private boolean loadingPlayback = false;

    private static final class SongResult {
        final String title;
        final String uploader;
        final String url;
        final long durationSeconds;
        final int score;

        SongResult(
                String title,
                String uploader,
                String url,
                long durationSeconds,
                int score
        ) {
            this.title = title;
            this.uploader = uploader;
            this.url = url;
            this.durationSeconds = durationSeconds;
            this.score = score;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();

        if (user == null || !user.isEmailVerified()) {
            Toast.makeText(
                    this,
                    "Please sign in with a verified account first.",
                    Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        buildInterface();
        initializeAds();

        try {
            NewPipe.init(
                    new AndroidDownloader(),
                    Localization.DEFAULT,
                    ContentCountry.DEFAULT
            );

            extractorInitialized = true;

        } catch (Exception e) {
            android.util.Log.e(
                    "Donut4UKaraoke",
                    "NewPipe initialization failed",
                    e
            );

            setStatus("Search initialization failed.");
        }
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(BG);

        scroll.addView(root);
        setContentView(scroll);

        TextView title = text(
                "DONUT4U KARAOKE",
                25,
                ACCENT,
                true
        );
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text(
                "Find a song. Play. Sing.",
                15,
                MUTED,
                false
        );
        subtitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.bottomMargin = dp(18);
        root.addView(subtitle, subtitleParams);

        addSectionTitle("FIND MY SONG");

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(WHITE);
        searchInput.setHintTextColor(MUTED);
        searchInput.setHint("karaoke - air supply");
        searchInput.setTextSize(16);
        searchInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchInput.setBackgroundColor(PANEL);

        root.addView(searchInput, matchWrap());

        TextView searchHelp = text(
                "Search for karaoke, instrumental, minus-one, or sing-along tracks.",
                13,
                MUTED,
                false
        );

        LinearLayout.LayoutParams helpParams = matchWrap();
        helpParams.topMargin = dp(5);
        helpParams.bottomMargin = dp(8);
        root.addView(searchHelp, helpParams);

        Button searchButton = button("SEARCH SONGS");
        root.addView(searchButton, matchWrap());
        searchButton.setOnClickListener(v -> searchSongs());

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);

        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(8);
        root.addView(progressBar, progressParams);

        statusText = text(
                "Enter a song title or artist to begin.",
                14,
                MUTED,
                false
        );

        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        statusParams.bottomMargin = dp(12);
        root.addView(statusText, statusParams);

        addSectionTitle("ONLINE SONG RESULTS");

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, matchWrap());

        addSectionTitle("KARAOKE PLAYER");

        nowPlayingText = text(
                "No song selected",
                16,
                WHITE,
                true
        );
        root.addView(nowPlayingText, matchWrap());

        videoSurfaceView = new SurfaceView(this);
        videoSurfaceView.setBackgroundColor(Color.BLACK);

        LinearLayout.LayoutParams videoParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(220)
                );

        videoParams.topMargin = dp(10);
        videoParams.bottomMargin = dp(6);

        root.addView(videoSurfaceView, videoParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, dp(10));

        playButton = button("PLAY");
        pauseButton = button("PAUSE");
        stopButton = button("STOP");

        controls.addView(playButton, weightedWrap());
        controls.addView(pauseButton, weightedWrap());
        controls.addView(stopButton, weightedWrap());

        root.addView(controls, matchWrap());

        playButton.setOnClickListener(v -> {
            if (player != null) {
                player.play();

            } else if (!currentVideoUrl.isEmpty()) {
                loadAndPlay(
                        currentVideoUrl,
                        currentTitle,
                        currentArtist
                );

            } else {
                Toast.makeText(
                        this,
                        "Choose a song first.",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        pauseButton.setOnClickListener(v -> {
            if (player != null) {
                player.pause();
            }
        });

        stopButton.setOnClickListener(v -> stopPlayback());

        addSectionTitle("LYRICS");

        lyricsText = text(
                "Lyrics will appear here when available.",
                15,
                WHITE,
                false
        );

        lyricsText.setPadding(dp(12), dp(12), dp(12), dp(12));
        lyricsText.setBackgroundColor(PANEL);

        root.addView(lyricsText, matchWrap());

        addSectionTitle("SUPPORT DONUT4U KARAOKE");

        TextView donateMessage = text(
                "Help keep running and growing DONATE",
                15,
                MUTED,
                false
        );
        root.addView(donateMessage, matchWrap());

        Button donateButton = button("DONATE");
        root.addView(donateButton, matchWrap());

        donateButton.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Support Donut4U Karaoke")
                        .setMessage(
                                "Thank you for supporting Donut4U Karaoke. " +
                                "Please use the official donation details " +
                                "provided by the app owner."
                        )
                        .setPositiveButton("OK", null)
                        .show()
        );

        Button questionButton = button("QUESTION AND ANSWER");
        root.addView(questionButton, matchWrap());

        questionButton.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Question and Answer")
                        .setMessage(
                                "For help, please use the official " +
                                "Donut4U Karaoke Questions and Suggestions channel."
                        )
                        .setPositiveButton("OK", null)
                        .show()
        );

        Button backButton = button("BACK TO STAGE");
        root.addView(backButton, matchWrap());
        backButton.setOnClickListener(v -> finish());

        Button logoutButton = button("LOG OUT");
        root.addView(logoutButton, matchWrap());

        logoutButton.setOnClickListener(v -> {
            stopPlayback();
            FirebaseAuth.getInstance().signOut();

            Intent intent = new Intent();
            intent.setClassName(
                    this,
                    "com.donut4u.karaoke.AuthActivity"
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            );

            startActivity(intent);
            finish();
        });

        View bottomSpace = new View(this);
        root.addView(
                bottomSpace,
                new LinearLayout.LayoutParams(1, dp(12))
        );

        TextView adLabel = text(
                "Advertisement",
                11,
                MUTED,
                false
        );

        adLabel.setGravity(Gravity.CENTER);
        root.addView(adLabel, matchWrap());

        adView = new AdView(this);
        adView.setAdSize(AdSize.BANNER);
        adView.setAdUnitId(AD_UNIT_ID);
        root.addView(adView, matchWrap());
    }

    private void addSectionTitle(String label) {
        TextView heading = text(label, 17, ACCENT, true);

        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(18);
        params.bottomMargin = dp(8);

        root.addView(heading, params);
    }

    private void searchSongs() {
        if (searching) {
            return;
        }

        String query = searchInput.getText().toString().trim();

        if (query.isEmpty()) {
            searchInput.setError("Enter a song title or artist");
            return;
        }

        hideKeyboard();

        if (!extractorInitialized) {
            setStatus("Search is not ready. Please restart the app.");
            return;
        }

        searching = true;
        progressBar.setVisibility(View.VISIBLE);
        resultsContainer.removeAllViews();
        currentResults.clear();

        setStatus("Searching YouTube for song results…");

        new Thread(() -> {
            List<SongResult> found = new ArrayList<>();
            String error = null;

            try {
                StreamingService service =
                        NewPipe.getService("YouTube");

                SearchQueryHandler handler =
                        service.getSearchQHFactory().fromQuery(query);

                SearchExtractor extractor =
                        service.getSearchExtractor(handler);

                extractor.fetchPage();

                SearchInfo info = SearchInfo.getInfo(extractor);

                for (org.schabi.newpipe.extractor.InfoItem item
                        : info.getRelatedItems()) {

                    if (!(item instanceof StreamInfoItem)) {
                        continue;
                    }

                    StreamInfoItem streamItem =
                            (StreamInfoItem) item;

                    String title = safe(streamItem.getName());
                    String url = safe(streamItem.getUrl());
                    String uploader = safe(streamItem.getUploaderName());

                    if (title.isEmpty() || url.isEmpty()) {
                        continue;
                    }

                    int score = scoreResult(title, uploader, query);

                    found.add(new SongResult(
                            title,
                            uploader,
                            url,
                            streamItem.getDuration(),
                            score
                    ));
                }

                Collections.sort(
                        found,
                        (a, b) -> Integer.compare(b.score, a.score)
                );

            } catch (Exception e) {
                error = e.getMessage();

                android.util.Log.e(
                        "Donut4UKaraoke",
                        "YouTube search failed",
                        e
                );
            }

            final List<SongResult> results = found;
            final String failure = error;

            mainHandler.post(() -> {
                searching = false;
                progressBar.setVisibility(View.GONE);

                if (results.isEmpty()) {
                    setStatus(
                            "No results loaded. The source may be " +
                            "temporarily unavailable. Try again later."
                    );

                    if (failure != null && !failure.trim().isEmpty()) {
                        android.util.Log.e(
                                "Donut4UKaraoke",
                                "Search detail: " + failure
                        );
                    }

                    return;
                }

                currentResults.addAll(results);
                setStatus(results.size() + " song results found.");

                for (SongResult result : results) {
                    addSongResult(result);
                }
            });
        }).start();
    }

    private int scoreResult(
            String title,
            String uploader,
            String query
    ) {
        String content =
                (title + " " + uploader).toLowerCase(Locale.ROOT);

        int score = 0;

        if (content.contains("karaoke")) score += 100;
        if (content.contains("instrumental")) score += 90;
        if (content.contains("minus one")) score += 90;
        if (content.contains("minus-one")) score += 90;
        if (content.contains("sing along")) score += 70;
        if (content.contains("sing-along")) score += 70;
        if (content.contains("backing track")) score += 65;
        if (content.contains("lyrics")) score += 10;
        if (content.contains("official audio")) score -= 10;
        if (content.contains("official music video")) score -= 15;

        String[] words =
                query.toLowerCase(Locale.ROOT).split("\\s+");

        for (String word : words) {
            if (word.length() > 2 && content.contains(word)) {
                score += 3;
            }
        }

        return score;
    }

    private void addSongResult(SongResult result) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = dp(8);

        resultsContainer.addView(card, cardParams);

        TextView songTitle = text(
                result.title,
                16,
                WHITE,
                true
        );
        card.addView(songTitle, matchWrap());

        String details = result.uploader;

        if (result.durationSeconds > 0) {
            details += " • " + formatDuration(result.durationSeconds);
        }

        TextView metadata = text(details, 12, MUTED, false);

        LinearLayout.LayoutParams metaParams = matchWrap();
        metaParams.topMargin = dp(4);

        card.addView(metadata, metaParams);

        Button play = button("PLAY IN DONUT4U");

        LinearLayout.LayoutParams playParams = matchWrap();
        playParams.topMargin = dp(8);

        card.addView(play, playParams);

        play.setOnClickListener(v ->
                loadAndPlay(
                        result.url,
                        result.title,
                        result.uploader
                )
        );
    }

    private void loadAndPlay(
            String url,
            String title,
            String artist
    ) {
        if (loadingPlayback) {
            return;
        }

        loadingPlayback = true;
        currentVideoUrl = url;
        currentTitle = title;
        currentArtist = artist;

        nowPlayingText.setText("Loading: " + title);
        setStatus("Preparing video and audio…");

        new Thread(() -> {
            String extractedVideoUrl = null;
            String errorMessage = null;

            try {
                StreamInfo info = StreamInfo.getInfo(url);
                List<VideoStream> streams = info.getVideoStreams();

                if (streams != null && !streams.isEmpty()) {
                    VideoStream best = null;
                    int bestHeight = -1;

                    for (VideoStream stream : streams) {
                        if (stream == null
                                || stream.isVideoOnly()
                                || !stream.isUrl()) {
                            continue;
                        }

                        String content = stream.getContent();

                        if (content == null
                                || !(content.startsWith("https://")
                                || content.startsWith("http://"))) {
                            continue;
                        }

                        int height = stream.getHeight();

                        if (best == null || height > bestHeight) {
                            best = stream;
                            bestHeight = height;
                        }
                    }

                    if (best != null) {
                        extractedVideoUrl = best.getContent();
                    }
                }

                if (extractedVideoUrl == null) {
                    errorMessage =
                            "No compatible combined video-and-audio stream was found.";
                }

            } catch (Exception e) {
                errorMessage = e.getMessage();

                android.util.Log.e(
                        "Donut4UKaraoke",
                        "Video extraction failed",
                        e
                );
            }

            final String videoUrl = extractedVideoUrl;
            final String failure = errorMessage;

            mainHandler.post(() -> {
                loadingPlayback = false;

                if (videoUrl == null || videoUrl.isEmpty()) {
                    setStatus(
                            "Video is unavailable for this result. " +
                            "Try another song."
                    );

                    if (failure != null) {
                        android.util.Log.e(
                                "Donut4UKaraoke",
                                "Video extraction detail: " + failure
                        );
                    }

                    return;
                }

                startVideo(videoUrl, title, artist);
                lookupLyrics(title, artist);
            });
        }).start();
    }

    private void startVideo(
            String videoUrl,
            String title,
            String artist
    ) {
        try {
            if (player != null) {
                player.release();
                player = null;
            }

            player = new ExoPlayer.Builder(this).build();
            player.setVideoSurfaceView(videoSurfaceView);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    setStatus(
                            "Playback failed. Please try another result."
                    );

                    android.util.Log.e(
                            "Donut4UKaraoke",
                            "Video playback failed",
                            error
                    );
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        setStatus("Ready to sing.");

                    } else if (state == Player.STATE_ENDED) {
                        setStatus("Song finished.");
                    }
                }
            });

            player.setMediaItem(MediaItem.fromUri(videoUrl));
            player.prepare();
            player.play();

            nowPlayingText.setText(
                    title + (artist.isEmpty() ? "" : "\n" + artist)
            );

            setStatus("Playing video and audio in Donut4U Karaoke.");

        } catch (Exception e) {
            setStatus("Unable to start video playback.");

            android.util.Log.e(
                    "Donut4UKaraoke",
                    "Media3 video playback error",
                    e
            );
        }
    }

    private void lookupLyrics(String title, String artist) {
        lyricsText.setText("Looking for lyrics…");

        new Thread(() -> {
            String foundLyrics = null;

            try {
                String query = title;

                if (artist != null && !artist.trim().isEmpty()) {
                    query = title + " " + artist;
                }

                String encoded = URLEncoder.encode(
                        query,
                        StandardCharsets.UTF_8.name()
                );

                String apiUrl =
                        "https://lrclib.net/api/search?q=" + encoded;

                String response = httpGet(apiUrl);
                JSONArray array = new JSONArray(response);

                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);

                    if (item == null) {
                        continue;
                    }

                    String plain = item.optString("plainLyrics", "");
                    String synced = item.optString("syncedLyrics", "");

                    if (!plain.trim().isEmpty()) {
                        foundLyrics = plain;
                        break;
                    }

                    if (!synced.trim().isEmpty()) {
                        foundLyrics = synced;
                        break;
                    }
                }

            } catch (Exception e) {
                android.util.Log.w(
                        "Donut4UKaraoke",
                        "Lyrics unavailable",
                        e
                );
            }

            final String lyrics = foundLyrics;

            mainHandler.post(() -> {
                if (lyrics == null || lyrics.trim().isEmpty()) {
                    lyricsText.setText(
                            "Lyrics are not available for this song."
                    );
                } else {
                    lyricsText.setText(lyrics);
                }
            });
        }).start();
    }

    private String httpGet(String address) throws Exception {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(address).openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);

            connection.setRequestProperty(
                    "User-Agent",
                    "Donut4U-Karaoke/1.0"
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            int code = connection.getResponseCode();

            InputStream input =
                    code >= 200 && code < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();

            String body = readFully(input);

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }

            return body;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readFully(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }

        try (InputStream in = input;
             ByteArrayOutputStream out =
                     new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int count;

            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }

            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }

        currentVideoUrl = "";
        currentTitle = "";
        currentArtist = "";

        nowPlayingText.setText("No song selected");
        lyricsText.setText("Lyrics will appear here when available.");

        setStatus("Playback stopped.");
    }

    private void initializeAds() {
        try {
            MobileAds.initialize(this, initializationStatus -> {});

            if (adView != null) {
                adView.loadAd(new AdRequest.Builder().build());
            }

        } catch (Exception e) {
            android.util.Log.w(
                    "Donut4UKaraoke",
                    "Ads initialization failed",
                    e
            );
        }
    }

    private void setStatus(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }
    }

    private TextView text(
            String value,
            int size,
            int color,
            boolean bold
    ) {
        TextView view = new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);

        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);

        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setAllCaps(false);

        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ACCENT)
        );

        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedWrap() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1
                );

        params.setMargins(dp(2), 0, dp(2), 0);

        return params;
    }

    private int dp(int value) {
        return (int) (
                value *
                getResources().getDisplayMetrics().density + 0.5f
        );
    }

    private String formatDuration(long seconds) {
        if (seconds < 0) {
            return "Duration unknown";
        }

        long minutes = seconds / 60;
        long remainder = seconds % 60;

        return String.format(
                Locale.getDefault(),
                "%d:%02d",
                minutes,
                remainder
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE
                    );

            View focus = getCurrentFocus();

            if (manager != null && focus != null) {
                manager.hideSoftInputFromWindow(
                        focus.getWindowToken(),
                        0
                );

                focus.clearFocus();
            }

        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (adView != null) {
            adView.resume();
        }
    }

    @Override
    protected void onPause() {
        if (adView != null) {
            adView.pause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
            adView = null;
        }

        if (player != null) {
            player.release();
            player = null;
        }

        mainHandler.removeCallbacksAndMessages(null);

        super.onDestroy();
    }

    private static final class AndroidDownloader extends Downloader {

        @Override
        public Response execute(Request request)
                throws IOException, ReCaptchaException {

            HttpURLConnection connection = null;

            try {
                URL url = new URL(request.url());

                connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod(request.httpMethod());
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(25000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) " +
                        "AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
                );

                Map<String, List<String>> headers = request.headers();

                if (headers != null) {
                    for (Map.Entry<String, List<String>> entry
                            : headers.entrySet()) {

                        String name = entry.getKey();
                        List<String> values = entry.getValue();

                        if (name == null || values == null) {
                            continue;
                        }

                        for (String value : values) {
                            if (value != null) {
                                connection.addRequestProperty(
                                        name,
                                        value
                                );
                            }
                        }
                    }
                }

                byte[] data = request.dataToSend();

                if (data != null
                        && ("POST".equalsIgnoreCase(request.httpMethod())
                        || "PUT".equalsIgnoreCase(request.httpMethod()))) {

                    connection.setDoOutput(true);

                    try (java.io.OutputStream output =
                                 connection.getOutputStream()) {
                        output.write(data);
                    }
                }

                int code = connection.getResponseCode();
                String message = connection.getResponseMessage();

                Map<String, List<String>> responseHeaders =
                        connection.getHeaderFields();

                InputStream input =
                        code >= 200 && code < 400
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                String body = readResponse(input);

                return new Response(
                        code,
                        message,
                        responseHeaders,
                        body,
                        connection.getURL().toString()
                );

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private String readResponse(InputStream input)
                throws IOException {

            if (input == null) {
                return "";
            }

            try (InputStream in = input;
                 ByteArrayOutputStream out =
                         new ByteArrayOutputStream()) {

                byte[] buffer = new byte[8192];
                int count;

                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }

                return out.toString(StandardCharsets.UTF_8.name());
            }
        }
    }
}
