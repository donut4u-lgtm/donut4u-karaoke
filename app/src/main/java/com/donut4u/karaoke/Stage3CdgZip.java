
package com.donut4u.karaoke;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Stage3CdgZip {

    private static final int MAX_ENTRIES = 100;
    private static final long MAX_TOTAL_BYTES =
            250L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES =
            150L * 1024L * 1024L;

    public static final class Pair {
        public final File audio;
        public final File graphics;

        Pair(File audio, File graphics) {
            this.audio = audio;
            this.graphics = graphics;
        }
    }

    private Stage3CdgZip() {
    }

    public static Pair extract(
            ContentResolver resolver,
            Uri zipUri,
            File destination) throws IOException {

        if (resolver == null || zipUri == null
                || destination == null) {
            throw new IOException("Missing ZIP input.");
        }

        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Cannot create karaoke cache.");
        }

        File audio = null;
        File graphics = null;
        long totalBytes = 0;
        int entries = 0;

        try (InputStream raw =
                     resolver.openInputStream(zipUri)) {

            if (raw == null) {
                throw new IOException("Cannot open selected ZIP.");
            }

            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buffer = new byte[16384];

                while ((entry = zip.getNextEntry()) != null) {
                    entries++;

                    if (entries > MAX_ENTRIES) {
                        throw new IOException(
                                "ZIP contains too many files.");
                    }

                    String name = entry.getName();

                    if (name == null || name.trim().isEmpty()
                            || name.startsWith("/")
                            || name.startsWith("\\\\")
                            || name.contains("../")
                            || name.contains("..\\\\")
                            || name.matches("^[A-Za-z]:.*")) {
                        throw new IOException(
                                "Unsafe path inside ZIP.");
                    }

                    if (entry.isDirectory()) {
                        zip.closeEntry();
                        continue;
                    }

                    String base = name.replace('\\\\', '/');
                    int slash = base.lastIndexOf('/');
                    if (slash >= 0) {
                        base = base.substring(slash + 1);
                    }

                    String lower =
                            base.toLowerCase(Locale.ROOT);

                    boolean isAudio = lower.endsWith(".mp3");
                    boolean isCdg = lower.endsWith(".cdg");

                    if (!isAudio && !isCdg) {
                        zip.closeEntry();
                        continue;
                    }

                    if ((isAudio && audio != null)
                            || (isCdg && graphics != null)) {
                        throw new IOException(
                                "ZIP must contain one MP3 "
                                + "and one CDG file.");
                    }

                    File target = new File(
                            destination,
                            isAudio ? "song.mp3" : "song.cdg");

                    long written = 0;

                    try (FileOutputStream out =
                                 new FileOutputStream(target)) {

                        int read;

                        while ((read = zip.read(buffer)) != -1) {
                            written += read;
                            totalBytes += read;

                            if (written > MAX_ENTRY_BYTES
                                    || totalBytes > MAX_TOTAL_BYTES) {
                                throw new IOException(
                                        "ZIP exceeds the size limit.");
                            }

                            out.write(buffer, 0, read);
                        }

                    } catch (IOException e) {
                        target.delete();
                        throw e;
                    }

                    if (written == 0) {
                        target.delete();
                        throw new IOException(
                                "Empty karaoke file in ZIP.");
                    }

                    if (isAudio) {
                        audio = target;
                    } else {
                        graphics = target;
                    }

                    zip.closeEntry();
                }
            }
        }

        if (audio == null || graphics == null) {
            if (audio != null) audio.delete();
            if (graphics != null) graphics.delete();

            throw new IOException(
                    "ZIP must contain a matching "
                    + ".mp3 and .cdg pair.");
        }

        return new Pair(audio, graphics);
    }
}
