
package com.donut4u.karaoke;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class TvPairingTls {

    private TvPairingTls() {
    }

    public static SSLSocket connect(
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

        final String expectedCode = pairingCode;

        X509TrustManager trustManager =
                new X509TrustManager() {

            @Override
            public void checkClientTrusted(
                    X509Certificate[] chain,
                    String authType
            ) throws CertificateException {
                throw new CertificateException(
                        "Client certificates are not accepted."
                );
            }

            @Override
            public void checkServerTrusted(
                    X509Certificate[] chain,
                    String authType
            ) throws CertificateException {

                if (chain == null || chain.length == 0
                        || chain[0] == null) {
                    throw new CertificateException(
                            "TV certificate is missing."
                    );
                }

                try {
                    String actualCode =
                            getCertificatePairingCode(chain[0]);

                    if (!expectedCode.equals(actualCode)) {
                        throw new CertificateException(
                                "The TV certificate does not match "
                                        + "the pairing code."
                        );
                    }

                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException(
                            "Could not verify TV certificate.",
                            e
                    );
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext sslContext =
                SSLContext.getInstance("TLS");

        sslContext.init(
                null,
                new TrustManager[]{trustManager},
                null
        );

        SSLSocket sslSocket =
                (SSLSocket) sslContext
                        .getSocketFactory()
                        .createSocket();

        try {
            sslSocket.connect(
                    new InetSocketAddress(address, port),
                    timeoutMillis
            );

            sslSocket.setSoTimeout(15000);

            String[] supported =
                    sslSocket.getSupportedProtocols();

            java.util.ArrayList<String> enabled =
                    new java.util.ArrayList<>();

            for (String protocol : supported) {
                if ("TLSv1.3".equals(protocol)
                        || "TLSv1.2".equals(protocol)) {
                    enabled.add(protocol);
                }
            }

            if (enabled.isEmpty()) {
                throw new IllegalStateException(
                        "This device does not support TLS 1.2 or 1.3."
                );
            }

            sslSocket.setEnabledProtocols(
                    enabled.toArray(new String[0])
            );

            sslSocket.startHandshake();

            return sslSocket;

        } catch (Exception e) {
            try {
                sslSocket.close();
            } catch (Exception ignored) {
            }

            throw e;
        }
    }

    private static String getCertificatePairingCode(
            X509Certificate certificate
    ) throws Exception {

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
}
