
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

        if (!destination.isDirectory()) {
            throw new IOException("Karaoke cache is not a directory.");
        }

        File audio = new File(destination, "song.mp3");
        File graphics = new File(destination, "song.cdg");

        audio.delete();
        graphics.delete();

        File foundAudio = null;
        File foundGraphics = null;

        String audioBaseName = null;
        String graphicsBaseName = null;

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

                    if (name == null || name.trim().isEmpty()) {
                        throw new IOException(
                                "Invalid file name inside ZIP.");
                    }

                    String normalized = name.replace('\\', '/');

                    if (normalized.startsWith("/")
                            || normalized.matches("^[A-Za-z]:.*")
                            || normalized.indexOf('\0') >= 0) {
                        throw new IOException(
                                "Unsafe path inside ZIP.");
                    }

                    String[] pathParts = normalized.split("/");

                    for (String part : pathParts) {
                        if ("..".equals(part) || ".".equals(part)) {
                            throw new IOException(
                                    "Unsafe path inside ZIP.");
                        }
                    }

                    if (entry.isDirectory()) {
                        zip.closeEntry();
                        continue;
                    }

                    int slash = normalized.lastIndexOf('/');
                    String fileName = slash >= 0
                            ? normalized.substring(slash + 1)
                            : normalized;

                    String lowerName =
                            fileName.toLowerCase(Locale.ROOT);

                    boolean isAudio = lowerName.endsWith(".mp3");
                    boolean isCdg = lowerName.endsWith(".cdg");

                    if (!isAudio && !isCdg) {
                        zip.closeEntry();
                        continue;
                    }

                    String baseName = lowerName.substring(
                            0, lowerName.lastIndexOf('.'));

                    if (isAudio && foundAudio != null) {
                        throw new IOException(
                                "ZIP contains more than one MP3 file.");
                    }

                    if (isCdg && foundGraphics != null) {
                        throw new IOException(
                                "ZIP contains more than one CDG file.");
                    }

                    File target = isAudio ? audio : graphics;

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
                                        "ZIP exceeds the allowed size.");
                            }

                            out.write(buffer, 0, read);
                        }
                    }

                    if (written == 0) {
                        throw new IOException(
                                "Empty karaoke file inside ZIP.");
                    }

                    if (isAudio) {
                        foundAudio = target;
                        audioBaseName = baseName;
                    } else {
                        foundGraphics = target;
                        graphicsBaseName = baseName;
                    }

                    zip.closeEntry();
                }
            }

        } catch (IOException e) {
            audio.delete();
            graphics.delete();
            throw e;
        }

        if (foundAudio == null || foundGraphics == null) {
            audio.delete();
            graphics.delete();

            throw new IOException(
                    "ZIP must contain one MP3 file and one CDG file.");
        }

        if (!audioBaseName.equals(graphicsBaseName)) {
            audio.delete();
            graphics.delete();

            throw new IOException(
                    "The MP3 and CDG files must have matching names.");
        }

        return new Pair(audio, graphics);
    }
}
