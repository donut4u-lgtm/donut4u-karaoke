
package com.donut4u.karaoke;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class TvPairingTls {

    private TvPairingTls() {
    }

    public static Socket connect(
            InetAddress address,
            int port,
            String pairingCode,
            int timeoutMillis
    ) throws Exception {

        if (address == null || port <= 0) {
            throw new IllegalArgumentException(
                    "Invalid TV address or port."
            );
        }

        if (pairingCode == null
                || !pairingCode.matches("[0-9]{6}")) {
            throw new IllegalArgumentException(
                    "A six-digit pairing code is required."
            );
        }

        Socket socket = new Socket();

        try {
            socket.connect(
                    new InetSocketAddress(address, port),
                    timeoutMillis
            );

            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            return socket;

        } catch (Exception e) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }

            throw e;
        }
    }
}
