
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
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("Composer: ").append(composer);
        }

        return text.toString();
    }

    public String textAtTick(long tick) {
        return styledTextAtTick(tick).toString();
    }

    /**
     * Displays the active line and the following line.
     * Kept for compatibility with existing callers.
     */
    public CharSequence styledTextAtTick(long tick) {
        if (events.isEmpty()) {
            return "No embedded lyrics found in this MIDI/KAR file.";
        }

        int active = activeEventIndex(tick);
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

        SpannableStringBuilder display =
                new SpannableStringBuilder();

        int activeStart = -1;
        int activeEnd = -1;

        for (int i = first; i <= displayLast; i++) {
            LyricEvent event = events.get(i);

            if (i > first) {
                LyricEvent previous = events.get(i - 1);

                if (event.line != previous.line) {
                    display.append('\n');
                } else if (needsWordSpace(previous, event)) {
                    display.append(' ');
                }
            }

            int start = display.length();
            display.append(event.text);
            int end = display.length();

            if (i == active) {
                activeStart = start;
                activeEnd = end;
            }
        }

        applyActiveHighlight(display, activeStart, activeEnd);
        return display;
    }

    /**
     * Displays every lyric event and highlights the currently active
     * timed lyric event in green.
     */
    public CharSequence styledFullTextAtTick(long tick) {
        return buildFullLyrics(tick).text;
    }

    /**
     * Returns the character offset where the active lyric line starts.
     * Used by Stage3PlayerActivity to scroll to the active line.
     */
    public int activeLineStartOffsetAtTick(long tick) {
        return buildFullLyrics(tick).activeLineStart;
    }

    private int activeEventIndex(long tick) {
        long safeTick = Math.max(0L, tick);
        int active = 0;

        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).tick <= safeTick) {
                active = i;
            } else {
                break;
            }
        }

        return active;
    }

    private FullLyricsResult buildFullLyrics(long tick) {
        SpannableStringBuilder display =
                new SpannableStringBuilder();

        if (events.isEmpty()) {
            display.append(
                    "No embedded lyrics found in this MIDI/KAR file.");
            return new FullLyricsResult(display, 0);
        }

        int active = activeEventIndex(tick);
        int activeLineStart = 0;
        int activeStart = -1;
        int activeEnd = -1;

        for (int i = 0; i < events.size(); i++) {
            LyricEvent event = events.get(i);

            if (i > 0) {
                LyricEvent previous = events.get(i - 1);

                if (event.line != previous.line) {
                    display.append('\n');
                } else if (needsWordSpace(previous, event)) {
                    display.append(' ');
                }
            }

            if (i == active) {
                activeStart = display.length();
                activeLineStart = findEventStartOffset(firstEventInLine(i));
            }

            display.append(event.text);

            if (i == active) {
                activeEnd = display.length();
            }
        }

        applyActiveHighlight(display, activeStart, activeEnd);

        return new FullLyricsResult(display, activeLineStart);
    }

    private int firstEventInLine(int index) {
        int line = events.get(index).line;

        while (index > 0 && events.get(index - 1).line == line) {
            index--;
        }

        return index;
    }

    private int findEventStartOffset(int targetIndex) {
        int offset = 0;

        for (int i = 0; i < targetIndex; i++) {
            LyricEvent current = events.get(i);
            LyricEvent next = events.get(i + 1);

            offset += current.text.length();

            if (next.line != current.line) {
                offset++;
            } else if (needsWordSpace(current, next)) {
                offset++;
            }
        }

        return offset;
    }

    private static void applyActiveHighlight(
            SpannableStringBuilder display,
            int start,
            int end) {

        if (start >= 0 && end > start) {
            display.setSpan(
                    new ForegroundColorSpan(ACTIVE_GREEN),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static boolean needsWordSpace(
            LyricEvent previous,
            LyricEvent current) {

        if (previous.text.isEmpty() || current.text.isEmpty()) {
            return false;
        }

        char last = previous.text.charAt(previous.text.length() - 1);
        char first = current.text.charAt(0);

        if (Character.isWhitespace(last)
                || Character.isWhitespace(first)) {
            return false;
        }

        if (last == '-' || last == '\'' || last == '’') {
            return false;
        }

        if (first == ',' || first == '.' || first == '!'
                || first == '?' || first == ';' || first == ':'
                || first == ')' || first == ']' || first == '’'
                || first == '\'') {
            return false;
        }

        long gap = current.tick - previous.tick;
        return gap >= current.wordGapThreshold;
    }

    public static Stage3MidiLyrics read(File file) {
        List<LyricEvent> result = new ArrayList<>();

        String singer = "";
        String composer = "";

        if (file == null || !file.isFile() || file.length() < 14) {
            return empty();
        }

        long ppq = 480L;

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            Reader reader = new Reader(data);

            if (!"MThd".equals(reader.ascii(4))) {
                return empty();
            }

            long headerLength = reader.u32();

            if (headerLength < 6
                    || headerLength > reader.remaining()) {
                return empty();
            }

            reader.u16();

            int trackCount = reader.u16();
            int division = reader.u16();

            if ((division & 0x8000) == 0) {
                ppq = division & 0x7FFF;
            }

            if (ppq <= 0) {
                ppq = 480L;
            }

            long wordGapThreshold = Math.max(120L, ppq / 2L);
            long phraseGapThreshold = Math.max(ppq * 2L, 960L);

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

                long tick = 0L;
                int runningStatus = 0;

                while (reader.position < end) {
                    tick += reader.vlq(end);

                    if (reader.position >= end) {
                        break;
                    }

                    int firstByte = reader.u8();
                    int status;
                    int firstData = -1;

                    if (firstByte < 0x80) {
                        if (runningStatus == 0) {
                            break;
                        }

                        status = runningStatus;
                        firstData = firstByte;
                    } else {
                        status = firstByte;
                    }

                    if (status == 0xFF) {
                        runningStatus = 0;

                        int metaType = reader.u8();
                        int length = reader.vlq(end);

                        if (length > end - reader.position) {
                            break;
                        }

                        byte[] raw = reader.bytes(length);
                        String value = decode(raw)
                                .replace("\u0000", "")
                                .trim();

                        if (metaType == 0x2F) {
                            break;
                        }

                        if (!value.isEmpty()) {
                            String lower =
                                    value.toLowerCase(Locale.ROOT);

                            if (metaType == 0x03) {
                                if (lower.startsWith("singer:")) {
                                    singer = removeLabel(value, "singer:");
                                } else if (lower.startsWith("composer:")) {
                                    composer = removeLabel(value, "composer:");
                                }
                            }

                            if (metaType == 0x01
                                    || metaType == 0x05
                                    || metaType == 0x06) {

                                MetadataResult metadata =
                                        parseMetadata(value);

                                if (metadata.isMetadata) {
                                    if (!metadata.singer.isEmpty()) {
                                        singer = metadata.singer;
                                    }

                                    if (!metadata.composer.isEmpty()) {
                                        composer = metadata.composer;
                                    }
                                } else if (!isNonLyricTag(value)) {
                                    boolean lineEnd =
                                            containsLineBreak(value);

                                    String lyric = cleanLyric(value);

                                    if (!lyric.isEmpty()) {
                                        result.add(new LyricEvent(
                                                tick,
                                                lyric,
                                                sequence++,
                                                lineEnd,
                                                wordGapThreshold,
                                                phraseGapThreshold));
                                    } else if (lineEnd && !result.isEmpty()) {
                                        result.get(result.size() - 1)
                                                .lineEnd = true;
                                    }
                                }
                            }

                            if (value.startsWith("@")) {
                                MetadataResult metadata =
                                        parseMetadata(value);

                                if (metadata.isMetadata) {
                                    if (!metadata.singer.isEmpty()) {
                                        singer = metadata.singer;
                                    }

                                    if (!metadata.composer.isEmpty()) {
                                        composer = metadata.composer;
                                    }
                                }
                            }
                        }

                    } else if (status == 0xF0 || status == 0xF7) {
                        runningStatus = 0;

                        int length = reader.vlq(end);

                        if (length > end - reader.position) {
                            break;
                        }

                        reader.skip(length);

                    } else if (status >= 0x80 && status <= 0xEF) {
                        runningStatus = status;

                        if (firstData < 0) {
                            reader.u8();
                        }

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
            // Retain any valid lyric events already parsed.
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

        for (int i = 0; i < result.size(); i++) {
            LyricEvent event = result.get(i);

            if (i > 0) {
                LyricEvent previous = result.get(i - 1);
                long gap = event.tick - previous.tick;

                boolean longPause =
                        gap >= event.phraseGapThreshold;

                boolean sentenceEnd = endsSentence(previous.text);

                if (previous.lineEnd || longPause || sentenceEnd) {
                    line++;
                }
            }

            event.line = line;
        }

        return new Stage3MidiLyrics(result, singer, composer);
    }

    private static boolean endsSentence(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        char last = text.charAt(text.length() - 1);

        return last == '.' || last == '!'
                || last == '?' || last == ';' || last == ':';
    }

    private static String cleanLyric(String value) {
        String result = value.trim();

        result = result.replace("/", "")
                .replace("\\", "")
                .replace("\r", "")
                .replace("\n", "");

        return result.trim();
    }

    private static boolean containsLineBreak(String value) {
        return value.contains("/")
                || value.contains("\\")
                || value.contains("\n")
                || value.contains("\r");
    }

    private static boolean isNonLyricTag(String value) {
        String lower = value.toLowerCase(Locale.ROOT);

        return lower.startsWith("singer:")
                || lower.startsWith("composer:")
                || lower.startsWith("artist:")
                || lower.startsWith("title:")
                || lower.startsWith("author:")
                || lower.startsWith("copyright:");
    }

    private static String removeLabel(String value, String label) {
        if (value.length() < label.length()) {
            return "";
        }

        return value.substring(label.length()).trim();
    }

    private static MetadataResult parseMetadata(String value) {
        String singer = "";
        String composer = "";
        boolean found = false;

        String cleaned = value.trim();

        if (cleaned.startsWith("@") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(2).trim();
        }

        String[] parts = cleaned.split("[\\r\\n;]+");

        for (String part : parts) {
            String item = part.trim();
            String lower = item.toLowerCase(Locale.ROOT);

            if (lower.startsWith("singer:")) {
                singer = removeLabel(item, "singer:");
                found = true;
            } else if (lower.startsWith("composer:")) {
                composer = removeLabel(item, "composer:");
                found = true;
            }
        }

        return new MetadataResult(found, singer, composer);
    }

    private static String decode(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);

        if (value.indexOf('\uFFFD') >= 0) {
            value = new String(
                    bytes, Charset.forName("ISO-8859-1"));
        }

        return value;
    }

    private static final class FullLyricsResult {
        final SpannableStringBuilder text;
        final int activeLineStart;

        FullLyricsResult(
                SpannableStringBuilder text,
                int activeLineStart) {
            this.text = text;
            this.activeLineStart = activeLineStart;
        }
    }

    private static final class MetadataResult {
        final boolean isMetadata;
        final String singer;
        final String composer;

        MetadataResult(
                boolean isMetadata,
                String singer,
                String composer) {
            this.isMetadata = isMetadata;
            this.singer = singer;
            this.composer = composer;
        }
    }

    private static final class LyricEvent {
        final long tick;
        final String text;
        final int sequence;
        final long wordGapThreshold;
        final long phraseGapThreshold;

        boolean lineEnd;
        int line;

        LyricEvent(
                long tick,
                String text,
                int sequence,
                boolean lineEnd,
                long wordGapThreshold,
                long phraseGapThreshold) {

            this.tick = tick;
            this.text = text;
            this.sequence = sequence;
            this.lineEnd = lineEnd;
            this.wordGapThreshold = wordGapThreshold;
            this.phraseGapThreshold = phraseGapThreshold;
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
                    data, position, length,
                    StandardCharsets.US_ASCII);

            position += length;
            return value;
        }

        byte[] bytes(int length) throws IOException {
            if (length < 0 || length > remaining()) {
                throw new IOException("Invalid MIDI event");
            }

            byte[] value = new byte[length];

            System.arraycopy(
                    data, position, value, 0, length);

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
                if (position >= end
                        || position >= data.length
                        || count++ >= 4) {
                    throw new IOException(
                            "Invalid MIDI event length");
                }

                next = data[position++] & 0xFF;
                value = (value << 7) | (next & 0x7F);

            } while ((next & 0x80) != 0);

            return value;
        }
    }
}
