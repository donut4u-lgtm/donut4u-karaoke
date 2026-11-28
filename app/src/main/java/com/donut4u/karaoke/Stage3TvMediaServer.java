
package com.donut4u.karaoke;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local HTTP media server for Stage 3 phone-to-TV presentation.
 *
 * The phone remains responsible for selecting files and controlling
 * playback. The TV receives an HTTP address for files that the phone
 * has permission to read.
 *
 * Supports:
 * - Local content:// and file:// sources readable by the phone.
 * - HTTP GET and HEAD.
 * - HTTP byte-range requests for video seeking.
 * - Separate registered media/background resources.
 *
 * Network traffic uses ordinary unencrypted local-network HTTP,
 * consistent with the existing TV connection design.
 *
 * This class does not publish microphone data or singing scores.
 */
public final class Stage3TvMediaServer {

    private static final String TAG = "Stage3TvMediaServer";

    private static final int PORT = 0;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Context context;
    private final ContentResolver resolver;

    private final Map<String, MediaSource> sources =
            new ConcurrentHashMap<>();

    private final SecureRandom secureRandom =
            new SecureRandom();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    private volatile String baseUrl;

    public Stage3TvMediaServer(Context context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Context cannot be null.");
        }

        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    /**
     * Starts the HTTP server on an available local TCP port.
     */
    public synchronized void start() throws IOException {

        if (running && serverSocket != null
                && !serverSocket.isClosed()) {
            return;
        }

        ServerSocket socket = new ServerSocket();

        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(
                "0.0.0.0", PORT));

        serverSocket = socket;
        running = true;

        String host = findLocalIpv4Address();

        if (host == null || host.trim().isEmpty()) {
            stop();
            throw new IOException(
                    "No local IPv4 network address is available.");
        }

        baseUrl = "http://" + host + ":"
                + socket.getLocalPort();

        acceptThread = new Thread(
                this::acceptConnections,
                "Stage3TvMediaServer");

        acceptThread.setDaemon(true);
        acceptThread.start();

