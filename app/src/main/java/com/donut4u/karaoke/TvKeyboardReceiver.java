
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
 * Local-network receiver for the Donut4U phone keyboard.
 *
 * The active TV stage starts this receiver.
 * A phone discovers the service, enters the six-digit pairing code,
 * and sends song searches over the local network.
 */
public final class TvKeyboardReceiver {

    public static final String SERVICE_TYPE =
            "_donut4ukeyboard._tcp.";

    private static final String SERVICE_NAME =
            "Donut4U-TV-Keyboard";

    private static final long PAIRING_VALIDITY_MS =
            5 * 60 * 1000L;

    public interface Listener {
        void onPairingCodeReady(String code);

        void onSearchRequested(String songTitle);

        void onStatusChanged(String message);

        void onError(String message);
    }

    private final Context appContext;
    private final Listener listener;

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

    private long pairingExpiresAt = 0L;

    public TvKeyboardReceiver(
            Context context,
            Listener listener
    ) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Starts the receiver and advertises it on the local network.
     * This method can safely be called again after stop().
     */
    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;

        pairingCode = createPairingCode();

        pairingExpiresAt =
                System.currentTimeMillis()
                        + PAIRING_VALIDITY_MS;

        networkExecutor =
                Executors.newSingleThreadExecutor();

        notifyPairingCode(pairingCode);

        notifyStatus(
                "Starting phone keyboard connection..."
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

                    int port = newServerSocket.getLocalPort();

                    registerNetworkService(port);

                    if (!running) {
                        return;
                    }

                    notifyStatus(
                            "Ready. Connect your phone using the code shown."
                    );

                    acceptConnections();

                } catch (Exception e) {
                    if (running) {
                        notifyError(
                                "Unable to start phone keyboard: "
                                        + safeMessage(e)
                        );
                    }
                }
            });

        } catch (Exception e) {
            running = false;

            notifyError(
                    "Unable to start phone keyboard: "
                            + safeMessage(e)
            );
        }
    }

    /**
     * Stops the receiver and removes its network advertisement.
     */
    public synchronized void stop() {
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

        notifyStatus("Phone keyboard disconnected.");
    }

    public synchronized String getPairingCode() {
        return pairingCode;
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
                    "Network discovery is not available on this TV."
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
                                    "TV found on local network. Enter the pairing code on your phone."
                            );
                        }
                    }

                    @Override
                    public void onServiceUnregistered(
                            NsdServiceInfo info
                    ) {
                        notifyStatus(
                                "TV keyboard service stopped."
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
                            "Phone disconnected. Waiting for another connection."
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
                            && System.currentTimeMillis()
                            <= pairingExpiresAt
                            && pairingCode.equals(suppliedCode)
                            && !pairingCode.isEmpty();

            JSONObject pairingResponse =
                    new JSONObject();

            pairingResponse.put("ok", codeValid);

            pairingResponse.put(
                    "message",
                    codeValid
                            ? "Connected"
                            : "Invalid or expired code"
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
                            if (running && listener != null) {
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
                            "Phone keyboard disconnected."
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
            if (running && listener != null) {
                listener.onPairingCodeReady(code);
            }
        });
    }

    private void notifyStatus(String message) {
        mainHandler.post(() -> {
            if (running && listener != null) {
                listener.onStatusChanged(message);
            }
        });
    }

    private void notifyError(String message) {
        mainHandler.post(() -> {
            if (running && listener != null) {
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
