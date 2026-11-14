
package com.donut4u.karaoke;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;

/**
 * Keeps the paired phone-to-TV TCP connection independent
 * of PhoneRemoteActivity.
 *
 * PhoneRemoteActivity transfers its paired socket to this
 * manager before finishing. Normal phone activities can then
 * send TV display and playback commands through this manager.
 *
 * All request() calls must run on a background thread.
 */
public final class PhoneTvConnectionManager {

    private static final int MAX_RESPONSE_LENGTH = 100000;

    private static volatile PhoneTvConnectionManager instance;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private boolean connected;

    private long lastSuccessfulCommunication;

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
     * Takes ownership of the already-paired socket.
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
     * IMPORTANT:
     * Call this method from an ExecutorService or another
     * background thread, never from the Android main thread.
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
     * Sends a one-way JSON message.
     *
     * Use only for protocol messages that do not expect
     * a response from the TV.
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
     * Closes the connection without sending a disconnect
     * command. Used after an unexpected connection failure.
     */
    public synchronized void close() {
        closeInternal();
    }

    private void closeInternal() {

        connected = false;

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
