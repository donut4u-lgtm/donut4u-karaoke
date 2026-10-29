
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

    /*
     * Instrumental-gap detection is a timing heuristic.
     * MIDI lyric events alone cannot prove that no singer is singing.
     */
    private final List<LyricEvent> events;
    private final String singer;
    private final String composer;
    private final int ticksPerQuarterNote;

    private Stage3MidiLyrics(
            List<LyricEvent> events,
            String singer,
            String composer,
            int ticksPerQuarterNote) {
        this.events = events;
        this.singer = singer;
        this.composer = composer;
        this.ticksPerQuarterNote =
                ticksPerQuarterNote > 0 ? ticksPerQuarterNote : 480;
    }

    public static Stage3MidiLyrics empty() {
        return new Stage3MidiLyrics(
                new ArrayList<LyricEvent>(), "", "", 480);
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
        return buildFullLyrics(tick).text;
    }

    public CharSequence styledFullTextAtTick(long tick) {
        return buildFullLyrics(tick).text;
    }

    public int activeLineStartOffsetAtTick(long tick) {
        return buildFullLyrics(tick).activeLineStart;
    }

    public int activeEventIndexAtTick(long tick) {
        return findActiveEventIndex(tick);
    }

    public int activeLineNumberAtTick(long tick) {
        int index = findActiveEventIndex(tick);
        return index < 0 ? -1 : events.get(index).line;
    }

    /*
     * Returns true during an intro or a likely instrumental gap.
     *
     * Intro: the first lyric starts after tick zero.
     * Interlude: two lyric events are separated by at least three
     * quarter notes, and playback is inside the middle of that gap.
     *
     * This is deliberately conservative. The MIDI lyric track does
     * not contain enough information to prove when vocals stop.
     */
    public boolean shouldShowDotsAtTick(long tick) {
        if (events.isEmpty()) return false;

        long safeTick = Math.max(0L, tick);
        long beat = Math.max(1L, ticksPerQuarterNote);

        if (safeTick < events.get(0).tick) {
            return events.get(0).tick > 0L;
        }

        int active = findActiveEventIndex(safeTick);

        if (active < 0 || active >= events.size() - 1) {
            return false;
        }

        LyricEvent current = events.get(active);
        LyricEvent next = events.get(active + 1);
        long gap = next.tick - current.tick;

        if (gap < beat * 3L) {
            return false;
        }

        return safeTick >= current.tick + beat
                && safeTick < next.tick - beat;
    }

    private int findActiveEventIndex(long tick) {
        int low = 0;
        int high = events.size() - 1;
        int result = -1;
        long safeTick = Math.max(0L, tick);

        while (low <= high) {
            int mid = low + (high - low) / 2;

            if (events.get(mid).tick <= safeTick) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return result;
    }

    private FullLyricsResult buildFullLyrics(long tick) {
        SpannableStringBuilder display =
                new SpannableStringBuilder();

        if (events.isEmpty()) {
            display.append(
                    "No embedded lyrics found in this MIDI/KAR file.");
            return new FullLyricsResult(display, 0);
        }

        int active = findActiveEventIndex(tick);
        int activeLineStart = 0;
        int activeStart = -1;
        int activeEnd = -1;
        int currentLine = -1;

        for (int i = 0; i < events.size(); i++) {
            LyricEvent event = events.get(i);

            if (i == 0 || event.line != currentLine) {
                if (i > 0) {
                    display.append('\n');
                }
                currentLine = event.line;
            }

            if (i == active) {
                activeStart = display.length();
                activeLineStart = findEventStartOffset(i);
            }

            display.append(event.text);

            if (i == active) {
                activeEnd = display.length();
            }
        }

        /*
         * If a word is split across lyric events, extend the green
         * highlight across adjoining pieces of that same word.
         * Example: "exa" followed by "mple" becomes "example".
         *
         * We only join pieces on the same lyric line when no
         * whitespace separates them.
         */
        if (active >= 0) {
            int first = active;
            int last = active;

            while (first > 0
                    && isSameWord(events.get(first - 1), events.get(first))) {
                first--;
            }

            while (last + 1 < events.size()
                    && isSameWord(events.get(last), events.get(last + 1))) {
                last++;
            }

            activeStart = findEventStartOffset(first);
            activeEnd = findEventEndOffset(last);
        }

        applyActiveHighlight(display, activeStart, activeEnd);

        return new FullLyricsResult(display, activeLineStart);
    }

    private static boolean isSameWord(
            LyricEvent left,
            LyricEvent right) {
        if (left.line != right.line) return false;
        if (left.text.isEmpty() || right.text.isEmpty()) return false;

        char leftChar = left.text.charAt(left.text.length() - 1);
        char rightChar = right.text.charAt(0);

        return !Character.isWhitespace(leftChar)
                && !Character.isWhitespace(rightChar);
    }

    private int findEventStartOffset(int targetIndex) {
        int offset = 0;
        int previousLine = -1;

        for (int i = 0; i < targetIndex; i++) {
            LyricEvent event = events.get(i);

            if (i > 0 && event.line != previousLine) {
                offset++;
            }

            offset += event.text.length();
            previousLine = event.line;
        }

        if (targetIndex > 0
                && events.get(targetIndex).line
                != events.get(targetIndex - 1).line) {
            offset++;
        }

        return offset;
    }

    private int findEventEndOffset(int targetIndex) {
        return findEventStartOffset(targetIndex)
                + events.get(targetIndex).text.length();
    }

    private static void applyActiveHighlight(
            SpannableStringBuilder display,
            int start,
            int end) {
        if (start >= 0 && end > start && end <= display.length()) {
            display.setSpan(
                    new ForegroundColorSpan(ACTIVE_GREEN),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static Stage3MidiLyrics read(File file) {
        List<LyricEvent> result = new ArrayList<>();

        String singer = "";
        String composer = "";
        int ticksPerQuarterNote = 480;

        if (file == null || !file.isFile() || file.length() < 14) {
            return empty();
        }

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            Reader reader = new Reader(data);

            if (!"MThd".equals(reader.ascii(4))) return empty();

            long headerLength = reader.u32();

            if (headerLength < 6
                    || headerLength > reader.remaining()) {
                return empty();
            }

            reader.u16(); // MIDI format
            int trackCount = reader.u16();
            int division = reader.u16();

            /*
             * A positive division value is ticks per quarter note.
             * A negative high byte represents SMPTE timing instead;
             * retain the fallback in that case.
             */
            if ((division & 0x8000) == 0 && division > 0) {
                ticksPerQuarterNote = division;
            }

            reader.skip((int) headerLength - 6);

            int sequence = 0;

            for (int track = 0;
                 track < trackCount && reader.remaining() >= 8;
                 track++) {

                String chunk = reader.ascii(4);
                long chunkLength = reader.u32();

                if (chunkLength > reader.remaining()) break;

                int end = reader.position + (int) chunkLength;

                if (!"MTrk".equals(chunk)) {
                    reader.position = end;
                    continue;
                }

                long tick = 0L;
                int runningStatus = 0;

                while (reader.position < end) {
                    tick += reader.vlq(end);

                    if (reader.position >= end) break;

                    int firstByte = reader.u8();
                    int status;
                    int firstData = -1;

                    if (firstByte < 0x80) {
                        if (runningStatus == 0) break;
                        status = runningStatus;
                        firstData = firstByte;
                    } else {
                        status = firstByte;
                    }

                    if (status == 0xFF) {
                        runningStatus = 0;

                        int metaType = reader.u8();
                        int length = reader.vlq(end);

                        if (length > end - reader.position) break;

                        byte[] raw = reader.bytes(length);

                        if (metaType == 0x2F) break;

                        String value = decode(raw)
                                .replace("\u0000", "");

                        String trimmed = value.trim();

                        if (trimmed.isEmpty()) continue;

                        String lower =
                                trimmed.toLowerCase(Locale.ROOT);

                        if (metaType == 0x03) {
                            if (lower.startsWith("singer:")) {
                                singer = removeLabel(trimmed, "singer:");
                            } else if (lower.startsWith("composer:")) {
                                composer = removeLabel(trimmed, "composer:");
                            }
                        }

                        if (metaType != 0x01
                                && metaType != 0x05
                                && metaType != 0x06) {
                            continue;
                        }

                        if (trimmed.startsWith("@")) {
                            MetadataResult metadata =
                                    parseMetadata(trimmed);

                            if (!metadata.singer.isEmpty()) {
                                singer = metadata.singer;
                            }
                            if (!metadata.composer.isEmpty()) {
                                composer = metadata.composer;
                            }
                            continue;
                        }

                        MetadataResult metadata = parseMetadata(trimmed);

                        if (metadata.isMetadata) {
                            if (!metadata.singer.isEmpty()) {
                                singer = metadata.singer;
                            }
                            if (!metadata.composer.isEmpty()) {
                                composer = metadata.composer;
                            }
                            continue;
                        }

                        if (isNonLyricTag(trimmed)) continue;

                        boolean lineEnd = containsLineBreak(value);
                        String lyric = cleanLyric(value);

                        if (!lyric.trim().isEmpty()
                                && !isControlToken(lyric)) {
                            result.add(new LyricEvent(
                                    tick,
                                    lyric,
                                    sequence++,
                                    lineEnd));
                        } else if (lineEnd && !result.isEmpty()) {
                            result.get(result.size() - 1).lineEnd = true;
                        }

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
            // Preserve any valid lyric events parsed before a malformed chunk.
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

            if (i > 0 && result.get(i - 1).lineEnd) {
                line++;
            }

            event.line = line;
        }

        return new Stage3MidiLyrics(
                result, singer, composer, ticksPerQuarterNote);
    }

    private static boolean isControlToken(String text) {
        if (text == null) return true;

        String value = text.trim();

        if (value.isEmpty()) return true;

        String noWhitespace = value.replaceAll("\\s+", "");

        if (noWhitespace.matches("(?i)0{2,}")) {
            return true;
        }

        if (noWhitespace.matches("(?i)0x0{2,}")) {
            return true;
        }

        return value.matches("[\\p{Cntrl}\\s]+");
    }

    private static String cleanLyric(String value) {
        return value.replace("/", "")
                .replace("\\", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\u0000", "");
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
        if (value.length() < label.length()) return "";
        return value.substring(label.length()).trim();
    }

    private static MetadataResult parseMetadata(String value) {
        String singer = "";
        String composer = "";
        boolean found = false;

        String cleaned = value.trim();
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

        boolean lineEnd;
        int line;

        LyricEvent(
                long tick,
                String text,
                int sequence,
                boolean lineEnd) {
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
                if (position >= end
                        || position >= data.length
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
