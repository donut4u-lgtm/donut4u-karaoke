
package com.donut4u.karaoke;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Stage3SongQueue {

    private static final String TAG = "Donut4UStage3Queue";
    private static final String PREFS = "donut4u_stage3_queue";
    private static final String QUEUE_KEY = "queue_json";
    private static final String PLAYING_KEY = "now_playing_json";

    private static final List<QueuedSong> queue = new ArrayList<>();
    private static QueuedSong nowPlaying;

    private static SharedPreferences preferences;
    private static boolean loaded;

    private Stage3SongQueue() {
    }

    public static final class QueuedSong {
        public final String number;
        public final String title;
        public final String uri;
        public final String type;

        public QueuedSong(
                String number,
                String title,
                String uri,
                String type
        ) {
            this.number = number == null ? "" : number;
            this.title = title == null ? "" : title;
            this.uri = uri == null ? "" : uri;
            this.type = type == null ? "" : type;
        }
    }

    /**
     * Call once from an Activity before using the queue.
     * Application context avoids retaining an Activity.
     */
    public static synchronized void initialize(Context context) {
        if (context == null) {
            return;
        }

        if (preferences == null) {
            preferences = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        if (!loaded) {
            loadLocked();
        }
    }

    private static void ensureLoadedLocked() {
        if (preferences != null && !loaded) {
            loadLocked();
        }
    }

    private static void loadLocked() {
        if (preferences == null) {
            return;
        }

        queue.clear();
        nowPlaying = null;

        try {
            JSONArray array = new JSONArray(
                    preferences.getString(QUEUE_KEY, "[]")
            );

            for (int i = 0; i < array.length(); i++) {
                QueuedSong song = fromJson(array.optJSONObject(i));

                if (song != null && !song.uri.trim().isEmpty()) {
                    queue.add(song);
                }
            }

            JSONObject playingObject = null;

            String playingJson = preferences.getString(
                    PLAYING_KEY, ""
            );

            if (!playingJson.isEmpty()) {
                playingObject = new JSONObject(playingJson);
            }

            nowPlaying = fromJson(playingObject);

        } catch (Exception e) {
            Log.e(TAG, "Could not restore saved queue", e);
        }

        loaded = true;
    }

    private static QueuedSong fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }

        return new QueuedSong(
                object.optString("number", ""),
                object.optString("title", ""),
                object.optString("uri", ""),
                object.optString("type", "")
        );
    }

    private static JSONObject toJson(QueuedSong song)
            throws Exception {
        JSONObject object = new JSONObject();

        object.put("number", song.number);
        object.put("title", song.title);
        object.put("uri", song.uri);
        object.put("type", song.type);

        return object;
    }

    private static void saveLocked() {
        if (preferences == null) {
            return;
        }

        try {
            JSONArray array = new JSONArray();

            for (QueuedSong song : queue) {
                array.put(toJson(song));
            }

            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(QUEUE_KEY, array.toString());

            if (nowPlaying == null) {
                editor.remove(PLAYING_KEY);
            } else {
                editor.putString(
                        PLAYING_KEY,
                        toJson(nowPlaying).toString()
                );
            }

            editor.apply();

        } catch (Exception e) {
            Log.e(TAG, "Could not save queue", e);
        }
    }

    public static synchronized boolean add(QueuedSong song) {
        ensureLoadedLocked();

        if (song == null || song.uri.trim().isEmpty()) {
            return false;
        }

        for (QueuedSong item : queue) {
            if (item.uri.equals(song.uri)) {
                return false;
            }
        }

        if (nowPlaying != null
                && nowPlaying.uri.equals(song.uri)) {
            return false;
        }

        queue.add(song);
        saveLocked();
        return true;
    }

    public static synchronized boolean removeAt(int position) {
        ensureLoadedLocked();

        if (position < 0 || position >= queue.size()) {
            return false;
        }

        queue.remove(position);
        saveLocked();
        return true;
    }

    public static synchronized QueuedSong playNext() {
        ensureLoadedLocked();

        if (queue.isEmpty()) {
            nowPlaying = null;
            saveLocked();
            return null;
        }

        nowPlaying = queue.remove(0);
        saveLocked();
        return nowPlaying;
    }

    public static synchronized QueuedSong getNowPlaying() {
        ensureLoadedLocked();
        return nowPlaying;
    }

    public static synchronized void setNowPlaying(QueuedSong song) {
        ensureLoadedLocked();
        nowPlaying = song;
        saveLocked();
    }

    public static synchronized List<QueuedSong> getQueue() {
        ensureLoadedLocked();

        return Collections.unmodifiableList(
                new ArrayList<>(queue)
        );
    }

    public static synchronized int size() {
        ensureLoadedLocked();
        return queue.size();
    }

    public static synchronized boolean isEmpty() {
        ensureLoadedLocked();
        return queue.isEmpty();
    }

    public static synchronized void clearQueue() {
        ensureLoadedLocked();
        queue.clear();
        saveLocked();
    }

    public static synchronized void stopAndClear() {
        ensureLoadedLocked();
        queue.clear();
        nowPlaying = null;
        saveLocked();
    }

    public static synchronized boolean move(
            int fromPosition,
            int toPosition
    ) {
        ensureLoadedLocked();

        if (fromPosition < 0
                || fromPosition >= queue.size()
                || toPosition < 0
                || toPosition >= queue.size()) {
            return false;
        }

        if (fromPosition == toPosition) {
            return true;
        }

        QueuedSong song = queue.remove(fromPosition);
        queue.add(toPosition, song);

        saveLocked();
        return true;
    }

    public static synchronized boolean containsUri(String uri) {
        ensureLoadedLocked();

        if (uri == null) {
            return false;
        }

        if (nowPlaying != null && nowPlaying.uri.equals(uri)) {
            return true;
        }

        for (QueuedSong song : queue) {
            if (song.uri.equals(uri)) {
                return true;
            }
        }

        return false;
    }
}
