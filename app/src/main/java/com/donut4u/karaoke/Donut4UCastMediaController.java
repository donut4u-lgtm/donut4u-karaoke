
package com.donut4u.karaoke;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.framework.media.RemoteMediaClient.MediaChannelResult;

import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import java.util.Locale;

/**
 * Sends supported, directly accessible media URLs to
 * an active Google Cast receiver.
 *
 * This controller does not convert local MIDI/KAR playback
 * into a network media stream.
 */
public final class Donut4UCastMediaController {

    private static final String TAG =
            "Donut4UCastMedia";

    private Donut4UCastMediaController() {
        // Utility class.
    }

    /**
     * Returns true when a Cast session is currently connected.
     */
    public static boolean isConnected(Context context) {

        if (context == null) {
            return false;
        }

        try {

            CastSession session =
                    CastContext.getSharedInstance(context)
                            .getSessionManager()
                            .getCurrentCastSession();

            return session != null
                    && session.isConnected()
                    && session.getRemoteMediaClient() != null;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not check Cast connection.",
                    e
            );

            return false;
        }
    }

    /**
     * Loads a directly accessible media URL on the connected receiver.
     *
     * The URL must be a real media resource supported by the receiver,
     * such as a supported MP4 or streaming media URL.
     *
     * @param context activity or application context
     * @param mediaUrl direct HTTP or HTTPS media URL
     * @param title song or media title
     * @param artist artist or channel name
     * @param contentType MIME type, for example video/mp4
     * @return true when the load request was submitted
     */
    public static boolean playMedia(
            Context context,
            String mediaUrl,
            String title,
            String artist,
            String contentType
    ) {

        if (context == null
                || mediaUrl == null
                || mediaUrl.trim().isEmpty()
                || contentType == null
                || contentType.trim().isEmpty()) {

            Log.w(
                    TAG,
                    "Cast media request has missing information."
            );

            return false;
        }

        Uri uri;

        try {

            uri = Uri.parse(mediaUrl.trim());

        } catch (Exception e) {

            Log.e(TAG, "Invalid media URL.", e);
            return false;
        }

        String scheme = uri.getScheme();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {

            Log.w(
                    TAG,
                    "Cast requires a network-accessible HTTP or HTTPS URL."
            );

            return false;
        }

        try {

            CastSession session =
                    CastContext.getSharedInstance(context)
                            .getSessionManager()
                            .getCurrentCastSession();

            if (session == null || !session.isConnected()) {

                Log.w(TAG, "No connected Cast receiver.");
                return false;
            }

            RemoteMediaClient remoteClient =
                    session.getRemoteMediaClient();

            if (remoteClient == null) {

                Log.w(TAG, "Cast media client is unavailable.");
                return false;
            }

            MediaMetadata metadata =
                    new MediaMetadata(
                            MediaMetadata.MEDIA_TYPE_MOVIE
                    );

            metadata.putString(
                    MediaMetadata.KEY_TITLE,
                    safe(title, "Donut4U Karaoke")
            );

            metadata.putString(
                    MediaMetadata.KEY_SUBTITLE,
                    safe(artist, "Donut4U")
            );

            MediaInfo mediaInfo =
                    new MediaInfo.Builder(uri.toString())
                            .setStreamType(
                                    MediaInfo.STREAM_TYPE_BUFFERED
                            )
                            .setContentType(
                                    contentType.trim().toLowerCase(
                                            Locale.ROOT
                                    )
                            )
                            .setMetadata(metadata)
                            .build();

            remoteClient.load(mediaInfo, true)
                    .setResultCallback(
                            new ResultCallback<MediaChannelResult>() {
                                @Override
                                public void onResult(
                                        MediaChannelResult result
                                ) {

                                    Status status =
                                            result.getStatus();

                                    if (status != null
                                            && status.isSuccess()) {

                                        Log.i(
                                                TAG,
                                                "Cast media load accepted."
                                        );

                                    } else {

                                        Log.e(
                                                TAG,
                                                "Cast media load failed: "
                                                        + status
                                        );
                                    }
                                }
                            }
                    );

            return true;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not send media to Cast receiver.",
                    e
            );

            return false;
        }
    }

    /**
     * Pauses the media currently playing on the receiver.
     */
    public static boolean pause(Context context) {
        RemoteMediaClient client = getRemoteClient(context);

        if (client == null) {
            return false;
        }

        client.pause();
        return true;
    }

    /**
     * Resumes media on the receiver.
     */
    public static boolean resume(Context context) {
        RemoteMediaClient client = getRemoteClient(context);

        if (client == null) {
            return false;
        }

        client.play();
        return true;
    }

    /**
     * Stops media on the receiver.
     */
    public static boolean stop(Context context) {
        RemoteMediaClient client = getRemoteClient(context);

        if (client == null) {
            return false;
        }

        client.stop();
        return true;
    }

    private static RemoteMediaClient getRemoteClient(
            Context context
    ) {

        if (context == null) {
            return null;
        }

        try {

            CastSession session =
                    CastContext.getSharedInstance(context)
                            .getSessionManager()
                            .getCurrentCastSession();

            if (session == null || !session.isConnected()) {
                return null;
            }

            return session.getRemoteMediaClient();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not access Cast media client.",
                    e
            );

            return null;
        }
    }

    private static String safe(
            String value,
            String fallback
    ) {

        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }

        return value.trim();
    }
}
