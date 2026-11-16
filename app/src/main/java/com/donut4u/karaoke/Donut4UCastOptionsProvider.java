
package com.donut4u.karaoke;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;
import com.google.android.gms.cast.framework.media.NotificationOptions;

import java.util.Collections;
import java.util.List;

/**
 * Google Cast configuration for Donut4U Karaoke.
 *
 * Uses Google's Default Media Receiver until a dedicated
 * Donut4U receiver application ID is registered.
 */
public final class Donut4UCastOptionsProvider
        implements OptionsProvider {

    public static final String RECEIVER_APPLICATION_ID =
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID;

    private static final String TAG =
            "Donut4UCastOptions";

    @Override
    public CastOptions getCastOptions(Context context) {

        android.util.Log.i(
                TAG,
                "Initializing Google Cast with receiver: "
                        + RECEIVER_APPLICATION_ID
        );

        NotificationOptions notificationOptions =
                new NotificationOptions.Builder()
                        .setTargetActivityClassName(
                                Donut4UExpandedCastControllerActivity.class
                                        .getName()
                        )
                        .build();

        CastMediaOptions mediaOptions =
                new CastMediaOptions.Builder()
                        .setNotificationOptions(
                                notificationOptions
                        )
                        .setExpandedControllerActivityClassName(
                                Donut4UExpandedCastControllerActivity.class
                                        .getName()
                        )
                        .build();

        return new CastOptions.Builder()
                .setReceiverApplicationId(
                        RECEIVER_APPLICATION_ID
                )
                .setCastMediaOptions(mediaOptions)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(
            Context context
    ) {
        return Collections.emptyList();
    }
}
