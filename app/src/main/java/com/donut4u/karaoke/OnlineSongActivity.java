
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OnlineSongActivity extends Activity {

    private static final int GREEN = Color.rgb(0, 220, 105);
    private static final int BG = Color.rgb(10, 12, 15);
    private static final int PANEL = Color.rgb(27, 32, 35);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.LTGRAY;

    private static final String DATABASE_URL =
            "https://donut4u-karaoke-39967-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private static final String DEVICE_PREFS = "donut4u_device_session";
    private static final String DEVICE_ID_KEY = "installation_id";
    private static final String CACHE_NAME = "donut4u_song_search_cache";
    private static final String FACEBOOK_URL =
            "https://www.facebook.com/share/17zkgf3o8j/";

    // 🔴 INSERTED: Correct banner ad unit for YouTube / Find My Song.
    // 🔴 Do not change the separate App Open ad unit in MainActivity.
    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/2970292504";

    private static final long CACHE_DURATION =
            24L * 60L * 60L * 1000L;

    private EditText searchBox;
    private Button searchButton;
    private TextView status;
    private LinearLayout resultsContainer;

    private View searchScreen;
    private FrameLayout playbackScreen;
    private FrameLayout playerHost;
    private WebView player;
    private AdView adView;

    private boolean donationScreenVisible = false;
    private boolean playbackVisible = false;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private volatile boolean closing = false;
    private int searchGeneration = 0;

    private FirebaseAuth auth;
    private DatabaseReference sessionRef;
    private ValueEventListener sessionListener;

    private String installationId = "";
    private boolean sessionValid = false;
    private boolean signingOutForSessionChange = false;

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

        auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();

        if (user == null || !user.isEmailVerified()) {
            openAuthentication();
            return;
        }

        AppUpdateChecker.checkForUpdates(this);

        installationId = getInstallationId();
        showSessionLoadingScreen();
        listenForActiveSession(user);
    }

    private String getInstallationId() {
        SharedPreferences prefs = getSharedPreferences(
                DEVICE_PREFS, MODE_PRIVATE);

        String id = prefs.getString(DEVICE_ID_KEY, null);

        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(DEVICE_ID_KEY, id).apply();
        }

        return id;
    }

    private void showSessionLoadingScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(BG);

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(GREEN);
        heading.setTextSize(24);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, fullWidthWrap());

        TextView message = new TextView(this);
        message.setText("Checking your active phone session...");
        message.setTextColor(WHITE);
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(16), 0, dp(16));
        root.addView(message, fullWidthWrap());

        setContentView(root);
    }

    private void listenForActiveSession(FirebaseUser user) {
        sessionRef = FirebaseDatabase.getInstance(DATABASE_URL)
                .getReference("activeSessions")
                .child(user.getUid());

        sessionListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (closing || isFinishing()) return;

                String activeId = snapshot.child("sessionId")
                        .getValue(String.class);

                if (activeId == null || activeId.trim().isEmpty()) {
                    handleSessionLost(
                            "Your active session could not be found. Please sign in again.");
                    return;
                }

                if (!installationId.equals(activeId)) {
                    handleSessionLost(
                            "Your account was signed in on another phone. Please sign in again.");
                    return;
                }

                if (!sessionValid) {
                    sessionValid = true;
                    buildSearchScreen();

                    String initialQuery =
                            getIntent().getStringExtra("search_query");

                    if (initialQuery != null
                            && !initialQuery.trim().isEmpty()) {
                        searchBox.setText(initialQuery);
                        searchVideos();
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (closing || isFinishing()) return;

                if (!sessionValid) {
                    showSessionError(
                            "Could not verify your active session. Check your internet connection and Firebase Realtime Database rules, then retry.");
                } else {
                    toast("The live session check was interrupted. Check your internet connection.");
                }
            }
        };

        sessionRef.addValueEventListener(sessionListener);
    }

    private void showSessionError(String messageText) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(BG);

        TextView heading = new TextView(this);
        heading.setText("SESSION CHECK FAILED");
        heading.setTextColor(GREEN);
        heading.setTextSize(21);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, fullWidthWrap());

        TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextColor(WHITE);
        message.setTextSize(14);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(16), 0, dp(16));
        root.addView(message, fullWidthWrap());

        Button retryButton = makeButton("RETRY SESSION CHECK");
        LinearLayout.LayoutParams retryParams = fullWidth(dp(50));
        retryParams.topMargin = dp(8);
        root.addView(retryButton, retryParams);

        retryButton.setOnClickListener(v -> {
            FirebaseUser user = auth.getCurrentUser();

            if (user == null || !user.isEmailVerified()) {
                openAuthentication();
                return;
            }

            if (sessionRef != null && sessionListener != null) {
                sessionRef.removeEventListener(sessionListener);
                sessionListener = null;
            }

            sessionValid = false;
            showSessionLoadingScreen();
            listenForActiveSession(user);
        });

        Button signOutButton = makeButton("SIGN OUT");
        LinearLayout.LayoutParams signOutParams = fullWidth(dp(50));
        signOutParams.topMargin = dp(10);
        root.addView(signOutButton, signOutParams);
        signOutButton.setOnClickListener(v -> signOutAndSwitchAccount());

        setContentView(root);
    }

    private void handleSessionLost(String messageText) {
        if (signingOutForSessionChange || closing) return;

        signingOutForSessionChange = true;
        sessionValid = false;
        destroyPlayer();

        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
            sessionListener = null;
        }

        if (auth != null) auth.signOut();

        Toast.makeText(getApplicationContext(),
                messageText, Toast.LENGTH_LONG).show();

        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openAuthentication() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void signOutAndSwitchAccount() {
        if (auth == null) auth = FirebaseAuth.getInstance();

        sessionValid = false;
        signingOutForSessionChange = true;

        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
            sessionListener = null;
        }

        destroyPlayer();
        auth.signOut();

        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openFacebookFeedback() {
        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW, Uri.parse(FACEBOOK_URL)));
        } catch (Exception e) {
            toast("Unable to open Facebook. Please try again.");
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
        heading.setText("🎤 DONUT4U KARAOKE STAGE");
        heading.setTextColor(GREEN);
        heading.setTextSize(24);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(8), 0, dp(8));
        root.addView(heading, fullWidthWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("YOUR SONG. YOUR STAGE.");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLetterSpacing(0.12f);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle, fullWidthWrap());

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Song title or artist");
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        styleEdit(searchBox);
        root.addView(searchBox, fullWidth(dp(56)));

        searchButton = makeButton("FIND MY SONG");
        LinearLayout.LayoutParams searchParams = fullWidth(dp(52));
        searchParams.topMargin = dp(10);
        root.addView(searchButton, searchParams);
        searchButton.setOnClickListener(v -> searchVideos());

        LinearLayout donationCard = new LinearLayout(this);
        donationCard.setOrientation(LinearLayout.HORIZONTAL);
        donationCard.setGravity(Gravity.CENTER_VERTICAL);
        donationCard.setPadding(dp(12), dp(8), dp(8), dp(8));
        donationCard.setBackground(round(PANEL, dp(12)));

        LinearLayout.LayoutParams donationCardParams = fullWidthWrap();
        donationCardParams.topMargin = dp(14);
        root.addView(donationCard, donationCardParams);

        LinearLayout donationTextContainer = new LinearLayout(this);
        donationTextContainer.setOrientation(LinearLayout.VERTICAL);

        donationCard.addView(donationTextContainer,
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView donationTitle = new TextView(this);
        donationTitle.setText("SUPPORT DONUT4U KARAOKE");
        donationTitle.setTextColor(GREEN);
        donationTitle.setTextSize(12);
        donationTitle.setTypeface(null, Typeface.BOLD);
        donationTextContainer.addView(donationTitle, fullWidthWrap());

        TextView donationSubtitle = new TextView(this);
        donationSubtitle.setText("Help keep it running and growing!");
        donationSubtitle.setTextColor(MUTED);
        donationSubtitle.setTextSize(10);
        donationSubtitle.setPadding(0, dp(3), 0, 0);
        donationTextContainer.addView(donationSubtitle, fullWidthWrap());

        Button donateNowButton = makeButton("DONATE NOW");
        donateNowButton.setTextSize(11);
        donateNowButton.setPadding(dp(8), 0, dp(8), 0);

        LinearLayout.LayoutParams donateButtonParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        donateButtonParams.leftMargin = dp(8);
        donationCard.addView(donateNowButton, donateButtonParams);
        donateNowButton.setOnClickListener(v -> showDonationScreen());

        Button studioButton = makeButton("🎤 KARAOKE STUDIO — PAGE 2");
        LinearLayout.LayoutParams studioParams = fullWidth(dp(50));
        studioParams.topMargin = dp(12);
        root.addView(studioButton, studioParams);

        studioButton.setOnClickListener(v -> {
            if (!sessionValid || closing) {
                toast("Please wait while your session is checked.");
                return;
            }
            startActivity(new Intent(
                    OnlineSongActivity.this,
                    KaraokeStudioActivity.class));
        });

        Button switchAccountButton =
                makeButton("LOG OUT / SWITCH ACCOUNT");
        LinearLayout.LayoutParams switchParams = fullWidth(dp(50));
        switchParams.topMargin = dp(10);
        root.addView(switchAccountButton, switchParams);
        switchAccountButton.setOnClickListener(
                v -> signOutAndSwitchAccount());

        Button feedbackButton =
                makeButton("💬 QUESTIONS & SUGGESTIONS");
        LinearLayout.LayoutParams feedbackParams = fullWidth(dp(50));
        feedbackParams.topMargin = dp(10);
        root.addView(feedbackButton, feedbackParams);
        feedbackButton.setOnClickListener(v -> openFacebookFeedback());

        status = new TextView(this);
        status.setText("Search for a song to see karaoke videos.");
        status.setTextColor(MUTED);
        status.setTextSize(14);
        status.setPadding(0, dp(16), 0, dp(12));
        root.addView(status, fullWidthWrap());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, fullWidthWrap());

        LinearLayout pageLayout = new LinearLayout(this);
        pageLayout.setOrientation(LinearLayout.VERTICAL);
        pageLayout.setBackgroundColor(BG);

        pageLayout.addView(outer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addAdMobBanner(pageLayout);

        searchScreen = pageLayout;
        setContentView(searchScreen);

        searchBox.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchVideos();
                return true;
            }
            return false;
        });
    }

    private void addAdMobBanner(LinearLayout parent) {
        try {
            MobileAds.initialize(this, initializationStatus -> {});

            AdView view = new AdView(this);
            adView = view;
            adView.setAdUnitId(AD_UNIT_ID);
            adView.setAdSize(AdSize.BANNER);

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_HORIZONTAL;

            parent.addView(adView, params);
            adView.loadAd(new AdRequest.Builder().build());

        } catch (Exception e) {
            if (adView != null) {
                adView.destroy();
                adView = null;
            }
        }
    }

    private void showDonationScreen() {
        if (!sessionValid || closing) return;

        donationScreenVisible = true;
        showSystemBars();

        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        outer.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        outer.addView(root);

        TextView heading = new TextView(this);
        heading.setText("SUPPORT DONUT4U KARAOKE");
        heading.setTextColor(GREEN);
        heading.setTextSize(22);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(8), 0, dp(16));
        root.addView(heading, fullWidthWrap());

        TextView message = new TextView(this);
        message.setText("Help keep Donut4U Karaoke running and growing!");
        message.setTextColor(WHITE);
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, dp(18));
        root.addView(message, fullWidthWrap());

        TextView scanHeading = new TextView(this);
        scanHeading.setText("SCAN TO DONATE VIA GCASH");
        scanHeading.setTextColor(GREEN);
        scanHeading.setTextSize(17);
        scanHeading.setTypeface(null, Typeface.BOLD);
        scanHeading.setGravity(Gravity.CENTER);
        scanHeading.setPadding(0, dp(8), 0, dp(12));
        root.addView(scanHeading, fullWidthWrap());

        ImageView qrImage = new ImageView(this);
        qrImage.setImageResource(R.drawable.gcash_qr);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrImage.setAdjustViewBounds(true);
        qrImage.setBackgroundColor(Color.WHITE);
        qrImage.setPadding(dp(6), dp(6), dp(6), dp(6));
        qrImage.setContentDescription("GCash donation QR code");

        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.bottomMargin = dp(18);
        root.addView(qrImage, qrParams);

        TextView instruction = new TextView(this);
        instruction.setText(
                "Open GCash on another phone and scan the QR code.");
        instruction.setTextColor(MUTED);
        instruction.setTextSize(13);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 0, 0, dp(18));
        root.addView(instruction, fullWidthWrap());

        Button backButton = makeButton("BACK TO KARAOKE");
        LinearLayout.LayoutParams backParams = fullWidth(dp(50));
        backParams.topMargin = dp(6);
        root.addView(backButton, backParams);
        backButton.setOnClickListener(v -> returnFromDonation());

        setContentView(outer);
    }

    private void returnFromDonation() {
        donationScreenVisible = false;
        showSystemBars();
        if (searchScreen != null) setContentView(searchScreen);
    }

    private String normalizedQuery(String query) {
        String result = query.trim().toLowerCase();
        if (!result.contains("karaoke")) result += " karaoke";
        return result;
    }

    private void saveSearchCache(String query, ArrayList<Video> videos) {
        try {
            SharedPreferences prefs =
                    getSharedPreferences(CACHE_NAME, MODE_PRIVATE);

            JSONObject root = new JSONObject();
            JSONArray array = new JSONArray();

            for (Video video : videos) {
                JSONObject item = new JSONObject();
                item.put("id", video.id);
                item.put("title", video.title);
                item.put("channel", video.channel);
                array.put(item);
            }

            root.put("time", System.currentTimeMillis());
            root.put("videos", array);
            prefs.edit().putString(query, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private ArrayList<Video> loadSearchCache(String query) {
        try {
            SharedPreferences prefs =
                    getSharedPreferences(CACHE_NAME, MODE_PRIVATE);
            String saved = prefs.getString(query, null);
            if (saved == null) return null;

            JSONObject root = new JSONObject(saved);
            long savedTime = root.optLong("time", 0);
            long now = System.currentTimeMillis();

            if (savedTime <= 0 || now < savedTime
                    || now - savedTime > CACHE_DURATION) {
                prefs.edit().remove(query).apply();
                return null;
            }

            JSONArray array = root.optJSONArray("videos");
            if (array == null || array.length() == 0) return null;

            ArrayList<Video> videos = new ArrayList<>();

            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;

                Video video = new Video();
                video.id = item.optString("id", "");
                video.title = item.optString(
                        "title", "YouTube karaoke video");
                video.channel = item.optString(
                        "channel", "YouTube channel");

                if (!video.id.isEmpty()) videos.add(video);
            }

            return videos.isEmpty() ? null : videos;
        } catch (Exception e) {
            return null;
        }
    }

    private void searchVideos() {
        if (!sessionValid || closing) return;

        String query = searchBox.getText().toString().trim();
        if (query.isEmpty()) {
            toast("Enter a song title or artist.");
            return;
        }

        Object service = getSystemService(Context.INPUT_METHOD_SERVICE);
        if (service instanceof InputMethodManager) {
            ((InputMethodManager) service).hideSoftInputFromWindow(
                    searchBox.getWindowToken(), 0);
        }

        final int generation = ++searchGeneration;
        final String searchQuery = normalizedQuery(query);

        searchButton.setEnabled(false);
        resultsContainer.removeAllViews();

        ArrayList<Video> cached = loadSearchCache(searchQuery);
        if (cached != null && !cached.isEmpty()) {
            status.setText("Showing saved search results.");
            displayVideos(cached, generation, true);
            return;
        }

        String apiKey = BuildConfig.YOUTUBE_API_KEY;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            searchButton.setEnabled(true);
            status.setText(
                    "YouTube API key is missing. Check the YOUTUBE_API_KEY GitHub secret.");
            return;
        }

        status.setText("Searching YouTube...");

        final String encodedQuery;
        final String encodedKey;
        try {
            encodedQuery = URLEncoder.encode(searchQuery, "UTF-8");
            encodedKey = URLEncoder.encode(apiKey, "UTF-8");
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
                        + "?part=snippet&type=video"
                        + "&videoEmbeddable=true&maxResults=25"
                        + "&regionCode=PH&q=" + encodedQuery
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
                    JSONObject errorJson = new JSONObject(response);
                    JSONObject errorObject =
                            errorJson.optJSONObject("error");
                    String message = errorObject == null ? ""
                            : errorObject.optString("message", "");

                    throw new Exception(message.isEmpty()
                            ? "YouTube API error: HTTP " + responseCode
                            : message);
                }

                JSONObject json = new JSONObject(response);
                JSONArray items = json.optJSONArray("items");

                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;

                        JSONObject idObject = item.optJSONObject("id");
                        JSONObject snippet = item.optJSONObject("snippet");
                        if (idObject == null || snippet == null) continue;

                        String videoId = idObject.optString("videoId", "");
                        if (videoId.isEmpty()) continue;

                        Video video = new Video();
                        video.id = videoId;
                        video.title = snippet.optString(
                                "title", "YouTube karaoke video");
                        video.channel = snippet.optString(
                                "channelTitle", "YouTube channel");

                        videos.add(video);
                    }
                }

                if (!videos.isEmpty()) saveSearchCache(searchQuery, videos);

            } catch (Exception e) {
                error = e.getMessage() == null
                        ? "Unable to search YouTube." : e.getMessage();
            } finally {
                if (connection != null) connection.disconnect();
            }

            final String finalError = error;
            final ArrayList<Video> found = videos;

            runOnUiThread(() -> {
                if (closing || isFinishing() || !sessionValid
                        || generation != searchGeneration) return;

                searchButton.setEnabled(true);

                if (finalError != null) {
                    status.setText("YouTube search failed: " + finalError);
                    return;
                }

                if (found.isEmpty()) {
                    status.setText(
                            "No embeddable videos found. Try another song title or artist.");
                    resultsContainer.removeAllViews();
                    return;
                }

                displayVideos(found, generation, false);
            });
        });
    }

    private void displayVideos(ArrayList<Video> videos,
                               int generation, boolean fromCache) {
        if (closing || isFinishing() || !sessionValid
                || generation != searchGeneration) return;

        searchButton.setEnabled(true);
        resultsContainer.removeAllViews();
        status.setText("Found " + videos.size() + " videos. "
                + (fromCache ? "Saved results. " : "")
                + "Tap a song to play.");

        for (Video video : videos) addVideoResult(video);
    }

    private void addVideoResult(Video video) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(round(PANEL, dp(12)));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams cardParams = fullWidthWrap();
        cardParams.bottomMargin = dp(10);
        resultsContainer.addView(card, cardParams);

        TextView title = new TextView(this);
        title.setText(video.title);
        title.setTextColor(WHITE);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title, fullWidthWrap());

        TextView channel = new TextView(this);
        channel.setText(video.channel);
        channel.setTextColor(MUTED);
        channel.setTextSize(13);
        channel.setPadding(0, dp(7), 0, 0);
        card.addView(channel, fullWidthWrap());

        TextView tapLabel = new TextView(this);
        tapLabel.setText("▶  TAP TO PLAY");
        tapLabel.setTextColor(GREEN);
        tapLabel.setTextSize(13);
        tapLabel.setTypeface(null, Typeface.BOLD);
        tapLabel.setPadding(0, dp(12), 0, 0);
        card.addView(tapLabel, fullWidthWrap());

        card.setOnClickListener(v -> playVideo(video));
    }

    private void playVideo(Video video) {
        if (!sessionValid || closing) return;

        donationScreenVisible = false;
        playbackVisible = true;
        destroyPlayer();
        hideSystemBars();

        playbackScreen = new FrameLayout(this);
        playbackScreen.setBackgroundColor(Color.BLACK);

        playerHost = new FrameLayout(this);
        playerHost.setBackgroundColor(Color.BLACK);
        playbackScreen.addView(playerHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(video.title);
        title.setTextColor(WHITE);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), dp(8), dp(12), dp(8));
        title.setMaxLines(2);
        title.setBackground(round(Color.argb(205, 10, 12, 15), dp(8)));

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        titleParams.setMargins(dp(10), dp(10), dp(10), 0);
        playbackScreen.addView(title, titleParams);

        Button backButton = makeButton("‹  BACK TO SONGS");
        backButton.setTextSize(13);
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        backParams.setMargins(dp(12), 0, dp(12), dp(12));
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

        playerHost.addView(player, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        String html = "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,"
                + " initial-scale=1, maximum-scale=1, user-scalable=no\">"
                + "<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
                + "<style>html,body{margin:0;padding:0;background:#000;"
                + "width:100%;height:100%;overflow:hidden;}"
                + "iframe{position:absolute;top:0;left:0;width:100%;"
                + "height:100%;border:0;}</style></head><body><iframe "
                + "src=\"https://www.youtube.com/embed/" + video.id
                + "?playsinline=1&controls=1&rel=0\" "
                + "title=\"YouTube karaoke player\" "
                + "allow=\"accelerometer; autoplay; clipboard-write; "
                + "encrypted-media; gyroscope; picture-in-picture; web-share\" "
                + "referrerpolicy=\"strict-origin-when-cross-origin\" "
                + "allowfullscreen></iframe></body></html>";

        player.loadDataWithBaseURL(
                "https://com.donut4u.karaoke/",
                html, "text/html", "UTF-8", null);
    }

    private void returnToResults() {
        destroyPlayer();
        playbackScreen = null;
        playerHost = null;
        playbackVisible = false;
        showSystemBars();
        if (searchScreen != null) setContentView(searchScreen);
    }

    private void hideSystemBars() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
    }

    private void destroyPlayer() {
        if (player != null) {
            player.stopLoading();
            player.loadUrl("about:blank");
            if (playerHost != null) playerHost.removeView(player);
            player.destroy();
            player = null;
        }
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private void styleEdit(EditText edit) {
        edit.setTextColor(WHITE);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(16);
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
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(round(GREEN, dp(10)));
        button.setStateListAnimator(null);
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
                LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    public void onBackPressed() {
        if (player != null || playbackVisible) {
            returnToResults();
            return;
        }

        if (donationScreenVisible) {
            returnFromDonation();
            return;
        }

        if (searchBox != null) {
            Object service = getSystemService(
                    Context.INPUT_METHOD_SERVICE);

            if (service instanceof InputMethodManager
                    && searchBox.hasFocus()) {
                InputMethodManager keyboard =
                        (InputMethodManager) service;

                if (keyboard.isAcceptingText()) {
                    keyboard.hideSoftInputFromWindow(
                            searchBox.getWindowToken(), 0);
                    searchBox.clearFocus();
                    return;
                }
            }
        }

        toast("You're in Donut4U Karaoke. Search for another song!");
    }

    @Override
    protected void onDestroy() {
        closing = true;
        searchGeneration++;

        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
            sessionListener = null;
        }

        destroyPlayer();

        if (adView != null) {
            adView.destroy();
            adView = null;
        }

        executor.shutdownNow();
        super.onDestroy();
    }
}
