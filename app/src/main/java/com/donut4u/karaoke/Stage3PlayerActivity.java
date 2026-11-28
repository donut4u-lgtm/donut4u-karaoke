
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Stage3MidiLyrics {

private static final int ACTIVE_GREEN = Color.rgb(0, 255, 100);
private static final long DEFAULT_TEMPO = 500000L;
private static final int MAX_VISIBLE_LINES = 5;
private static final int MAX_LINE_CHARACTERS = 36;
private static final int MAX_LINE_EVENTS = 16;

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
if (!e.section && !e.text.trim().isEmpty()) return true;
}
return false;
}

/*
 * Converts a matched external LRC file into the same timed lyric
 * event structure used by the MIDI lyric renderer.
 *
 * The MIDI tempo map and PPQ are retained so LRC timestamps
 * synchronize with the MIDI player's tick position.
 */
public Stage3MidiLyrics withLrcText(String lrcText) {

if (lrcText == null || lrcText.trim().isEmpty()) {
return this;
}

List<LyricEvent> parsed = new ArrayList<>();

String lrcTitle = "";
String lrcSinger = "";
long offsetMillis = 0L;
int sequence = 0;
int lineNumber = 0;

Pattern timestampPattern = Pattern.compile(
"\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

String[] lines = lrcText
.replace("\r\n", "\n")
.replace('\r', '\n')
.split("\n");

for (String originalLine : lines) {

if (originalLine == null) continue;

String line = originalLine.trim();
if (line.isEmpty()) continue;

String lower = line.toLowerCase(Locale.ROOT);

if (lower.startsWith("[ti:")) {
lrcTitle = metadataValue(line);
continue;
}

if (lower.startsWith("[ar:")) {
lrcSinger = metadataValue(line);
continue;
}

if (lower.startsWith("[offset:")) {
String value = metadataValue(line);
try {
offsetMillis = Long.parseLong(value.trim());
} catch (Exception ignored) {
offsetMillis = 0L;
}
continue;
}

Matcher matcher = timestampPattern.matcher(line);

List<Long> timestamps = new ArrayList<>();
int lastTimestampEnd = -1;

while (matcher.find()) {

try {
long minutes = Long.parseLong(matcher.group(1));
long seconds = Long.parseLong(matcher.group(2));

String fractionText = matcher.group(3);
long fraction = 0L;

if (fractionText != null) {
if (fractionText.length() == 1) {
fraction = Long.parseLong(fractionText) * 100L;
} else if (fractionText.length() == 2) {
fraction = Long.parseLong(fractionText) * 10L;
} else {
fraction = Long.parseLong(
fractionText.substring(0, 3));
}
}

long millis =
(minutes * 60000L)
+ (seconds * 1000L)
+ fraction;

millis = Math.max(0L, millis + offsetMillis);
timestamps.add(millis);
lastTimestampEnd = matcher.end();

} catch (Exception ignored) {
}
}

if (timestamps.isEmpty()) {
continue;
}

String lyricText = line.substring(
Math.max(0, lastTimestampEnd)).trim();

if (lyricText.isEmpty()) {
continue;
}

if (lyricText.startsWith("[")
&& lyricText.endsWith("]")) {
String heading = extractHeading(lyricText);
if (heading != null) {
continue;
}
}

if (isCredit(lyricText)
|| isNonLyricTag(lyricText)
|| isControlToken(lyricText)) {
continue;
}

for (Long timestamp : timestamps) {

if (timestamp == null) continue;

long tick = millisToTick(timestamp);

parsed.add(new LyricEvent(
tick,
lyricText,
sequence++,
true,
false,
false,
false,
false));
}
lineNumber++;
}

if (parsed.isEmpty()) {
return this;
}

Collections.sort(parsed, new Comparator<LyricEvent>() {
@Override
public int compare(LyricEvent a, LyricEvent b) {
int byTick = Long.compare(a.tick, b.tick);
return byTick != 0
? byTick
: Integer.compare(a.sequence, b.sequence);
}
});

int line = 0;
int stanza = 0;

for (int i = 0; i < parsed.size(); i++) {

LyricEvent event = parsed.get(i);

if (i > 0) {
LyricEvent previous = parsed.get(i - 1);

if (event.tick != previous.tick
|| !event.text.equals(previous.text)) {
line++;
}
}

event.line = line;
event.stanza = stanza;
}

List<TempoEvent> copiedTempos =
new ArrayList<>(tempos);

String finalTitle = !lrcTitle.isEmpty()
? lrcTitle : title;

String finalSinger = !lrcSinger.isEmpty()
? lrcSinger : singer;

return new Stage3MidiLyrics(
parsed,
copiedTempos,
finalTitle,
finalSinger,
composer,
ppq,
false);
}

private static String metadataValue(String line) {

if (line == null) return "";

int colon = line.indexOf(':');
int close = line.lastIndexOf(']');

if (colon < 0) return "";

if (close <= colon) {
return line.substring(colon + 1).trim();
}

return line.substring(colon + 1, close).trim();
}

private long millisToTick(long millis) {

long targetMillis = Math.max(0L, millis);

long low = 0L;
long high = Math.max(1000000L, (long) ppq * 10000L);

while (tickToMillis(high) < targetMillis
&& high < Long.MAX_VALUE / 4L) {
high *= 2L;
}

while (low < high) {

long middle = low + ((high - low) >>> 1);

if (tickToMillis(middle) < targetMillis) {
low = middle + 1L;
} else {
high = middle;
}
}

return low;
}

public String getEditableText() {

StringBuilder result = new StringBuilder();
int previousLine = Integer.MIN_VALUE;
int previousStanza = Integer.MIN_VALUE;
LyricEvent previous = null;

for (LyricEvent e : events) {

if (e.section || e.text.trim().isEmpty()) continue;

if (e.line != previousLine || e.stanza != previousStanza) {

if (result.length() > 0) result.append('\n');

previousLine = e.line;
previousStanza = e.stanza;
previous = null;

} else if (hasWordBoundaryBefore(e, previous)) {

result.append(' ');
}

result.append(e.text);
previous = e;
}

return result.toString();
}

public boolean applyEditedText(String editedText) {

if (editedText == null || events.isEmpty()) return false;

String normalized = editedText
.replace("\r\n", "\n")
.replace('\r', '\n');

String[] lines = normalized.split("\n", -1);

List<List<LyricEvent>> groupedLines = new ArrayList<>();
List<LyricEvent> current = null;

int oldLine = Integer.MIN_VALUE;
int oldStanza = Integer.MIN_VALUE;

for (LyricEvent e : events) {

if (e.section || e.text.trim().isEmpty()) continue;

if (current == null
|| e.line != oldLine
|| e.stanza != oldStanza) {

current = new ArrayList<>();
groupedLines.add(current);
oldLine = e.line;
oldStanza = e.stanza;
}

current.add(e);
}

if (lines.length != groupedLines.size()) return false;

List<String[]> words = new ArrayList<>();

for (String line : lines) {

String clean = line.trim();

if (clean.isEmpty()) return false;

words.add(clean.split("\\s+"));
}

for (int lineIndex = 0;
lineIndex < groupedLines.size();
lineIndex++) {

List<LyricEvent> lineEvents = groupedLines.get(lineIndex);
String[] lineWords = words.get(lineIndex);

if (lineEvents.isEmpty()) return false;

List<List<LyricEvent>> groups = new ArrayList<>();
List<LyricEvent> group = null;

for (int i = 0; i < lineEvents.size(); i++) {

LyricEvent e = lineEvents.get(i);
LyricEvent previous = i > 0
? lineEvents.get(i - 1) : null;

if (group == null
|| hasWordBoundaryBefore(e, previous)) {

group = new ArrayList<>();
groups.add(group);
}

group.add(e);
}

List<String> assignments = new ArrayList<>();

for (int i = 0; i < lineEvents.size(); i++) {
assignments.add("");
}

for (int i = 0; i < lineWords.length; i++) {

int groupIndex = Math.min(
groups.size() - 1,
(int) (((long) i * groups.size()) / lineWords.length));

int eventIndex = lineEvents.indexOf(
groups.get(groupIndex).get(0));

if (eventIndex < 0) return false;

String existing = assignments.get(eventIndex);

if (!existing.isEmpty()) existing += " ";

assignments.set(
eventIndex,
existing + lineWords[i]);
}

for (int i = 0; i < lineEvents.size(); i++) {
lineEvents.get(i).text = assignments.get(i);
}
}

return true;
}

private static boolean hasWordBoundaryBefore(
LyricEvent event,
LyricEvent previous) {

return event.spaceBefore
|| (previous != null && previous.spaceAfter);
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
long previousTick = 0;
long tempo = DEFAULT_TEMPO;
double micros = 0;

for (TempoEvent e : tempos) {

if (e.tick > target) break;

if (e.tick == 0) {
tempo = e.micros;
continue;
}

micros +=
((double) (e.tick - previousTick) * tempo) / ppq;

previousTick = e.tick;
tempo = e.micros;
}

micros +=
((double) (target - previousTick) * tempo) / ppq;

return Math.max(0, (long) (micros / 1000.0));
}

public String textAtTick(long tick) {
return styledTextAtTick(tick).toString();
}

public CharSequence styledTextAtTick(long tick) {
return build(tick, Long.MAX_VALUE).text;
}

public CharSequence styledTextAtTick(
long tick,
long durationTicks) {
return build(tick, durationTicks).text;
}

public CharSequence styledFullTextAtTick(long tick) {
return build(tick, Long.MAX_VALUE).text;
}

public CharSequence styledFullTextAtTick(
long tick,
long durationTicks) {
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

return i < 0 || events.get(i).section
? -1 : events.get(i).line;
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

if (e.text.trim().isEmpty() || e.line == line) continue;

if (b.length() > 0) break;

line = e.line;
b.append(e.text);
}

return b.toString().trim();
}

