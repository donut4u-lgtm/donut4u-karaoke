
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class Stage3PostFileUploadActivity extends Activity {

    private static final int REQUEST_FILES = 3201;

    private static final String API_URL =
            "https://postfile.net/v1/upload";

    private static final String KEY_ALIAS =
            "Donut4UPostFileUploadKey";

    private static final String KEY_PREFS =
            "donut4u_postfile_secure";

    private static final String SONGBOOK_PREFS =
            "donut4u_stage3_songbook";

    private static final String SONGS_KEY = "songs_json";

    private final ArrayList<Uri> selectedFiles =
            new ArrayList<>();

    private TextView status;
    private TextView fileList;
    private ProgressBar progressBar;
    private Button uploadButton;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private int currentIndex;
    private String currentTitle;
    private String currentArtist;
    private boolean uploading;

    // Cached after being read from Android Keystore in the background.
    private volatile String cachedApiKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();

        status.setText("Checking saved PostFile API key...");

        // Android Keystore must not block the UI thread.
        new Thread(() -> {
            String key = getSavedApiKey();
            cachedApiKey = key;

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (key.isEmpty()) {
                    status.setText("PostFile API key required.");
                    showApiKeyDialog();
                } else {
                    status.setText(
                            "PostFile API key is saved on this device.");
                }

                uploadButton.setEnabled(
                        !selectedFiles.isEmpty()
                                && !key.isEmpty());
            });
        }, "PostFile-Key-Check").start();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(14));
        root.setBackgroundColor(Color.rgb(10, 20, 15));

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(Color.rgb(80, 255, 150));
        heading.setTextSize(24);
        heading.setGravity(Gravity.CENTER);
        heading.setTypeface(null, 1);
        root.addView(heading,
                new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • POSTFILE UPLOADER");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        status = new TextView(this);
        status.setText("Ready to connect.");
        status.setTextColor(Color.rgb(140, 255, 180));
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        Button keyButton =
                makeButton("SET / CHANGE POSTFILE API KEY");
        keyButton.setOnClickListener(v -> showApiKeyDialog());
        root.addView(keyButton, buttonParams());

        Button checkButton =
                makeButton("CHECK POSTFILE ACCOUNT & FILES");
        checkButton.setOnClickListener(
                v -> checkPostFileAccount());
        root.addView(checkButton, buttonParams());

        Button selectButton =
                makeButton("SELECT MIDI / KAR FILES");
        selectButton.setOnClickListener(v -> chooseFiles());
        root.addView(selectButton, buttonParams());

        fileList = new TextView(this);
        fileList.setText("No files selected.");
        fileList.setTextColor(Color.WHITE);
        fileList.setTextSize(14);
        fileList.setPadding(0, dp(12), 0, dp(12));
        root.addView(fileList);

        progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar,
                new LinearLayout.LayoutParams(-1, dp(12)));

        uploadButton =
                makeButton("UPLOAD SELECTED SONGS");
        uploadButton.setEnabled(false);
        uploadButton.setOnClickListener(v -> beginUploads());
        root.addView(uploadButton, buttonParams());

        TextView note = new TextView(this);
        note.setText(
                "Supported files: MID, MIDI and KAR\n"
                + "PostFile free-plan file limit: 50 MB per file.\n"
                + "Your API key is encrypted on this device.");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(12);
        note.setPadding(0, dp(12), 0, dp(16));
        root.addView(note);

        Button backButton = makeButton("BACK TO SONGBOOK");
        backButton.setOnClickListener(v -> finish());
        root.addView(backButton, buttonParams());

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(20, 125, 65)));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(9);
        return params;
    }

    // ---------------------------------------------------------
    // ENCRYPTED API KEY STORAGE
    // ---------------------------------------------------------

    private SecretKey getOrCreateEncryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");

        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT
                        | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());

        return generator.generateKey();
    }

    private void saveApiKey(String apiKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey());

        byte[] encrypted = cipher.doFinal(
                apiKey.getBytes(StandardCharsets.UTF_8));

        SharedPreferences prefs =
                getSharedPreferences(KEY_PREFS, MODE_PRIVATE);

        prefs.edit()
                .putString("iv", Base64.encodeToString(
                        cipher.getIV(), Base64.NO_WRAP))
                .putString("key", Base64.encodeToString(
                        encrypted, Base64.NO_WRAP))
                .apply();

        cachedApiKey = apiKey;
    }

    private String getSavedApiKey() {
        try {
            SharedPreferences prefs =
                    getSharedPreferences(KEY_PREFS, MODE_PRIVATE);

            String ivText = prefs.getString("iv", "");
            String encryptedText = prefs.getString("key", "");

            if (ivText.isEmpty() || encryptedText.isEmpty()) {
                return "";
            }

            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(
                    encryptedText, Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    getOrCreateEncryptionKey(),
                    new GCMParameterSpec(128, iv));

            return new String(
                    cipher.doFinal(encrypted),
                    StandardCharsets.UTF_8);

        } catch (Exception e) {
            return "";
        }
    }

    private void showApiKeyDialog() {
        if (uploading) return;

        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("Paste your PostFile API key");
        keyInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("POSTFILE API KEY")
                .setMessage(
                        "Enter your existing PostFile API key.\n"
                        + "It will be encrypted and saved on this phone.")
                .setView(keyInput)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", null)
                .create();

        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(button -> {
                            String value = keyInput.getText()
                                    .toString().trim();

                            if (value.isEmpty()
                                    || value.contains(" ")) {
                                keyInput.setError(
                                        "Enter a valid API key");
                                return;
                            }

                            try {
                                saveApiKey(value);
                                status.setText(
                                        "PostFile API key saved securely.");
                                uploadButton.setEnabled(
                                        !selectedFiles.isEmpty());
                                dialog.dismiss();

                            } catch (Exception e) {
                                Toast.makeText(this,
                                        "Could not securely save the key.",
                                        Toast.LENGTH_LONG).show();
                            }
                        }));

        dialog.show();
    }

    // ---------------------------------------------------------
    // POSTFILE ACCOUNT AND FILE LIST CHECK
    // ---------------------------------------------------------

    private void checkPostFileAccount() {
        if (uploading) {
            Toast.makeText(this,
                    "Wait until uploading finishes.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String apiKey = cachedApiKey;

        if (apiKey.isEmpty()) {
            showApiKeyDialog();
            return;
        }

        status.setText("Checking PostFile account and files...");

        new Thread(() -> {
            try {
                JSONObject account = getPostFileJson(
                        "https://postfile.net/v1/account",
                        apiKey);

                JSONObject listing = getPostFileJson(
                        "https://postfile.net/v1/files?page=1&per_page=50",
                        apiKey);

                String email = account.optString(
                        "email", "Not provided");

                String plan = account.optString(
                        "plan", "Unknown");

                int uploads = account.optInt(
                        "uploads_this_month", -1);

                int limit = account.optInt(
                        "upload_limit", -1);

                int total = listing.optInt("total", -1);

                JSONArray files = listing.optJSONArray("files");

                StringBuilder report = new StringBuilder();

                report.append("POSTFILE ACCOUNT\n\n");
                report.append("Email: ")
                        .append(email).append("\n");
                report.append("Plan: ")
                        .append(plan).append("\n");

                report.append("Uploads this month: ")
                        .append(uploads >= 0
                                ? String.valueOf(uploads)
                                : "Unknown");

                if (limit >= 0) {
                    report.append(" / ").append(limit);
                }

                report.append("\n\n");
                report.append("TOTAL FILES: ")
                        .append(total >= 0
                                ? String.valueOf(total)
                                : "Unknown")
                        .append("\n\n");

                report.append("FILES IN THIS ACCOUNT:\n");

                if (files != null && files.length() > 0) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file =
                                files.getJSONObject(i);

                        report.append("\n")
                                .append(i + 1)
                                .append(". ")
                                .append(file.optString(
                                        "name", "Unnamed file"))
                                .append("\nID: ")
                                .append(file.optString(
                                        "file_id", "Unknown"))
                                .append("\n");
                    }
                } else {
                    report.append("No files returned.\n");
                }

                String finalReport = report.toString();

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    status.setText(
                            "PostFile account check completed.");

                    new AlertDialog.Builder(this)
                            .setTitle("POSTFILE ACCOUNT CHECK")
                            .setMessage(finalReport)
                            .setPositiveButton("OK", null)
                            .show();
                });

            } catch (Exception e) {
                String error = e.getMessage() == null
                        ? "Could not check PostFile account."
                        : e.getMessage();

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    status.setText("Account check failed.");

                    new AlertDialog.Builder(this)
                            .setTitle("POSTFILE CHECK FAILED")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }).start();
    }

    private JSONObject getPostFileJson(
            String address, String apiKey) throws Exception {

        HttpURLConnection connection = null;

        try {
            URL url = new URL(address);

            connection = (HttpURLConnection)
                    url.openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty(
                    "X-API-Key", apiKey);

            int responseCode = connection.getResponseCode();

            InputStream stream = responseCode >= 200
                    && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            StringBuilder response = new StringBuilder();

            if (stream != null) {
                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             stream,
                                             StandardCharsets.UTF_8))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }

            if (responseCode < 200 || responseCode >= 300) {
                String error = "PostFile HTTP " + responseCode;

                try {
                    JSONObject errorJson =
                            new JSONObject(response.toString());

                    error += ": " + errorJson.optString(
                            "detail", response.toString());

                } catch (Exception ignored) {
                    error += ": " + response;
                }

                throw new Exception(error);
            }

            return new JSONObject(response.toString());

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------
    // FILE SELECTION
    // ---------------------------------------------------------

    private void chooseFiles() {
        if (uploading) return;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        startActivityForResult(intent, REQUEST_FILES);
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_FILES
                || resultCode != RESULT_OK || data == null) {
            return;
        }

        selectedFiles.clear();

        if (data.getClipData() != null) {
            android.content.ClipData clip = data.getClipData();

            for (int i = 0; i < clip.getItemCount(); i++) {
                addSelectedFile(clip.getItemAt(i).getUri(),
                        data.getFlags());
            }

        } else if (data.getData() != null) {
            addSelectedFile(data.getData(), data.getFlags());
        }

        StringBuilder names = new StringBuilder();

        for (Uri uri : selectedFiles) {
            names.append("• ")
                    .append(getFileName(uri))
                    .append("\n");
        }

        fileList.setText(selectedFiles.isEmpty()
                ? "No supported MIDI/KAR files selected."
                : names.toString());

        uploadButton.setEnabled(
                !selectedFiles.isEmpty()
                        && !cachedApiKey.isEmpty());

        status.setText(selectedFiles.size()
                + " supported file(s) selected.");
    }

    private void addSelectedFile(Uri uri, int flags) {
        if (uri == null) return;

        String extension = getExtension(getFileName(uri));

        if (!extension.equals("mid")
                && !extension.equals("midi")
                && !extension.equals("kar")) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }

        if (!selectedFiles.contains(uri)) {
            selectedFiles.add(uri);
        }
    }

    private String getFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, null, null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME);

                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null) return name;
                }
            }
        } catch (Exception ignored) {
        }

        String fallback = uri.getLastPathSegment();
        return fallback == null ? "karaoke.mid" : fallback;
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String defaultTitle(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    // ---------------------------------------------------------
    // UPLOAD SEQUENCE
    // ---------------------------------------------------------

    private void beginUploads() {
        if (uploading || selectedFiles.isEmpty()) return;

        if (cachedApiKey.isEmpty()) {
            showApiKeyDialog();
            return;
        }

        uploading = true;
        currentIndex = 0;
        uploadButton.setEnabled(false);

        requestSongDetails();
    }

    private void requestSongDetails() {
        if (currentIndex >= selectedFiles.size()) {
            uploading = false;
            uploadButton.setEnabled(true);
            progressBar.setProgress(100);
            status.setText("All selected files processed.");

            Toast.makeText(this,
                    "Upload session finished.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Uri uri = selectedFiles.get(currentIndex);
        String filename = getFileName(uri);

        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint("Song title");
        titleInput.setText(defaultTitle(filename));

        EditText artistInput = new EditText(this);
        artistInput.setSingleLine(true);
        artistInput.setHint("Singer / Artist (optional)");

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        form.addView(titleInput);
        form.addView(artistInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("SONG " + (currentIndex + 1)
                        + " OF " + selectedFiles.size())
                .setMessage(filename)
                .setView(form)
                .setNegativeButton("CANCEL ALL", (d, w) -> {
                    uploading = false;
                    uploadButton.setEnabled(true);
                    status.setText("Upload cancelled.");
                })
                .setPositiveButton("UPLOAD THIS SONG", null)
                .create();

        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(button -> {
                            String title = titleInput.getText()
                                    .toString().trim();

                            if (title.isEmpty()) {
                                titleInput.setError(
                                        "Enter the song title");
                                return;
                            }

                            currentTitle = title;
                            currentArtist = artistInput.getText()
                                    .toString().trim();

                            dialog.dismiss();
                            uploadCurrentFile(uri);
                        }));

        dialog.show();
    }

    private void uploadCurrentFile(Uri uri) {
        status.setText("Uploading "
                + getFileName(uri) + "...");

        progressBar.setProgress(0);

        new Thread(() -> {
            try {
                String apiKey = cachedApiKey;

                if (apiKey.isEmpty()) {
                    throw new Exception(
                            "PostFile API key is missing. Save it again.");
                }

                String cdnUrl = uploadToPostFile(uri, apiKey);

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    // This method is reached only after PostFile
                    // returns a valid URL AND the account file list
                    // confirms the uploaded file ID and URL.
                    saveUploadedSong(
                            currentTitle, currentArtist, cdnUrl);

                    status.setText(
                            "HOSTED AND VERIFIED: " + currentTitle);

                    currentIndex++;
                    requestSongDetails();
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Upload failed." : e.getMessage();

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    new AlertDialog.Builder(this)
                            .setTitle("UPLOAD FAILED / NOT VERIFIED")
                            .setMessage(message
                                    + "\n\nContinue with the next file?")
                            .setNegativeButton("STOP", (d, w) -> {
                                uploading = false;
                                uploadButton.setEnabled(true);
                                status.setText("Upload stopped.");
                            })
                            .setPositiveButton("NEXT FILE", (d, w) -> {
                                currentIndex++;
                                requestSongDetails();
                            })
                            .show();
                });
            }
        }, "PostFile-Upload").start();
    }

    // ---------------------------------------------------------
    // POSTFILE MULTIPART UPLOAD
    // ---------------------------------------------------------

    private String uploadToPostFile(
            Uri uri, String apiKey) throws Exception {

        String boundary =
                "----Donut4U" + System.currentTimeMillis();

        HttpURLConnection connection = null;

        try {
            URL url = new URL(API_URL);
            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(65536);

            connection.setRequestProperty("X-API-Key", apiKey);
            connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=" + boundary);

            String filename = getFileName(uri)
                    .replace("\"", "")
                    .replace("\r", "")
                    .replace("\n", "");

            try (DataOutputStream output =
                         new DataOutputStream(
                                 connection.getOutputStream())) {

                output.writeBytes("--" + boundary + "\r\n");
                output.writeBytes(
                        "Content-Disposition: form-data; name=\"file\"; "
                                + "filename=\"" + filename + "\"\r\n");
                output.writeBytes(
                        "Content-Type: application/octet-stream\r\n\r\n");

                long total = 0;
                byte[] buffer = new byte[8192];

                try (InputStream input =
                             getContentResolver().openInputStream(uri)) {

                    if (input == null) {
                        throw new Exception(
                                "Cannot read the selected file.");
                    }

                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        total += count;

                        if (total > 50L * 1024L * 1024L) {
                            throw new Exception(
                                    "This file exceeds the 50 MB "
                                            + "PostFile free-plan limit.");
                        }

                        output.write(buffer, 0, count);
                    }
                }

                if (total == 0) {
                    throw new Exception(
                            "The selected file is empty.");
                }

                output.writeBytes(
                        "\r\n--" + boundary + "--\r\n");
                output.flush();
            }

            int responseCode = connection.getResponseCode();

            InputStream responseStream = responseCode >= 200
                    && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            StringBuilder response = new StringBuilder();

            if (responseStream != null) {
                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             responseStream,
                                             StandardCharsets.UTF_8))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }

            if (responseCode < 200 || responseCode >= 300) {
                String error = "PostFile returned HTTP "
                        + responseCode;

                try {
                    JSONObject json =
                            new JSONObject(response.toString());

                    error += ": " + json.optString(
                            "detail", response.toString());

                } catch (Exception ignored) {
                    error += ": " + response;
                }

                throw new Exception(error);
            }

            JSONObject result =
                    new JSONObject(response.toString());

            String fileId =
                    result.optString("file_id", "").trim();

            String cdnUrl =
                    result.optString("url", "").trim();

            long returnedSize =
                    result.optLong("size", 0L);

            if (fileId.isEmpty()) {
                throw new Exception(
                        "PostFile did not return a file ID. "
                                + "The app will not mark this song as hosted.\n"
                                + response);
            }

            if (!cdnUrl.startsWith(
                    "https://cdn.postfile.download/file/")) {
                throw new Exception(
                        "PostFile did not return a valid CDN URL. "
                                + "The app will not save this song locally.\n"
                                + response);
            }

            if (returnedSize <= 0L) {
                throw new Exception(
                        "PostFile returned an invalid file size. "
                                + "Hosting could not be verified.\n"
                                + response);
            }

            // Verify that this exact file is visible in the account.
            verifyPostFileHosting(fileId, cdnUrl, apiKey);

            return cdnUrl;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------
    // VERIFY FILE IN POSTFILE HOSTING ACCOUNT
    // ---------------------------------------------------------

    private void verifyPostFileHosting(
            String fileId, String expectedUrl, String apiKey)
            throws Exception {

        Exception lastError = null;

        // Retry because the account file list may take a moment
        // to show a newly uploaded file.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject listing = getPostFileJson(
                        "https://postfile.net/v1/files?page=1&per_page=200",
                        apiKey);

                JSONArray files = listing.optJSONArray("files");

                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.getJSONObject(i);

                        String listedId =
                                file.optString("file_id", "");

                        if (fileId.equals(listedId)) {
                            String listedUrl =
                                    file.optString("url", "").trim();

                            if (!expectedUrl.equals(listedUrl)) {
                                throw new Exception(
                                        "PostFile returned this file ID, "
                                                + "but the hosted URL does not match. "
                                                + "Song not added to the songbook.");
                            }

                            return;
                        }
                    }
                }

                lastError = new Exception(
                        "File ID " + fileId
                                + " was not found in the PostFile account file list.");

            } catch (Exception e) {
                lastError = e;
            }

            if (attempt < 2) {
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Exception(
                            "PostFile hosting verification was interrupted.", e);
                }
            }
        }

        throw new Exception(
                "HOSTING NOT VERIFIED. Donut4U will not report this "
                        + "song as successfully hosted. PostFile file ID: "
                        + fileId + ". "
                        + (lastError == null
                        ? "The file was not found in the account list."
                        : lastError.getMessage()));
    }

    // ---------------------------------------------------------
    // AUTOMATIC STAGE 3 SONGBOOK REGISTRATION
    // ---------------------------------------------------------

    private void saveUploadedSong(
            String title, String artist, String cdnUrl) {

        SharedPreferences prefs = getSharedPreferences(
                SONGBOOK_PREFS, MODE_PRIVATE);

        try {
            JSONArray songs = new JSONArray(
                    prefs.getString(SONGS_KEY, "[]"));

            for (int i = 0; i < songs.length(); i++) {
                JSONObject existing = songs.getJSONObject(i);

                if (cdnUrl.equals(
                        existing.optString("uri", ""))) {
                    status.setText(
                            "This uploaded URL is already registered.");
                    return;
                }
            }

            int nextNumber = 10000;

            for (int i = 0; i < songs.length(); i++) {
                try {
                    int number = Integer.parseInt(
                            songs.getJSONObject(i)
                                    .optString("number", "0"));

                    nextNumber = Math.max(
                            nextNumber, number + 1);

                } catch (Exception ignored) {
                }
            }

            String displayTitle = artist.isEmpty()
                    ? title : title + " — " + artist;

            JSONObject song = new JSONObject();
            song.put("number", String.format(
                    Locale.US, "%05d", nextNumber));
            song.put("title", displayTitle);
            song.put("uri", cdnUrl);
            song.put("type", "MIDI/KAR");

            songs.put(song);

            prefs.edit()
                    .putString(SONGS_KEY, songs.toString())
                    .apply();

            Toast.makeText(this,
                    "Hosted and verified on PostFile: "
                            + displayTitle,
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this,
                    "PostFile hosting succeeded, but songbook "
                            + "registration failed. CDN link: " + cdnUrl,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
