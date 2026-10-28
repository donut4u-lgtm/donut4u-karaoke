
package com.donut4u.karaoke;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses SongHub shubidx catalog metadata.
 *
 * Blob offsets and lengths are retained as sector references.
 * This class does not decrypt or extract protected blob content.
 */
public final class Stage3SongHubIndex {

    private static final Charset LATIN1 =
            Charset.forName("ISO-8859-1");

    private Stage3SongHubIndex() {
    }

    public static final class Entry {
        public final String number;
        public final String title;
        public final String artist;
        public final String genre;
        public final int blobOffsetSectors;
        public final int blobLengthSectors;
        public final int mode;

        Entry(
                String number,
                String title,
                String artist,
                String genre,
                int blobOffsetSectors,
                int blobLengthSectors,
                int mode) {
            this.number = number;
            this.title = title;
            this.artist = artist;
            this.genre = genre;
            this.blobOffsetSectors = blobOffsetSectors;
            this.blobLengthSectors = blobLengthSectors;
            this.mode = mode;
        }
    }

    private static final class RawSong {
        int genreId;
        int singerIndex;
        int offset;
        int length;
        int mode;
        String title;
    }

    public static List<Entry> parse(byte[] bytes) {
        List<Entry> result = new ArrayList<>();

        if (bytes == null || bytes.length < 56) {
            return result;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN);

        long startValue = Integer.toUnsignedLong(buffer.getInt(52));

        if (startValue <= 0 || startValue >= bytes.length) {
            return result;
        }

        int offset = (int) startValue;

        final int TYPE_SONG = 1;
        final int TYPE_GENRE = 4;
        final int TYPE_SINGER = 5;
        final int TYPE_CODE = 9;

        List<String> singers = new ArrayList<>();
        List<Integer> codes = new ArrayList<>();
        Map<Integer, String> genres = new HashMap<>();
        List<RawSong> rawSongs = new ArrayList<>();

        while (offset < bytes.length) {
            int recordType = bytes[offset] & 0xff;

            if (recordType == 0) {
                offset++;
                continue;
            }

            if (recordType == TYPE_CODE) {
                if (offset + 4 > bytes.length) {
                    break;
                }

                int raw = TYPE_CODE
                        | ((bytes[offset + 1] & 0xff) << 8)
                        | ((bytes[offset + 2] & 0xff) << 16)
                        | ((bytes[offset + 3] & 0xff) << 24);

                int code = ((raw >>> 16) & 0xffff)
                        | ((raw & 0xff00) << 8);

                codes.add(code);
                offset += 4;
                continue;
            }

            if (offset + 2 > bytes.length) {
                break;
            }

            int length = bytes[offset + 1] & 0xff;

            if (length < 2 || offset + length > bytes.length) {
                break;
            }

            if (recordType == TYPE_GENRE) {
                if (length >= 5) {
                    int genreId = bytes[offset + 2] & 0xff;
                    String name = decode(
                            bytes, offset + 5, offset + length);
                    genres.put(genreId, name);
                }
            } else if (recordType == TYPE_SINGER) {
                if (length >= 5) {
                    singers.add(decode(
                            bytes, offset + 5, offset + length));
                } else {
                    singers.add("Unknown");
                }
            } else if (recordType == TYPE_SONG) {
                if (length < 12) {
                    offset += length;
                    continue;
                }

                RawSong song = new RawSong();

                song.genreId = bytes[offset + 2] & 0xff;

                int flags = bytes[offset + 3] & 0xff;
                song.mode = (flags & 0x80) != 0 ? 2 : 1;

                song.singerIndex =
                        buffer.getShort(offset + 4) & 0x3fff;

                song.length =
                        buffer.getShort(offset + 6) & 0xffff;

                song.offset = buffer.getInt(offset + 8);

                int cursor = song.mode == 2
                        ? offset + 18
                        : offset + 12;

                int fixedPayload = song.mode == 2 ? 16 : 10;
                int titleLength = (length - 2) - fixedPayload;

                song.title = "Unknown Title";

                if (titleLength > 0
                        && cursor >= offset
                        && cursor + titleLength <= offset + length) {
                    song.title = decode(
                            bytes, cursor, cursor + titleLength);
                }

                if (song.offset >= 0 && song.length > 0) {
                    rawSongs.add(song);
                }
            }

            offset += length;
        }

        for (int i = 0; i < rawSongs.size(); i++) {
            RawSong song = rawSongs.get(i);

            int code = i < codes.size() ? codes.get(i) : i;
            String number = String.format(
                    java.util.Locale.US, "%05d", code);

            String artist = song.singerIndex < singers.size()
                    ? singers.get(song.singerIndex)
                    : "Unknown";

            String genre = genres.containsKey(song.genreId)
                    ? genres.get(song.genreId)
                    : "Unknown";

            result.add(new Entry(
                    number,
                    song.title,
                    artist,
                    genre,
                    song.offset,
                    song.length,
                    song.mode
            ));
        }

        return result;
    }

    private static String decode(
            byte[] bytes, int start, int end) {
        if (start < 0 || end < start || end > bytes.length) {
            return "";
        }

        return new String(bytes, start, end - start).trim();
    }
}
