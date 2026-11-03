
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Stage3MediaFireActivity extends Activity {

    private static final String FOLDER_URL =
            "https://www.mediafire.com/folder/rmpdn8g24drnm/File_1";

    private static final String PREFS =
            "donut4u_stage3_songbook";

    private static final String SONGS_KEY = "songs_json";

    private WebView webView;
    private TextView status;
    private ProgressBar progress;
    private Button backButton;

    private boolean autoLoadingFolder = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(Color.rgb(5, 24, 15));

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(Color.rgb(80, 255, 150));
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        root.addView(title,
                new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • MEDIAFIRE SONGBOOK");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextSize(13);

        root.addView(subtitle,
                new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button reload = makeButton("RELOAD");
        reload.setOnClickListener(v ->
                webView.loadUrl(FOLDER_URL));

        backButton = makeButton("BACK");
        backButton.setOnClickListener(v -> goBack());

        controls.addView(reload,
                new LinearLayout.LayoutParams(0, dp(44), 1));

        controls.addView(backButton,
                new LinearLayout.LayoutParams(0, dp(44), 1));

        root.addView(controls);

        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);

        progress.setMax(100);
        progress.setProgress(0);

        root.addView(progress,
                new LinearLayout.LayoutParams(-1, dp(4)));

        status = new TextView(this);
        status.setTextColor(Color.rgb(150, 255, 180));
        status.setTextSize(12);
        status.setPadding(dp(4), dp(5), dp(4), dp(5));
        status.setText("Opening your MediaFire song folder...");

        root.addView(status);

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);

        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);

                if (value < 100) {
                    progress.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(
                    WebView view, String url,
                    android.graphics.Bitmap favicon) {

                autoLoadingFolder = false;
                status.setText("Loading MediaFire folder...");
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(
                    WebView view, String url) {

                if (url == null ||
                        !url.contains("mediafire.com")) {
                    progress.setVisibility(View.GONE);
                    return;
                }

                status.setText(
                        "Loading the complete folder list. Please wait...");

                progress.setVisibility(View.VISIBLE);

                loadAllFolderEntries(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, String url) {

                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();

                if (scheme == null ||
                        (!scheme.equalsIgnoreCase("https")
                        && !scheme.equalsIgnoreCase("http"))) {
                    return true;
                }

                String host = uri.getHost();

                if (host == null ||
                        !(host.equalsIgnoreCase("mediafire.com")
                        || host.toLowerCase(Locale.ROOT)
                        .endsWith(".mediafire.com")
                        || host.equalsIgnoreCase("google.com")
                        || host.toLowerCase(Locale.ROOT)
                        .endsWith(".google.com"))) {
                    return true;
                }

                return false;
            }
        });

        webView.setDownloadListener(
                new DownloadListener() {
                    @Override
                    public void onDownloadStart(
                            String url,
                            String userAgent,
                            String contentDisposition,
                            String mimeType,
                            long contentLength) {

                        startDownload(
                                url,
                                userAgent,
                                contentDisposition,
                                mimeType);
                    }
                });

        root.addView(webView,
                new LinearLayout.LayoutParams(
                        -1, 0, 1f));

        Button openFolder = makeButton(
                "OPEN MY MEDIAFIRE SONG FOLDER");

        openFolder.setOnClickListener(v ->
                webView.loadUrl(FOLDER_URL));

        root.addView(openFolder,
                new LinearLayout.LayoutParams(-1, dp(46)));

        setContentView(root);

        webView.loadUrl(FOLDER_URL);
    }

    /*
     * Ask MediaFire's page to progressively scroll through
     * its scrollable areas so lazy-loaded entries can appear.
     *
     * This does not download files. It only loads folder entries.
     */
    private void loadAllFolderEntries(WebView view) {

        if (autoLoadingFolder) {
            return;
        }

        autoLoadingFolder = true;

        String script =
                "(function() {" +
                "if (window.__donut4uFolderLoader) return;" +
                "window.__donut4uFolderLoader = true;" +

                "var steps = 0;" +
                "var unchanged = 0;" +
                "var previous = '';" +
                "var maxSteps = 100;" +

                "function measure() {" +
                "var root = document.scrollingElement || document.documentElement;" +
                "var links = document.querySelectorAll('a').length;" +
                "var height = Math.max(" +
                "root.scrollHeight," +
                "document.body ? document.body.scrollHeight : 0);" +
                "return links + ':' + height;" +
                "}" +

                "function moveDown() {" +
                "var root = document.scrollingElement || document.documentElement;" +
                "var amount = Math.max(400, window.innerHeight * 0.75);" +

                "window.scrollBy(0, amount);" +
                "root.scrollTop = root.scrollTop + amount;" +

                "var elements = document.querySelectorAll('*');" +
                "for (var i = 0; i < elements.length; i++) {" +
                "var el = elements[i];" +
                "var style = window.getComputedStyle(el);" +
                "if ((style.overflowY === 'auto' || style.overflowY === 'scroll')" +
                " && el.scrollHeight > el.clientHeight + 100) {" +
                "el.scrollTop = el.scrollTop + amount;" +
                "}" +
                "}" +
                "}" +

                "function finish() {" +
                "window.scrollTo(0, 0);" +
                "var root = document.scrollingElement || document.documentElement;" +
                "root.scrollTop = 0;" +
                "var elements = document.querySelectorAll('*');" +
                "for (var i = 0; i < elements.length; i++) {" +
                "var el = elements[i];" +
                "var style = window.getComputedStyle(el);" +
                "if (style.overflowY === 'auto' || style.overflowY === 'scroll') {" +
                "el.scrollTop = 0;" +
                "}" +
                "}" +
                "window.__donut4uFolderLoader = false;" +
                "}" +

                "function step() {" +
                "steps++;" +
                "var current = measure();" +
                "if (current === previous) {" +
                "unchanged++;" +
                "} else {" +
                "unchanged = 0;" +
                "previous = current;" +
                "}" +

                "if (steps >= maxSteps || unchanged >= 8) {" +
                "finish();" +
                "return;" +
                "}" +

                "moveDown();" +
                "setTimeout(step, 700);" +
                "}" +

                "setTimeout(step, 1000);" +
                "})();";

        view.evaluateJavascript(script, null);

        view.postDelayed(() -> {
            autoLoadingFolder = false;

            progress.setVisibility(View.GONE);

            status.setText(
                    "Folder loading attempt finished. " +
                    "Scroll down to browse the available songs.");

        }, 75000);
    }

    private Button makeButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(20, 125, 65)));
        return button;
    }

    private void goBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    private boolean isAllowedMediaFireUrl(String address) {
        try {
            Uri uri = Uri.parse(address);
            String host = uri.getHost();

            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("mediafire.com")
                    || host.toLowerCase(Locale.ROOT)
                    .endsWith(".mediafire.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private void startDownload(
            String address,
            String userAgent,
            String contentDisposition,
            String mimeType) {

        if (!isAllowedMediaFireUrl(address)) {
            status.setText(
                    "Download link is not a recognized MediaFire link.");
            return;
        }

        status.setText("Preparing MIDI/KAR download...");
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);

        new Thread(() -> {
            File downloaded = null;

            try {
                downloaded = downloadFile(
                        address,
                        userAgent,
                        contentDisposition,
                        mimeType);

                File finalFile = downloaded;
                String title = removeExtension(
                        finalFile.getName());

                String localUri =
                        Uri.fromFile(finalFile).toString();

                String number = registerSong(title, localUri);

                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setVisibility(View.GONE);

                    Stage3SongQueue.initialize(this);

                    Stage3SongQueue.setNowPlaying(
                            new Stage3SongQueue.QueuedSong(
                                    number,
                                    title,
                                    localUri,
                                    "MIDI/KAR"));

                    status.setText(
                            "Downloaded successfully:\n" + title);

                    Toast.makeText(
                            this,
                            "Song added to Stage 3 songbook",
                            Toast.LENGTH_LONG).show();

                    startActivity(new android.content.Intent(
                            this,
                            Stage3PlayerActivity.class));
                });

            } catch (Exception e) {
                if (downloaded != null && downloaded.exists()) {
                    downloaded.delete();
                }

                String message = e.getMessage() == null
                        ? "Unknown download error"
                        : e.getMessage();

                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setVisibility(View.GONE);
                    status.setText("DOWNLOAD FAILED:\n" + message);
                });
            }
        }, "Donut4U-MediaFire").start();
    }

    private File downloadFile(
            String address,
            String userAgent,
            String contentDisposition,
            String mimeType) throws Exception {

        HttpURLConnection connection = null;
        File outputFile = null;

        try {
            URL url = new URL(address);

            connection = (HttpURLConnection)
                    url.openConnection();

            connection.setConnectTimeout(25000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);

            connection.setRequestProperty(
                    "User-Agent",
                    userAgent == null || userAgent.isEmpty()
                            ? webView.getSettings().getUserAgentString()
                            : userAgent);

            connection.setRequestProperty(
                    "Referer", FOLDER_URL);

            String cookie = CookieManager.getInstance()
                    .getCookie(address);

            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }

            connection.connect();

            int response = connection.getResponseCode();

            if (response < 200 || response >= 300) {
                throw new Exception(
                        "MediaFire download returned HTTP " + response);
            }

            String fileName = getFileName(
                    contentDisposition,
                    connection.getHeaderField("Content-Disposition"),
                    address);

            String extension = getMidiExtension(fileName);

            if (extension.isEmpty()) {
                throw new Exception(
                        "This download is not identified as MID, MIDI or KAR.");
            }

            String type = connection.getContentType();

            if ((type != null && type.toLowerCase(Locale.ROOT)
                    .contains("text/html"))
                    || (mimeType != null && mimeType.toLowerCase(Locale.ROOT)
                    .contains("text/html"))) {
                throw new Exception(
                        "MediaFire returned a web page instead of a song file. " +
                        "The download may require browser verification.");
            }

            File folder = new File(
                    getFilesDir(), "stage3_mediafire");

            if (!folder.exists() && !folder.mkdirs()) {
                throw new Exception(
                        "Could not create Stage 3 download folder.");
            }

            String safeName = fileName.replaceAll(
                    "[^A-Za-z0-9._-]", "_");

            outputFile = new File(
                    folder,
                    System.currentTimeMillis() + "_" + safeName);

            long total = 0;

            try (InputStream input =
                         new BufferedInputStream(
                                 connection.getInputStream());
                 FileOutputStream output =
                         new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    total += count;

                    if (total > 100L * 1024L * 1024L) {
                        throw new Exception(
                                "File exceeds the 100 MB download limit.");
                    }

                    output.write(buffer, 0, count);
                }
            }

            if (total < 14 || !hasMidiHeader(outputFile)) {
                throw new Exception(
                        "The downloaded file is not a valid MIDI/KAR file.");
            }

            return outputFile;

        } catch (Exception e) {
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
            throw e;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String getFileName(
            String firstDisposition,
            String secondDisposition,
            String address) {

        String disposition = firstDisposition != null
                ? firstDisposition : secondDisposition;

        if (disposition != null) {
            Matcher matcher = Pattern.compile(
                    "filename\\*?=(?:UTF-8''|\"?)([^\";]+)",
                    Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);

            if (matcher.find()) {
                try {
                    return URLDecoder.decode(
                            matcher.group(1).trim(), "UTF-8");
                } catch (Exception ignored) {
                    return matcher.group(1).trim();
                }
            }
        }

        Uri uri = Uri.parse(address);
        String path = uri.getLastPathSegment();

        if (path != null && getMidiExtension(path).length() > 0) {
            return Uri.decode(path);
        }

        throw new IllegalArgumentException(
                "MediaFire did not provide a MIDI/KAR filename.");
    }

    private String getMidiExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".midi")) return ".midi";
        if (lower.endsWith(".mid")) return ".mid";
        if (lower.endsWith(".kar")) return ".kar";

        return "";
    }

    private boolean hasMidiHeader(File file) {
        try (InputStream input =
                     new java.io.FileInputStream(file)) {

            byte[] header = new byte[4];

            if (input.read(header) != 4) {
                return false;
            }

            return header[0] == 'M'
                    && header[1] == 'T'
                    && header[2] == 'h'
                    && header[3] == 'd';

        } catch (Exception e) {
            return false;
        }
    }

    private String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String registerSong(
            String title,
            String uri) throws Exception {

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        JSONArray songs = new JSONArray(
                prefs.getString(SONGS_KEY, "[]"));

        int nextNumber = 10000;

        for (int i = 0; i < songs.length(); i++) {
            JSONObject item = songs.getJSONObject(i);

            if (uri.equals(item.optString("uri", ""))) {
                return item.optString("number", "10000");
            }

            try {
                int oldNumber = Integer.parseInt(
                        item.optString("number", "0"));

                nextNumber = Math.max(
                        nextNumber, oldNumber + 1);

            } catch (NumberFormatException ignored) {
            }
        }

        String number = String.format(
                Locale.US, "%05d", nextNumber);

        JSONObject song = new JSONObject();
        song.put("number", number);
        song.put("title", title);
        song.put("uri", uri);
        song.put("type", "MIDI/KAR");

        songs.put(song);

        prefs.edit()
                .putString(SONGS_KEY, songs.toString())
                .apply();

        return number;
    }
}
