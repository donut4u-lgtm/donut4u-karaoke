
package com.donut4u.karaoke;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

public final class Stage3MidiLyrics {

    private final TreeMap<Long, String> lyrics;

    private Stage3MidiLyrics(TreeMap<Long, String> lyrics) {
        this.lyrics = lyrics;
    }

    public static Stage3MidiLyrics empty() {
        return new Stage3MidiLyrics(new TreeMap<Long, String>());
    }

    public boolean hasLyrics() {
        return !lyrics.isEmpty();
    }

    public String textAtTick(long tick) {
        if (lyrics.isEmpty()) {
            return "No embedded lyrics found in this MIDI/KAR file.";
        }

        Map.Entry<Long, String> entry =
                lyrics.floorEntry(Math.max(0L, tick));

        if (entry == null) {
            entry = lyrics.firstEntry();
        }

        String text = entry.getValue();
        return text == null || text.trim().isEmpty() ? "♪" : text;
    }

    public static Stage3MidiLyrics read(File file) {
        TreeMap<Long, String> result = new TreeMap<>();

        if (file == null || !file.isFile() || file.length() < 14) {
            return empty();
        }

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            Reader r = new Reader(data);

            if (!r.ascii(4).equals("MThd")) {
                return empty();
            }

            long headerLength = r.u32();
            if (headerLength < 6 || headerLength > r.remaining()) {
                return empty();
            }

            r.u16(); // MIDI format
            int trackCount = r.u16();
            r.u16(); // ticks per quarter note
            r.skip((int) headerLength - 6);

            for (int track = 0;
                 track < trackCount && r.remaining() >= 8;
                 track++) {

                String chunk = r.ascii(4);
                long chunkLength = r.u32();

                if (chunkLength > r.remaining()) {
                    break;
                }

                int end = r.position + (int) chunkLength;

                if (!"MTrk".equals(chunk)) {
                    r.position = end;
                    continue;
                }

                long tick = 0;
                int runningStatus = 0;
                StringBuilder line = new StringBuilder();

                while (r.position < end) {
                    tick += r.vlq(end);
                    if (r.position >= end) break;

                    int first = r.u8();
                    int status;
                    int firstData = -1;

                    if (first < 0x80) {
                        if (runningStatus == 0) break;
                        status = runningStatus;
                        firstData = first;
                    } else {
                        status = first;
                    }

                    if (status == 0xFF) {
                        runningStatus = 0;
                        int metaType = r.u8();
                        int length = r.vlq(end);

                        if (length > end - r.position) break;

                        if (metaType == 0x01 ||
                            metaType == 0x05 ||
                            metaType == 0x06) {

                            String value = decode(r.bytes(length))
                                    .replace("\u0000", "")
                                    .trim();

                            if (!value.isEmpty()
                                    && !value.startsWith("@")) {

                                boolean lineEnd =
                                        value.contains("/") ||
                                        value.contains("\\");

                                value = value.replace("/", "")
                                        .replace("\\", "")
                                        .trim();

                                if (!value.isEmpty()) {
                                    line.append(value);
                                }

                                if (line.length() > 0) {
                                    result.put(tick, line.toString().trim());
                                }

                                if (lineEnd) {
                                    line.setLength(0);
                                }
                            }
                        } else {
                            r.skip(length);
                        }

                        if (metaType == 0x2F) break;

                    } else if (status == 0xF0 || status == 0xF7) {
                        runningStatus = 0;
                        int length = r.vlq(end);

                        if (length > end - r.position) break;
                        r.skip(length);

                    } else if (status >= 0x80 && status <= 0xEF) {
                        runningStatus = status;

                        if (firstData < 0) {
                            r.u8();
                        }

                        int command = status & 0xF0;
                        if (command != 0xC0 && command != 0xD0) {
                            r.u8();
                        }
                    } else {
                        runningStatus = 0;
                    }
                }

                r.position = end;
            }

        } catch (Exception ignored) {
            // Return any valid lyric events found before a parsing error.
        }

        return new Stage3MidiLyrics(result);
    }

    private static String decode(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);

        if (value.indexOf('\uFFFD') >= 0) {
            value = new String(bytes, Charset.forName("ISO-8859-1"));
        }

        return value;
    }

    private static final class Reader {
        final byte[] data;
        int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - position;
        }

        int u8() throws IOException {
            if (position >= data.length) {
                throw new IOException("Unexpected end of MIDI file");
            }
            return data[position++] & 0xFF;
        }

        int u16() throws IOException {
            return (u8() << 8) | u8();
        }

        long u32() throws IOException {
            return ((long) u8() << 24)
                    | ((long) u8() << 16)
                    | ((long) u8() << 8)
                    | u8();
        }

        String ascii(int length) throws IOException {
            if (length < 0 || length > remaining()) {
                throw new IOException("Invalid MIDI chunk");
            }

            String value = new String(
                    data, position, length, StandardCharsets.US_ASCII);

            position += length;
            return value;
        }

        byte[] bytes(int length) throws IOException {
            if (length < 0 || length > remaining()) {
                throw new IOException("Invalid MIDI event");
            }

            byte[] value = new byte[length];
            System.arraycopy(data, position, value, 0, length);
            position += length;
            return value;
        }

        void skip(int length) throws IOException {
            if (length < 0 || length > remaining()) {
                throw new IOException("Invalid MIDI length");
            }
            position += length;
        }

        int vlq(int end) throws IOException {
            int value = 0;
            int count = 0;
            int next;

            do {
                if (position >= end || position >= data.length
                        || count++ >= 4) {
                    throw new IOException("Invalid MIDI event length");
                }

                next = data[position++] & 0xFF;
                value = (value << 7) | (next & 0x7F);

            } while ((next & 0x80) != 0);

            return value;
        }
    }
}
