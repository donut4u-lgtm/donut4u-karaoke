
package com.donut4u.karaoke;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

public final class Stage3LrcMatcher {

    private static final String TAG = "Stage3LrcMatcher";
    private static final String PREFS = "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";

    private Stage3LrcMatcher() {
    }

    public static final class Match {
        public final String uri;
        public final String title;
        public final String text;

        Match(String uri, String title, String text) {
            this.uri = uri == null ? "" : uri;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }

        public boolean isValid() {
            return !text.trim().isEmpty()
                    && text.matches("(?s).*\\[\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?\\].*");
        }
    }

    public static Match findMatchingLyrics(
            Context context,
            String songTitle,
            String songUri) {

        if (context == null || songTitle == null
                || songTitle.trim().isEmpty()) {
            return null;
        }

        try {
            SharedPreferences preferences =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

            String json = preferences.getString(SONGS_KEY, "[]");
            JSONArray songs = new JSONArray(json);

            String target = normalize(songTitle);
            String targetFile = normalize(fileName(songUri));

            int bestIndex = -1;
            int bestScore = 0;

            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.optJSONObject(i);
                if (item == null) {
                    continue;
                }

                String type = item.optString("type", "");
                String uri = item.optString("uri", "");
                String title = item.optString("title", "");

                if (uri.trim().isEmpty()) {
                    continue;
                }

                if (!isLyricsEntry(type, uri, title)) {
                    continue;
                }

                int score = score(target, targetFile, title, uri);

                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }

            // Require a strong title match. Never attach unrelated lyrics.
            if (bestIndex < 0 || bestScore < 80) {
                return null;
            }

            JSONObject selected = songs.optJSONObject(bestIndex);
            if (selected == null) {
                return null;
            }

            String selectedUri = selected.optString("uri", "");
            String selectedTitle = selected.optString("title", "");

            String text = readText(context, selectedUri);
            if (text == null || text.trim().isEmpty()) {
                return null;
            }

            Match match = new Match(selectedUri, selectedTitle, text);
            return match.isValid() ? match : null;

        } catch (Exception error) {
            Log.e(TAG, "Could not find matching Songbook LRC.", error);
            return null;
        }
    }

    private static boolean isLyricsEntry(
            String type,
            String uri,
            String title) {

        String normalizedType = type == null
                ? ""
                : type.trim().toUpperCase(Locale.ROOT);

        String lowerUri = uri == null
                ? ""
                : uri.toLowerCase(Locale.ROOT);

        String lowerTitle = title == null
                ? ""
                : title.toLowerCase(Locale.ROOT);

        return "LYRICS".equals(normalizedType)
                || lowerUri.endsWith(".lrc")
                || lowerTitle.endsWith(".lrc");
    }

    private static int score(
            String targetTitle,
            String targetFile,
            String lyricTitle,
            String lyricUri) {

        String candidateTitle = normalize(lyricTitle);
        String candidateFile = normalize(fileName(lyricUri));

        if (targetTitle.isEmpty()) {
            return 0;
        }

        if (targetTitle.equals(candidateTitle)
                || targetTitle.equals(candidateFile)) {
            return 100;
        }

        if (!targetFile.isEmpty()
                && (targetFile.equals(candidateTitle)
                || targetFile.equals(candidateFile))) {
            return 100;
        }

        if (candidateTitle.length() >= 5
                && targetTitle.length() >= 5
                && (targetTitle.contains(candidateTitle)
                || candidateTitle.contains(targetTitle))) {
            return 85;
        }

        if (candidateFile.length() >= 5
                && targetTitle.length() >= 5
                && (targetTitle.contains(candidateFile)
                || candidateFile.contains(targetTitle))) {
            return 85;
        }

        return 0;
    }

    private static String readText(Context context, String uriText) {

        if (uriText == null || uriText.trim().isEmpty()) {
            return null;
        }

        try {
            Uri uri = Uri.parse(uriText);

            try (InputStream input =
                         context.getContentResolver().openInputStream(uri)) {

                if (input == null) {
                    return null;
                }

                StringBuilder result = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                input, StandardCharsets.UTF_8))) {

                    String line;
                    int total = 0;

                    while ((line = reader.readLine()) != null) {
                        total += line.length();

                        // Avoid loading an unexpectedly large document.
                        if (total > 1024 * 1024) {
                            break;
                        }

                        result.append(line).append('\n');
                    }
                }

                return result.toString();
            }

        } catch (Exception error) {
            Log.e(TAG, "Could not read LRC: " + uriText, error);
            return null;
        }
    }

    private static String fileName(String value) {

        if (value == null || value.trim().isEmpty()) {
            return "";
        }

        try {
            String path = Uri.parse(value).getLastPathSegment();

            if (path == null || path.trim().isEmpty()) {
                path = value;
            }

            int slash = Math.max(
                    path.lastIndexOf('/'),
                    path.lastIndexOf('\\'));

            if (slash >= 0 && slash + 1 < path.length()) {
                path = path.substring(slash + 1);
            }

            int dot = path.lastIndexOf('.');
            if (dot > 0) {
                path = path.substring(0, dot);
            }

            return path;

        } catch (Exception ignored) {
            return value;
        }
    }

    private static String normalize(String value) {

        if (value == null) {
            return "";
        }

        String result = fileName(value);

        result = Normalizer.normalize(
                result, Normalizer.Form.NFD);

        result = result.replaceAll("\\p{M}+", "");
        result = result.toLowerCase(Locale.ROOT);
        result = result.replaceAll(
                "\\.(mid|midi|kar|lrc)$", "");

        result = result.replaceAll(
                "[^a-z0-9]+", "");

        return result.trim();
    }
}
