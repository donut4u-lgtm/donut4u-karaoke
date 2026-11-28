package com.donut4u.karaoke;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/**
 * Application-wide owner of the paired phone-to-TV connection.
 *
 * The pairing Activity transfers its socket to this manager.
 * The connection then remains available while the user navigates
 * between normal phone activities.
 *
 * All network methods must be called from a background thread.
 */
public final class PhoneTvConnectionManager {

    private static final int MAX_RESPONSE_LENGTH = 100000;

    private static final long MAX_MEDIA_BYTES =
            12L * 1024L * 1024L;

    private static final long MAX_MIDI_BYTES =
            64L * 1024L * 1024L;

    private static final int MEDIA_CHUNK_BYTES = 48000;

    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_WEBP = "image/webp";
    private static final String MIME_MIDI = "audio/midi";

    private static volatile PhoneTvConnectionManager instance;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private boolean connected;

    private long lastSuccessfulCommunication;
    private long stateSequence;

    private PhoneTvConnectionManager() {
    }

    public static PhoneTvConnectionManager getInstance() {

        if (instance == null) {
            synchronized (PhoneTvConnectionManager.class) {
                if (instance == null) {
                    instance = new PhoneTvConnectionManager();
                }
            }
        }

        return instance;
    }

    public synchronized boolean adoptConnection(
            Socket newSocket,
            BufferedReader newReader,
            BufferedWriter newWriter
    ) {

        if (newSocket == null
                || newReader == null
                || newWriter == null
                || newSocket.isClosed()
                || !newSocket.isConnected()) {

            return false;
        }

        closeInternal();

        try {
            newSocket.setTcpNoDelay(true);
            newSocket.setKeepAlive(true);
            newSocket.setSoTimeout(15000);

            socket = newSocket;
            reader = newReader;
            writer = newWriter;

            connected = true;
            stateSequence = 0L;

            lastSuccessfulCommunication =
                    System.currentTimeMillis();

            return true;

        } catch (Exception e) {

            closeInternal();

            return false;
        }
    }

    public synchronized boolean isConnected() {

        return connected
                && socket != null
                && socket.isConnected()
                && !socket.isClosed()
                && !socket.isInputShutdown()
                && !socket.isOutputShutdown();
    }

    public synchronized JSONObject request(
            JSONObject request
    ) throws Exception {

        if (request == null) {
            throw new IOException("TV request is empty.");
        }

        if (!isConnected()
                || reader == null
                || writer == null) {

            closeInternal();

            throw new IOException(
                    "TV is disconnected. Pair again."
            );
        }

        try {

            writer.write(request.toString());
            writer.newLine();
            writer.flush();

            String responseLine = reader.readLine();

            if (responseLine == null) {
                throw new IOException(
                        "TV closed the connection."
                );
            }

            if (responseLine.length() > MAX_RESPONSE_LENGTH) {
                throw new IOException(
                        "TV response exceeded the allowed size."
                );
            }

            JSONObject response =
                    new JSONObject(responseLine);

            lastSuccessfulCommunication =
                    System.currentTimeMillis();

            return response;

        } catch (Exception e) {

            closeInternal();

            throw e;
        }
    }

    public synchronized JSONObject publishState(
            JSONObject state
    ) throws Exception {

        if (state == null) {
            throw new IOException("TV display state is empty.");
        }

        if (state.toString().length() > 65000) {
            throw new IOException(
                    "TV display state is too large."
            );
        }

        long nextSequence = stateSequence + 1L;

        if (nextSequence <= 0L) {
            nextSequence = 1L;
        }

        JSONObject request = new JSONObject();

        request.put("type", "state");
        request.put(
                "version",
                TvKeyboardReceiver.STATE_PROTOCOL_VERSION
        );
        request.put("sequence", nextSequence);
        request.put("state", new JSONObject(state.toString()));

        JSONObject response = request(request);

        if (!response.optBoolean("ok", false)) {
            throw new IOException(
                    response.optString(
                            "message",
                            "TV rejected the display update."
                    )
            );
        }

        stateSequence = nextSequence;

        return response;
    }

