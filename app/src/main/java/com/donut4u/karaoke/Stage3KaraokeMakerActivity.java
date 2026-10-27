
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Stage3KaraokeMakerActivity extends Activity {

    private static final String API =
            "https://donut4u-karaoke.onrender.com";
    private static final int PICK_AUDIO = 4301;
    private static final String PREFS = "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private TextView status;
    private Button chooseButton;
    private volatile boolean cancelled;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private int dp(int value) {
        return (int) (value
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(14));
        root.setBackgroundColor(Color.rgb(8, 17, 12));

        TextView heading = new TextView(this);
        heading.setText("DONUT4U KARAOKE");
        heading.setTextColor(Color.rgb(80, 255, 150));
        heading.setTextSize(25);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading,
                new LinearLayout.LayoutParams(-1, dp(44)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • KARAOKE FILE MAKER");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(17);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        TextView explanation = new TextView(this);
        explanation.setText(
                "Choose an audio or video song from your phone. " +
                "The online server will remove vocals, detect lyrics " +
                "and create a karaoke video. An internet connection " +
                "is required. Processing may take several minutes."
        );
        explanation.setTextColor(Color.LTGRAY);
        explanation.setTextSize(15);
        explanation.setPadding(dp(4), dp(4), dp(4), dp(16));
        root.addView(explanation);

        chooseButton = button("CHOOSE SONG AND MAKE KARAOKE");
        chooseButton.setOnClickListener(v -> chooseFile());
        root.addView(chooseButton, buttonParams());

        status = new TextView(this);
        status.setText("Ready. Select a song to begin.");
        status.setTextColor(Color.rgb(140, 255, 180));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(18), dp(8), dp(18));
        root.addView(status,
                new LinearLayout.LayoutParams(-1, 0, 1f));

        Button back = button("BACK TO SONGBOOK");
        back.setOnClickListener(v -> finish());
        root.addView(back, buttonParams());

        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(9);
        return params;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(20, 125, 65)));
        return button;
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(intent, PICK_AUDIO);
        } catch (Exception e) {
            toast("Could not open the file picker.");
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_AUDIO
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    data.getFlags()
                            & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }

        String name = getName(uri);
        cancelled = false;
        chooseButton.setEnabled(false);
        status.setText("Uploading " + name + "…");

        worker.execute(() -> processSelectedFile(uri, name));
    }

    private void processSelectedFile(Uri uri, String name) {
        try {
            JSONObject submitted = upload(uri, name);
            String jobId = submitted.getString("job_id");

            JSONObject job = null;

            for (int attempt = 0; attempt < 450 && !cancelled;
                 attempt++) {
                Thread.sleep(4000L);

                job = getJson(API + "/v1/jobs/" + jobId);

                String state = job.optString("status", "unknown");
                String progress = job.optString("progress", state);

                showStatus(
                        "Karaoke processing: " +
                        progress.replace('_', ' ') +
                        "\nPlease keep this screen open."
                );

                if ("completed".equalsIgnoreCase(state)) {
                    break;
                }

                if ("failed".equalsIgnoreCase(state)) {
                    throw new Exception(job.optString(
                            "error", "The server could not process this song."));
                }
            }

            if (cancelled) {
                return;
            }

            if (job == null ||
                    !"completed".equalsIgnoreCase(
                            job.optString("status"))) {
                throw new Exception(
                        "Processing took too long. Please try again later.");
            }

            File musicFolder = new File(
                    getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                    "Donut4U-Karaoke");

            if (!musicFolder.exists() && !musicFolder.mkdirs()) {
                throw new Exception("Could not create the output folder.");
            }

            String base = safeName(name);
            File instrumental = new File(
                    musicFolder, base + "_instrumental.mp3");
            File lyricsJson = new File(
                    musicFolder, base + "_lyrics.json");
            File lyricsLrc = new File(
                    musicFolder, base + "_lyrics.lrc");
            File video = new File(
                    musicFolder, base + "_karaoke.mp4");

            showStatus("Downloading instrumental music…");
            download(API + "/v1/jobs/" + jobId
                    + "/instrumental-mp3", instrumental);

            showStatus("Saving synchronized lyrics…");
            download(API + "/v1/jobs/" + jobId
                    + "/lyrics", lyricsJson);
            createLrc(lyricsJson, lyricsLrc);

            showStatus("Rendering karaoke video…");
            JSONObject render = postJson(
                    API + "/v1/jobs/" + jobId + "/video",
                    new JSONObject());

            String videoUrl = render.optString(
                    "video_url",
                    "/v1/jobs/" + jobId + "/video");

            if (videoUrl.startsWith("/")) {
                videoUrl = API + videoUrl;
            }

            download(videoUrl, video);

            addToSongbook(instrumental, lyricsLrc, video);

            showStatus(
                    "FILES CREATED\n\n" +
                    "Instrumental MP3\n" +
                    "Synchronized lyrics LRC\n" +
                    "Karaoke video MP4\n\n" +
                    "Return to the Songbook to find your files."
            );

            runOnUiThread(() -> {
                chooseButton.setEnabled(true);
                toast("Karaoke files added to the songbook.");
            });

        } catch (Exception e) {
            showStatus("Could not finish this song.\n\n"
                    + safeMessage(e));
            runOnUiThread(() -> chooseButton.setEnabled(true));
        }
    }

    private JSONObject upload(Uri uri, String name) throws Exception {
        String boundary = "----Donut4U" + System.currentTimeMillis();

        HttpURLConnection connection =
                (HttpURLConnection) new URL(
                        API + "/v1/jobs/from-upload").openConnection();

        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty(
                "Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");

        try (OutputStream out = new BufferedOutputStream(
                connection.getOutputStream())) {

            write(out, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                    + name + "\r\n");

            write(out, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"artist\"\r\n\r\n"
                    + "Unknown\r\n");

            write(out, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeName(name) + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n");

            try (InputStream in = new BufferedInputStream(
                    getContentResolver().openInputStream(uri))) {
                byte[] buffer = new byte[64 * 1024];
                int count;

                while ((count = in.read(buffer)) != -1) {
                    if (cancelled) {
                        throw new Exception("Cancelled.");
                    }
                    out.write(buffer, 0, count);
                }
            }

            write(out, "\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        int code = connection.getResponseCode();
        String body = readResponse(connection, code);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception(apiError(
                    body, "Upload failed (HTTP " + code + ")."));
        }

        return new JSONObject(body);
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();

        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Accept", "application/json");

        int code = connection.getResponseCode();
        String body = readResponse(connection, code);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception(apiError(
                    body, "Server request failed (HTTP " + code + ")."));
        }

        return new JSONObject(body);
    }

    private JSONObject postJson(
            String address, JSONObject payload) throws Exception {

        HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();

        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty(
                "Content-Type", "application/json; charset=UTF-8");

        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        String body = readResponse(connection, code);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception(apiError(
                    body, "Video rendering failed (HTTP " + code + ")."));
        }

        return new JSONObject(body);
    }

    private void download(String address, File destination)
            throws Exception {

        HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();

        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setInstanceFollowRedirects(true);

        int code = connection.getResponseCode();

        if (code < 200 || code >= 300) {
            String body = readResponse(connection, code);
            connection.disconnect();
            throw new Exception(apiError(
                    body, "Download failed (HTTP " + code + ")."));
        }

        File temporary = new File(
                destination.getParentFile(),
                destination.getName() + ".part");

        try (InputStream in = new BufferedInputStream(
                connection.getInputStream());
             OutputStream out = new BufferedOutputStream(
                     new FileOutputStream(temporary))) {

            byte[] buffer = new byte[64 * 1024];
            int count;

            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new Exception(
                    "Could not save " + destination.getName());
        }
    }

    private void createLrc(File jsonFile, File lrcFile)
            throws Exception {

        JSONObject root = new JSONObject(readFile(jsonFile));
        JSONArray words = root.optJSONArray("words");

        StringBuilder lrc =
                new StringBuilder("[ar:Donut4U Karaoke]\n");

        if (words != null) {
            StringBuilder line = new StringBuilder();
            double lineStart = -1;

            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.optJSONObject(i);
                if (word == null) continue;

                double start = word.optDouble("start", 0);
                String text = word.optString("text", "").trim();

                if (text.isEmpty()) continue;
                if (lineStart < 0) lineStart = start;

                if (line.length() > 0) line.append(' ');
                line.append(text);

                boolean endLine =
                        text.matches(".*[.!?,;:]$")
                        || line.length() > 48
                        || i == words.length() - 1;

                if (endLine) {
                    lrc.append('[')
                            .append(lrcTime(lineStart))
                            .append(']')
                            .append(line)
                            .append('\n');

                    line.setLength(0);
                    lineStart = -1;
                }
            }

            if (line.length() > 0) {
                lrc.append('[')
                        .append(lrcTime(Math.max(0, lineStart)))
                        .append(']')
                        .append(line)
                        .append('\n');
            }
        }

        try (OutputStream out = new FileOutputStream(lrcFile)) {
            out.write(lrc.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private String lrcTime(double seconds) {
        int centis = (int) Math.max(0, Math.round(seconds * 100));

        return String.format(
                Locale.US, "%02d:%02d.%02d",
                centis / 6000, (centis / 100) % 60, centis % 100);
    }

    private void addToSongbook(
            File audio, File lyrics, File video) throws Exception {

        SharedPreferences preferences =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        JSONArray list;

        try {
            list = new JSONArray(
                    preferences.getString(SONGS_KEY, "[]"));
        } catch (Exception ignored) {
            list = new JSONArray();
        }

        addSong(list, audio, "AUDIO");
        addSong(list, lyrics, "LYRICS");
        addSong(list, video, "VIDEO");

        if (!preferences.edit()
                .putString(SONGS_KEY, list.toString())
                .commit()) {
            throw new Exception(
                    "Could not update the songbook list.");
        }
    }

    private void addSong(
            JSONArray list, File file, String type) throws Exception {

        String uri = Uri.fromFile(file).toString();

        for (int i = 0; i < list.length(); i++) {
            if (uri.equals(list.getJSONObject(i).optString("uri"))) {
                return;
            }
        }

        int number =
                70000 + (int) (System.currentTimeMillis() % 20000);

        boolean used;

        do {
            used = false;

            for (int i = 0; i < list.length(); i++) {
                if (String.format(
                        Locale.US, "%05d", number).equals(
                        list.getJSONObject(i).optString("number"))) {
                    used = true;
                    number++;
                    break;
                }
            }
        } while (used);

        JSONObject item = new JSONObject();
        item.put("number", String.format(Locale.US, "%05d", number));
        item.put("title", file.getName());
        item.put("uri", uri);
        item.put("type", type);
        list.put(item);
    }

    private String getName(Uri uri) {
        String name = "karaoke-song.mp3";
        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    uri, null, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME);

                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        return name == null || name.trim().isEmpty()
                ? "karaoke-song.mp3" : name;
    }

    private String safeName(String input) {
        String name = input == null ? "karaoke-song" : input;

        name = name.replaceAll(
                "(?i)\\.(mp3|wav|m4a|aac|ogg|opus|flac|webm|mp4|mpeg|mpga|wma)$",
                "");

        name = name.replaceAll("[^A-Za-z0-9._-]", "_");

        return name.length() > 70 ? name.substring(0, 70) : name;
    }

    private String readFile(File file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
        }

        return bytes.toString("UTF-8");
    }

    private String readResponse(
            HttpURLConnection connection, int code) throws Exception {

        InputStream input = code >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();

        if (input == null) return "";

        try (InputStream in = input;
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int count;

            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }

            return bytes.toString("UTF-8");
        }
    }

    private void write(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private String apiError(String body, String fallback) {
        try {
            return new JSONObject(body).optString("detail", fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();

        return message == null || message.trim().isEmpty()
                ? "Unexpected processing error." : message;
    }

    private void showStatus(String message) {
        runOnUiThread(() -> {
            if (status != null) status.setText(message);
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