public long nextLyricTickAfter(long tick) {

for (LyricEvent e : events) {

if (!e.section
&& !e.text.trim().isEmpty()
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

long gap = events.get(next).tick - current.tick;

return gap >= ppq * 3L
&& tick >= current.tick + ppq / 2L
&& tick < events.get(next).tick - ppq / 2L;
}

public int getCountdownNumberAtTick(long tick) {

long now = Math.max(0L, tick);
long beat = Math.max(1L, ppq);
long window = beat * 5L;

int first = firstLyricIndex();

if (first < 0) return 0;

int second = secondCountdownLyricIndex(first);

if (insideCountdown(first, now, window)) {
return countdownValue(events.get(first).tick - now, beat);
}

if (second >= 0 && insideCountdown(second, now, window)) {
return countdownValue(events.get(second).tick - now, beat);
}

return 0;
}

private boolean insideCountdown(
int index,
long now,
long window) {

if (index < 0 || index >= events.size()) return false;

long remaining = events.get(index).tick - now;

return remaining > 0 && remaining <= window;
}

private int countdownValue(long remaining, long beat) {

return (int) Math.max(
1L,
Math.min(5L, (remaining + beat - 1L) / beat));
}

private int secondCountdownLyricIndex(int first) {

int marked = -1;

for (int i = first + 1; i < events.size(); i++) {

LyricEvent e = events.get(i);

if (!e.section) continue;

String section = e.text.toUpperCase(Locale.ROOT);

if (section.contains("INTERLUDE")
|| section.contains("CHORUS")) {
marked = i;
break;
}
}

if (marked >= 0) return nextLyricIndex(marked + 1);

int previous = first;

for (int i = first + 1; i < events.size(); i++) {

LyricEvent e = events.get(i);

if (e.section || e.text.trim().isEmpty()) continue;

if (e.tick - events.get(previous).tick >= ppq * 5L
|| hasInstrumentalSectionBetween(previous, i)) {
return i;
}

previous = i;
}

return -1;
}

private boolean hasInstrumentalSectionBetween(
int start,
int end) {

for (int i = start + 1;
i < end && i < events.size();
i++) {

LyricEvent e = events.get(i);

if (e.section && isInstrumental(e.text)) return true;
}

return false;
}

private int firstLyricIndex() {

for (int i = 0; i < events.size(); i++) {

LyricEvent e = events.get(i);

if (!e.section && !e.text.trim().isEmpty()) return i;
}

return -1;
}

private int nextLyricIndex(int start) {

for (int i = Math.max(0, start);
i < events.size();
i++) {

LyricEvent e = events.get(i);

if (!e.section && !e.text.trim().isEmpty()) return i;
}

return -1;
}

private int activeIndex(long tick) {

long target = Math.max(0, tick);

int low = 0;
int high = events.size() - 1;
int result = -1;

while (low <= high) {

int mid = low + (high - low) / 2;

if (events.get(mid).tick <= target) {
result = mid;
low = mid + 1;
} else {
high = mid - 1;
}
}

while (result >= 0) {

LyricEvent e = events.get(result);

if (e.section) return result;

if (!e.text.trim().isEmpty()) return result;

result--;
}

return -1;
}

private FullResult build(long tick, long durationTicks) {

SpannableStringBuilder output =
new SpannableStringBuilder();

if (events.isEmpty()) {

output.append(
"No embedded lyrics found in this MIDI/KAR file.");

return new FullResult(output, 0);
}

int active = activeIndex(tick);

if (active < 0) {
active = firstLyricIndex();
} else if (events.get(active).section) {
active = nextLyricIndex(active + 1);
}

if (active < 0) return new FullResult(output, 0);

LyricEvent activeEvent = events.get(active);

int stanza = activeEvent.stanza;
int firstStanzaLine = Integer.MAX_VALUE;
int lastStanzaLine = Integer.MIN_VALUE;

for (LyricEvent e : events) {

if (!e.section
&& !e.text.trim().isEmpty()
&& e.stanza == stanza) {

firstStanzaLine = Math.min(firstStanzaLine, e.line);
lastStanzaLine = Math.max(lastStanzaLine, e.line);
}
}

if (firstStanzaLine == Integer.MAX_VALUE) {
return new FullResult(output, 0);
}

int firstLine = Math.max(
firstStanzaLine,
activeEvent.line - 2);

int lastLine = Math.min(
lastStanzaLine,
firstLine + MAX_VISIBLE_LINES - 1);

firstLine = Math.max(
firstStanzaLine,
lastLine - MAX_VISIBLE_LINES + 1);

int[] starts = new int[events.size()];
int[] ends = new int[events.size()];

for (int i = 0; i < events.size(); i++) {
starts[i] = -1;
ends[i] = -1;
}

int previousLine = Integer.MIN_VALUE;
int previousEvent = -1;
int activeLineStart = 0;

for (int i = 0; i < events.size(); i++) {

LyricEvent e = events.get(i);

if (e.section
|| e.text.trim().isEmpty()
|| e.stanza != stanza
|| e.line < firstLine
|| e.line > lastLine) {
continue;
}

if (previousLine != e.line) {

if (output.length() > 0) output.append('\n');

previousLine = e.line;
previousEvent = -1;
}

if (previousEvent >= 0) {

LyricEvent previous = events.get(previousEvent);

if (hasWordBoundaryBefore(e, previous)) {
output.append(' ');
}
}

int start = output.length();

output.append(e.text);

int end = output.length();

starts[i] = start;
ends[i] = end;

if (e.line == activeEvent.line
&& activeLineStart == 0) {
activeLineStart = start;
}

previousEvent = i;
}

if (output.length() == 0) {
return new FullResult(output, 0);
}

boolean allowHighlight =
durationTicks <= 0 || tick < durationTicks;

if (allowHighlight) {

int activeStart = -1;
int activeEnd = -1;

if (starts[active] >= 0
&& ends[active] > starts[active]) {

int firstWord = active;
int lastWord = active;
long activeTick = events.get(active).tick;

while (firstWord > 0
&& events.get(firstWord - 1).tick == activeTick
&& starts[firstWord - 1] >= 0) {
firstWord--;
}

while (lastWord + 1 < events.size()
&& events.get(lastWord + 1).tick == activeTick
&& starts[lastWord + 1] >= 0) {
lastWord++;
}

while (firstWord > 0) {

int previousIndex = firstWord - 1;

LyricEvent previous = events.get(previousIndex);
LyricEvent current = events.get(firstWord);

if (previous.section
|| current.section
|| previous.stanza != current.stanza
|| previous.line != current.line
|| starts[previousIndex] < 0
|| ends[previousIndex] <= starts[previousIndex]
|| hasWordBoundaryBefore(current, previous)) {
break;
}

firstWord--;
}

while (lastWord + 1 < events.size()) {

int nextIndex = lastWord + 1;

LyricEvent current = events.get(lastWord);
LyricEvent next = events.get(nextIndex);

if (current.section
|| next.section
|| current.stanza != next.stanza
|| current.line != next.line
|| starts[nextIndex] < 0
|| ends[nextIndex] <= starts[nextIndex]
|| hasWordBoundaryBefore(next, current)) {
break;
}

lastWord++;
}

activeStart = starts[firstWord];
activeEnd = ends[lastWord];
}

if (activeStart >= 0 && activeEnd > activeStart) {

output.setSpan(
new ForegroundColorSpan(ACTIVE_GREEN),
activeStart,
activeEnd,
Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
}
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

if (headerLength < 6
|| headerLength > r.remaining()) {
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

LyricBreakState textBreak = new LyricBreakState();
LyricBreakState lyricBreak = new LyricBreakState();

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

boolean separatorOnly =
(type == 0x01 || type == 0x05)
&& containsLyricSeparator(value)
&& trimmed.isEmpty();

boolean whitespaceOnly =
(type == 0x01 || type == 0x05)
&& !value.isEmpty()
&& trimmed.isEmpty();

if (trimmed.isEmpty()
&& !separatorOnly
&& !whitespaceOnly) {
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
tick,
heading,
sequence++,
false,
true,
false,
false,
false));
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
} else if (titleField >= 3 && composer.isEmpty()) {

String credit = field.toLowerCase(Locale.ROOT);

if (credit.contains("music")
|| credit.contains("word")
|| credit.contains("copyright")) {
composer = field;
}
}
}

} else if (lower.startsWith("@i")) {

String info = trimmed.substring(2).trim();
String lowerInfo = info.toLowerCase(Locale.ROOT);

if (composer.isEmpty()
&& (lowerInfo.contains("music")
|| lowerInfo.contains("word"))) {
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

if (!separatorOnly
&& !whitespaceOnly
&& !isLikelyLyricText(trimmed)) {
continue;
}

LyricBreakState state = type == 0x05
? lyricBreak : textBreak;

List<LyricEvent> parsed = parseLyricEvents(
value, tick, sequence, state);

sequence += parsed.size();

for (LyricEvent e : parsed) {

if (e.stanzaBreak) explicitStanzas = true;

if (type == 0x01) {
textEvents.add(e);
} else {
lyricEvents.add(e);
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

int textCharacterCount = 0;
int lyricCharacterCount = 0;
int textEventCount = 0;
int lyricEventCount = 0;

for (LyricEvent event : textEvents) {

if (event == null || event.section || event.text == null) {
continue;
}

String value = event.text.trim();

if (!value.isEmpty()) {
textCharacterCount += value.length();
textEventCount++;
}
}

for (LyricEvent event : lyricEvents) {

if (event == null || event.section || event.text == null) {
continue;
}

String value = event.text.trim();

if (!value.isEmpty()) {
lyricCharacterCount += value.length();
lyricEventCount++;
}
}

List<LyricEvent> chosen;

if (lyricCharacterCount > textCharacterCount) {
chosen = lyricEvents;
} else if (textCharacterCount > lyricCharacterCount) {
chosen = textEvents;
} else if (lyricEventCount > textEventCount) {
chosen = lyricEvents;
} else if (textEventCount > 0) {
chosen = textEvents;
} else {
chosen = lyricEvents;
}

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

inferWordBoundaries(chosen, ppq);
applyAutomaticFormatting(chosen);

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
chosen,
tempos,
title,
singer,
composer,
ppq,
explicitStanzas);

} catch (Exception ignored) {

return empty();
}
}

/*
 * Adds a word boundary between separate timed lyric events.
 *
 * Events at the same MIDI tick are kept together because they
 * may be fragments belonging to one simultaneous lyric group.
 *
 * Existing explicit spaces and line/stanza separators are kept.
 */
private static void inferWordBoundaries(
List<LyricEvent> list,
int ppq) {

if (list == null || list.isEmpty()) {
return;
}

LyricEvent previous = null;

for (LyricEvent current : list) {

if (current == null
|| current.section
|| current.text == null
|| current.text.trim().isEmpty()) {

previous = null;
continue;
}

if (current.lineBreak || current.stanzaBreak) {
previous = null;
}

if (previous != null
&& current.tick != previous.tick
&& !current.spaceBefore
&& !previous.spaceAfter) {

current.spaceBefore = true;
}

previous = current;
}
}

private static void applyAutomaticFormatting(
List<LyricEvent> list) {

if (list == null || list.isEmpty()) return;

int characters = 0;
int eventCount = 0;
LyricEvent previous = null;

for (LyricEvent current : list) {

if (current.section) {

previous = null;
characters = 0;
eventCount = 0;
continue;
}

if (current.lineBreak || current.stanzaBreak) {

previous = null;
characters = 0;
eventCount = 0;
}

int extraSpace =
hasWordBoundaryBefore(current, previous) ? 1 : 0;

int nextLength =
characters + extraSpace + current.text.length();

if (previous != null
&& !current.lineBreak
&& !current.stanzaBreak
&& hasWordBoundaryBefore(current, previous)
&& (nextLength > MAX_LINE_CHARACTERS
|| eventCount >= MAX_LINE_EVENTS)) {

current.lineBreak = true;
characters = 0;
eventCount = 0;
extraSpace = 0;
}

characters += extraSpace + current.text.length();
eventCount++;
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

if (lower.matches("^(gm|gs|xg)\\s*[:=].*$")) return false;

if (lower.matches(
"^[0-9]+([.,][0-9]+)?\\s*(bpm|ms|hz)$")) {
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
LyricBreakState state) {

List<LyricEvent> result = new ArrayList<>();

if (value == null || state == null) return result;

String source = value.replace("\u0000", "");

StringBuilder fragment = new StringBuilder();

boolean pendingLine = state.pendingLineBreak;
boolean pendingStanza = state.pendingStanzaBreak;
boolean pendingSpace = state.pendingSpace;

int sequence = firstSequence;

for (int i = 0; i < source.length(); i++) {

char c = source.charAt(i);

if (c == '/' || c == '\\' || c == '\r' || c == '\n') {

addFragment(
result,
fragment.toString(),
tick,
sequence++,
pendingLine,
pendingStanza,
pendingSpace);

String clean = cleanLyricFragment(fragment.toString());

if (!clean.isEmpty() && !isControlToken(clean)) {

pendingLine = false;
pendingStanza = false;
pendingSpace = false;

} else if (!fragment.toString().isEmpty()
&& fragment.toString().trim().isEmpty()) {

pendingSpace = true;
}

fragment.setLength(0);
pendingLine = true;

if (c == '\\' || c == '\n') {
pendingStanza = true;
}

if (c == '\r'
&& i + 1 < source.length()
&& source.charAt(i + 1) == '\n') {

i++;
pendingStanza = true;
}

} else {

fragment.append(c);
}
}

String raw = fragment.toString();
String clean = cleanLyricFragment(raw);

if (!clean.isEmpty() && !isControlToken(clean)) {

result.add(new LyricEvent(
tick,
clean,
sequence,
pendingLine,
false,
pendingStanza,
pendingSpace || hasLeadingSpace(raw),
hasTrailingSpace(raw)));

pendingLine = false;
pendingStanza = false;
pendingSpace = false;

} else if (!raw.isEmpty() && raw.trim().isEmpty()) {

pendingSpace = true;
}

state.pendingLineBreak = pendingLine;
state.pendingStanzaBreak = pendingStanza;
state.pendingSpace = pendingSpace;

return result;
}

private static void addFragment(
List<LyricEvent> result,
String raw,
long tick,
int sequence,
boolean lineBreak,
boolean stanzaBreak,
boolean pendingSpace) {

String clean = cleanLyricFragment(raw);

if (clean.isEmpty() || isControlToken(clean)) return;

result.add(new LyricEvent(
tick,
clean,
sequence,
lineBreak,
false,
stanzaBreak,
pendingSpace || hasLeadingSpace(raw),
hasTrailingSpace(raw)));
}

private static boolean hasLeadingSpace(String value) {

return value != null
&& !value.isEmpty()
&& Character.isWhitespace(value.charAt(0));
}

private static boolean hasTrailingSpace(String value) {

return value != null
&& !value.isEmpty()
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
boolean spaceBefore;
final boolean spaceAfter;
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
boolean spaceAfter) {

this.tick = tick;
this.text = text;
this.sequence = sequence;
this.lineBreak = lineBreak;
this.section = section;
this.stanzaBreak = stanzaBreak;
this.spaceBefore = spaceBefore;
this.spaceAfter = spaceAfter;
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

FullResult(
SpannableStringBuilder text,
int activeLineStart) {

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
data,
position,
count,
StandardCharsets.US_ASCII);

position += count;

return s;
}

byte[] bytes(int count) throws IOException {

if (count < 0 || count > remaining()) {
throw new IOException("Invalid MIDI event");
}

byte[] b = new byte[count];

System.arraycopy(
data,
position,
b,
0,
count);

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

if (position >= end
|| position >= data.length
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