    public synchronized JSONObject sendMediaFile(
            File mediaFile,
            String mimeType,
            String transferId
    ) throws Exception {

        if (mediaFile == null || !mediaFile.isFile()) {
            throw new IOException(
                    "TV media file does not exist."
            );
        }

        if (!mediaFile.canRead()) {
            throw new IOException(
                    "TV media file cannot be read."
            );
        }

        if (!isSupportedMediaMimeType(mimeType)) {
            throw new IOException(
                    "Unsupported TV media file format."
            );
        }

        long totalBytes = mediaFile.length();

        long maximumBytes = MIME_MIDI.equals(mimeType)
                ? MAX_MIDI_BYTES : MAX_MEDIA_BYTES;

        if (totalBytes <= 0L || totalBytes > maximumBytes) {
            throw new IOException(
                    MIME_MIDI.equals(mimeType)
                            ? "MIDI/KAR file must be between 1 byte and 64 MB."
                            : "Background image must be between 1 byte and 12 MB."
            );
        }

        String safeTransferId = transferId;

        if (safeTransferId == null
                || !safeTransferId.matches(
                        "[A-Za-z0-9_-]{1,64}"
                )) {

            safeTransferId = UUID.randomUUID().toString()
                    .replace("-", "");
        }

        if (!isConnected()) {
            closeInternal();

            throw new IOException(
                    "TV is disconnected. Pair again."
            );
        }

        boolean transferStarted = false;

        try (FileInputStream input =
                     new FileInputStream(mediaFile)) {

            JSONObject begin = new JSONObject();

            begin.put("type", "media_begin");
            begin.put("transferId", safeTransferId);
            begin.put("mimeType", mimeType);
            begin.put("totalBytes", totalBytes);

            JSONObject beginResponse = request(begin);

            requireSuccessfulResponse(
                    beginResponse,
                    "TV rejected the media transfer."
            );

            transferStarted = true;

            byte[] buffer = new byte[MEDIA_CHUNK_BYTES];

            long offset = 0L;

            while (offset < totalBytes) {

                int bytesToRead = (int) Math.min(
                        buffer.length,
                        totalBytes - offset
                );

                int bytesRead = input.read(
                        buffer,
                        0,
                        bytesToRead
                );

                if (bytesRead <= 0) {
                    throw new IOException(
                            "Unexpected end of TV media file."
                    );
                }

                byte[] chunk;

                if (bytesRead == buffer.length) {
                    chunk = buffer;
                } else {
                    chunk = new byte[bytesRead];

                    System.arraycopy(
                            buffer,
                            0,
                            chunk,
                            0,
                            bytesRead
                    );
                }

                String encodedData = Base64.encodeToString(
                        chunk,
                        Base64.NO_WRAP
                );

                JSONObject chunkRequest = new JSONObject();

                chunkRequest.put("type", "media_chunk");
                chunkRequest.put("transferId", safeTransferId);
                chunkRequest.put("offset", offset);
                chunkRequest.put("data", encodedData);

                JSONObject chunkResponse =
                        request(chunkRequest);

                requireSuccessfulResponse(
                        chunkResponse,
                        "TV rejected a media data chunk."
                );

                long expectedReceivedBytes =
                        offset + bytesRead;

                long acknowledgedBytes =
                        chunkResponse.optLong(
                                "receivedBytes",
                                -1L
                        );

                if (acknowledgedBytes != expectedReceivedBytes) {
                    throw new IOException(
                            "TV media transfer acknowledgement "
                                    + "does not match the sent data."
                    );
                }

                offset = expectedReceivedBytes;
            }

            JSONObject end = new JSONObject();

            end.put("type", "media_end");
            end.put("transferId", safeTransferId);

            JSONObject endResponse = request(end);

            requireSuccessfulResponse(
                    endResponse,
                    "TV could not finalize the media file."
            );

            return endResponse;

        } catch (Exception e) {

            if (transferStarted && isConnected()) {

                try {
                    JSONObject abort = new JSONObject();

                    abort.put("type", "media_abort");
                    abort.put("transferId", safeTransferId);

                    request(abort);

                } catch (Exception ignored) {
                }
            }

            throw e;
        }
    }

    private boolean isSupportedMediaMimeType(
            String mimeType
    ) {

        return MIME_JPEG.equals(mimeType)
                || MIME_PNG.equals(mimeType)
                || MIME_WEBP.equals(mimeType)
                || MIME_MIDI.equals(mimeType);
    }

    private void requireSuccessfulResponse(
            JSONObject response,
            String fallbackMessage
    ) throws IOException {

        if (response == null
                || !response.optBoolean("ok", false)) {

            throw new IOException(
                    response == null
                            ? fallbackMessage
                            : response.optString(
                                    "message",
                                    fallbackMessage
                            )
            );
        }
    }

    public synchronized void send(
            JSONObject message
    ) throws Exception {

        if (message == null) {
            throw new IOException("TV message is empty.");
        }

        if (!isConnected() || writer == null) {

            closeInternal();

            throw new IOException(
                    "TV is disconnected. Pair again."
            );
        }

        try {

            writer.write(message.toString());
            writer.newLine();
            writer.flush();

            lastSuccessfulCommunication =
                    System.currentTimeMillis();

        } catch (Exception e) {

            closeInternal();

            throw e;
        }
    }

    public synchronized long getLastSuccessfulCommunication() {
        return lastSuccessfulCommunication;
    }

    public synchronized long getStateSequence() {
        return stateSequence;
    }

    public synchronized void disconnect() {

        if (connected && writer != null) {

            try {

                JSONObject request = new JSONObject();
                request.put("type", "disconnect");

                writer.write(request.toString());
                writer.newLine();
                writer.flush();

            } catch (Exception ignored) {
            }
        }

        closeInternal();
    }

    public synchronized void close() {
        closeInternal();
    }

    private void closeInternal() {

        connected = false;
        stateSequence = 0L;

        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }

        socket = null;
        reader = null;
        writer = null;
    }
}