        Log.i(TAG, "Local media server started: " + baseUrl);
    }

    /**
     * Registers a phone-readable URI and returns its TV-accessible URL.
     *
     * Example:
     * String url = server.register(uri, "video/mp4");
     */
    public String register(Uri uri, String mimeType)
            throws IOException {

        if (uri == null) {
            throw new IllegalArgumentException(
                    "Media URI cannot be null.");
        }

        if (!running || baseUrl == null) {
            throw new IOException(
                    "The Stage 3 TV media server is not running.");
        }

        String token = createToken();

        String resolvedMime = mimeType;

        if (resolvedMime == null
                || resolvedMime.trim().isEmpty()) {
            resolvedMime = resolver.getType(uri);
        }

        if (resolvedMime == null
                || resolvedMime.trim().isEmpty()) {
            resolvedMime = "application/octet-stream";
        }

        long size = getContentSize(uri);

        MediaSource source = new MediaSource(
                uri,
                resolvedMime,
                size);

        sources.put(token, source);

        return baseUrl + "/f/" + token;
    }

    /**
     * Removes a previously registered resource.
     */
    public void unregisterUrl(String url) {

        if (url == null || baseUrl == null) {
            return;
        }

        String prefix = baseUrl + "/f/";

        if (!url.startsWith(prefix)) {
            return;
        }

        String token = url.substring(prefix.length());

        if (token.contains("/")
                || token.contains("?")
                || token.contains("#")) {
            return;
        }

        sources.remove(token);
    }

    /**
     * Returns whether the local server is currently running.
     */
    public boolean isRunning() {
        return running
                && serverSocket != null
                && !serverSocket.isClosed();
    }

    /**
     * Returns the server base address, or an empty string if stopped.
     */
    public String getBaseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    /**
     * Stops the listener and removes all registered resources.
     */
    public synchronized void stop() {

        running = false;

        sources.clear();

        baseUrl = null;

        ServerSocket oldSocket = serverSocket;
        serverSocket = null;

        if (oldSocket != null) {
            try {
                oldSocket.close();
            } catch (IOException ignored) {
            }
        }

        Thread oldThread = acceptThread;
        acceptThread = null;

        if (oldThread != null) {
            oldThread.interrupt();
        }

        Log.i(TAG, "Local media server stopped.");
    }

    private void acceptConnections() {

        while (running) {

            ServerSocket socket = serverSocket;

            if (socket == null || socket.isClosed()) {
                break;
            }

            try {

                Socket client = socket.accept();

                client.setSoTimeout(30000);
                client.setTcpNoDelay(true);

                Thread worker = new Thread(
                        () -> handleClient(client),
                        "Stage3TvMediaClient");

                worker.setDaemon(true);
                worker.start();

            } catch (IOException error) {

                if (running) {
                    Log.w(TAG,
                            "Media client connection failed.",
                            error);
                }
            }
        }
    }

    private void handleClient(Socket client) {

        try (Socket socket = client;
             InputStream socketInput = socket.getInputStream();
             OutputStream socketOutput = socket.getOutputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(
                             socketInput,
                             StandardCharsets.US_ASCII))) {

            String requestLine = reader.readLine();

            if (requestLine == null
                    || requestLine.length() > 4096) {
                return;
            }

            String[] requestParts =
                    requestLine.trim().split("\\s+");

            if (requestParts.length < 2) {
                writeError(socketOutput, 400, "Bad Request");
                return;
            }

            String method = requestParts[0].toUpperCase(
                    java.util.Locale.ROOT);

            String requestPath = requestParts[1];

            boolean headRequest = "HEAD".equals(method);

            if (!headRequest && !"GET".equals(method)) {
                writeError(socketOutput, 405, "Method Not Allowed");
                return;
            }

            String rangeHeader = null;

            String header;

            while ((header = reader.readLine()) != null) {

                if (header.isEmpty()) {
                    break;
                }

                if (header.length() > 8192) {
                    writeError(
                            socketOutput,
                            431,
                            "Request Header Too Large");
                    return;
                }

                int separator = header.indexOf(':');

                if (separator <= 0) {
                    continue;
                }

                String name = header.substring(
                        0, separator).trim();

                String value = header.substring(
                        separator + 1).trim();

                if ("Range".equalsIgnoreCase(name)) {
                    rangeHeader = value;
                }
            }

            int queryIndex = requestPath.indexOf('?');

            if (queryIndex >= 0) {
                requestPath = requestPath.substring(
                        0, queryIndex);
            }

            if (!requestPath.startsWith("/f/")) {
                writeError(socketOutput, 404, "Not Found");
                return;
            }

            String token = requestPath.substring(3);

            if (token.isEmpty()
                    || token.contains("/")
                    || token.contains("\\")
                    || token.contains("..")) {
                writeError(socketOutput, 404, "Not Found");
                return;
            }

            MediaSource source = sources.get(token);

            if (source == null) {
                writeError(socketOutput, 404, "Not Found");
                return;
            }

            serveSource(
                    socketOutput,
                    source,
                    rangeHeader,
                    headRequest);

        } catch (Exception error) {

            Log.w(TAG,
                    "Media request could not be completed.",
                    error);
        }
    }

    private void serveSource(
            OutputStream output,
            MediaSource source,
            String rangeHeader,
            boolean headRequest) throws IOException {

        long totalSize = source.size;

        if (totalSize < 0L) {
            writeError(
                    output,
                    500,
                    "Media Size Unavailable");
            return;
        }

        long start = 0L;
        long end = totalSize - 1L;

        boolean partial = false;

        if (rangeHeader != null
                && rangeHeader.startsWith("bytes=")) {

            String range = rangeHeader.substring(6).trim();

            if (range.contains(",")) {
                writeRangeError(output, totalSize);
                return;
            }

            int dash = range.indexOf('-');

            if (dash < 0) {
                writeRangeError(output, totalSize);
                return;
            }

            try {

                String startText =
                        range.substring(0, dash).trim();

                String endText =
                        range.substring(dash + 1).trim();

                if (startText.isEmpty()) {

                    long suffixLength =
                            Long.parseLong(endText);

                    if (suffixLength <= 0L || totalSize <= 0L) {
                        writeRangeError(output, totalSize);
                        return;
                    }

                    start = Math.max(
                            0L,
                            totalSize - suffixLength);

                } else {

                    start = Long.parseLong(startText);

                    if (!endText.isEmpty()) {
                        end = Long.parseLong(endText);
                    }
                }

                if (start < 0L
                        || start >= totalSize
                        || end < start) {
                    writeRangeError(output, totalSize);
                    return;
                }

                end = Math.min(end, totalSize - 1L);

                partial = true;

            } catch (NumberFormatException error) {

                writeRangeError(output, totalSize);
                return;
            }
        }

        long contentLength = totalSize <= 0L
                ? 0L
                : end - start + 1L;

        String status = partial
                ? "206 Partial Content"
                : "200 OK";

        StringBuilder response = new StringBuilder();

        response.append("HTTP/1.1 ")
                .append(status)
                .append("\r\n");

        response.append("Content-Type: ")
                .append(source.mimeType)
                .append("\r\n");

        response.append("Content-Length: ")
                .append(contentLength)
                .append("\r\n");

        response.append("Accept-Ranges: bytes\r\n");
        response.append("Access-Control-Allow-Origin: *\r\n");
        response.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        response.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        response.append("Cache-Control: no-store\r\n");
        response.append("Connection: close\r\n");

        if (partial) {
            response.append("Content-Range: bytes ")
                    .append(start)
                    .append("-")
                    .append(end)
                    .append("/")
                    .append(totalSize)
                    .append("\r\n");
        }

        response.append("\r\n");

        output.write(response.toString().getBytes(
                StandardCharsets.US_ASCII));

        output.flush();

        if (headRequest || contentLength <= 0L) {
            return;
        }

        try (InputStream input =
                     resolver.openInputStream(source.uri)) {

            if (input == null) {
                throw new IOException(
                        "Cannot open selected phone media.");
            }

            skipFully(input, start);

            byte[] buffer = new byte[BUFFER_SIZE];

            long remaining = contentLength;

            while (remaining > 0L) {

                int wanted = (int) Math.min(
                        buffer.length,
                        remaining);

                int count = input.read(
                        buffer, 0, wanted);

                if (count < 0) {
                    break;
                }

                output.write(buffer, 0, count);

                remaining -= count;
            }

            output.flush();

            if (remaining > 0L) {
                Log.w(TAG,
                        "Media stream ended before all bytes were sent.");
            }
        }
    }

    private void skipFully(
            InputStream input,
            long bytes) throws IOException {

        long remaining = bytes;

        while (remaining > 0L) {

            long skipped = input.skip(remaining);

            if (skipped > 0L) {
                remaining -= skipped;

            } else {

                if (input.read() == -1) {
                    throw new IOException(
                            "Media ended before requested range.");
                }

                remaining--;
            }
        }
    }

    private long getContentSize(Uri uri) {

        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.SIZE},
                null,
                null,
                null)) {

            if (cursor != null && cursor.moveToFirst()) {

                int index = cursor.getColumnIndex(
                        OpenableColumns.SIZE);

                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }

        } catch (Exception error) {

            Log.w(TAG,
                    "Could not query media size.",
                    error);
        }

        try (android.content.res.AssetFileDescriptor descriptor =
                     resolver.openAssetFileDescriptor(uri, "r")) {

            if (descriptor != null) {
                return descriptor.getLength();
            }

        } catch (Exception error) {

            Log.w(TAG,
                    "Could not read media descriptor size.",
                    error);
        }

        return -1L;
    }

    private String createToken() {

        byte[] bytes = new byte[24];

        secureRandom.nextBytes(bytes);

        StringBuilder result = new StringBuilder();

        for (byte value : bytes) {
            result.append(String.format(
                    java.util.Locale.ROOT,
                    "%02x",
                    value & 0xff));
        }

        return result.toString();
    }

    private String findLocalIpv4Address() {

        try {

            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            if (interfaces == null) {
                return null;
            }

            String fallback = null;

            for (NetworkInterface networkInterface :
                    Collections.list(interfaces)) {

                try {

                    if (!networkInterface.isUp()
                            || networkInterface.isLoopback()) {
                        continue;
                    }

                    Enumeration<InetAddress> addresses =
                            networkInterface.getInetAddresses();

                    for (InetAddress address :
                            Collections.list(addresses)) {

                        if (!(address instanceof Inet4Address)
                                || address.isLoopbackAddress()
                                || address.isLinkLocalAddress()
                                || !address.isSiteLocalAddress()) {
                            continue;
                        }

                        String host = address.getHostAddress();

                        if (host == null || host.isEmpty()) {
                            continue;
                        }

                        String interfaceName =
                                networkInterface.getName();

                        if (interfaceName != null
                                && interfaceName.startsWith("wlan")) {
                            return host;
                        }

                        if (fallback == null) {
                            fallback = host;
                        }
                    }

                } catch (Exception ignored) {
                }
            }

            return fallback;

        } catch (Exception error) {

            Log.w(TAG,
                    "Could not find local IPv4 address.",
                    error);

            return null;
        }
    }

    private void writeRangeError(
            OutputStream output,
            long totalSize) throws IOException {

        String response =
                "HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */"
                        + totalSize
                        + "\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";

        output.write(response.getBytes(
                StandardCharsets.US_ASCII));

        output.flush();
    }

    private void writeError(
            OutputStream output,
            int status,
            String message) throws IOException {

        byte[] body = message.getBytes(
                StandardCharsets.UTF_8);

        String response =
                "HTTP/1.1 "
                        + status
                        + " "
                        + message
                        + "\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: "
                        + body.length
                        + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";

        output.write(response.getBytes(
                StandardCharsets.US_ASCII));

        output.write(body);
        output.flush();
    }

    private static final class MediaSource {

        final Uri uri;
        final String mimeType;
        final long size;

        MediaSource(
                Uri uri,
                String mimeType,
                long size) {

            this.uri = uri;
            this.mimeType = mimeType;
            this.size = size;
        }
    }
}
