
package com.donut4u.karaoke;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class Stage3MidiLyrics {

    private static final int ACTIVE_GREEN = Color.rgb(0, 255, 100);

    private final List<LyricEvent> events;
    private final String singer;
    private final String composer;

    private Stage3MidiLyrics(
            List<LyricEvent> events,
            String singer,
            String composer) {
        this.events = events;
        this.singer = singer;
        this.composer = composer;
    }

    public static Stage3MidiLyrics empty() {
        return new Stage3MidiLyrics(
                new ArrayList<LyricEvent>(), "", "");
    }

    public boolean hasLyrics() {
        return !events.isEmpty();
    }

    public String getSinger() {
        return singer;
    }

    public String getComposer() {
        return composer;
    }

    public String getMetadataText() {
        StringBuilder text = new StringBuilder();

        if (!singer.isEmpty()) {
            text.append("Singer: ").append(singer);
        }

        if (!composer.isEmpty()) {
            if (text.length() > 0) text.append('\n');
            text.append("Composer: ").append(composer);
        }

        return text.toString();
    }

    public String textAtTick(long tick) {
        return styledTextAtTick(tick).toString();
    }

    public CharSequence styledTextAtTick(long tick) {
        if (events.isEmpty()) {
            return "No embedded lyrics found in this MIDI/KAR file.";
        }

        int active = 0;
        long safeTick = Math.max(0L, tick);

        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).tick <= safeTick) {
                active = i;
            } else {
                break;
            }
        }

        int activeLine = events.get(active).line;
        int first = active;
        int last = active;

        while (first > 0
                && events.get(first - 1).line == activeLine) {
            first--;
        }

        while (last + 1 < events.size()
                && events.get(last + 1).line == activeLine) {
            last++;
        }

        int displayLast = last;

        if (last + 1 < events.size()) {
            int nextLine = events.get(last + 1).line;

            while (displayLast + 1 < events.size()
                    && events.get(displayLast + 1).line == nextLine) {
                displayLast++;
            }
        }

        SpannableStringBuilder display = new SpannableStringBuilder();
        int activeStart = -1;
        int activeEnd = -1;

        for (int i = first; i <= displayLast; i++) {
            LyricEvent event = events.get(i);

            if (i > first
                    && event.line != events.get(i - 1).line) {
                display.append('\n');
            }

            int start = display.length();
            display.append(event.text);
            int end = display.length();

            if (i == active) {
                activeStart = start;
                activeEnd = end;
            }
        }

        if (activeStart >= 0 && activeEnd > activeStart) {
            display.setSpan(
                    new ForegroundColorSpan(ACTIVE_GREEN),
                    activeStart,
                    activeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return display;
    }

    public static Stage3MidiLyrics read(File file) {
        List<LyricEvent> result = new ArrayList<>();
        String singer = "";
        String composer = "";

        if (file == null || !file.isFile() || file.length() < 14) {
            return empty();
        }

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            Reader reader = new Reader(data);

            if (!"MThd".equals(reader.ascii(4))) {
                return empty();
            }

            long headerLength = reader.u32();

            if (headerLength < 6 || headerLength > reader.remaining()) {
                return empty();
            }

            reader.u16();
            int trackCount = reader.u16();
            reader.u16();
            reader.skip((int) headerLength - 6);

            int sequence = 0;

            for (int track = 0;
                 track < trackCount && reader.remaining() >= 8;
                 track++) {

                String chunk = reader.ascii(4);
                long chunkLength = reader.u32();

                if (chunkLength > reader.remaining()) {
                    break;
                }

                int end = reader.position + (int) chunkLength;

                if (!"MTrk".equals(chunk)) {
                    reader.position = end;
                    continue;
                }

                long tick = 0;
                int runningStatus = 0;

                while (reader.position < end) {
                    tick += reader.vlq(end);

                    if (reader.position >= end) break;

                    int first = reader.u8();
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

                        int metaType = reader.u8();
                        int length = reader.vlq(end);

                        if (length > end - reader.position) break;

                        String value = decode(reader.bytes(length))
                                .replace("\u0000", "")
                                .trim();

                        if (metaType == 0x03 && !value.isEmpty()) {
                            String lower = value.toLowerCase(Locale.ROOT);

                            if (lower.startsWith("singer:")) {
                                singer = value.substring(7).trim();
                            } else if (lower.startsWith("composer:")) {
                                composer = value.substring(9).trim();
                            }

                        } else if (metaType == 0x01
                                || metaType == 0x05
                                || metaType == 0x06) {

                            if (!value.isEmpty() && !value.startsWith("@")) {
                                String lower = value.toLowerCase(Locale.ROOT);

                                if (lower.startsWith("singer:")) {
                                    singer = value.substring(7).trim();
                                } else if (lower.startsWith("composer:")) {
                                    composer = value.substring(9).trim();
                                } else {
                                    boolean lineEnd =
                                            value.contains("/")
                                            || value.contains("\\");

                                    value = value.replace("/", "")
                                            .replace("\\", "")
                                            .trim();

                                    if (!value.isEmpty()) {
                                        result.add(new LyricEvent(
                                                tick, value, sequence++, lineEnd));
                                    } else if (lineEnd && !result.isEmpty()) {
                                        result.get(result.size() - 1).lineEnd = true;
                                    }
                                }
                            }
                        }

                        if (metaType == 0x2F) break;

                    } else if (status == 0xF0 || status == 0xF7) {
                        runningStatus = 0;
                        int length = reader.vlq(end);

                        if (length > end - reader.position) break;
                        reader.skip(length);

                    } else if (status >= 0x80 && status <= 0xEF) {
                        runningStatus = status;

                        if (firstData < 0) reader.u8();

                        int command = status & 0xF0;

                        if (command != 0xC0 && command != 0xD0) {
                            reader.u8();
                        }
                    } else {
                        runningStatus = 0;
                    }
                }

                reader.position = end;
            }

        } catch (Exception ignored) {
            // Retain events already read.
        }

        Collections.sort(result, new Comparator<LyricEvent>() {
            @Override
            public int compare(LyricEvent a, LyricEvent b) {
                int byTick = Long.compare(a.tick, b.tick);
                return byTick != 0
                        ? byTick
                        : Integer.compare(a.sequence, b.sequence);
            }
        });

        int line = 0;

        for (LyricEvent event : result) {
            event.line = line;
            if (event.lineEnd) line++;
        }

        return new Stage3MidiLyrics(result, singer, composer);
    }

    private static String decode(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);

        if (value.indexOf('\uFFFD') >= 0) {
            value = new String(bytes, Charset.forName("ISO-8859-1"));
        }

        return value;
    }

    private static final class LyricEvent {
        final long tick;
        final String text;
        final int sequence;
        boolean lineEnd;
        int line;

        LyricEvent(long tick, String text, int sequence, boolean lineEnd) {
            this.tick = tick;
            this.text = text;
            this.sequence = sequence;
            this.lineEnd = lineEnd;
        }
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
                if (position >= end || position >= data.length || count++ >= 4) {
                    throw new IOException("Invalid MIDI event length");
                }

                next = data[position++] & 0xFF;
                value = (value << 7) | (next & 0x7F);
            } while ((next & 0x80) != 0);

            return value;
        }
    }
}
