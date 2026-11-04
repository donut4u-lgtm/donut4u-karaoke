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
    private static final int SECTION_GOLD = Color.rgb(255, 205, 90);
    private static final long DEFAULT_TEMPO = 500000L;
    private static final int MAX_VISIBLE_LINES = 5;

    private final List<LyricEvent> events;
    private final List<TempoEvent> tempos;
    private final String title;
    private final String singer;
    private final String composer;
    private final int ppq;
    private final boolean hasExplicitStanzas;

    private Stage3MidiLyrics(
            List<LyricEvent> events,
            List<TempoEvent> tempos,
            String title,
            String singer,
            String composer,
            int ppq,
            boolean hasExplicitStanzas) {

        this.events = events;
        this.tempos = tempos;
        this.title = safe(title);
        this.singer = safe(singer);
        this.composer = safe(composer);
        this.ppq = ppq > 0 ? ppq : 480;
        this.hasExplicitStanzas = hasExplicitStanzas;

        if (this.tempos.isEmpty()) {
            this.tempos.add(new TempoEvent(0, DEFAULT_TEMPO));
        }

        Collections.sort(this.tempos, new Comparator<TempoEvent>() {
            @Override
            public int compare(TempoEvent a, TempoEvent b) {
                return Long.compare(a.tick, b.tick);
            }
        });
    }

    public static Stage3MidiLyrics empty() {
        List<LyricEvent> e = new ArrayList<>();
        List<TempoEvent> t = new ArrayList<>();
        t.add(new TempoEvent(0, DEFAULT_TEMPO));

        return new Stage3MidiLyrics(e, t, "", "", "", 480, false);
    }

    public String getTitle() {
        return title;
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

    public boolean hasLyrics() {
        for (LyricEvent e : events) {
            if (!e.section && !e.text.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public String getEditableText() {
        StringBuilder result = new StringBuilder();
        int previousStanza = Integer.MIN_VALUE;
        int previousLine = Integer.MIN_VALUE;
        boolean firstLine = true;

        for (LyricEvent event : events) {
            if (event.section || event.text.trim().isEmpty()) {
                continue;
            }

            if (event.stanza != previousStanza
                    || event.line != previousLine) {

                if (!firstLine) {
                    result.append('\n');
                }

                previousStanza = event.stanza;
                previousLine = event.line;
                firstLine = false;

            } else if (hasWordBoundaryBefore(event)) {
                result.append(' ');
            }

            result.append(event.text);
        }

        return result.toString();
    }

    public boolean applyEditedText(String editedText) {
        if (editedText == null || events.isEmpty()) {
            return false;
        }

        String normalized = editedText
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        String[] editedLines = normalized.split("\n", -1);

        List<List<LyricEvent>> eventsByLine = new ArrayList<>();
        List<LyricEvent> currentLine = null;

        int previousLine = Integer.MIN_VALUE;
        int previousStanza = Integer.MIN_VALUE;

        for (LyricEvent event : events) {
            if (event.section || event.text.trim().isEmpty()) {
                continue;
            }

            if (currentLine == null
                    || event.line != previousLine
                    || event.stanza != previousStanza) {

                currentLine = new ArrayList<>();
                eventsByLine.add(currentLine);

                previousLine = event.line;
                previousStanza = event.stanza;
            }

            currentLine.add(event);
        }

        if (eventsByLine.size() != editedLines.length) {
            return false;
        }

        List<String[]> wordsByLine = new ArrayList<>();

        for (String line : editedLines) {
            String clean = line.trim();

            if (clean.isEmpty()) {
                return false;
            }

            String[] words = clean.split("\\s+");

            if (words.length == 0) {
                return false;
            }

            wordsByLine.add(words);
        }

        List<List<String>> assignments = new ArrayList<>();

        for (int lineIndex = 0;
             lineIndex < eventsByLine.size();
             lineIndex++) {

            List<LyricEvent> originalEvents =
                    eventsByLine.get(lineIndex);

            String[] words = wordsByLine.get(lineIndex);

            if (originalEvents.isEmpty()) {
                return false;
            }

            List<List<LyricEvent>> groups = new ArrayList<>();
            List<LyricEvent> group = null;

            for (int i = 0; i < originalEvents.size(); i++) {
                LyricEvent event = originalEvents.get(i);

                boolean startsNewWord = i == 0
                        || hasWordBoundaryBefore(event);

                if (group == null || startsNewWord) {
                    group = new ArrayList<>();
                    groups.add(group);
                }

                group.add(event);
            }

            if (groups.isEmpty()) {
                return false;
            }

            List<String> lineAssignments = new ArrayList<>();

            for (int i = 0; i < originalEvents.size(); i++) {
                lineAssignments.add("");
            }

            for (int wordIndex = 0;
                 wordIndex < words.length;
                 wordIndex++) {

                int groupIndex = Math.min(
                        groups.size() - 1,
                        (int) (((long) wordIndex * groups.size())
                                / words.length));

                List<LyricEvent> targetGroup = groups.get(groupIndex);

                LyricEvent firstEvent = targetGroup.get(0);
                int eventIndex = originalEvents.indexOf(firstEvent);

                if (eventIndex < 0) {
                    return false;
                }

                String existing = lineAssignments.get(eventIndex);

                if (!existing.isEmpty()) {
                    existing += " ";
                }

                lineAssignments.set(
                        eventIndex,
                        existing + words[wordIndex]);
            }

            assignments.add(lineAssignments);
        }

        for (int lineIndex = 0;
             lineIndex < eventsByLine.size();
             lineIndex++) {

            List<LyricEvent> lineEvents =
                    eventsByLine.get(lineIndex);

            List<String> lineAssignments =
                    assignments.get(lineIndex);

            for (int i = 0; i < lineEvents.size(); i++) {
                lineEvents.get(i).text = lineAssignments.get(i);
            }
        }

        return true;
    }

    private static boolean hasWordBoundaryBefore(LyricEvent event) {
        return event.spaceBefore || event.inferredSpaceBefore;
    }

    public String getMetadataText() {
        StringBuilder b = new StringBuilder();

        if (!singer.isEmpty()) {
            b.append("Singer: ").append(singer);
        }

        if (!composer.isEmpty()) {
            if (b.length() > 0) b.append('\n');
            b.append("Composer: ").append(composer);
        }

        return b.toString();
    }

    public long tickToMillis(long tick) {
        long target = Math.max(0, tick);
        long previous = 0;
        long tempo = DEFAULT_TEMPO;
        double micros = 0;

        for (TempoEvent e : tempos) {
            if (e.tick == 0) {
                tempo = e.micros;
                continue;
            }

            if (e.tick >= target) break;

            micros += ((double) (e.tick - previous) * tempo) / ppq;
            previous = e.tick;
            tempo = e.micros;
        }

        micros += ((double) (target - previous) * tempo) / ppq;
        return Math.max(0, (long) (micros / 1000.0));
    }

    public String textAtTick(long tick) {
        return styledTextAtTick(tick).toString();
    }

    public CharSequence styledTextAtTick(long tick) {
        return build(tick, Long.MAX_VALUE).text;
    }

    public CharSequence styledTextAtTick(long tick, long durationTicks) {
        return build(tick, durationTicks).text;
    }

    public CharSequence styledFullTextAtTick(long tick) {
        return build(tick, Long.MAX_VALUE).text;
    }

    public CharSequence styledFullTextAtTick(long tick, long durationTicks) {
        return build(tick, durationTicks).text;
    }

    public int activeLineStartOffsetAtTick(long tick) {
        return build(tick, Long.MAX_VALUE).activeLineStart;
    }

    public int activeEventIndexAtTick(long tick) {
        return activeIndex(tick);
    }

    public int activeLineNumberAtTick(long tick) {
        int i = activeIndex(tick);
        return i < 0 || events.get(i).section ? -1 : events.get(i).line;
    }

    public String upcomingLineAtTick(long tick) {
        int active = activeIndex(tick);
        if (active < 0 || events.get(active).section) return "";

        int line = events.get(active).line;
        int stanza = events.get(active).stanza;
        StringBuilder b = new StringBuilder();

        for (int i = active + 1; i < events.size(); i++) {
            LyricEvent e = events.get(i);

            if (e.section || e.stanza != stanza) break;
            if (e.text.trim().isEmpty()) continue;
            if (e.line == line) continue;

            if (b.length() > 0) break;

            line = e.line;
            b.append(e.text);
        }

        return b.toString().trim();
    }

    public long nextLyricTickAfter(long tick) {
        for (LyricEvent e : events) {
            if (!e.section && !e.text.trim().isEmpty()
                    && e.tick > tick) {
                return e.tick;
            }
        }
        return -1;
    }

    public boolean shouldShowDotsAtTick(long tick) {
        int active = activeIndex(tick);

        if (active < 0) {
            int first = firstLyricIndex();
            if (first < 0) return false;

            long remaining = events.get(first).tick - tick;
            return remaining > 0 && remaining <= ppq * 5L;
        }

        LyricEvent current = events.get(active);
        if (current.section) return isInstrumental(current.text);

        int next = nextLyricIndex(active + 1);
        if (next < 0) return false;

        LyricEvent upcoming = events.get(next);
        long gap = upcoming.tick - current.tick;

        return gap >= ppq * 3L
                && tick >= current.tick + ppq / 2L
                && tick < upcoming.tick - ppq / 2L;
    }

    public int getCountdownNumberAtTick(long tick) {
        long now = Math.max(0L, tick);
        long beat = Math.max(1L, ppq);
        long countdownWindow = beat * 5L;

        int previousLyric = -1;

        for (int i = 0; i < events.size(); i++) {
            LyricEvent candidate = events.get(i);

            if (candidate.section || candidate.text.trim().isEmpty()) {
                continue;
            }

            if (candidate.tick > now) {
                long remaining = candidate.tick - now;

                if (remaining <= 0L || remaining > countdownWindow) {
                    return 0;
                }

                boolean vocalEntrance = previousLyric < 0;

                if (previousLyric >= 0) {
                    LyricEvent previous = events.get(previousLyric);
                    long gap = candidate.tick - previous.tick;

                    vocalEntrance = gap >= countdownWindow
                            || hasInstrumentalSectionBetween(
                                    previousLyric, i);
                }

                if (!vocalEntrance) return 0;

                return (int) Math.max(
                        1L,
                        Math.min(
                                5L,
                                (remaining + beat - 1L) / beat
                        )
                );
            }

            previousLyric = i;
        }

        return 0;
    }

    private boolean hasInstrumentalSectionBetween(int start, int end) {
        for (int i = Math.max(0, start + 1);
             i < end && i < events.size(); i++) {

            LyricEvent event = events.get(i);

            if (event.section && isInstrumental(event.text)) {
                return true;
            }
        }

        return false;
    }

    private int firstLyricIndex() {
        for (int i = 0; i < events.size(); i++) {
            LyricEvent e = events.get(i);

            if (!e.section && !e.text.trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int nextLyricIndex(int start) {
        for (int i = Math.max(0, start); i < events.size(); i++) {
            LyricEvent e = events.get(i);

            if (!e.section && !e.text.trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int activeIndex(long tick) {
        int low = 0;
        int high = events.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = low + (high - low) / 2;

            if (events.get(mid).tick <= Math.max(0, tick)) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (result >= 0
                && !events.get(result).section
                && events.get(result).text.trim().isEmpty()) {

            int previous = result;

            while (previous >= 0) {
                LyricEvent event = events.get(previous);

                if (event.section) {
                    return previous;
                }

                if (!event.text.trim().isEmpty()) {
                    return previous;
                }

                previous--;
            }
        }

        return result;
    }

    private FullResult build(long tick, long durationTicks) {
        SpannableStringBuilder output = new SpannableStringBuilder();

        if (events.isEmpty()) {
            output.append("No embedded lyrics found in this MIDI/KAR file.");
            return new FullResult(output, 0);
        }

        int active = activeIndex(tick);

        if (active < 0) {
            active = firstLyricIndex();
        } else if (events.get(active).section) {
            active = nextLyricIndex(active + 1);
        }

        if (active < 0) {
            return new FullResult(output, 0);
        }

        LyricEvent activeEvent = events.get(active);
        int stanza = activeEvent.stanza;

        int stanzaFirstLine = Integer.MAX_VALUE;
        int stanzaLastLine = Integer.MIN_VALUE;

        for (LyricEvent e : events) {
            if (!e.section && !e.text.trim().isEmpty()
                    && e.stanza == stanza) {
                stanzaFirstLine = Math.min(stanzaFirstLine, e.line);
                stanzaLastLine = Math.max(stanzaLastLine, e.line);
            }
        }

        if (stanzaFirstLine == Integer.MAX_VALUE) {
            return new FullResult(output, 0);
        }

        int firstLine = Math.max(stanzaFirstLine, activeEvent.line - 2);
        int lastLine = Math.min(
                stanzaLastLine,
                firstLine + MAX_VISIBLE_LINES - 1);

        firstLine = Math.max(
                stanzaFirstLine,
                lastLine - MAX_VISIBLE_LINES + 1);

        int activeLineStart = 0;

        int[] starts = new int[events.size()];
        int[] ends = new int[events.size()];

        for (int i = 0; i < events.size(); i++) {
            starts[i] = -1;
            ends[i] = -1;
        }

        int previousLine = Integer.MIN_VALUE;
        int previousEvent = -1;

        for (int i = 0; i < events.size(); i++) {
            LyricEvent e = events.get(i);

            if (e.section || e.text.trim().isEmpty()
                    || e.stanza != stanza
                    || e.line < firstLine || e.line > lastLine) {
                continue;
            }

            if (previousLine != e.line) {
                if (output.length() > 0) {
                    output.append('\n');
                }

                previousLine = e.line;
                previousEvent = -1;
            }

            if (previousEvent >= 0) {
                LyricEvent previous = events.get(previousEvent);

                if (previous.spaceAfter
                        || e.spaceBefore
                        || e.inferredSpaceBefore) {
                    output.append(' ');
                }
            }

            int start = output.length();
            output.append(e.text);
            int end = output.length();

            starts[i] = start;
            ends[i] = end;

            if (e.line == activeEvent.line && activeLineStart == 0) {
                activeLineStart = start;
            }

            previousEvent = i;
        }

        if (output.length() == 0) {
            return new FullResult(output, 0);
        }

        boolean allowHighlight =
                durationTicks <= 0 || tick < durationTicks;

        int activeStart = -1;
        int activeEnd = -1;

        if (allowHighlight && starts[active] >= 0
                && ends[active] > starts[active]) {

            int firstWordEvent = active;
            int lastWordEvent = active;

            while (firstWordEvent > 0) {
                int previousIndex = firstWordEvent - 1;

                LyricEvent previous = events.get(previousIndex);
                LyricEvent current = events.get(firstWordEvent);

                if (previous.section || current.section
                        || previous.stanza != current.stanza
                        || previous.line != current.line
                        || starts[previousIndex] < 0
                        || ends[previousIndex] <= starts[previousIndex]
                        || previous.spaceAfter
                        || current.spaceBefore
                        || current.inferredSpaceBefore) {
                    break;
                }

                firstWordEvent--;
            }

            while (lastWordEvent + 1 < events.size()) {
                int nextIndex = lastWordEvent + 1;

                LyricEvent current = events.get(lastWordEvent);
                LyricEvent next = events.get(nextIndex);

                if (current.section || next.section
                        || current.stanza != next.stanza
                        || current.line != next.line
                        || starts[nextIndex] < 0
                        || ends[lastWordEvent] <= starts[lastWordEvent]
                        || current.spaceAfter
                        || next.spaceBefore
                        || next.inferredSpaceBefore) {
                    break;
                }

                lastWordEvent++;
            }

            activeStart = starts[firstWordEvent];
            activeEnd = ends[lastWordEvent];
        }

        if (allowHighlight && activeStart >= 0
                && activeEnd > activeStart) {

            output.setSpan(
                    new ForegroundColorSpan(ACTIVE_GREEN),
                    activeStart,
                    activeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return new FullResult(output, activeLineStart);
    }

    public static Stage3MidiLyrics read(File file) {
        if (file == null || !file.isFile() || file.length() < 14) {
            return empty();
        }

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            Reader r = new Reader(data);

            if (!"MThd".equals(r.ascii(4))) return empty();

            long headerLength = r.u32();

            if (headerLength < 6 || headerLength > r.remaining()) {
                return empty();
            }

            r.u16();
            int trackCount = r.u16();
            int division = r.u16();

            if ((division & 0x8000) != 0 || division == 0) {
                return empty();
            }

            int ppq = division;
            r.skip((int) headerLength - 6);

            List<LyricEvent> textEvents = new ArrayList<>();
            List<LyricEvent> lyricEvents = new ArrayList<>();
            List<LyricEvent> sectionEvents = new ArrayList<>();
            List<TempoEvent> tempos = new ArrayList<>();

            tempos.add(new TempoEvent(0, DEFAULT_TEMPO));

            String title = "";
            String singer = "";
            String composer = "";

            int titleField = 0;
            int sequence = 0;
            boolean explicitStanzas = false;

            for (int track = 0;
                 track < trackCount && r.remaining() >= 8;
                 track++) {

                String chunk = r.ascii(4);
                long length = r.u32();

                if (length > r.remaining()) break;

                int end = r.position + (int) length;

                if (!"MTrk".equals(chunk)) {
                    r.position = end;
                    continue;
                }

                long tick = 0;
                int runningStatus = 0;

                LyricBreakState textBreakState = new LyricBreakState();
                LyricBreakState lyricBreakState = new LyricBreakState();

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

                        int type = r.u8();
                        int size = r.vlq(end);

                        if (size > end - r.position) break;

                        byte[] raw = r.bytes(size);

                        if (type == 0x2F) break;

                        if (type == 0x51 && raw.length == 3) {
                            long tempo =
                                    ((long) (raw[0] & 255) << 16)
                                    | ((long) (raw[1] & 255) << 8)
                                    | (raw[2] & 255);

                            if (tempo > 0) {
                                tempos.add(new TempoEvent(tick, tempo));
                            }
                            continue;
                        }

                        String value = decode(raw).replace("\u0000", "");
                        String trimmed = value.trim();

                        boolean separatorOnlyLyricEvent =
                                (type == 0x01 || type == 0x05)
                                && containsLyricSeparator(value)
                                && trimmed.isEmpty();

                        boolean whitespaceOnlyLyricEvent =
                                (type == 0x01 || type == 0x05)
                                && !value.isEmpty()
                                && trimmed.isEmpty();

                        if (trimmed.isEmpty()
                                && !separatorOnlyLyricEvent
                                && !whitespaceOnlyLyricEvent) {
                            continue;
                        }

                        if (type == 0x03) {
                            String lower = trimmed.toLowerCase(Locale.ROOT);

                            if (lower.startsWith("singer:")) {
                                singer = removeLabel(trimmed, "singer:");
                            } else if (lower.startsWith("composer:")) {
                                composer = removeLabel(trimmed, "composer:");
                            }
                        }

                        if (type == 0x06) {
                            String heading = extractHeading(trimmed);

                            if (heading != null) {
                                sectionEvents.add(new LyricEvent(
                                        tick, heading, sequence++,
                                        false, true, false,
                                        false, true, false));
                            }
                            continue;
                        }

                        if (type != 0x01 && type != 0x05) continue;

                        if (trimmed.startsWith("@")) {
                            String lower = trimmed.toLowerCase(Locale.ROOT);

                            if (lower.startsWith("@v")) {
                                singer = trimmed.substring(2).trim();

                            } else if (lower.startsWith("@t")) {
                                String field = trimmed.substring(2).trim();

                                if (!field.isEmpty()) {
                                    titleField++;

                                    if (titleField == 1 && title.isEmpty()) {
                                        title = field;
                                    } else if (titleField == 2 && singer.isEmpty()) {
                                        singer = field;
                                    } else if (titleField >= 3) {
                                        String credit = field.toLowerCase(Locale.ROOT);

                                        if (credit.contains("music")
                                                || credit.contains("word")
                                                || credit.contains("copyright")) {
                                            if (composer.isEmpty()) composer = field;
                                        }
                                    }
                                }

                            } else if (lower.startsWith("@i")) {
                                String info = trimmed.substring(2).trim();

                                if (composer.isEmpty()
                                        && (info.toLowerCase(Locale.ROOT).contains("music")
                                        || info.toLowerCase(Locale.ROOT).contains("word"))) {
                                    composer = info;
                                }
                            }

                            continue;
                        }

                        String lower = trimmed.toLowerCase(Locale.ROOT);

                        if (lower.startsWith("title:")) {
                            if (title.isEmpty()) {
                                title = removeLabel(trimmed, "title:");
                            }
                            continue;
                        }

                        if (lower.startsWith("singer:")
                                || lower.startsWith("artist:")) {
                            if (singer.isEmpty()) {
                                singer = lower.startsWith("singer:")
                                        ? removeLabel(trimmed, "singer:")
                                        : removeLabel(trimmed, "artist:");
                            }
                            continue;
                        }

                        if (lower.startsWith("composer:")) {
                            composer = removeLabel(trimmed, "composer:");
                            continue;
                        }

                        if (isCredit(trimmed) || isNonLyricTag(trimmed)) {
                            continue;
                        }

                        /*
                         * Filter metadata from BOTH MIDI text and lyric events.
                         * Whitespace and separator-only events are preserved
                         * because they carry word and line boundary information.
                         */
                        if (!separatorOnlyLyricEvent
                                && !whitespaceOnlyLyricEvent
                                && !isLikelyLyricText(trimmed)) {
                            continue;
                        }

                        LyricBreakState breakState = type == 0x05
                                ? lyricBreakState : textBreakState;

                        List<LyricEvent> parsed =
                                parseLyricEvents(value, tick, sequence, breakState);

                        sequence += parsed.size();

                        for (LyricEvent event : parsed) {
                            if (event.stanzaBreak) explicitStanzas = true;

                            if (type == 0x01) {
                                textEvents.add(event);
                            } else {
                                lyricEvents.add(event);
                            }
                        }

                    } else if (status == 0xF0 || status == 0xF7) {
                        runningStatus = 0;

                        int size = r.vlq(end);
                        if (size > end - r.position) break;
                        r.skip(size);

                    } else if (status >= 0x80 && status <= 0xEF) {
                        runningStatus = status;

                        if (firstData < 0) r.u8();

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

            List<LyricEvent> chosen = !lyricEvents.isEmpty()
                    ? lyricEvents : textEvents;

            chosen.addAll(sectionEvents);

            Collections.sort(chosen, new Comparator<LyricEvent>() {
                @Override
                public int compare(LyricEvent a, LyricEvent b) {
                    int byTick = Long.compare(a.tick, b.tick);
                    return byTick != 0
                            ? byTick
                            : Integer.compare(a.sequence, b.sequence);
                }
            });

            applyAutomaticFormatting(chosen, ppq);

            int line = 0;
            int stanza = 0;

            for (LyricEvent e : chosen) {
                if (e.stanzaBreak) {
                    stanza++;
                    line++;
                } else if (e.lineBreak) {
                    line++;
                }

                if (e.section) {
                    stanza++;
                    line++;
                }

                e.line = line;
                e.stanza = stanza;
            }

            return new Stage3MidiLyrics(
                    chosen, tempos, title, singer, composer,
                    ppq, explicitStanzas);

        } catch (Exception ignored) {
            return empty();
        }
    }

    /*
     * Automatic formatting:
     * Never split words just because two MIDI events have a timing gap.
     * MIDI files often store syllables as separate events.
     * Only explicit spacing and punctuation create word boundaries.
     */
    private static void applyAutomaticFormatting(
            List<LyricEvent> lyricEvents,
            int ppq) {

        if (lyricEvents == null || lyricEvents.isEmpty()) return;

        int maximumCharacters = 32;
        int maximumEventsPerLine = 12;

        int lineCharacters = 0;
        int lineEvents = 0;

        LyricEvent previous = null;

        for (LyricEvent current : lyricEvents) {

            if (current.section) {
                previous = null;
                lineCharacters = 0;
                lineEvents = 0;
                continue;
            }

            if (current.lineBreak || current.stanzaBreak) {
                previous = null;
                lineCharacters = 0;
                lineEvents = 0;
            }

            if (previous != null) {
                String previousText = previous.text.trim();

                boolean sentenceEnd =
                        previousText.endsWith(".")
                        || previousText.endsWith("?")
                        || previousText.endsWith("!")
                        || previousText.endsWith("…");

                boolean phraseEnd =
                        previousText.endsWith(",")
                        || previousText.endsWith(";")
                        || previousText.endsWith(":");

                boolean explicitWordBoundary =
                        previous.spaceAfter || current.spaceBefore;

                /*
                 * Do not infer spaces from timing gaps.
                 * A long syllable gap is not necessarily a word boundary.
                 */
                boolean wordBoundary = explicitWordBoundary;

                boolean lineWouldOverflow =
                        lineCharacters
                        + current.text.length()
                        + (wordBoundary ? 1 : 0)
                        > maximumCharacters;

                boolean lineTooManyEvents =
                        lineEvents >= maximumEventsPerLine;

                boolean newLine =
                        sentenceEnd
                        || phraseEnd
                        || (wordBoundary
                                && (lineWouldOverflow || lineTooManyEvents));

                if (!current.lineBreak && !current.stanzaBreak) {
                    if (sentenceEnd && previousText.endsWith(".")) {
                        current.lineBreak = true;
                        lineCharacters = 0;
                        lineEvents = 0;

                    } else if (newLine) {
                        current.lineBreak = true;
                        lineCharacters = 0;
                        lineEvents = 0;
                    }
                }
            }

            int addedLength = current.text.length();

            if (current.inferredSpaceBefore || current.spaceBefore) {
                addedLength++;
            }

            lineCharacters += addedLength;
            lineEvents++;

            previous = current;
        }
    }

    private static boolean containsLyricSeparator(String value) {
        if (value == null) return false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c == '/' || c == '\\' || c == '\r' || c == '\n') {
                return true;
            }
        }

        return false;
    }

    private static boolean isLikelyLyricText(String value) {
        if (value == null) return false;

        String s = value.trim();
        if (s.isEmpty()) return false;

        String lower = s.toLowerCase(Locale.ROOT);

        if (isControlToken(s)
                || isCredit(s)
                || isNonLyricTag(s)
                || extractHeading(s) != null) {
            return false;
        }

        if (lower.matches("^(track|channel|tempo|time signature|"
                + "key signature|program|instrument|copyright|"
                + "sequence|midi|karaoke|kar)\\s*[:=].*$")) {
            return false;
        }

        if (lower.matches("^(gm|gs|xg)\\s*[:=].*$")) {
            return false;
        }

        if (lower.matches("^[0-9]+([.,][0-9]+)?\\s*(bpm|ms|hz)$")) {
            return false;
        }

        if (lower.matches("^(ch\\.?\\s*)?[0-9]{1,2}$")) {
            return false;
        }

        if (lower.matches("^(\\d{1,3}\\s*){2,}$")) {
            return false;
        }

        if (s.matches("^[#=<>\\[\\]{}|]+$")) return false;

        if (s.length() > 3
                && s.matches("^[A-Fa-f0-9:/\\\\._-]+$")) {
            return false;
        }

        return s.matches("(?s).*[\\p{L}].*");
    }

    private static List<LyricEvent> parseLyricEvents(
            String value,
            long tick,
            int firstSequence,
            LyricBreakState breakState) {

        List<LyricEvent> result = new ArrayList<>();

        if (value == null || breakState == null) return result;

        String source = value.replace("\u0000", "");
        StringBuilder fragment = new StringBuilder();

        boolean pendingLineBreak = breakState.pendingLineBreak;
        boolean pendingStanzaBreak = breakState.pendingStanzaBreak;
        boolean pendingSpace = breakState.pendingSpace;

        int sequence = firstSequence;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);

            if (c == '/' || c == '\\' || c == '\r' || c == '\n') {
                String rawFragment = fragment.toString();
                String text = cleanLyricFragment(rawFragment);

                if (!text.isEmpty() && !isControlToken(text)) {
                    result.add(new LyricEvent(
                            tick, text, sequence++,
                            pendingLineBreak, false, pendingStanzaBreak,
                            pendingSpace || hasLeadingSpace(rawFragment),
                            hasTrailingSpace(rawFragment),
                            false));

                    pendingLineBreak = false;
                    pendingStanzaBreak = false;
                    pendingSpace = false;

                } else if (!rawFragment.isEmpty()
                        && rawFragment.trim().isEmpty()) {
                    pendingSpace = false;
                }

                fragment.setLength(0);
                pendingLineBreak = true;

                if (c == '\\' || c == '\n') {
                    pendingStanzaBreak = true;
                }

                if (c == '\r' && i + 1 < source.length()
                        && source.charAt(i + 1) == '\n') {
                    i++;
                    pendingStanzaBreak = true;
                }

            } else {
                fragment.append(c);
            }
        }

        String rawFragment = fragment.toString();
        String text = cleanLyricFragment(rawFragment);

        if (!text.isEmpty() && !isControlToken(text)) {
            result.add(new LyricEvent(
                    tick, text, sequence,
                    pendingLineBreak, false, pendingStanzaBreak,
                    pendingSpace || hasLeadingSpace(rawFragment),
                    hasTrailingSpace(rawFragment),
                    false));

            pendingLineBreak = false;
            pendingStanzaBreak = false;
            pendingSpace = false;

        } else if (!rawFragment.isEmpty()
                && rawFragment.trim().isEmpty()) {
            pendingSpace = true;
        }

        breakState.pendingLineBreak = pendingLineBreak;
        breakState.pendingStanzaBreak = pendingStanzaBreak;
        breakState.pendingSpace = pendingSpace;

        return result;
    }

    private static boolean hasLeadingSpace(String value) {
        return value != null && !value.isEmpty()
                && Character.isWhitespace(value.charAt(0));
    }

    private static boolean hasTrailingSpace(String value) {
        return value != null && !value.isEmpty()
                && Character.isWhitespace(value.charAt(value.length() - 1));
    }

    private static String cleanLyricFragment(String value) {
        if (value == null) return "";

        return value.replace("\u0000", "")
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "")
                .trim();
    }

    private static boolean isCredit(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT);

        return s.matches("^(words?\\s*/\\s*music|music\\s*/\\s*words?|"
                + "words?\\s+and\\s+music|music\\s+and\\s+words?)\\s*:?$")
                || s.startsWith("copyright:")
                || s.startsWith("(c)")
                || s.startsWith("©");
    }

    private static boolean isNonLyricTag(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT);

        return s.startsWith("singer:")
                || s.startsWith("composer:")
                || s.startsWith("artist:")
                || s.startsWith("title:")
                || s.startsWith("author:")
                || s.startsWith("copyright:")
                || s.startsWith("words/music:")
                || s.startsWith("words and music:");
    }

    private static String extractHeading(String value) {
        if (value == null) return null;

        String s = value.trim();

        if ((s.startsWith("[") && s.endsWith("]"))
                || (s.startsWith("<") && s.endsWith(">"))
                || (s.startsWith("*") && s.endsWith("*"))) {
            s = s.substring(1, s.length() - 1).trim();
        }

        String normalized = s.toLowerCase(Locale.ROOT)
                .replaceAll("[_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[.!:]+$", "")
                .trim();

        if (normalized.matches(
                "^(intro|instrumental|interlude|outro|"
                + "verse\\s*[0-9ivx]*|chorus\\s*[0-9ivx]*|"
                + "last chorus|final chorus|bridge|refrain|"
                + "solo|break|ending|pre chorus|post chorus|"
                + "repeat|coda)$")) {
            return normalized.toUpperCase(Locale.ROOT);
        }

        return null;
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

    private static boolean isControlToken(String s) {
        if (s == null || s.trim().isEmpty()) return true;

        String v = s.trim().replaceAll("\\s+", "");

        return v.matches("(?i)0{2,}")
                || v.matches("(?i)0x0{2,}")
                || s.matches("[\\p{Cntrl}\\s]+");
    }

    private static String removeLabel(String value, String label) {
        if (value == null || value.length() < label.length()) {
            return "";
        }

        return value.substring(label.length()).trim();
    }

    private static String decode(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);

        if (value.indexOf('\uFFFD') >= 0) {
            value = new String(bytes, Charset.forName("ISO-8859-1"));
        }

        return value;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static final class LyricBreakState {
        boolean pendingLineBreak;
        boolean pendingStanzaBreak;
        boolean pendingSpace;
    }

    private static final class LyricEvent {
        final long tick;
        String text;
        final int sequence;
        final boolean section;

        boolean stanzaBreak;
        boolean lineBreak;

        final boolean spaceBefore;
        final boolean spaceAfter;

        boolean inferredSpaceBefore;

        int line;
        int stanza;

        LyricEvent(
                long tick,
                String text,
                int sequence,
                boolean lineBreak,
                boolean section,
                boolean stanzaBreak,
                boolean spaceBefore,
                boolean spaceAfter,
                boolean unused) {

            this.tick = tick;
            this.text = text;
            this.sequence = sequence;
            this.lineBreak = lineBreak;
            this.section = section;
            this.stanzaBreak = stanzaBreak;
            this.spaceBefore = spaceBefore;
            this.spaceAfter = spaceAfter;
            this.inferredSpaceBefore = false;
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

    private static final class FullResult {
        final SpannableStringBuilder text;
        final int activeLineStart;

        FullResult(SpannableStringBuilder text, int activeLineStart) {
            this.text = text;
            this.activeLineStart = activeLineStart;
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
            return data[position++] & 255;
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

        String ascii(int count) throws IOException {
            if (count < 0 || count > remaining()) {
                throw new IOException("Invalid MIDI chunk");
            }

            String s = new String(
                    data, position, count, StandardCharsets.US_ASCII);

            position += count;
            return s;
        }

        byte[] bytes(int count) throws IOException {
            if (count < 0 || count > remaining()) {
                throw new IOException("Invalid MIDI event");
            }

            byte[] b = new byte[count];
            System.arraycopy(data, position, b, 0, count);
            position += count;
            return b;
        }

        void skip(int count) throws IOException {
            if (count < 0 || count > remaining()) {
                throw new IOException("Invalid MIDI length");
            }
            position += count;
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

                next = data[position++] & 255;
                value = (value << 7) | (next & 127);

            } while ((next & 128) != 0);

            return value;
        }
    }
}
