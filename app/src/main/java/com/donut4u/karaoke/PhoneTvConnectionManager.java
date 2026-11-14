
package com.donut4u.karaoke;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;

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

    /**
     * Takes ownership of an already-paired socket.
     *
     * The caller must stop using and closing the socket after
     * this method succeeds.
     */
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

    /**
     * Sends one JSON request and waits for its JSON response.
     *
     * Call only from a background thread.
     */
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

    /**
     * Publishes the phone's current state to the TV.
     *
     * The existing TvKeyboardReceiver protocol requires:
     * - type = state
     * - version = STATE_PROTOCOL_VERSION
     * - sequence = increasing positive number
     * - state = supplied JSON object
     *
     * The TV sends an acknowledgement for every state update.
     *
     * Call only from a background thread.
     */
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

    /**
     * Sends a one-way JSON message.
     *
     * Use only for protocol messages that do not expect
     * a response from the TV.
     *
     * Do not use this method for state updates.
     */
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

    /**
     * Gracefully tells the TV that the phone is disconnecting.
     */
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

    /**
     * Closes the connection without sending a disconnect command.
     */
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
