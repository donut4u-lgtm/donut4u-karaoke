**File 2:** `app/src/main/java/com/donut4u/karaoke/OnlineSongActivity.java`

This preserves the existing Stage 1 YouTube search, playback, and microphone scoring code. The changes let the TV menu open Stage 1, suppress the phone ad banner on TV, and return to the TV menu when Back is pressed.

Copy the entire file. **Part 1 of 4 — continue with the next parts in order, without adding or deleting lines.**

```java
package com.donut4u.karaoke;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final String EXTRA_TV_STAGE1 =
            "com.donut4u.karaoke.EXTRA_TV_STAGE1";

    private static final String DATABASE_URL =
            "https://donut4u-karaoke-39967-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private static final String DEVICE_PREFS = "donut4u_device_session";
    private static final String DEVICE_ID_KEY = "installation_id";

    private static final String OFFLINE_SESSION_PREFS =
            "donut4u_verified_offline_session";

    private static final String OFFLINE_UID_KEY = "verified_uid";
    private static final String OFFLINE_DEVICE_KEY = "verified_device_id";

    private static final String CACHE_NAME = "donut4u_song_search_cache";

    private static final String FACEBOOK_URL =
            "https://www.facebook.com/share/17zkgf3o8j/";

    private static final String AD_UNIT_ID =
            "ca-app-pub-5625332026157890/2970292504";

    private static final long CACHE_DURATION =
            24L * 60L * 60L * 1000L;

    private static final int REQUEST_STAGE12_MICROPHONE = 1201;

    private Stage3MicrophoneMonitor microphoneMonitor;

    private final Stage12PerformanceScorer performanceScorer =
            new Stage12PerformanceScorer();

    private Button microphoneButton;
    private TextView microphoneStatus;
    private TextView microphoneLevelText;
    private TextView microphoneScoreText;
    private TextView microphonePitchText;
    private TextView microphoneStreakText;
    private TextView microphoneRatingText;
    private View microphoneLevelBar;
    private View microphoneLevelFill;

    private boolean microphonePermissionPending = false;
    private boolean microphoneWasStoppedByLifecycle = false;

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

    private final Handler sessionHandler =
            new Handler(Looper.getMainLooper());

    private volatile boolean closing = false;

    private int searchGeneration = 0;

    private FirebaseAuth auth;
    private DatabaseReference sessionRef;
    private ValueEventListener sessionListener;

    private DatabaseReference connectionRef;
    private ValueEventListener connectionListener;

    private String installationId = "";

    private boolean sessionValid = false;
    private boolean signingOutForSessionChange = false;
    private boolean databaseConnected = false;
    private boolean offlineAccess = false;

    private Runnable pendingSessionMismatch;

    private static class Video {
        String id;
        String title;
        String channel;
    }

    /**
     * Detect Android TV using the explicit TV-mode flag,
     * the Leanback system feature, or television UI mode.
     *
     * The explicit flag also supports TV routing when an
     * activity is launched by another TV-aware activity.
     */
    private boolean isAndroidTv() {
        if (getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_TV_MODE, false)) {
            return true;
        }

        return getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK)
                || (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
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

        /*
         * Stage 1 on Android TV:
         * Use the existing YouTube search screen without starting
         * a phone-only installation session or changing the
         * phone's active session.
         */
        if (isAndroidTv()
                && getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_TV_STAGE1, false)) {
            sessionValid = true;
            buildSearchScreen();
            return;
        }

        /*
         * Direct Android TV entry without a selected stage opens
         * the dedicated Stage 4 TV songbook.
         */
        if (isAndroidTv()) {
            Intent tvIntent = new Intent(
                    OnlineSongActivity.this,
                    Stage4TvActivity.class
            );

            tvIntent.putExtra(EXTRA_TV_MODE, true);

            tvIntent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
            );

            startActivity(tvIntent);
            finish();
            return;
        }

        // Original phone session flow remains unchanged.
        installationId = getInstallationId();

        showSessionLoadingScreen();

        listenForConnection(user);
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

    private boolean hasSavedOfflineSession(FirebaseUser user) {
        if (user == null || installationId == null) {
            return false;
        }

        SharedPreferences prefs = getSharedPreferences(
                OFFLINE_SESSION_PREFS, MODE_PRIVATE);

        String savedUid = prefs.getString(OFFLINE_UID_KEY, "");
        String savedDevice = prefs.getString(OFFLINE_DEVICE_KEY, "");

        return user.getUid().equals(savedUid)
                && installationId.equals(savedDevice);
    }

    private void saveVerifiedOfflineSession(FirebaseUser user) {
        if (user == null || installationId == null) {
            return;
        }

        getSharedPreferences(
                OFFLINE_SESSION_PREFS, MODE_PRIVATE)
                .edit()
                .putString(OFFLINE_UID_KEY, user.getUid())
                .putString(OFFLINE_DEVICE_KEY, installationId)
                .apply();
    }

    private void clearSavedOfflineSession() {
        getSharedPreferences(
                OFFLINE_SESSION_PREFS, MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private void allowOfflineAccess(FirebaseUser user) {
        if (closing || isFinishing() || sessionValid) {
            return;
        }

        if (!hasSavedOfflineSession(user)) {
            showSessionError(
                    "Internet is unavailable and this phone has no previously verified offline session. Connect to the internet and retry.");
            return;
        }

        offlineAccess = true;
        sessionValid = true;

        buildSearchScreen();

        toast("Offline mode: previously verified session. Connect to the internet to verify your account again.");
    }

    private void listenForConnection(FirebaseUser user) {
        connectionRef = FirebaseDatabase.getInstance(DATABASE_URL)
                .getReference(".info")
                .child("connected");

        connectionListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (closing || isFinishing()) {
                    return;
                }

                Boolean connected = snapshot.getValue(Boolean.class);

                databaseConnected = Boolean.TRUE.equals(connected);

                if (databaseConnected) {
                    if (sessionListener == null) {
                        listenForActiveSession(user);
                    }
                } else {
                    if (sessionRef != null && sessionListener != null) {
                        sessionRef.removeEventListener(sessionListener);
                        sessionListener = null;
                    }

                    if (!sessionValid) {
                        allowOfflineAccess(user);
                    } else {
                        offlineAccess = true;
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (closing || isFinishing()) {
                    return;
                }

                if (!sessionValid) {
                    allowOfflineAccess(user);
                }
            }
        };

        connectionRef.addValueEventListener(connectionListener);
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
            offlineAccess = false;

            showSessionLoadingScreen();

            listenForConnection(user);
        });

        Button signOutButton = makeButton("SIGN OUT");

        LinearLayout.LayoutParams signOutParams = fullWidth(dp(50));
        signOutParams.topMargin = dp(10);
        root.addView(signOutButton, signOutParams);

        signOutButton.setOnClickListener(
                v -> signOutAndSwitchAccount());

        setContentView(root);
    }

    private void listenForActiveSession(FirebaseUser user) {
        if (closing || isFinishing() || !databaseConnected) {
            return;
        }

        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
            sessionListener = null;
        }

        sessionRef = FirebaseDatabase.getInstance(DATABASE_URL)
                .getReference("activeSessions")
                .child(user.getUid());

        sessionListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (closing || isFinishing()) {
                    return;
                }

                String activeId = snapshot.child("sessionId")
                        .getValue(String.class);

                if (activeId == null || activeId.trim().isEmpty()
                        || !installationId.equals(activeId)) {

                    scheduleSessionMismatch(
                            activeId == null || activeId.trim().isEmpty()
                                    ? "Your active session could not be found. Please sign in again."
                                    : "Your account was signed in on another phone. Please sign in again."
                    );

                    return;
                }

                cancelPendingSessionMismatch();

                offlineAccess = false;
                sessionValid = true;

                saveVerifiedOfflineSession(user);

                if (searchScreen == null) {
                    buildSearchScreen();

                    String initialQuery =
                            getIntent().getStringExtra("search_query");

                    if (initialQuery != null
                            && !initialQuery.trim().isEmpty()) {
                        searchBox.setText(initialQuery);
                        searchVideos();
                    }
                }
