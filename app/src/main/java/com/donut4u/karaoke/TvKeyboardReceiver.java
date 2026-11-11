
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared phone-to-TV keyboard connection.
 *
 * One receiver and one pairing code are kept for the
 * lifetime of the TV application session.
 *
 * Stage activities can replace the active listener without
 * stopping the network connection.
 */
public final class TvKeyboardReceiver {

    public static final String SERVICE_TYPE =
            "_donut4ukeyboard._tcp.";

    private static final String SERVICE_NAME =
            "Donut4U-TV-Keyboard";

    public interface Listener {
        void onPairingCodeReady(String code);
        void onSearchRequested(String songTitle);
        void onStatusChanged(String message);
        void onError(String message);
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

    private String pairingCode = "";

    /**
     * Stage activities use this constructor.
     * Creating another stage receiver changes the active listener,
     * but does not create another network service.
     */
    public TvKeyboardReceiver(
            Context context,
            Listener listener
    ) {
        // FIX: initialize appContext in this constructor too.
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

    /**
     * Private constructor for the one shared receiver.
     */
    private TvKeyboardReceiver(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * Called by the TV menu when CONNECT PHONE is selected.
     */
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

    /**
     * Called when the user chooses TV ONLY or ends the session.
     */
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
                    : sharedReceiver.pairingCode;
        }
    }

    /**
     * Existing Stage 1, 2 and 3 activities call start().
     * They now reuse the shared receiver.
     */
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
     * Intentionally does not stop the shared receiver.
     * Activities call stop() when changing screens.
     *
     * The session remains alive while the user changes stages.
     */
    public void stop() {
        // Do not stop the application-wide TV session here.
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
        pairingCode = createPairingCode();

        networkExecutor =
                Executors.newSingleThreadExecutor();

        notifyPairingCode(pairingCode);

        notifyStatus(
                "Starting phone connection..."
        );

        try {
            networkExecutor.execute(() -> {
                try {
                    if (!running) {
                        return;
                    }

                    ServerSocket newServerSocket =
                            new ServerSocket(0);

                    newServerSocket.setReuseAddress(true);

                    synchronized (TvKeyboardReceiver.this) {
                        if (!running) {
                            newServerSocket.close();
                            return;
                        }

                        serverSocket = newServerSocket;
                    }

                    registerNetworkService(
                            newServerSocket.getLocalPort()
                    );

                    if (!running) {
                        return;
                    }

                    notifyStatus(
                            "Ready. Enter the code on your phone."
                    );

                    acceptConnections();

                } catch (Exception e) {
                    if (running) {
                        notifyError(
                                "Unable to start phone connection: "
                                        + safeMessage(e)
                        );
                    }
                }
            });

        } catch (Exception e) {
            running = false;

            notifyError(
                    "Unable to start phone connection: "
                            + safeMessage(e)
            );
        }
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

    private String createPairingCode() {
        int number = secureRandom.nextInt(1000000);

        return String.format(
                Locale.US,
                "%06d",
                number
        );
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

                socket.setSoTimeout(120000);

                handlePhone(socket);

            } catch (Exception e) {
                if (running) {
                    notifyStatus(
                            "Phone disconnected. Waiting for reconnection."
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
                            ? "Connected"
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

            // Do not disconnect just because the phone is idle.
            socket.setSoTimeout(0);

            notifyStatus("Phone keyboard connected.");

            String line;

            while (running
                    && (line = reader.readLine()) != null) {

                JSONObject request =
                        new JSONObject(line);

                String type =
                        request.optString("type", "");

                if ("search".equals(type)) {
                    String songTitle =
                            request.optString(
                                    "text",
                                    ""
                            ).trim();

                    if (!songTitle.isEmpty()
                            && songTitle.length() <= 200) {

                        mainHandler.post(() -> {
                            Listener listener = activeListener;

                            if (running
                                    && sessionEnabled
                                    && listener != null) {
                                listener.onSearchRequested(
                                        songTitle
                                );
                            }
                        });

                        JSONObject response =
                                new JSONObject();

                        response.put("ok", true);
                        response.put(
                                "message",
                                "Search sent"
                        );

                        writer.write(response.toString());
                        writer.newLine();
                        writer.flush();

                    } else {
                        JSONObject response =
                                new JSONObject();

                        response.put("ok", false);
                        response.put(
                                "message",
                                "Invalid song title"
                        );

                        writer.write(response.toString());
                        writer.newLine();
                        writer.flush();
                    }

                } else if ("disconnect".equals(type)) {
                    notifyStatus(
                            "Phone disconnected. TV is ready to reconnect."
                    );
                    return;
                }
            }

        } catch (Exception e) {
            if (running) {
                notifyStatus(
                        "Phone connection ended."
                );
            }
        }
    }

    private void notifyPairingCode(String code) {
        mainHandler.post(() -> {
            Listener listener = activeListener;

            if (running
                    && sessionEnabled
                    && listener != null) {
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
