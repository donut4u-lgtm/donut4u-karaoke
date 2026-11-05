package com.donut4u.karaoke;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Reads melody/instrument note events from Standard MIDI Files.
 *
 * Supports MIDI format 0 and 1 with PPQ timing.
 * Percussion channel 10 is excluded from melody candidates.
 *
 * This class does not play audio and does not change the MIDI engine.
 */
public final class Stage3MidiNoteReader {

    private Stage3MidiNoteReader() {
    }

    public static Timeline read(File file) throws IOException {

        if (file == null || !file.isFile()) {
            throw new IOException("MIDI file not found.");
        }

        if (file.length() <= 0 || file.length() > Integer.MAX_VALUE) {
            throw new IOException("Invalid MIDI file size.");
        }

        byte[] data = new byte[(int) file.length()];

        try (FileInputStream input = new FileInputStream(file)) {

            int offset = 0;

            while (offset < data.length) {

                int count = input.read(
                        data,
                        offset,
                        data.length - offset);

                if (count < 0) break;

                offset += count;
            }

            if (offset != data.length) {
                throw new IOException("Could not read the complete MIDI file.");
            }
        }

        return read(data);
    }

    public static Timeline read(byte[] data) throws IOException {

        if (data == null || data.length < 14) {
            throw new IOException("MIDI file is too small.");
        }

        Reader reader = new Reader(data);

        if (!reader.hasBytes(0, 4)
                || reader.readAscii(0, 4).equals("MThd") == false) {

            throw new IOException("This is not a Standard MIDI file.");
        }

        long headerLength = reader.readUnsignedInt(4);

        if (headerLength < 6
                || headerLength > data.length - 8) {

            throw new IOException("Invalid MIDI header.");
        }

        int format = reader.readUnsignedShort(8);
        int trackCount = reader.readUnsignedShort(10);
        int division = reader.readUnsignedShort(12);

        if (format != 0 && format != 1) {
            throw new IOException(
                    "Only MIDI format 0 and format 1 are supported.");
        }

        if (trackCount < 1 || trackCount > 256) {
            throw new IOException("Invalid MIDI track count.");
        }

        if ((division & 0x8000) != 0 || division == 0) {
            throw new IOException(
                    "SMPTE MIDI timing is not supported.");
        }

        int ppq = division;

        int position = (int) (8 + headerLength);

        List<Track> tracks = new ArrayList<>();

        long longestTrack = 0;

        for (int trackIndex = 0;
             trackIndex < trackCount;
             trackIndex++) {

            if (!reader.hasBytes(position, 8)) {
                throw new IOException("MIDI track header is missing.");
            }

            String chunkName = reader.readAscii(position, 4);

            if (!"MTrk".equals(chunkName)) {
                throw new IOException(
                        "Invalid MIDI track at index " + trackIndex);
            }

            long chunkLength = reader.readUnsignedInt(position + 4);

            if (chunkLength > Integer.MAX_VALUE
                    || chunkLength > data.length - position - 8) {

                throw new IOException(
                        "Invalid MIDI track length.");
            }

            int trackStart = position + 8;
            int trackEnd = trackStart + (int) chunkLength;

            Track track = parseTrack(
                    data,
                    trackIndex,
                    trackStart,
                    trackEnd);

            tracks.add(track);

            longestTrack = Math.max(
                    longestTrack,
                    track.getDurationTicks());

            position = trackEnd;
        }

        return new Timeline(
                format,
                ppq,
                longestTrack,
                tracks);
    }

