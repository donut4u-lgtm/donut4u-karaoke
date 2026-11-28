package com.donut4u.karaoke;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public final class Stage3TvBackgroundTransfer {

    private static final long MAX_TRANSFER_BYTES =
            12L * 1024L * 1024L;

    private static final int MAX_IMAGE_DIMENSION = 2560;

    private static final int MIN_JPEG_QUALITY = 55;

    private Stage3TvBackgroundTransfer() {
    }

    public static final class Result {

        public final String transferId;
        public final String fileName;
        public final String mimeType;
        public final File file;

        Result(
                String transferId,
                String fileName,
                String mimeType,
                File file
        ) {
            this.transferId = transferId;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.file = file;
        }
    }

    /**
     * Converts the selected phone image into a JPEG and transfers it.
     *
     * IMPORTANT:
     * Call this method from a background thread.
     * It performs file and network operations.
     */
    public static Result send(
            Context context,
            Uri sourceUri
    ) throws Exception {

        if (context == null) {
            throw new IOException("Application context is unavailable.");
        }

        if (sourceUri == null) {
            throw new IOException("No background picture was selected.");
        }

        PhoneTvConnectionManager manager =
                PhoneTvConnectionManager.getInstance();

        if (manager == null || !manager.isConnected()) {
            throw new IOException("The phone is not connected to the TV.");
        }

        Context appContext = context.getApplicationContext();

        File jpegFile = convertToJpeg(appContext, sourceUri);

        if (!jpegFile.exists()
                || !jpegFile.isFile()
                || jpegFile.length() <= 0L
                || jpegFile.length() > MAX_TRANSFER_BYTES) {

            jpegFile.delete();

            throw new IOException(
                    "The converted background image is invalid or exceeds 12 MB.");
        }

        String transferId =
                UUID.randomUUID().toString().replace("-", "");

        JSONObject response = manager.sendMediaFile(
                jpegFile,
                "image/jpeg",
                transferId
        );

        if (response == null
                || !response.optBoolean("ok", false)) {

            String message = response == null
                    ? "The TV did not confirm the image transfer."
                    : response.optString(
                            "message",
                            "The TV rejected the image transfer.");

            throw new IOException(message);
        }

        return new Result(
                transferId,
                "stage3_background.jpg",
                "image/jpeg",
                jpegFile
        );
    }

    private static File convertToJpeg(
            Context context,
            Uri sourceUri
    ) throws IOException {

        ContentResolver resolver =
                context.getContentResolver();

        BitmapFactory.Options bounds =
                new BitmapFactory.Options();

        bounds.inJustDecodeBounds = true;

        try (InputStream input =
                     resolver.openInputStream(sourceUri)) {

            if (input == null) {
                throw new IOException(
                        "Cannot open the selected background picture.");
            }

            BitmapFactory.decodeStream(input, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException(
                    "The selected file is not a supported picture.");
        }

        int sampleSize = 1;

        while (bounds.outWidth / sampleSize > MAX_IMAGE_DIMENSION
                || bounds.outHeight / sampleSize > MAX_IMAGE_DIMENSION) {

            sampleSize *= 2;
        }

        Bitmap bitmap = null;

        try {

            BitmapFactory.Options decodeOptions =
                    new BitmapFactory.Options();

            decodeOptions.inSampleSize = sampleSize;
            decodeOptions.inPreferredConfig =
                    Bitmap.Config.RGB_565;

            try (InputStream input =
                         resolver.openInputStream(sourceUri)) {

                if (input == null) {
                    throw new IOException(
                            "Cannot read the selected background picture.");
                }

                bitmap = BitmapFactory.decodeStream(
                        input,
                        null,
                        decodeOptions);
            }

            if (bitmap == null) {
                throw new IOException(
                        "Cannot decode the selected background picture.");
            }

            byte[] jpegBytes = compressWithinLimit(bitmap);

            File directory = new File(
                    context.getCacheDir(),
                    "stage3_tv_background");

            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException(
                        "Cannot create the temporary image folder.");
            }

            File destination = new File(
                    directory,
                    "stage3_background.jpg");

            try (FileOutputStream output =
                         new FileOutputStream(destination, false)) {

                output.write(jpegBytes);
                output.flush();
                output.getFD().sync();
            }

            if (!destination.exists()
                    || destination.length() != jpegBytes.length
                    || destination.length() > MAX_TRANSFER_BYTES) {

                destination.delete();

                throw new IOException(
                        "Could not prepare the TV background image.");
            }

            return destination;

        } finally {

            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private static byte[] compressWithinLimit(
            Bitmap original
    ) throws IOException {

        Bitmap working = original;

        boolean ownsWorkingBitmap = false;

        try {

            while (true) {

                for (int quality = 90;
                     quality >= MIN_JPEG_QUALITY;
                     quality -= 5) {

                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream();

                    boolean compressed = working.compress(
                            Bitmap.CompressFormat.JPEG,
                            quality,
                            output);

                    if (!compressed) {
                        throw new IOException(
                                "JPEG conversion failed.");
                    }

                    byte[] bytes = output.toByteArray();

                    if (bytes.length > 0
                            && bytes.length <= MAX_TRANSFER_BYTES) {
                        return bytes;
                    }
                }

                int newWidth = Math.max(
                        1,
                        working.getWidth() / 2);

                int newHeight = Math.max(
                        1,
                        working.getHeight() / 2);

                if (newWidth < 320 || newHeight < 240) {
                    throw new IOException(
                            "The picture cannot be reduced below the 12 MB transfer limit.");
                }

                Bitmap smaller = Bitmap.createScaledBitmap(
                        working,
                        newWidth,
                        newHeight,
                        true);

                if (smaller == null) {
                    throw new IOException(
                            "Could not resize the background picture.");
                }

                if (ownsWorkingBitmap
                        && working != smaller
                        && !working.isRecycled()) {
                    working.recycle();
                }

                working = smaller;
                ownsWorkingBitmap = true;
            }

        } finally {

            if (ownsWorkingBitmap
                    && working != null
                    && !working.isRecycled()) {
                working.recycle();
            }
        }
    }
}
