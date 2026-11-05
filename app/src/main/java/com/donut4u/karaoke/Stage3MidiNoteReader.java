
package com.donut4u.karaoke;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Stage3MidiNoteReader {

    private static final int MIN_MELODY_NOTE = 48;
    private static final int MAX_MELODY_NOTE = 88;
    private static final int PERCUSSION_CHANNEL = 9;

    public static final class NoteSpan {

        public final int pitch;
        public final int channel;
        public final long startTick;
        public final long endTick;
        public final int trackIndex;

        private NoteSpan(
                int pitch,
                int channel,
                long startTick,
                long endTick,
                int trackIndex) {

            this.pitch = pitch;
            this.channel = channel;
            this.startTick = startTick;
            this.endTick = Math.max(startTick + 1, endTick);
            this.trackIndex = trackIndex;
        }

        public boolean isActiveAt(long tick) {
            return tick >= startTick && tick < endTick;
        }
    }

    private static final class TrackData {

        final int index;
        final List<NoteSpan> notes = new ArrayList<>();

        long endTick;
        double selectionScore;

        TrackData(int index) {
            this.index = index;
        }
    }

    private final List<NoteSpan> melodyNotes;

    private final int selectedTrackIndex;
    private final int ticksPerQuarterNote;
    private final long durationTicks;

    private Stage3MidiNoteReader(
            List<NoteSpan> notes,
            int selectedTrack,
            int ppq,
            long duration) {

        melodyNotes = notes;
        selectedTrackIndex = selectedTrack;
        ticksPerQuarterNote = ppq;
        durationTicks = duration;
    }

    public static Stage3MidiNoteReader read(File file)
            throws IOException {

        if (file == null || !file.isFile()) {
            throw new IOException("MIDI file does not exist.");
        }

        byte[] data = readAll(file);

        if (data.length < 14
                || data[0] != 'M'
                || data[1] != 'T'
                || data[2] != 'h'
                || data[3] != 'd') {

            throw new IOException("Invalid MIDI header.");
        }

        Cursor cursor = new Cursor(data);

        cursor.position = 4;

        long headerLength = cursor.readUInt32();

        if (headerLength < 6
                || headerLength > data.length - 8) {

            throw new IOException("Invalid MIDI header length.");
        }

        int format = cursor.readUInt16();
        int trackCount = cursor.readUInt16();
        int division = cursor.readUInt16();

        if (format > 2 || trackCount <= 0) {
            throw new IOException("Unsupported MIDI format.");
        }

        if ((division & 0x8000) != 0 || division <= 0) {
            throw new IOException(
                    "SMPTE MIDI timing is not supported.");
        }

        int ppq = division;

        cursor.position = (int) (8 + headerLength);

        List<TrackData> tracks = new ArrayList<>();

        long totalDuration = 0L;

        for (int i = 0; i < trackCount; i++) {

            if (cursor.position + 8 > data.length) {
                break;
            }

            String chunk = cursor.readFourCC();
            long chunkLength = cursor.readUInt32();

            if (chunkLength > Integer.MAX_VALUE
                    || chunkLength > data.length - cursor.position) {

                throw new IOException("Invalid MIDI track length.");
            }

            int trackEnd =
                    cursor.position + (int) chunkLength;

            if (!"MTrk".equals(chunk)) {
                cursor.position = trackEnd;
                continue;
            }

            TrackData track = parseTrack(
                    data,
                    cursor.position,
                    trackEnd,
                    i);

            tracks.add(track);

            totalDuration = Math.max(
                    totalDuration,
                    track.endTick);

            cursor.position = trackEnd;
        }

        if (tracks.isEmpty()) {
            throw new IOException("No MIDI tracks were found.");
        }

        TrackData selected = chooseMelodyTrack(tracks);

        if (selected == null || selected.notes.isEmpty()) {
            throw new IOException(
                    "No playable melody notes were found.");
        }

        List<NoteSpan> result =
                new ArrayList<>(selected.notes);

        return new Stage3MidiNoteReader(
                result,
                selected.index,
                ppq,
                totalDuration);
    }

    private static byte[] readAll(File file)
            throws IOException {

        try (FileInputStream input =
                     new FileInputStream(file);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];

            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            return output.toByteArray();
        }
    }

    private static TrackData parseTrack(
            byte[] data,
            int start,
            int end,
            int trackIndex) throws IOException {

        TrackData track = new TrackData(trackIndex);

        Cursor cursor = new Cursor(data);
        cursor.position = start;

        Map<Integer, ArrayDeque<Long>> active =
                new HashMap<>();

        int runningStatus = 0;
        long tick = 0L;

        while (cursor.position < end) {

            long delta = cursor.readVariableLength(end);

            if (delta < 0L
                    || Long.MAX_VALUE - tick < delta) {

                throw new IOException("Invalid MIDI delta time.");
            }

            tick += delta;

            if (cursor.position >= end) {
                break;
            }

            int first = cursor.readByte(end);

            int status;
            int firstData = -1;

            if (first < 0x80) {

                if (runningStatus == 0) {
                    throw new IOException(
                            "Invalid MIDI running status.");
                }

                status = runningStatus;
                firstData = first;

            } else {

                status = first;

                if (status >= 0x80 && status <= 0xEF) {
                    runningStatus = status;
                } else {
                    runningStatus = 0;
                }
            }

            if (status == 0xFF) {

                int metaType = cursor.readByte(end);

                long length =
                        cursor.readVariableLength(end);

                cursor.skip(length, end);

                if (metaType == 0x2F) {
                    track.endTick = Math.max(
                            track.endTick, tick);
                    break;
                }

                continue;
            }

            if (status == 0xF0 || status == 0xF7) {

                long length =
                        cursor.readVariableLength(end);

                cursor.skip(length, end);

                continue;
            }

            if (status >= 0xF0) {
                throw new IOException(
                        "Unsupported MIDI system event.");
            }

            int command = status & 0xF0;
            int channel = status & 0x0F;

            int dataLength =
                    command == 0xC0 || command == 0xD0
                            ? 1 : 2;

            int data1 = firstData >= 0
                    ? firstData
                    : cursor.readByte(end);

            int data2 = dataLength == 2
                    ? cursor.readByte(end)
                    : 0;

            if (command == 0x90 && data2 > 0) {

                if (channel == PERCUSSION_CHANNEL) {
                    continue;
                }

                int key = channel * 128 + data1;

                ArrayDeque<Long> starts =
                        active.get(key);

                if (starts == null) {
                    starts = new ArrayDeque<>();
                    active.put(key, starts);
                }

                starts.addLast(tick);

            } else if (command == 0x80
                    || (command == 0x90 && data2 == 0)) {

                if (channel == PERCUSSION_CHANNEL) {
                    continue;
                }

                int key = channel * 128 + data1;

                ArrayDeque<Long> starts =
                        active.get(key);

                if (starts == null || starts.isEmpty()) {
                    continue;
                }

                long noteStart = starts.removeFirst();

                track.notes.add(
                        new NoteSpan(
                                data1,
                                channel,
                                noteStart,
                                tick,
                                trackIndex));
            }
        }

        track.endTick = Math.max(track.endTick, tick);

        // Close notes that have no matching note-off event.
        for (Map.Entry<Integer, ArrayDeque<Long>> entry
                : active.entrySet()) {

            int key = entry.getKey();

            int channel = key / 128;
            int pitch = key % 128;

            ArrayDeque<Long> starts = entry.getValue();

            while (!starts.isEmpty()) {

                long noteStart = starts.removeFirst();

                long noteEnd = Math.max(
                        noteStart + 1,
                        track.endTick);

                track.notes.add(
                        new NoteSpan(
                                pitch,
                                channel,
                                noteStart,
                                noteEnd,
                                trackIndex));
            }
        }

        calculateTrackScore(track);

        return track;
    }

    private static void calculateTrackScore(
            TrackData track) {

        int melodyRangeCount = 0;
        int otherCount = 0;

        long totalDuration = 0L;
        long pitchSum = 0L;

        for (NoteSpan note : track.notes) {

            long length =
                    Math.max(1L, note.endTick - note.startTick);

            totalDuration += Math.min(length, 2000L);
            pitchSum += note.pitch;

            if (note.pitch >= MIN_MELODY_NOTE
                    && note.pitch <= MAX_MELODY_NOTE) {

                melodyRangeCount++;

            } else {
                otherCount++;
            }
        }

        if (track.notes.isEmpty()) {
            track.selectionScore = 0.0;
            return;
        }

        double averagePitch =
                pitchSum / (double) track.notes.size();

        track.selectionScore =
                melodyRangeCount * 5.0
                        + Math.min(totalDuration / 100.0, 100.0)
                        + averagePitch * 0.15
                        - otherCount * 0.5;
    }

    private static TrackData chooseMelodyTrack(
            List<TrackData> tracks) {

        TrackData best = null;

        for (TrackData track : tracks) {

            if (track.notes.isEmpty()) {
                continue;
            }

            if (best == null
                    || track.selectionScore
                    > best.selectionScore) {

                best = track;
            }
        }

        return best;
    }

    /**
     * Returns the expected melody MIDI note at this tick.
     * Returns -1 when there is no active note.
     *
     * If notes overlap, the highest active melody-range
     * note is selected as a practical melody estimate.
     */
    public int getMelodyNoteAtTick(long tick) {

        if (tick < 0L || melodyNotes.isEmpty()) {
            return -1;
        }

        int bestPitch = -1;

        for (NoteSpan note : melodyNotes) {

            if (!note.isActiveAt(tick)) {
                continue;
            }

            if (note.pitch < MIN_MELODY_NOTE
                    || note.pitch > MAX_MELODY_NOTE) {
                continue;
            }

            if (note.pitch > bestPitch) {
                bestPitch = note.pitch;
            }
        }

        if (bestPitch >= 0) {
            return bestPitch;
        }

        // Fallback for songs whose melody lies outside
        // the usual singing range.
        for (NoteSpan note : melodyNotes) {

            if (note.isActiveAt(tick)
                    && note.pitch > bestPitch) {

                bestPitch = note.pitch;
            }
        }

        return bestPitch;
    }

    public int getSelectedTrackIndex() {
        return selectedTrackIndex;
    }

    public int getTicksPerQuarterNote() {
        return ticksPerQuarterNote;
    }

    public long getDurationTicks() {
        return durationTicks;
    }

    public int getNoteCount() {
        return melodyNotes.size();
    }

    public List<NoteSpan> getMelodyNotes() {
        return new ArrayList<>(melodyNotes);
    }

    private static final class Cursor {

        final byte[] data;
        int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        int readByte(int limit) throws IOException {

            if (position >= limit || position >= data.length) {
                throw new IOException("Unexpected end of MIDI data.");
            }

            return data[position++] & 0xFF;
        }

        int readUInt16() throws IOException {

            int a = readByte(data.length);
            int b = readByte(data.length);

            return (a << 8) | b;
        }

        long readUInt32() throws IOException {

            long a = readByte(data.length);
            long b = readByte(data.length);
            long c = readByte(data.length);
            long d = readByte(data.length);

            return (a << 24) | (b << 16) | (c << 8) | d;
        }

        String readFourCC() throws IOException {

            return ""
                    + (char) readByte(data.length)
                    + (char) readByte(data.length)
                    + (char) readByte(data.length)
                    + (char) readByte(data.length);
        }

        long readVariableLength(int limit)
                throws IOException {

            long value = 0L;

            for (int i = 0; i < 4; i++) {

                int b = readByte(limit);

                value = (value << 7) | (b & 0x7F);

                if ((b & 0x80) == 0) {
                    return value;
                }
            }

            throw new IOException(
                    "Invalid MIDI variable-length value.");
        }

        void skip(long count, int limit)
                throws IOException {

            if (count < 0L
                    || count > limit - position) {

                throw new IOException(
                        "MIDI event exceeds track boundary.");
            }

            position += (int) count;
        }
    }
}
