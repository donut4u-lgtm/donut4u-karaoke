
package com.donut4u.karaoke;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TvKeyboardReceiver {

    public static final String SERVICE_TYPE =
            "_donut4ukeyboard._tcp.";

    public static final int STATE_PROTOCOL_VERSION = 1;

    private static final String SERVICE_NAME =
            "Donut4U-TV-Keyboard";

    private static final int MAX_TEXT_LENGTH = 2000;

    private static final int MAX_STATE_LENGTH = 65536;

    private static final int MAX_MESSAGE_LENGTH = 100000;

    private static final int CALLBACK_TIMEOUT_SECONDS = 3;

    public interface Listener {

        void onPairingCodeReady(String code);

        void onSearchRequested(String songTitle);

        void onStatusChanged(String message);

        void onError(String message);

        default void onTextInput(
                String target,
                String text,
                String action
        ) {
        }

        default void onStateReceived(
                JSONObject state,
                long sequence
        ) {
        }
    }

    private static final Object LOCK = new Object();

    private static TvKeyboardReceiver sharedReceiver;

    private static volatile Listener activeListener;

    private static volatile boolean sessionEnabled = false;

    private final Context appContext;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final SecureRandom secureRandom =
            new SecureRandom();

    private volatile boolean running = false;

    private ExecutorService networkExecutor;

    private ServerSocket serverSocket;

    private NsdManager nsdManager;

    private NsdManager.RegistrationListener registrationListener;

    private volatile String pairingCode = "";

    public TvKeyboardReceiver(
            Context context,
            Listener listener
    ) {
        appContext = context.getApplicationContext();

        synchronized (LOCK) {
            activeListener = listener;

            if (sharedReceiver == null) {
                sharedReceiver =
                        new TvKeyboardReceiver(
                                context.getApplicationContext()
                        );
            }
        }
    }

    private TvKeyboardReceiver(Context context) {
        appContext = context.getApplicationContext();
    }

    public static void enableSession(
            Context context,
            Listener listener
    ) {
        TvKeyboardReceiver receiver;

        synchronized (LOCK) {
            sessionEnabled = true;
            activeListener = listener;

            if (sharedReceiver == null) {
                sharedReceiver =
                        new TvKeyboardReceiver(
                                context.getApplicationContext()
                        );
            }

            receiver = sharedReceiver;
        }

        receiver.startInternal();
    }

    public static void disableSession() {
        TvKeyboardReceiver receiver;

        synchronized (LOCK) {
            sessionEnabled = false;
            activeListener = null;
            receiver = sharedReceiver;
        }

        if (receiver != null) {
            receiver.stopInternal();
        }
    }

    public static boolean isSessionEnabled() {
        return sessionEnabled;
    }

    public static String getCurrentPairingCode() {
        synchronized (LOCK) {
            return sharedReceiver == null
                    ? ""
                    : sharedReceiver.getPairingCode();
        }
    }

    public void start() {
        if (!sessionEnabled) {
            return;
        }

        TvKeyboardReceiver receiver;

        synchronized (LOCK) {
            receiver = sharedReceiver;
        }

        if (receiver != null) {
            receiver.startInternal();
        }
    }

    /**
     * Stage changes must not terminate the shared connection.
     */
    public void stop() {
        // Keep the application-wide session alive.
    }

    public synchronized String getPairingCode() {
        return pairingCode;
    }

    private synchronized void startInternal() {
        if (running) {
            notifyPairingCode(pairingCode);
            return;
        }

        running = true;
        pairingCode = "";

        networkExecutor = Executors.newSingleThreadExecutor();

        notifyStatus("Preparing phone connection...");

        try {
            networkExecutor.execute(() -> {
                try {
                    if (!running) {
                        return;
                    }

                    ServerSocket newServerSocket =
                            createPlainServer();

                    if (!running) {
                        newServerSocket.close();
                        return;
                    }

                    String newPairingCode =
                            generatePairingCode();

                    synchronized (TvKeyboardReceiver.this) {
                        if (!running) {
                            newServerSocket.close();
                            return;
                        }

                        serverSocket = newServerSocket;
                        pairingCode = newPairingCode;
                    }

                    notifyPairingCode(newPairingCode);

                    registerNetworkService(
                            newServerSocket.getLocalPort()
                    );

                    if (!running) {
                        return;
                    }

                    notifyStatus(
                            "Phone connection ready. "
                                    + "Enter the code on your phone."
                    );

                    acceptConnections();

                } catch (Exception e) {
                    if (running) {
                        notifyError(
                                "Unable to start phone connection: "
                                        + safeMessage(e)
                        );

                        stopInternal();
                    }
                }
            });

        } catch (Exception e) {
            running = false;

            notifyError(
                    "Unable to prepare phone connection: "
                            + safeMessage(e)
            );
        }
    }

    /**
     * Creates an ordinary TCP server socket.
     * No TLS, SSL, certificates, or encryption are used.
     */
    private ServerSocket createPlainServer()
            throws Exception {

        ServerSocket socket = new ServerSocket();

        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(0));

        return socket;
    }

    /**
     * Generates a random six-digit pairing code.
     */
    private String generatePairingCode() {

        int number =
                secureRandom.nextInt(1000000);

        return String.format(
                Locale.US,
                "%06d",
                number
        );
    }

    private synchronized void stopInternal() {
        if (!running
                && networkExecutor == null
                && serverSocket == null) {
            return;
        }

        running = false;

        unregisterNetworkService();

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }

            serverSocket = null;
        }

        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
            networkExecutor = null;
        }

        pairingCode = "";

        notifyStatus("Phone connection ended.");
    }

    private void registerNetworkService(int port) {
        if (!running) {
            return;
        }

        nsdManager = (NsdManager)
                appContext.getSystemService(
                        Context.NSD_SERVICE
                );

        if (nsdManager == null) {
            notifyError(
                    "Network discovery is unavailable on this TV."
            );
            return;
        }

        NsdServiceInfo serviceInfo =
                new NsdServiceInfo();

        serviceInfo.setServiceName(SERVICE_NAME);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        NsdManager.RegistrationListener newListener =
                new NsdManager.RegistrationListener() {

                    @Override
                    public void onRegistrationFailed(
                            NsdServiceInfo info,
                            int errorCode
                    ) {
                        if (running) {
                            notifyError(
                                    "TV network registration failed. Code: "
                                            + errorCode
                            );
                        }
                    }

                    @Override
                    public void onUnregistrationFailed(
                            NsdServiceInfo info,
                            int errorCode
                    ) {
                        notifyStatus(
                                "TV network advertisement could not be removed."
                        );
                    }

                    @Override
                    public void onServiceRegistered(
                            NsdServiceInfo info
                    ) {
                        if (running) {
                            notifyStatus(
                                    "TV found on the local network."
                            );
                        }
                    }

                    @Override
                    public void onServiceUnregistered(
                            NsdServiceInfo info
                    ) {
                        notifyStatus(
                                "TV network service stopped."
                        );
                    }
                };

        synchronized (this) {
            if (!running) {
                return;
            }

            registrationListener = newListener;
        }

        try {
            nsdManager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    newListener
            );

        } catch (Exception e) {
            notifyError(
                    "Unable to publish TV connection: "
                            + safeMessage(e)
            );
        }
    }

    private synchronized void unregisterNetworkService() {
        if (nsdManager == null
                || registrationListener == null) {
            registrationListener = null;
            return;
        }

        try {
            nsdManager.unregisterService(
                    registrationListener
            );
        } catch (Exception ignored) {
        }

        registrationListener = null;
    }

    private void acceptConnections() {
        while (running) {
            Socket socket = null;

            try {
                ServerSocket currentServer;

                synchronized (this) {
                    currentServer = serverSocket;
                }

                if (currentServer == null
                        || currentServer.isClosed()) {
                    return;
                }

                socket = currentServer.accept();

                socket.setSoTimeout(15000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

                handlePhone(socket);

            } catch (Exception e) {
                if (running) {
                    notifyStatus(
                            "Phone disconnected. "
                                    + "Waiting for reconnection."
                    );
                }

            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void handlePhone(Socket socket) {
        try {
            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );

            BufferedWriter writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    socket.getOutputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );

            String firstMessage = reader.readLine();

            if (firstMessage == null || !running) {
                return;
            }

            if (firstMessage.length() > MAX_MESSAGE_LENGTH) {
                sendResponse(
                        writer,
                        false,
                        "Pairing message is too large"
                );
                return;
            }

            JSONObject pairingRequest =
                    new JSONObject(firstMessage);

            String suppliedCode =
                    pairingRequest.optString("code", "");

            boolean codeValid =
                    running
                            && pairingCode.equals(suppliedCode)
                            && !pairingCode.isEmpty();

            JSONObject pairingResponse =
                    new JSONObject();

            pairingResponse.put("ok", codeValid);

            pairingResponse.put(
                    "message",
                    codeValid
                            ? "Connection established"
                            : "Invalid code"
            );

            writer.write(pairingResponse.toString());
            writer.newLine();
            writer.flush();

            if (!codeValid) {
                notifyStatus(
                        "A phone tried to connect with an invalid code."
                );
                return;
            }

            socket.setSoTimeout(0);

            notifyStatus("Phone controller connected.");

            String line;
            long lastStateSequence = 0L;

            while (running
                    && (line = reader.readLine()) != null) {

                if (line.length() > MAX_MESSAGE_LENGTH) {
                    sendResponse(
                            writer,
                            false,
                            "Message is too large"
                    );
                    continue;
                }

                JSONObject request =
                        new JSONObject(line);

                String type =
                        request.optString("type", "");

                if ("search".equals(type)) {

                    handleSearchRequest(
                            request,
                            writer
                    );

                } else if ("input".equals(type)) {

                    handleTextInputRequest(
                            request,
                            writer
                    );

                } else if ("state".equals(type)) {

                    lastStateSequence =
                            handleStateRequest(
                                    request,
                                    writer,
                                    lastStateSequence
                            );

                } else if ("disconnect".equals(type)) {

                    notifyStatus(
                            "Phone disconnected. TV is ready to reconnect."
                    );
                    return;

                } else {

                    sendResponse(
                            writer,
                            false,
                            "Unknown command"
                    );
                }
            }

        } catch (Exception e) {
            if (running) {
                notifyStatus("Phone connection ended.");
            }
        }
    }

    private void handleSearchRequest(
            JSONObject request,
            BufferedWriter writer
    ) throws Exception {

        String songTitle =
                request.optString("text", "").trim();

        if (songTitle.isEmpty()
                || songTitle.length() > 200) {

            sendResponse(
                    writer,
                    false,
                    "Invalid song title"
            );
            return;
        }

        Listener listener = activeListener;

        if (!sessionEnabled || listener == null) {
            sendResponse(
                    writer,
                    false,
                    "No active TV screen is listening."
            );
            return;
        }

        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean expired = new AtomicBoolean(false);

        mainHandler.post(() -> {
            try {
                Listener current = activeListener;

                if (!expired.get()
                        && running
                        && sessionEnabled
                        && current != null) {
                    current.onSearchRequested(songTitle);
                    delivered.set(true);
                }
            } catch (Exception ignored) {
            } finally {
                completed.countDown();
            }
        });

        boolean finished = completed.await(
                CALLBACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );

        if (!finished) {
            expired.set(true);
        }

        sendResponse(
                writer,
                finished && delivered.get(),
                finished && delivered.get()
                        ? "Search sent"
                        : "No active TV screen accepted the search"
        );
    }

    private void handleTextInputRequest(
            JSONObject request,
            BufferedWriter writer
    ) throws Exception {

        String target =
                request.optString("target", "").trim();

        String text =
                request.optString("text", "");

        String action =
                request.optString(
                        "action",
                        "replace"
                ).trim().toLowerCase(Locale.US);

        if (target.isEmpty()
                || target.length() > 64) {

            sendResponse(
                    writer,
                    false,
                    "Invalid input target"
            );
            return;
        }

        if (text.length() > MAX_TEXT_LENGTH) {
            sendResponse(
                    writer,
                    false,
                    "Text is too long"
            );
            return;
        }

        boolean validAction =
                "replace".equals(action)
                        || "append".equals(action)
                        || "backspace".equals(action)
                        || "clear".equals(action)
                        || "submit".equals(action);

        if (!validAction) {
            sendResponse(
                    writer,
                    false,
                    "Unsupported keyboard action"
            );
            return;
        }

        Listener listener = activeListener;

        if (!sessionEnabled || listener == null) {
            sendResponse(
                    writer,
                    false,
                    "No active TV screen is listening."
            );
            return;
        }

        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean expired = new AtomicBoolean(false);

        mainHandler.post(() -> {
            try {
                Listener current = activeListener;

                if (!expired.get()
                        && running
                        && sessionEnabled
                        && current != null) {

                    current.onTextInput(
                            target,
                            text,
                            action
                    );

                    delivered.set(true);
                }
            } catch (Exception ignored) {
            } finally {
                completed.countDown();
            }
        });

        boolean finished = completed.await(
                CALLBACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );

        if (!finished) {
            expired.set(true);
        }

        sendResponse(
                writer,
                finished && delivered.get(),
                finished && delivered.get()
                        ? "Text input delivered"
                        : "No active TV screen accepted the input"
        );
    }

    private long handleStateRequest(
            JSONObject request,
            BufferedWriter writer,
            long lastAcceptedSequence
    ) throws Exception {

        int version =
                request.optInt("version", -1);

        if (version != STATE_PROTOCOL_VERSION) {
            sendResponse(
                    writer,
                    false,
                    "Unsupported state protocol version"
            );
            return lastAcceptedSequence;
        }

        long sequence =
                request.optLong("sequence", -1L);

        if (sequence <= 0L) {
            sendResponse(
                    writer,
                    false,
                    "Invalid state sequence"
            );
            return lastAcceptedSequence;
        }

        if (sequence <= lastAcceptedSequence) {
            sendResponse(
                    writer,
                    false,
                    "Outdated state update"
            );
            return lastAcceptedSequence;
        }

        JSONObject suppliedState =
                request.optJSONObject("state");

        if (suppliedState == null) {
            sendResponse(
                    writer,
                    false,
                    "Missing state object"
            );
            return lastAcceptedSequence;
        }

        String stateText = suppliedState.toString();

        if (stateText.length() > MAX_STATE_LENGTH) {
            sendResponse(
                    writer,
                    false,
                    "TV state is too large"
            );
            return lastAcceptedSequence;
        }

        Listener listener = activeListener;

        if (!sessionEnabled || listener == null) {
            sendResponse(
                    writer,
                    false,
                    "No active TV screen is listening."
            );
            return lastAcceptedSequence;
        }

        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean expired = new AtomicBoolean(false);

        mainHandler.post(() -> {
            try {
                Listener current = activeListener;

                if (!expired.get()
                        && running
                        && sessionEnabled
                        && current != null) {

                    JSONObject stateCopy =
                            new JSONObject(stateText);

                    current.onStateReceived(
                            stateCopy,
                            sequence
                    );

                    delivered.set(true);
                }
            } catch (Exception ignored) {
            } finally {
                completed.countDown();
            }
        });

        boolean finished = completed.await(
                CALLBACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );

        if (!finished) {
            expired.set(true);
        }

        boolean accepted =
                finished && delivered.get();

        sendResponse(
                writer,
                accepted,
                accepted
                        ? "TV state accepted"
                        : "No active TV screen accepted the state"
        );

        return accepted
                ? sequence
                : lastAcceptedSequence;
    }

    private void sendResponse(
            BufferedWriter writer,
            boolean success,
            String message
    ) throws Exception {

        JSONObject response =
                new JSONObject();

        response.put("ok", success);
        response.put("message", message);

        writer.write(response.toString());
        writer.newLine();
        writer.flush();
    }

    private void notifyPairingCode(String code) {
        mainHandler.post(() -> {
            Listener listener = activeListener;

            if (running
                    && sessionEnabled
                    && listener != null
                    && !code.isEmpty()) {
                listener.onPairingCodeReady(code);
            }
        });
    }

    private void notifyStatus(String message) {
        mainHandler.post(() -> {
            Listener listener = activeListener;

            if (listener != null) {
                listener.onStatusChanged(message);
            }
        });
    }

    private void notifyError(String message) {
        mainHandler.post(() -> {
            Listener listener = activeListener;

            if (listener != null) {
                listener.onError(message);
            }
        });
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();

        return message == null
                || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