    private static Track parseTrack(
            byte[] data,
            int trackIndex,
            int start,
            int end) throws IOException {

        Reader reader = new Reader(data);

        int position = start;
        int runningStatus = -1;

        long absoluteTick = 0;

        String trackName = "";

        List<NoteEvent> notes = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Deque<OpenNote>[] activeNotes = new Deque[16 * 128];

        while (position < end) {

            ReadValue delta = reader.readVariableLength(
                    position,
                    end);

            absoluteTick += delta.value;
            position = delta.nextPosition;

            if (position >= end) {
                throw new IOException("Incomplete MIDI event.");
            }

            int first = data[position] & 0xFF;

            int status;

            if (first >= 0x80) {

                status = first;
                position++;

            } else {

                if (runningStatus < 0) {
                    throw new IOException(
                            "Invalid MIDI running status.");
                }

                status = runningStatus;
            }

            if (status == 0xFF) {

                runningStatus = -1;

                if (position >= end) {
                    throw new IOException("Incomplete MIDI meta event.");
                }

                int metaType = data[position++] & 0xFF;

                ReadValue length = reader.readVariableLength(
                        position,
                        end);

                position = length.nextPosition;

                int metaLength = length.value;

                if (metaLength < 0
                        || metaLength > end - position) {

                    throw new IOException(
                            "Invalid MIDI meta event length.");
                }

                if (metaType == 0x03 && metaLength > 0) {

                    trackName = new String(
                            data,
                            position,
                            metaLength,
                            java.nio.charset.StandardCharsets.UTF_8);
                }

                position += metaLength;

                if (metaType == 0x2F) {
                    break;
                }

                continue;
            }

            if (status == 0xF0 || status == 0xF7) {

                runningStatus = -1;

                ReadValue length = reader.readVariableLength(
                        position,
                        end);

                position = length.nextPosition;

                if (length.value < 0
                        || length.value > end - position) {

                    throw new IOException(
                            "Invalid MIDI system event length.");
                }

                position += length.value;
                continue;
            }

            if (status < 0x80 || status >= 0xF0) {
                throw new IOException(
                        "Unsupported MIDI event.");
            }

            runningStatus = status;

            int command = status & 0xF0;
            int channel = status & 0x0F;

            int dataLength;

            if (command == 0xC0 || command == 0xD0) {
                dataLength = 1;
            } else {
                dataLength = 2;
            }

            if (position + dataLength > end) {
                throw new IOException(
                        "Incomplete MIDI channel event.");
            }

            int data1 = data[position++] & 0x7F;

            int data2 = 0;

            if (dataLength == 2) {
                data2 = data[position++] & 0x7F;
            }

            /*
             * MIDI channel 10 is percussion.
             * It is not useful as a sung melody reference.
             */
            if (channel == 9) {
                continue;
            }

            if (command == 0x90 && data2 > 0) {

                int key = channel * 128 + data1;

                if (activeNotes[key] == null) {
                    activeNotes[key] = new ArrayDeque<>();
                }

                activeNotes[key].push(
                        new OpenNote(
                                absoluteTick,
                                data1,
                                data2,
                                channel));

            } else if (command == 0x80
                    || (command == 0x90 && data2 == 0)) {

                int key = channel * 128 + data1;

                Deque<OpenNote> stack = activeNotes[key];

                if (stack != null && !stack.isEmpty()) {

                    OpenNote open = stack.pop();

                    long duration = Math.max(
                            0,
                            absoluteTick - open.startTick);

                    notes.add(
                            new NoteEvent(
                                    trackIndex,
                                    channel,
                                    open.pitch,
                                    open.velocity,
                                    open.startTick,
                                    absoluteTick,
                                    duration));
                }
            }
        }

        /*
         * Close notes that did not receive a note-off event.
         */
        for (int i = 0; i < activeNotes.length; i++) {

            Deque<OpenNote> stack = activeNotes[i];

            if (stack == null) continue;

            while (!stack.isEmpty()) {

                OpenNote open = stack.pop();

                long duration = Math.max(
                        0,
                        absoluteTick - open.startTick);

                notes.add(
                        new NoteEvent(
                                trackIndex,
                                open.channel,
                                open.pitch,
                                open.velocity,
                                open.startTick,
                                absoluteTick,
                                duration));
            }
        }

        Collections.sort(
                notes,
                Comparator.comparingLong(NoteEvent::getStartTick));

        return new Track(
                trackIndex,
                trackName,
                absoluteTick,
                notes);
    }

    private static final class Reader {

        private final byte[] data;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean hasBytes(int offset, int count) {

            return offset >= 0
                    && count >= 0
                    && offset <= data.length
                    && count <= data.length - offset;
        }

        String readAscii(int offset, int length)
                throws IOException {

            if (!hasBytes(offset, length)) {
                throw new IOException("Unexpected end of MIDI file.");
            }

            return new String(
                    data,
                    offset,
                    length,
                    java.nio.charset.StandardCharsets.US_ASCII);
        }

        int readUnsignedShort(int offset) throws IOException {

            if (!hasBytes(offset, 2)) {
                throw new IOException("Unexpected end of MIDI file.");
            }

            return ((data[offset] & 0xFF) << 8)
                    | (data[offset + 1] & 0xFF);
        }

        long readUnsignedInt(int offset) throws IOException {

            if (!hasBytes(offset, 4)) {
                throw new IOException("Unexpected end of MIDI file.");
            }

            return ((long) (data[offset] & 0xFF) << 24)
                    | ((long) (data[offset + 1] & 0xFF) << 16)
                    | ((long) (data[offset + 2] & 0xFF) << 8)
                    | (long) (data[offset + 3] & 0xFF);
        }

