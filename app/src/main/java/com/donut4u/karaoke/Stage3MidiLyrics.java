
package com.donut4u.karaoke;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class Stage3MidiLyrics {

    private static final int ACTIVE_GREEN = Color.rgb(0, 255, 100);
    private static final int SECTION_GOLD = Color.rgb(255, 205, 90);
    private static final long DEFAULT_TEMPO_MICROS = 500000L;

    private final List<LyricEvent> events;
    private final List<TempoEvent> tempoEvents;
    private final String singer;
    private final String composer;
    private final int ticksPerQuarterNote;

    private Stage3MidiLyrics(
            List<LyricEvent> events,
            String singer,
            String composer,
            int ticksPerQuarterNote,
            List<TempoEvent> tempoEvents) {

        this.events = events;
        this.singer = singer == null ? "" : singer;
        this.composer = composer == null ? "" : composer;
        this.ticksPerQuarterNote =
                ticksPerQuarterNote > 0 ? ticksPerQuarterNote : 480;

        this.tempoEvents = new ArrayList<>();
        if (tempoEvents != null) {
            this.tempoEvents.addAll(tempoEvents);
        }

        if (this.tempoEvents.isEmpty()) {
            this.tempoEvents.add(
                    new TempoEvent(0L, DEFAULT_TEMPO_MICROS));
        }

        Collections.sort(this.tempoEvents, new Comparator<TempoEvent>() {
            @Override
            public int compare(TempoEvent a, TempoEvent b) {
                return Long.compare(a.tick, b.tick);
            }
        });
    }

    public static Stage3MidiLyrics empty() {
        List<TempoEvent> tempos = new ArrayList<>();
        tempos.add(new TempoEvent(0L, DEFAULT_TEMPO_MICROS));

        return new Stage3MidiLyrics(
                new ArrayList<LyricEvent>(),
                "", "", 480, tempos);
    }

    public boolean hasLyrics() {
        for (LyricEvent event : events) {
            if (!event.section && !event.text.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public String getSinger() {
        return singer;
    }

    public String getComposer() {
        return composer;
    }

    public int getTicksPerQuarterNote() {
        return ticksPerQuarterNote;
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

    public long tickToMillis(long targetTick) {
        long target = Math.max(0L, targetTick);
        long previousTick = 0L;
        long tempo = DEFAULT_TEMPO_MICROS;
        double micros = 0.0;

        for (TempoEvent event : tempoEvents) {
            if (event.tick <= 0L) {
                tempo = event.microsecondsPerQuarter;
                continue;
            }

            if (event.tick >= target) break;

            long span = event.tick - previousTick;
            micros += ((double) span * tempo)
                    / ticksPerQuarterNote;

            previousTick = event.tick;
            tempo = event.microsecondsPerQuarter;
        }

        micros += ((double) (target - previousTick) * tempo)
                / ticksPerQuarterNote;

        return Math.max(0L, (long) (micros / 1000.0));
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

    public String upcomingLineAtTick(long tick) {
        int active = findActiveEventIndex(tick);

        int currentLine = active >= 0
                ? events.get(active).line : Integer.MIN_VALUE;

        StringBuilder next = new StringBuilder();
        int nextLine = Integer.MIN_VALUE;

        for (int i = Math.max(0, active + 1); i < events.size(); i++) {
            LyricEvent event = events.get(i);

            if (event.section) {
                if (next.length() > 0) break;
                continue;
            }

            if (event.line == currentLine) continue;

            if (nextLine == Integer.MIN_VALUE) {
                nextLine = event.line;
            } else if (event.line != nextLine) {
                break;
            }

            appendLyricFragment(next, event.text);
        }

        return next.toString().trim();
    }

    public long nextLyricTickAfter(long tick) {
        long safeTick = Math.max(0L, tick);

        for (LyricEvent event : events) {
            if (!event.section && event.tick > safeTick) {
                return event.tick;
            }
        }

        return -1L;
    }

    public int getCountdownNumberAtTick(long tick) {
        if (events.isEmpty()) return 0;

        long safeTick = Math.max(0L, tick);
        long beat = Math.max(1L, ticksPerQuarterNote);
        int targetLyricIndex = -1;

        int firstLyric = firstLyricIndex();
        if (firstLyric < 0) return 0;

        LyricEvent first = events.get(firstLyric);

        if (safeTick < first.tick) {
            targetLyricIndex = firstLyric;
        } else {
            int sectionIndex = mostRecentSectionIndex(safeTick);
            if (sectionIndex < 0) return 0;

            LyricEvent section = events.get(sectionIndex);

            if (!isInstrumentalHeading(section.text)) return 0;

            targetLyricIndex = nextLyricIndex(sectionIndex + 1);
        }

        if (targetLyricIndex < 0) return 0;

        long targetTick = events.get(targetLyricIndex).tick;

        if (targetTick <= safeTick) return 0;

        long remainingTicks = targetTick - safeTick;
        long beatsRemaining = remainingTicks / beat;

        if (remainingTicks % beat != 0L) {
            beatsRemaining++;
        }

        if (beatsRemaining < 1L || beatsRemaining > 5L) {
            return 0;
        }

        return (int) beatsRemaining;
    }

    public boolean shouldShowDotsAtTick(long tick) {
        return getCountdownNumberAtTick(tick) > 0;
    }

    private int mostRecentSectionIndex(long tick) {
        for (int i = events.size() - 1; i >= 0; i--) {
            LyricEvent event = events.get(i);
            if (event.tick <= tick && event.section) return i;
        }
        return -1;
    }

    private static boolean isInstrumentalHeading(String heading) {
        if (heading == null) return false;

        String value = heading.trim().toUpperCase(Locale.ROOT);

        return value.contains("INSTRUMENTAL")
                || value.equals("INTRO")
                || value.equals("INTERLUDE")
                || value.equals("OUTRO")
                || value.equals("SOLO")
                || value.equals("BREAK")
                || value.equals("ENDING")
                || value.equals("CODA");
    }

    private int firstLyricIndex() {
        for (int i = 0; i < events.size(); i++) {
            if (!events.get(i).section) return i;
        }
        return -1;
    }

    private int nextLyricIndex(int start) {
        for (int i = Math.max(0, start); i < events.size(); i++) {
            if (!events.get(i).section) return i;
        }
        return -1;
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

        int[] starts = new int[events.size()];
        int[] ends = new int[events.size()];

        for (int i = 0; i < events.size(); i++) {
            starts[i] = -1;
            ends[i] = -1;
        }

        int currentLine = Integer.MIN_VALUE;
        int previousLyricEvent = -1;

        for (int i = 0; i < events.size(); i++) {
            LyricEvent event = events.get(i);

            if (event.section) {
                if (display.length() > 0
                        && display.charAt(display.length() - 1) != '\n') {
                    display.append("\n\n");
                }

                int start = display.length();
                display.append(event.text.trim());
                int end = display.length();

                starts[i] = start;
                ends[i] = end;

                if (end > start) {
                    display.setSpan(
                            new ForegroundColorSpan(SECTION_GOLD),
                            start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                    display.setSpan(
                            new StyleSpan(Typeface.BOLD),
                            start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }

                display.append('\n');
                currentLine = Integer.MIN_VALUE;
                previousLyricEvent = -1;
                continue;
            }

            if (currentLine != event.line) {
                if (display.length() > 0
                        && display.charAt(display.length() - 1) != '\n') {
                    display.append('\n');
                }

                currentLine = event.line;
                previousLyricEvent = -1;
            }

            if (previousLyricEvent >= 0) {
                LyricEvent previousEvent =
                        events.get(previousLyricEvent);

                String previous = previousEvent.text;

                long eventGap = Math.max(
                        0L, event.tick - previousEvent.tick);

                long wordGap = Math.max(
                        1L, ticksPerQuarterNote / 3L);

                if (needsWordSpace(previous, event.text)
                        && eventGap >= wordGap) {
                    display.append(' ');
                }
            }

            int start = display.length();
            display.append(event.text);
            int end = display.length();

            starts[i] = start;
            ends[i] = end;

            if (i == active) activeLineStart = start;

            previousLyricEvent = i;
        }

        if (active >= 0 && active < events.size()
                && !events.get(active).section) {

            int first = active;
            int last = active;

            while (first > 0) {
                LyricEvent previous = events.get(first - 1);
                LyricEvent current = events.get(first);

                if (previous.section || current.section
                        || previous.line != current.line
                        || !isSameWord(previous, current)) {
                    break;
                }

                first--;
            }

            while (last + 1 < events.size()) {
                LyricEvent current = events.get(last);
                LyricEvent next = events.get(last + 1);

                if (current.section || next.section
                        || current.line != next.line
                        || !isSameWord(current, next)) {
                    break;
                }

                last++;
            }

            if (starts[first] >= 0
                    && ends[last] >= starts[first]) {
                activeStart = starts[first];
                activeEnd = ends[last];
            }
        }

        applyActiveHighlight(display, activeStart, activeEnd);

        return new FullLyricsResult(display, activeLineStart);
    }

    private static boolean needsWordSpace(
            String previousText,
            String nextText) {

        if (previousText == null || nextText == null
                || previousText.isEmpty() || nextText.isEmpty()) {
            return false;
        }

        char previous =
                previousText.charAt(previousText.length() - 1);
        char next = nextText.charAt(0);

        return !Character.isWhitespace(previous)
                && !Character.isWhitespace(next);
    }

    private static void appendLyricFragment(
            StringBuilder target,
            String fragment) {

        if (target == null || fragment == null || fragment.isEmpty()) {
            return;
        }

        target.append(fragment);
    }

    private static boolean isSameWord(
            LyricEvent left,
            LyricEvent right) {

        if (left.line != right.line
                || left.text.isEmpty()
                || right.text.isEmpty()) {
            return false;
        }

        char leftChar =
                left.text.charAt(left.text.length() - 1);
        char rightChar = right.text.charAt(0);

        return !Character.isWhitespace(leftChar)
                && !Character.isWhitespace(rightChar);
    }

    private static void applyActiveHighlight(
            SpannableStringBuilder display,
            int start,
            int end) {

        if (start >= 0 && end > start
                && end <= display.length()) {
            display.setSpan(
                    new ForegroundColorSpan(ACTIVE_GREEN),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static Stage3MidiLyrics read(File file) {
        List<LyricEvent> result = new ArrayList<>();
        List<TempoEvent> tempos = new ArrayList<>();

        tempos.add(new TempoEvent(0L, DEFAULT_TEMPO_MICROS));

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

            reader.u16();
            int trackCount = reader.u16();
            int division = reader.u16();

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

                        if (metaType == 0x51 && raw.length == 3) {
                            long tempo =
                                    ((long) (raw[0] & 0xFF) << 16)
                                    | ((long) (raw[1] & 0xFF) << 8)
                                    | (long) (raw[2] & 0xFF);

                            if (tempo > 0L) {
                                tempos.add(
                                        new TempoEvent(tick, tempo));
                            }
                            continue;
                        }

                        String value =
                                decode(raw).replace("\u0000", "");
                        String trimmed = value.trim();

                        if (trimmed.isEmpty()) continue;

                        String lower =
                                trimmed.toLowerCase(Locale.ROOT);

                        if (metaType == 0x03) {
                            if (lower.startsWith("singer:")) {
                                singer = removeLabel(
                                        trimmed, "singer:");
                            } else if (lower.startsWith("composer:")) {
                                composer = removeLabel(
                                        trimmed, "composer:");
                            }
                        }

                        if (metaType == 0x06) {
                            String heading = extractHeading(trimmed);

                            if (heading != null) {
                                result.add(new LyricEvent(
                                        tick, heading,
                                        sequence++, false, true));
                            }
                            continue;
                        }

                        if (metaType != 0x01 && metaType != 0x05) {
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

                        MetadataResult metadata =
                                parseMetadata(trimmed);

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

                        String heading = extractHeading(trimmed);

                        if (heading != null) {
                            result.add(new LyricEvent(
                                    tick, heading,
                                    sequence++, false, true));
                            continue;
                        }

                        boolean lineEnd = containsLineBreak(value);
                        String lyric = cleanLyric(value);

                        if (!lyric.trim().isEmpty()
                                && !isControlToken(lyric)) {
                            result.add(new LyricEvent(
                                    tick, lyric,
                                    sequence++, lineEnd, false));
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
            // Preserve events parsed before malformed data.
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

            if (i > 0 && result.get(i - 1).lineEnd) line++;
            if (event.section) line++;

            event.line = line;
        }

        return new Stage3MidiLyrics(
                result, singer, composer,
                ticksPerQuarterNote, tempos);
    }

    private static String extractHeading(String value) {
        if (value == null) return null;

        String candidate = value.trim();

        if ((candidate.startsWith("[")
                && candidate.endsWith("]"))
                || (candidate.startsWith("<")
                && candidate.endsWith(">"))
                || (candidate.startsWith("*")
                && candidate.endsWith("*"))) {

            if (candidate.length() >= 2) {
                candidate = candidate.substring(
                        1, candidate.length() - 1).trim();
            }
        }

        if (!isSectionHeading(candidate)) return null;

        return normalizeHeading(candidate);
    }

    private static boolean isSectionHeading(String value) {
        if (value == null) return false;

        String normalized =
                value.trim().toLowerCase(Locale.ROOT);

        normalized = normalized.replaceAll("[_\\-]+", " ");
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replaceAll("[.!:]+$", "").trim();

        return normalized.matches(
                "^(intro|instrumental|interlude|outro|"
                + "verse\\s*[0-9ivx]*|"
                + "chorus\\s*[0-9ivx]*|"
                + "last\\s+chorus|final\\s+chorus|"
                + "bridge|refrain|solo|break|ending|"
                + "pre\\s+chorus|post\\s+chorus|"
                + "repeat|coda)(\\s*[:.]?)$");
    }

    private static String normalizeHeading(String value) {
        return value.trim()
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("[.!:]+$", "")
                .toUpperCase(Locale.ROOT);
    }

    private static boolean isControlToken(String text) {
        if (text == null) return true;

        String value = text.trim();

        if (value.isEmpty()) return true;

        String noWhitespace = value.replaceAll("\\s+", "");

        if (noWhitespace.matches("(?i)0{2,}")) return true;
        if (noWhitespace.matches("(?i)0x0{2,}")) return true;

        return value.matches("[\\p{Cntrl}\\s]+");
    }

    private static String cleanLyric(String value) {
        if (value == null) return "";

        String cleaned = value
                .replace("\r", "")
                .replace("\n", "")
                .replace("\u0000", "");

        while (!cleaned.isEmpty()) {
            char first = cleaned.charAt(0);

            if (first == '/' || first == '\\') {
                cleaned = cleaned.substring(1);
            } else {
                break;
            }
        }

        return cleaned;
    }

    private static boolean containsLineBreak(String value) {
        if (value == null || value.isEmpty()) return false;

        char first = value.charAt(0);

        return first == '/'
                || first == '\\'
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
    }

    private static boolean isNonLyricTag(String value) {
        if (value == null) return true;

        String lower = value.trim().toLowerCase(Locale.ROOT);

        return lower.startsWith("@kmidi")
                || lower.startsWith("@v")
                || lower.startsWith("@i")
                || lower.startsWith("@l")
                || lower.startsWith("@t")
                || lower.startsWith("@k")
                || lower.startsWith("singer:")
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

        if (value == null) {
            return new MetadataResult(false, "", "");
        }

        String[] parts = value.trim().split("[\\r\\n;]+");

        for (String part : parts) {
            String item = part.trim();
            String lower = item.toLowerCase(Locale.ROOT);

            if (lower.startsWith("@v")) {
                singer = item.substring(2).trim();
                found = true;

            } else if (lower.startsWith("singer:")) {
                singer = removeLabel(item, "singer:");
                found = true;

            } else if (lower.startsWith("composer:")) {
                composer = removeLabel(item, "composer:");
                found = true;

            } else if (lower.startsWith("@i")) {
                String info = item.substring(2).trim();

                if (info.toLowerCase(Locale.ROOT)
                        .startsWith("composer:")) {
                    composer = removeLabel(info, "composer:");
                }

                found = true;

            } else if (lower.startsWith("@k")
                    || lower.startsWith("@t")
                    || lower.startsWith("@l")) {
                found = true;
            }
        }

        return new MetadataResult(found, singer, composer);
    }

    private static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

        } catch (Exception ignored) {
            // Try Shift-JIS next.
        }

        try {
            return Charset.forName("Shift_JIS")
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

        } catch (Exception ignored) {
            return new String(
                    bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static final class TempoEvent {
        final long tick;
        final long microsecondsPerQuarter;

        TempoEvent(long tick, long microsecondsPerQuarter) {
            this.tick = Math.max(0L, tick);
            this.microsecondsPerQuarter =
                    Math.max(1L, microsecondsPerQuarter);
        }
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
        final boolean section;

        boolean lineEnd;
        int line;

        LyricEvent(
                long tick,
                String text,
                int sequence,
                boolean lineEnd,
                boolean section) {
            this.tick = tick;
            this.text = text;
            this.sequence = sequence;
            this.lineEnd = lineEnd;
            this.section = section;
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
                throw new IOException(
                        "Unexpected end of MIDI file");
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
