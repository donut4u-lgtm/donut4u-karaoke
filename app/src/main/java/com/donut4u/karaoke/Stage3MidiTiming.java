
package com.donut4u.karaoke;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads MIDI timing independently of the lyric parser.
 * Supports standard MIDI files (SMF) using PPQ timing.
 */
public final class Stage3MidiTiming {

    private static final int DEFAULT_MICROSECONDS_PER_QUARTER = 500000;

    private final int ticksPerQuarter;
    private final long durationTicks;
    private final List<TempoEvent> tempos;

    private Stage3MidiTiming(
            int ticksPerQuarter,
            long durationTicks,
            List<TempoEvent> tempos) {

        this.ticksPerQuarter = ticksPerQuarter;
        this.durationTicks = durationTicks;
        this.tempos = tempos;
    }

    public int getTicksPerQuarterNote() {
        return ticksPerQuarter;
    }

    public long getDurationTicks() {
        return durationTicks;
    }

    public long getDurationMillis() {
        return tickToMillis(durationTicks);
    }

    public long tickToMillis(long targetTick) {

        if (ticksPerQuarter <= 0) {
            return 0L;
        }

        long endTick = Math.max(
                0L,
                Math.min(targetTick, durationTicks));

        long previousTick = 0L;
        long elapsedMicros = 0L;

        int tempo = DEFAULT_MICROSECONDS_PER_QUARTER;

        for (TempoEvent event : tempos) {

            if (event.tick > endTick) {
                break;
            }

            if (event.tick > previousTick) {

                elapsedMicros +=
                        (event.tick - previousTick)
                                * (long) tempo
                                / ticksPerQuarter;

                previousTick = event.tick;
            }

            tempo = event.microsecondsPerQuarter;
        }

        if (endTick > previousTick) {

            elapsedMicros +=
                    (endTick - previousTick)
                            * (long) tempo
                            / ticksPerQuarter;
        }

        return elapsedMicros / 1000L;
    }

    public static Stage3MidiTiming read(File file)
            throws IOException {

        if (file == null || !file.isFile()) {
            throw new IOException("MIDI file not found.");
        }

        byte[] data = readAll(file);

        if (data.length < 14
                || !matches(data, 0, "MThd")) {
            throw new IOException("Invalid MIDI header.");
        }

        int headerLength = readInt32(data, 4);

        if (headerLength < 6
                || 8L + headerLength > data.length) {
            throw new IOException("Invalid MIDI header length.");
        }

        int trackCount = readUInt16(data, 10);
        int division = readUInt16(data, 12);

        // The high bit means SMPTE timing, not PPQ timing.
        if ((division & 0x8000) != 0 || division == 0) {
            throw new IOException(
                    "This MIDI file uses unsupported SMPTE timing.");
        }

        int ppq = division;

        List<TempoEvent> tempos = new ArrayList<>();

        tempos.add(new TempoEvent(
                0L,
                DEFAULT_MICROSECONDS_PER_QUARTER));

        long maximumDurationTicks = 0L;

        int position = 8 + headerLength;

        for (int track = 0; track < trackCount; track++) {

            if (position + 8 > data.length) {
                throw new IOException("Incomplete MIDI track.");
            }

            if (!matches(data, position, "MTrk")) {
                throw new IOException("Invalid MIDI track header.");
            }

            int trackLength = readInt32(data, position + 4);

            if (trackLength < 0
                    || (long) position + 8L + trackLength
                    > data.length) {
                throw new IOException("Invalid MIDI track length.");
            }

            int trackEnd = position + 8 + trackLength;
            int cursor = position + 8;

            long absoluteTick = 0L;
            int runningStatus = -1;

            while (cursor < trackEnd) {

                Value delta = readVariableLength(
                        data, cursor, trackEnd);

                cursor = delta.nextPosition;

                absoluteTick += delta.value;

                if (absoluteTick < 0L) {
                    throw new IOException("MIDI tick overflow.");
                }

                if (cursor >= trackEnd) {
                    break;
                }

                int first = data[cursor] & 0xFF;
                int status;

                if (first < 0x80) {

                    if (runningStatus < 0) {
                        throw new IOException(
                                "Invalid MIDI running status.");
                    }

                    status = runningStatus;

                } else {

                    status = first;
                    cursor++;

                    if (status >= 0x80 && status <= 0xEF) {
                        runningStatus = status;
                    }
                }

                if (status == 0xFF) {

                    runningStatus = -1;

                    if (cursor >= trackEnd) {
                        throw new IOException(
                                "Incomplete MIDI meta event.");
                    }

                    int metaType = data[cursor++] & 0xFF;

                    Value length = readVariableLength(
                            data, cursor, trackEnd);

                    cursor = length.nextPosition;

                    int eventLength = length.value;

                    if (eventLength < 0
                            || cursor + (long) eventLength > trackEnd) {
                        throw new IOException(
                                "Invalid MIDI meta event length.");
                    }

                    if (metaType == 0x51 && eventLength == 3) {

                        int tempo =
                                ((data[cursor] & 0xFF) << 16)
                                | ((data[cursor + 1] & 0xFF) << 8)
                                | (data[cursor + 2] & 0xFF);

                        if (tempo > 0) {
                            tempos.add(new TempoEvent(
                                    absoluteTick, tempo));
                        }
                    }

                    cursor += eventLength;

                    if (metaType == 0x2F) {
                        break;
                    }

                } else if (status == 0xF0 || status == 0xF7) {

                    runningStatus = -1;

                    Value length = readVariableLength(
                            data, cursor, trackEnd);

                    cursor = length.nextPosition;

                    if (length.value < 0
                            || cursor + (long) length.value > trackEnd) {
                        throw new IOException(
                                "Invalid MIDI system event length.");
                    }

                    cursor += length.value;

                } else if (status >= 0x80 && status <= 0xEF) {

                    int command = status & 0xF0;

                    int dataBytes =
                            (command == 0xC0 || command == 0xD0)
                                    ? 1 : 2;

                    if (first < 0x80) {
                        // Running status: first byte is event data.
                        cursor++;
                        dataBytes--;
                    }

                    if (cursor + dataBytes > trackEnd) {
                        throw new IOException(
                                "Incomplete MIDI channel event.");
                    }

                    cursor += dataBytes;

                } else {
                    throw new IOException(
                            "Unsupported MIDI event.");
                }
            }

            maximumDurationTicks = Math.max(
                    maximumDurationTicks,
                    absoluteTick);

            position = trackEnd;
        }

        Collections.sort(
                tempos,
                Comparator.comparingLong(event -> event.tick));

        return new Stage3MidiTiming(
                ppq,
                maximumDurationTicks,
                tempos);
    }

