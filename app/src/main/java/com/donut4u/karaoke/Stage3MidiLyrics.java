package com.donut4u.karaoke;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class Stage3MidiLyrics {

    private static final int ACTIVE_GREEN = Color.rgb(0, 255, 100);
    private static final long DEFAULT_TEMPO = 500000L;

    /*
     * Fallback stanza detection:
     * A pause of at least four quarter-note beats is considered
     * a possible stanza boundary only when the previous lyric
     * event also ends a line or a sentence.
     *
     * A pause by itself is not enough. This helps avoid splitting
     * ordinary instrumental gaps inside a stanza.
     */
    private static final long MIN_STANZA_GAP_BEATS = 4;

    private final List<LyricEvent> events;
    private final List<TempoEvent> tempos;
    private final String singer;
    private final String composer;
    private final int ppq;

    private Stage3MidiLyrics(
            List<LyricEvent> events,
            String singer,
            String composer,
            int ppq,
            List<TempoEvent> tempos) {

        this.events = events;
        this.singer = singer == null ? "" : singer;
        this.composer = composer == null ? "" : composer;
        this.ppq = ppq > 0 ? ppq : 480;
        this.tempos = new ArrayList<>(tempos);

        if (this.tempos.isEmpty()) {
            this.tempos.add(new TempoEvent(0, DEFAULT_TEMPO));
        }

        Collections.sort(
                this.tempos,
                Comparator.comparingLong(a -> a.tick));
    }

    public static Stage3MidiLyrics empty() {
        List<TempoEvent> t = new ArrayList<>();
        t.add(new TempoEvent(0, DEFAULT_TEMPO));

        return new Stage3MidiLyrics(
                new ArrayList<>(),
                "",
                "",
                480,
                t);
    }

    public boolean hasLyrics() {
        for (LyricEvent e : events) {
            if (!e.section && !e.text.trim().isEmpty()) {
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
        return ppq;
    }

    public String getMetadataText() {
        StringBuilder b = new StringBuilder();

        if (!singer.isEmpty()) {
            b.append("Singer: ").append(singer);
        }

        if (!composer.isEmpty()) {
            if (b.length() > 0) {
                b.append('\n');
            }

            b.append("Composer: ").append(composer);
        }

        return b.toString();
    }

    public long tickToMillis(long target) {
        target = Math.max(0, target);

        long previous = 0;
        long tempo = DEFAULT_TEMPO;
        double micros = 0;

        for (TempoEvent e : tempos) {
            if (e.tick <= 0) {
                tempo = e.micros;
                continue;
            }

            if (e.tick >= target) {
                break;
            }

            micros += (double) (e.tick - previous)
                    * tempo / ppq;

            previous = e.tick;
            tempo = e.micros;
        }

        micros += (double) (target - previous)
                * tempo / ppq;

        return Math.max(0, (long) (micros / 1000));
    }

    public String textAtTick(long tick) {
        return styledTextAtTick(tick).toString();
    }

    public CharSequence styledTextAtTick(long tick) {
        return build(tick).text;
    }

    public CharSequence styledFullTextAtTick(long tick) {
        return build(tick).text;
    }

    public int activeLineStartOffsetAtTick(long tick) {
        return build(tick).lineStart;
    }

    public int activeEventIndexAtTick(long tick) {
        return activeIndex(tick);
    }

    public int activeLineNumberAtTick(long tick) {
        int i = activeIndex(tick);

        return i < 0 ? -1 : events.get(i).line;
    }

    public String upcomingLineAtTick(long tick) {
        int active = activeIndex(tick);

        int currentLine = active < 0
                ? Integer.MIN_VALUE
                : events.get(active).line;

        int currentStanza = active < 0
                ? firstStanza()
                : events.get(active).stanza;

        int nextLine = Integer.MIN_VALUE;
        StringBuilder b = new StringBuilder();

        for (int i = Math.max(0, active + 1);
                i < events.size(); i++) {

            LyricEvent e = events.get(i);

            if (e.stanza != currentStanza) {
                break;
            }

            if (e.section) {
                continue;
            }

            if (e.line == currentLine) {
                continue;
            }

            if (nextLine == Integer.MIN_VALUE) {
                nextLine = e.line;
            }

            if (e.line != nextLine) {
                break;
            }

            appendFragment(b, e.text);
        }

        return b.toString().trim();
    }

    public long nextLyricTickAfter(long tick) {
        for (LyricEvent e : events) {
            if (!e.section
                    && e.tick > Math.max(0, tick)) {
                return e.tick;
            }
        }

        return -1;
    }

    public int getCountdownNumberAtTick(long tick) {
        int first = nextLyricIndex(0);

        if (first < 0) {
            return 0;
        }

        int target = -1;

        if (tick < events.get(first).tick) {
            target = first;

        } else {
            int section = -1;

            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).tick <= tick
                        && events.get(i).section) {
                    section = i;
                }
            }

            if (section >= 0
                    && isInstrumental(events.get(section).text)) {
                target = nextLyricIndex(section + 1);
            }
        }

        if (target < 0) {
            return 0;
        }

        long remaining = events.get(target).tick - tick;

        if (remaining <= 0) {
            return 0;
        }

        long beat = Math.max(1, ppq);
        long beats = (remaining + beat - 1) / beat;

        return beats >= 1 && beats <= 5
                ? (int) beats
                : 0;
    }

    public boolean shouldShowDotsAtTick(long tick) {
        return getCountdownNumberAtTick(tick) > 0;
    }

    private static boolean isInstrumental(String s) {
        String v = s.toUpperCase(Locale.ROOT);

        return v.contains("INSTRUMENTAL")
                || v.equals("INTRO")
                || v.equals("INTERLUDE")
                || v.equals("OUTRO")
                || v.equals("SOLO")
                || v.equals("BREAK")
                || v.equals("ENDING")
                || v.equals("CODA");
    }

    private int nextLyricIndex(int from) {
        for (int i = Math.max(0, from);
                i < events.size(); i++) {

            if (!events.get(i).section) {
                return i;
            }
        }

        return -1;
    }

    private int firstStanza() {
        for (LyricEvent e : events) {
            if (!e.section) {
                return e.stanza;
            }
        }

        return 0;
    }

    /*
     * Returns the latest lyric event at or before the current tick.
     * A section marker temporarily clears the active lyric.
     */
    private int activeIndex(long tick) {
        int low = 0;
        int high = events.size() - 1;
        int found = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;

            if (events.get(mid).tick <= tick) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (found >= 0 && events.get(found).section) {
            return -1;
        }

        return found;
    }

    /*
     * Render only the current stanza.
     *
     * Section labels are used internally to divide the song,
     * but are not displayed as lyric text.
     *
     * The active lyric event is highlighted green.
     * Other lyrics in the same stanza remain available for
     * the player to display in white.
     */
    private FullText build(long tick) {
        SpannableStringBuilder out =
                new SpannableStringBuilder();

        if (events.isEmpty()) {
            return new FullText(out, 0);
        }

        int active = activeIndex(tick);

        int latest = -1;
        int low = 0;
        int high = events.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;

            if (events.get(mid).tick <= tick) {
                latest = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        int targetStanza = latest >= 0
                ? events.get(latest).stanza
                : firstStanza();

        int[] starts = new int[events.size()];
        int[] ends = new int[events.size()];

        for (int i = 0; i < events.size(); i++) {
            starts[i] = -1;
            ends[i] = -1;
        }

        int lineStart = 0;
        int renderedLineStart = 0;
        int currentLine = Integer.MIN_VALUE;

        for (int i = 0; i < events.size(); i++) {
            LyricEvent e = events.get(i);

            if (e.stanza != targetStanza || e.section) {
                continue;
            }

            if (currentLine != e.line) {
                trimTrailingNewlines(out);

                if (out.length() > 0) {
                    out.append('\n');
                }

                currentLine = e.line;
                renderedLineStart = out.length();
            }

            starts[i] = out.length();
            out.append(e.text);
            ends[i] = out.length();

            if (i == active) {
                lineStart = renderedLineStart;
            }
        }

        /*
         * Highlight the active lyric event.
         * Expand to the surrounding word when the event
         * contains only part of a word.
         */
        if (active >= 0
                && starts[active] >= 0
                && ends[active] > starts[active]) {

            int wordStart = starts[active];
            int wordEnd = ends[active];

            while (wordStart < wordEnd
                    && Character.isWhitespace(
                            out.charAt(wordStart))) {
                wordStart++;
            }

            if (wordStart < wordEnd) {
                while (wordStart > 0
                        && !Character.isWhitespace(
                                out.charAt(wordStart - 1))) {
                    wordStart--;
                }

                while (wordEnd < out.length()
                        && !Character.isWhitespace(
                                out.charAt(wordEnd))) {
                    wordEnd++;
                }

                if (wordEnd > wordStart) {
                    out.setSpan(
                            new ForegroundColorSpan(ACTIVE_GREEN),
                            wordStart,
                            wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        trimTrailingNewlines(out);

        return new FullText(out, lineStart);
    }

    private static void trimTrailingNewlines(
            SpannableStringBuilder b) {

        while (b.length() > 0
                && (b.charAt(b.length() - 1) == '\n'
                || b.charAt(b.length() - 1) == '\r')) {

            b.delete(b.length() - 1, b.length());
        }
    }

    private static void appendFragment(
            StringBuilder b,
            String s) {

        if (s == null || s.isEmpty()) {
            return;
        }

        b.append(s);
    }

    public static Stage3MidiLyrics read(File file) {
        List<LyricEvent> lyrics = new ArrayList<>();
        List<LyricEvent> texts = new ArrayList<>();
        List<LyricEvent> sections = new ArrayList<>();
        List<TempoEvent> tempos = new ArrayList<>();

        tempos.add(new TempoEvent(0, DEFAULT_TEMPO));

        String singer = "";
        String composer = "";

        int ppq = 480;
        int sequence = 0;

        if (file == null
                || !file.isFile()
                || file.length() < 14) {
            return empty();
        }

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            Reader r = new Reader(data);

            if (!"MThd".equals(r.ascii(4))) {
                return empty();
            }

            long headerLength = r.u32();

            if (headerLength < 6
                    || headerLength > r.remaining()) {
                return empty();
            }

            r.u16();

            int tracks = r.u16();
            int division = r.u16();

            if ((division & 0x8000) == 0 && division > 0) {
                ppq = division;
            }

            r.skip((int) headerLength - 6);

            for (int track = 0;
                    track < tracks && r.remaining() >= 8;
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
                int running = 0;

                while (r.position < end) {
                    tick += r.vlq(end);

                    if (r.position >= end) {
                        break;
                    }

                    int first = r.u8();
                    int status;
                    int firstData = -1;

                    if (first < 0x80) {
                        if (running == 0) {
                            break;
                        }

                        status = running;
                        firstData = first;

                    } else {
                        status = first;
                    }

                    if (status == 0xFF) {
                        running = 0;

                        int type = r.u8();
                        int length = r.vlq(end);

                        if (length > end - r.position) {
                            break;
                        }

                        byte[] raw = r.bytes(length);

                        if (type == 0x2F) {
                            break;
                        }

                        if (type == 0x51 && raw.length == 3) {
                            long tempo =
                                    ((long) (raw[0] & 255) << 16)
                                    | ((long) (raw[1] & 255) << 8)
                                    | (raw[2] & 255L);

                            if (tempo > 0) {
                                tempos.add(
                                        new TempoEvent(tick, tempo));
                            }

                            continue;
                        }

                        String value =
                                decode(raw).replace("\u0000", "");

                        String trimmed = value.trim();

                        if (trimmed.isEmpty()) {
                            continue;
                        }

                        String lower =
                                trimmed.toLowerCase(Locale.ROOT);

                        if (type == 0x03) {
                            if (lower.startsWith("singer:")) {
                                singer = trimmed.substring(7).trim();
                            }

                            if (lower.startsWith("composer:")) {
                                composer = trimmed.substring(9).trim();
                            }
                        }

                        if (type == 0x06) {
                            String heading = heading(trimmed);

                            if (heading != null) {
                                sections.add(new LyricEvent(
                                        tick,
                                        heading,
                                        sequence++,
                                        false,
                                        false,
                                        true));
                            }

                            continue;
                        }

                        if (type != 0x01 && type != 0x05) {
                            continue;
                        }

                        Metadata m = metadata(trimmed);

                        if (m.found) {
                            if (!m.singer.isEmpty()) {
                                singer = m.singer;
                            }

                            if (!m.composer.isEmpty()) {
                                composer = m.composer;
                            }

                            continue;
                        }

                        if (nonLyric(trimmed)) {
                            continue;
                        }

                        String h = heading(trimmed);

                        if (h != null) {
                            sections.add(new LyricEvent(
                                    tick,
                                    h,
                                    sequence++,
                                    false,
                                    false,
                                    true));

                            continue;
                        }

                        boolean start = startsBreak(value);
                        boolean finish = endsBreak(value);
                        boolean stanzaBreak =
                                hasStanzaBreak(value);

                        /*
                         * Keep embedded newlines.
                         * They are used later to preserve the
                         * original lyric line layout.
                         */
                        String clean = clean(value);

                        if (clean.trim().isEmpty()
                                || control(clean)) {
                            continue;
                        }

                        LyricEvent e = new LyricEvent(
                                tick,
                                clean,
                                sequence++,
                                finish,
                                stanzaBreak,
                                false);

                        e.lineStart = start;

                        if (type == 0x05) {
                            lyrics.add(e);
                        } else {
                            texts.add(e);
                        }

                    } else if (status == 0xF0
                            || status == 0xF7) {

                        running = 0;

                        int length = r.vlq(end);

                        if (length > end - r.position) {
                            break;
                        }

                        r.skip(length);

                    } else if (status >= 0x80
                            && status <= 0xEF) {

                        running = status;

                        if (firstData < 0) {
                            r.u8();
                        }

                        int command = status & 0xF0;

                        if (command != 0xC0
                                && command != 0xD0) {
                            r.u8();
                        }

                    } else {
                        running = 0;
                    }
                }

                r.position = end;
            }

        } catch (Exception ignored) {
            // Keep any valid lyric events already read.
        }

        List<LyricEvent> all = new ArrayList<>();

        all.addAll(lyrics.isEmpty() ? texts : lyrics);
        all.addAll(sections);

        Collections.sort(all, (a, b) -> {
            int c = Long.compare(a.tick, b.tick);

            return c != 0
                    ? c
                    : Integer.compare(a.sequence, b.sequence);
        });

        /*
         * Add fallback stanza boundaries before assigning
         * stanza and line numbers.
         */
        inferMissingStanzaBreaks(all, ppq);

        int line = 0;
        int stanza = 0;
        LyricEvent previous = null;

        for (LyricEvent e : all) {
            if (e.section) {
                if (previous != null) {
                    stanza++;
                }

                e.stanza = stanza;
                e.line = line++;
                previous = null;

                continue;
            }

            if (previous != null) {
                if (previous.stanzaEnd) {
                    stanza++;
                    line++;

                } else {
                    int embeddedBreaks =
                            countLineBreaks(previous.text);

                    if (embeddedBreaks > 0) {
                        line += embeddedBreaks;

                    } else if (previous.lineEnd
                            || e.lineStart) {
                        line++;
                    }
                }
            }

            e.stanza = stanza;
            e.line = line;
            previous = e;
        }

        return new Stage3MidiLyrics(
                all,
                singer,
                composer,
                ppq,
                tempos);
    }

    /*
     * Infer stanza divisions only when there is enough evidence.
     *
     * A long pause must be accompanied by a line ending or
     * sentence-ending punctuation. Explicit stanza markers
     * always take priority.
     *
     * Section markers reset the comparison so that a pause
     * across a section heading is not counted twice.
     */
    private static void inferMissingStanzaBreaks(
            List<LyricEvent> all,
            int ppq) {

        if (all == null || all.isEmpty()) {
            return;
        }

        long minimumGap = Math.max(
                1L,
                (long) Math.max(1, ppq)
                        * MIN_STANZA_GAP_BEATS);

        LyricEvent previous = null;

        for (LyricEvent current : all) {
            if (current.section) {
                previous = null;
                continue;
            }

            if (previous != null
                    && !previous.stanzaEnd) {

                long gap = current.tick - previous.tick;

                boolean previousEndsLine =
                        previous.lineEnd
                        || endsSentence(previous.text);

                if (gap >= minimumGap
                        && previousEndsLine) {

                    previous.stanzaEnd = true;
                    previous.lineEnd = true;
                }
            }

            previous = current;
        }
    }

    /*
     * Recognize common sentence-ending punctuation,
     * including punctuation followed by closing quotation
     * marks or brackets.
     */
    private static boolean endsSentence(String text) {
        if (text == null) {
            return false;
        }

        String s = text.trim();

        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);

            if (c == '"'
                    || c == '\''
                    || c == ')'
                    || c == ']'
                    || c == '}'
                    || c == '»'
                    || c == '”'
                    || c == '’') {

                s = s.substring(0, s.length() - 1).trim();

            } else {
                break;
            }
        }

        if (s.isEmpty()) {
            return false;
        }

        char last = s.charAt(s.length() - 1);

        return last == '.'
                || last == '!'
                || last == '?'
                || last == '…'
                || last == '。'
                || last == '！'
                || last == '？';
    }

    /*
     * Count embedded line breaks so that later lyric events
     * receive the correct line number.
     */
    private static int countLineBreaks(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int count = 0;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }

        return count;
    }

    private static boolean hasStanzaBreak(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }

        int trailing = 0;

        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);

            if (c == '/' || c == '\\') {
                trailing++;

            } else if (!Character.isWhitespace(c)) {
                break;
            }
        }

        if (trailing >= 2) {
            return true;
        }

        int leading = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '/' || c == '\\') {
                leading++;

            } else if (!Character.isWhitespace(c)) {
                break;
            }
        }

        if (leading >= 2) {
            return true;
        }

        String normalized = s.replace("\r\n", "\n")
                .replace('\r', '\n');

        int consecutive = 0;

        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                consecutive++;

                if (consecutive >= 2) {
                    return true;
                }

            } else if (!Character.isWhitespace(
                    normalized.charAt(i))) {

                consecutive = 0;
            }
        }

        return false;
    }

    private static String heading(String value) {
        if (value == null) {
            return null;
        }

        String s = value.trim();

        if (s.length() >= 2
                && ((s.startsWith("[") && s.endsWith("]"))
                || (s.startsWith("<") && s.endsWith(">"))
                || (s.startsWith("*") && s.endsWith("*")))) {

            s = s.substring(1, s.length() - 1).trim();
        }

        String n = s.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("[.!:]+$", "")
                .trim();

        if (n.matches(
                "^(intro|instrumental|interlude|outro|verse\\s*[0-9ivx]*|chorus\\s*[0-9ivx]*|last chorus|final chorus|bridge|refrain|solo|break|ending|pre chorus|post chorus|repeat|coda)$")) {

            return n.toUpperCase(Locale.ROOT);
        }

        return null;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }

        s = s.replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        int start = 0;
        int end = s.length();

        while (start < end
                && (s.charAt(start) == '/'
                || s.charAt(start) == '\\')) {
            start++;
        }

        while (end > start
                && (s.charAt(end - 1) == '/'
                || s.charAt(end - 1) == '\\')) {
            end--;
        }

        return s.substring(start, end);
    }

    private static boolean startsBreak(String s) {
        return s != null
                && !s.isEmpty()
                && (s.charAt(0) == '/'
                || s.charAt(0) == '\\');
    }

    private static boolean endsBreak(String s) {
        return s != null
                && !s.isEmpty()
                && (s.charAt(s.length() - 1) == '/'
                || s.charAt(s.length() - 1) == '\\'
                || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0);
    }

    private static boolean control(String s) {
        String v = s.trim();

        return v.isEmpty()
                || v.matches("[\\p{Cntrl}\\s]+")
                || v.replaceAll("\\s+", "")
                        .matches("(?i)(0{2,}|0x0{2,})");
    }

    private static boolean nonLyric(String value) {
        if (value == null) {
            return true;
        }

        String s = value.toLowerCase(Locale.ROOT)
                .replace('\u0000', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        if (s.isEmpty() || s.startsWith("@")) {
            return true;
        }

        if (s.matches("^(xfhd|fln)(\\b.*)?$")) {
            return true;
        }

        if (s.matches("^\\d+\\s*[-_.].*\\.(mid|midi|kar)$")) {
            return true;
        }

        if (s.matches(".*\\.(mid|midi|kar)$")
                && (s.contains(" - ")
                || s.contains("_")
                || s.contains("/"))) {
            return true;
        }

        String[] prefixes = {
                "converted with", "created with",
                "generated with", "exported with",
                "encoded with", "rendered with",
                "recorded with", "processed with",
                "edited with", "made with",
                "created by", "generated by",
                "exported by", "sequenced by",
                "arranged by", "transcribed by",
                "programmed by", "converted by",
                "psruti", "all rights reserved",
                "midi file:", "filename:", "file name:",
                "track name:", "track:", "format 0",
                "format 1", "midi format", "midi type",
                "standard midi file", "smf:", "software:",
                "sequencer:", "program:", "author:",
                "arranger:", "copyright:", "©", "(c)",
                "www.", "http://", "https://", "mailto:",
                "singer:", "composer:", "artist:", "title:"
        };

        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }

        return s.contains("converted with psruti")
                || s.contains("created with psruti")
                || s.contains("generated by psruti");
    }

    private static Metadata metadata(String value) {
        String singer = "";
        String composer = "";
        boolean found = false;

        for (String part : value.trim().split("[\\r\\n;]+")) {
            String item = part.trim();
            String lower = item.toLowerCase(Locale.ROOT);

            if (lower.startsWith("@v")) {
                singer = item.substring(2).trim();
                found = true;

            } else if (lower.startsWith("singer:")) {
                singer = item.substring(7).trim();
                found = true;

            } else if (lower.startsWith("composer:")) {
                composer = item.substring(9).trim();
                found = true;

            } else if (lower.startsWith("@i")) {
                String info = item.substring(2).trim();

                if (info.toLowerCase(Locale.ROOT)
                        .startsWith("composer:")) {
                    composer = info.substring(9).trim();
                }

                found = true;

            } else if (lower.startsWith("@k")
                    || lower.startsWith("@t")
                    || lower.startsWith("@l")) {

                found = true;
            }
        }

        return new Metadata(found, singer, composer);
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

        } catch (Exception ignored) {
        }

        try {
            return Charset.forName("Shift_JIS").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

        } catch (Exception ignored) {
            return new String(
                    bytes,
                    StandardCharsets.ISO_8859_1);
        }
    }

    private static final class TempoEvent {
        final long tick;
        final long micros;

        TempoEvent(long tick, long micros) {
            this.tick = Math.max(0, tick);
            this.micros = Math.max(1, micros);
        }
    }

    private static final class LyricEvent {
        final long tick;
        final String text;
        final int sequence;
        final boolean section;

        boolean lineEnd;
        boolean lineStart;
        boolean stanzaEnd;

        int line;
        int stanza;

        LyricEvent(
                long tick,
                String text,
                int sequence,
                boolean lineEnd,
                boolean stanzaEnd,
                boolean section) {

            this.tick = tick;
            this.text = text;
            this.sequence = sequence;
            this.lineEnd = lineEnd;
            this.stanzaEnd = stanzaEnd;
            this.section = section;
        }
    }

    private static final class FullText {
        final SpannableStringBuilder text;
        final int lineStart;

        FullText(
                SpannableStringBuilder text,
                int lineStart) {

            this.text = text;
            this.lineStart = lineStart;
        }
    }

    private static final class Metadata {
        final boolean found;
        final String singer;
        final String composer;

        Metadata(
                boolean found,
                String singer,
                String composer) {

            this.found = found;
            this.singer = singer;
            this.composer = composer;
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

        int u8() throws java.io.IOException {
            if (position >= data.length) {
                throw new java.io.IOException(
                        "Unexpected end");
            }

            return data[position++] & 255;
        }

        int u16() throws java.io.IOException {
            return (u8() << 8) | u8();
        }

        long u32() throws java.io.IOException {
            return ((long) u8() << 24)
                    | ((long) u8() << 16)
                    | ((long) u8() << 8)
                    | u8();
        }

        String ascii(int n) throws java.io.IOException {
            if (n < 0 || n > remaining()) {
                throw new java.io.IOException(
                        "Invalid chunk");
            }

            String s = new String(
                    data,
                    position,
                    n,
                    StandardCharsets.US_ASCII);

            position += n;

            return s;
        }

        byte[] bytes(int n) throws java.io.IOException {
            if (n < 0 || n > remaining()) {
                throw new java.io.IOException(
                        "Invalid event");
            }

            byte[] b = new byte[n];

            System.arraycopy(
                    data,
                    position,
                    b,
                    0,
                    n);

            position += n;

            return b;
        }

        void skip(int n) throws java.io.IOException {
            if (n < 0 || n > remaining()) {
                throw new java.io.IOException(
                        "Invalid length");
            }

            position += n;
        }

        int vlq(int end) throws java.io.IOException {
            int value = 0;
            int count = 0;
            int next;

            do {
                if (position >= end
                        || position >= data.length
                        || count++ >= 4) {

                    throw new java.io.IOException(
                            "Invalid MIDI length");
                }

                next = data[position++] & 255;
                value = (value << 7) | (next & 127);

            } while ((next & 128) != 0);

            return value;
        }
    }
}
