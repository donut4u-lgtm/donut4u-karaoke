
package com.donut4u.karaoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Stage3SongQueue {

    private static final List<QueuedSong> queue = new ArrayList<>();
    private static QueuedSong nowPlaying;

    private Stage3SongQueue() {
        // Utility class.
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

    public static synchronized boolean add(QueuedSong song) {
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
        return true;
    }

    public static synchronized boolean removeAt(int position) {
        if (position < 0 || position >= queue.size()) {
            return false;
        }

        queue.remove(position);
        return true;
    }

    public static synchronized QueuedSong playNext() {
        if (queue.isEmpty()) {
            nowPlaying = null;
            return null;
        }

        nowPlaying = queue.remove(0);
        return nowPlaying;
    }

    public static synchronized QueuedSong getNowPlaying() {
        return nowPlaying;
    }

    public static synchronized void setNowPlaying(QueuedSong song) {
        nowPlaying = song;
    }

    public static synchronized List<QueuedSong> getQueue() {
        return Collections.unmodifiableList(
                new ArrayList<>(queue)
        );
    }

    public static synchronized int size() {
        return queue.size();
    }

    public static synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public static synchronized void clearQueue() {
        queue.clear();
    }

    public static synchronized void stopAndClear() {
        queue.clear();
        nowPlaying = null;
    }

    public static synchronized boolean move(
            int fromPosition,
            int toPosition
    ) {
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
        return true;
    }

    public static synchronized boolean containsUri(String uri) {
        if (uri == null) {
            return false;
        }

        if (nowPlaying != null
                && nowPlaying.uri.equals(uri)) {
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
