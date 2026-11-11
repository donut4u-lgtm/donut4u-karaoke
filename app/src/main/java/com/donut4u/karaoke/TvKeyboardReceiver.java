
package com.donut4u.karaoke;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.security.KeyPairGeneratorSpec;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.security.auth.x500.X500Principal;

public final class TvKeyboardReceiver {

    public static final String SERVICE_TYPE =
            "_donut4ukeyboard._tcp.";

    private static final String SERVICE_NAME =
            "Donut4U-TV-Keyboard";

    private static final String KEY_ALIAS =
            "donut4u_tv_pairing_session";

    private static final int MAX_TEXT_LENGTH = 2000;

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

    private SSLServerSocket serverSocket;

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

        notifyStatus("Preparing encrypted phone connection...");

        try {
            networkExecutor.execute(() -> {
                try {
                    if (!running) {
                        return;
                    }

                    SSLServerSocket newServerSocket =
                            createEncryptedServer();

                    if (!running) {
                        newServerSocket.close();
                        return;
                    }

                    String newPairingCode =
                            getCertificatePairingCode();

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
                            "Encrypted connection ready. "
                                    + "Enter the code on your phone."
                    );

                    acceptConnections();

                } catch (Exception e) {
                    if (running) {
                        notifyError(
                                "Unable to start encrypted connection: "
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
     * Creates a temporary TV certificate in Android Keystore.
     *
     * The private key remains in Android Keystore.
     * It is not exported into a PKCS12 file.
     */
    private SSLServerSocket createEncryptedServer()
            throws Exception {

        KeyStore androidKeyStore =
                KeyStore.getInstance("AndroidKeyStore");

        androidKeyStore.load(null);

        if (androidKeyStore.containsAlias(KEY_ALIAS)) {
            androidKeyStore.deleteEntry(KEY_ALIAS);
        }

        Calendar start = Calendar.getInstance();

        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 10);

        KeyPairGenerator generator =
                KeyPairGenerator.getInstance(
                        "RSA",
                        "AndroidKeyStore"
                );

        KeyPairGeneratorSpec specification =
                new KeyPairGeneratorSpec.Builder(appContext)
                        .setAlias(KEY_ALIAS)
                        .setSubject(
                                new X500Principal("CN=Donut4U TV")
                        )
                        .setSerialNumber(BigInteger.ONE)
                        .setStartDate(start.getTime())
                        .setEndDate(end.getTime())
                        .setKeySize(2048)
                        .build();

        generator.initialize(specification);
        generator.generateKeyPair();

        if (androidKeyStore.getKey(KEY_ALIAS, null) == null
                || androidKeyStore.getCertificate(KEY_ALIAS) == null) {
            throw new IllegalStateException(
                    "TV pairing certificate could not be created."
            );
        }

        /*
         * Important:
         * Initialize the key manager directly with Android Keystore.
         * Do not copy the private key into a temporary PKCS12 store.
         */
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm()
                );

        keyManagerFactory.init(androidKeyStore, null);

        SSLContext sslContext =
                SSLContext.getInstance("TLS");

        sslContext.init(
                keyManagerFactory.getKeyManagers(),
                null,
                secureRandom
        );

        SSLServerSocket sslServerSocket =
                (SSLServerSocket) sslContext
                        .getServerSocketFactory()
                        .createServerSocket(0);

        sslServerSocket.setReuseAddress(true);
        sslServerSocket.setNeedClientAuth(false);

        enableSupportedTlsProtocols(sslServerSocket);

        return sslServerSocket;
    }

    private void enableSupportedTlsProtocols(
            SSLServerSocket socket
    ) {
        String[] supported = socket.getSupportedProtocols();

        ArrayList<String> enabled = new ArrayList<>();

        for (String protocol : supported) {
            if ("TLSv1.3".equals(protocol)
                    || "TLSv1.2".equals(protocol)) {
                enabled.add(protocol);
            }
        }

        if (!enabled.isEmpty()) {
            socket.setEnabledProtocols(
                    enabled.toArray(new String[0])
            );
        }
    }

    /**
     * Pairing code calculation must match the phone TLS helper.
     */
    private String getCertificatePairingCode()
            throws Exception {

        KeyStore keyStore =
                KeyStore.getInstance("AndroidKeyStore");

        keyStore.load(null);

        java.security.cert.Certificate certificate =
                keyStore.getCertificate(KEY_ALIAS);

        if (certificate == null) {
            throw new IllegalStateException(
                    "TV certificate is missing."
            );
        }

        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(
                                certificate.getPublicKey().getEncoded()
                        );

        long number =
                ((digest[0] & 0xffL) << 24)
                        | ((digest[1] & 0xffL) << 16)
                        | ((digest[2] & 0xffL) << 8)
                        | (digest[3] & 0xffL);

        return String.format(
                Locale.US,
                "%06d",
                number % 1000000L
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
            SSLSocket socket = null;

            try {
                SSLServerSocket currentServer;

                synchronized (this) {
                    currentServer = serverSocket;
                }

                if (currentServer == null
                        || currentServer.isClosed()) {
                    return;
                }

                socket = (SSLSocket) currentServer.accept();

                enableSupportedTlsProtocols(socket);

                socket.setSoTimeout(15000);
                socket.startHandshake();

                handlePhone(socket);

            } catch (Exception e) {
                if (running) {
                    notifyStatus(
                            "Phone disconnected or TLS verification failed. "
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

    private void enableSupportedTlsProtocols(SSLSocket socket) {
        String[] supported = socket.getSupportedProtocols();

        ArrayList<String> enabled = new ArrayList<>();

        for (String protocol : supported) {
            if ("TLSv1.3".equals(protocol)
                    || "TLSv1.2".equals(protocol)) {
                enabled.add(protocol);
            }
        }

        if (!enabled.isEmpty()) {
            socket.setEnabledProtocols(
                    enabled.toArray(new String[0])
            );
        }
    }

    private void handlePhone(SSLSocket socket) {
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
                            ? "Encrypted connection established"
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

            notifyStatus("Encrypted phone keyboard connected.");

            String line;

            while (running
                    && (line = reader.readLine()) != null) {

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

        mainHandler.post(() -> {
            try {
                Listener current = activeListener;

                if (running
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

        mainHandler.post(() -> {
            try {
                Listener current = activeListener;

                if (running
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

        sendResponse(
                writer,
                finished && delivered.get(),
                finished && delivered.get()
                        ? "Text input delivered"
                        : "No active TV screen accepted the input"
        );
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