    private static byte[] readAll(File file)
            throws IOException {

        long length = file.length();

        if (length < 14 || length > Integer.MAX_VALUE) {
            throw new IOException("Invalid MIDI file size.");
        }

        byte[] data = new byte[(int) length];

        try (FileInputStream input =
                     new FileInputStream(file)) {

            int offset = 0;

            while (offset < data.length) {

                int count = input.read(
                        data, offset, data.length - offset);

                if (count < 0) {
                    throw new IOException(
                            "Could not read the complete MIDI file.");
                }

                offset += count;
            }
        }

        return data;
    }

    private static boolean matches(
            byte[] data,
            int offset,
            String value) {

        if (offset < 0
                || offset + value.length() > data.length) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (data[offset + i] != (byte) value.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    private static int readUInt16(
            byte[] data,
            int offset) {

        return ((data[offset] & 0xFF) << 8)
                | (data[offset + 1] & 0xFF);
    }

    private static int readInt32(
            byte[] data,
            int offset) {

        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static Value readVariableLength(
            byte[] data,
            int offset,
            int limit) throws IOException {

        int value = 0;
        int cursor = offset;

        for (int i = 0; i < 4; i++) {

            if (cursor >= limit) {
                throw new IOException(
                        "Incomplete MIDI variable-length value.");
            }

            int next = data[cursor++] & 0xFF;

            value = (value << 7) | (next & 0x7F);

            if ((next & 0x80) == 0) {
                return new Value(value, cursor);
            }
        }

        throw new IOException(
                "Invalid MIDI variable-length value.");
    }

    private static final class Value {

        final int value;
        final int nextPosition;

        Value(int value, int nextPosition) {
            this.value = value;
            this.nextPosition = nextPosition;
        }
    }

    private static final class TempoEvent {

        final long tick;
        final int microsecondsPerQuarter;

        TempoEvent(long tick, int tempo) {
            this.tick = tick;
            this.microsecondsPerQuarter = tempo;
        }
    }
}
