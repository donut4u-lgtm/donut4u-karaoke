
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class AppUpdateChecker {

    private static final String VERSION_URL =
            "https://github.com/donut4u-lgtm/donut4u-karaoke/releases/download/v1.0.0/version.txt";

    private static final String APK_URL =
            "https://github.com/donut4u-lgtm/donut4u-karaoke/releases/download/v1.0.0/Donut4U-Karaoke.apk";

    private AppUpdateChecker() {
        // Utility class.
    }

    public static void checkForUpdates(Activity activity) {
        if (activity == null
                || activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }

        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(
                        VERSION_URL + "?t=" + System.currentTimeMillis()
                );

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setUseCaches(false);
                connection.setRequestProperty(
                        "Cache-Control",
                        "no-cache"
                );

                if (connection.getResponseCode()
                        != HttpURLConnection.HTTP_OK) {
                    return;
                }

                StringBuilder response = new StringBuilder();

                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line.trim());
                    }
                }

                String versionText = response.toString().trim();

                if (versionText.isEmpty()) {
                    return;
                }

                int latestVersionCode = Integer.parseInt(versionText);
                int installedVersionCode = BuildConfig.VERSION_CODE;

                if (latestVersionCode > installedVersionCode) {
                    final int availableVersion = latestVersionCode;

                    activity.runOnUiThread(() ->
                            showUpdateDialog(
                                    activity,
                                    availableVersion
                            )
                    );
                }

            } catch (Exception ignored) {
                // Update checking must never block normal app use.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "Donut4U-AppUpdateCheck");

        thread.setDaemon(true);
        thread.start();
    }

    private static void showUpdateDialog(
            Activity activity,
            int latestVersionCode
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Update Available")
                .setMessage(
                        "A newer version of Donut4U Karaoke is available.\n\n"
                                + "Installed version code: "
                                + BuildConfig.VERSION_CODE
                                + "\nLatest version code: "
                                + latestVersionCode
                                + "\n\nDownload the latest APK now?"
                )
                .setPositiveButton(
                        "DOWNLOAD UPDATE",
                        (dialog, which) -> {
                            Intent intent = new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(APK_URL)
                            );
                            activity.startActivity(intent);
                        }
                )
                .setNegativeButton(
                        "LATER",
                        (dialog, which) -> dialog.dismiss()
                )
                .setCancelable(true)
                .show();
    }
}