        ReadValue readVariableLength(
                int offset,
                int limit) throws IOException {

            int value = 0;
            int position = offset;

            for (int i = 0; i < 4; i++) {

                if (position >= limit || position >= data.length) {
                    throw new IOException(
                            "Incomplete MIDI variable-length value.");
                }

                int next = data[position++] & 0xFF;

                value = (value << 7) | (next & 0x7F);

                if ((next & 0x80) == 0) {

                    return new ReadValue(value, position);
                }
            }

            throw new IOException(
                    "Invalid MIDI variable-length value.");
        }
    }

    private static final class ReadValue {

        final int value;
        final int nextPosition;

        ReadValue(int value, int nextPosition) {
            this.value = value;
            this.nextPosition = nextPosition;
        }
    }

    private static final class OpenNote {

        final long startTick;
        final int pitch;
        final int velocity;
        final int channel;

        OpenNote(
                long startTick,
                int pitch,
                int velocity,
                int channel) {

            this.startTick = startTick;
            this.pitch = pitch;
            this.velocity = velocity;
            this.channel = channel;
        }
    }

    public static final class NoteEvent {

        private final int trackIndex;
        private final int channel;
        private final int pitch;
        private final int velocity;
        private final long startTick;
        private final long endTick;
        private final long durationTicks;

        NoteEvent(
                int trackIndex,
                int channel,
                int pitch,
                int velocity,
                long startTick,
                long endTick,
                long durationTicks) {

            this.trackIndex = trackIndex;
            this.channel = channel;
            this.pitch = pitch;
            this.velocity = velocity;
            this.startTick = startTick;
            this.endTick = endTick;
            this.durationTicks = durationTicks;
        }

        public int getTrackIndex() {
            return trackIndex;
        }

        public int getChannel() {
            return channel;
        }

        public int getPitch() {
            return pitch;
        }

        public int getVelocity() {
            return velocity;
        }

        public long getStartTick() {
            return startTick;
        }

        public long getEndTick() {
            return endTick;
        }

        public long getDurationTicks() {
            return durationTicks;
        }
    }

    public static final class Track {

        private final int index;
        private final String name;
        private final long durationTicks;
        private final List<NoteEvent> notes;

        Track(
                int index,
                String name,
                long durationTicks,
                List<NoteEvent> notes) {

            this.index = index;
            this.name = name;
            this.durationTicks = durationTicks;

            this.notes = Collections.unmodifiableList(
                    new ArrayList<>(notes));
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return name;
        }

        public long getDurationTicks() {
            return durationTicks;
        }

        public List<NoteEvent> getNotes() {
            return notes;
        }

        public int getNoteCount() {
            return notes.size();
        }

        public int getAveragePitch() {

            if (notes.isEmpty()) return -1;

            long total = 0;

            for (NoteEvent note : notes) {
                total += note.getPitch();
            }

            return (int) Math.round(
                    total / (double) notes.size());
        }

        public int getPitchRange() {

            if (notes.isEmpty()) return 0;

            int lowest = 127;
            int highest = 0;

            for (NoteEvent note : notes) {

                lowest = Math.min(lowest, note.getPitch());
                highest = Math.max(highest, note.getPitch());
            }

            return highest - lowest;
        }

        /*
         * A candidate score only helps rank tracks.
         * It does not prove that a track is the vocal melody.
         */
        double getMelodyCandidateScore() {

            if (notes.size() < 4) return -1.0;

            double score = notes.size();

            score += getPitchRange() * 0.8;
            score += Math.max(0, getAveragePitch()) * 0.15;

            return score;
        }
    }

    public static final class Timeline {

        private final int format;
        private final int ticksPerQuarterNote;
        private final long durationTicks;
        private final List<Track> tracks;

        Timeline(
                int format,
                int ticksPerQuarterNote,
                long durationTicks,
                List<Track> tracks) {

            this.format = format;
            this.ticksPerQuarterNote = ticksPerQuarterNote;
            this.durationTicks = durationTicks;

            this.tracks = Collections.unmodifiableList(
                    new ArrayList<>(tracks));
        }

        public int getFormat() {
            return format;
        }

        public int getTicksPerQuarterNote() {
            return ticksPerQuarterNote;
        }

        public long getDurationTicks() {
            return durationTicks;
        }

        public List<Track> getTracks() {
            return tracks;
        }

        public List<NoteEvent> getSuggestedMelodyNotes() {

            Track best = getSuggestedMelodyTrack();

            if (best == null) {
                return Collections.emptyList();
            }

            return best.getNotes();
        }

        public Track getSuggestedMelodyTrack() {

            Track best = null;
            double bestScore = -1.0;

            for (Track track : tracks) {

                double score = track.getMelodyCandidateScore();

                if (score > bestScore) {

                    bestScore = score;
                    best = track;
                }
            }

            return best;
        }
    }
}
