
package com.donut4u.karaoke;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;

import java.util.Collections;
import java.util.List;

/**
 * Google Cast configuration for Donut4U Karaoke.
 *
 * This initial version uses Google's Default Media Receiver.
 * A dedicated Donut4U receiver application ID will replace it
 * when the custom receiver is registered.
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

        return new CastOptions.Builder()
                .setReceiverApplicationId(
                        RECEIVER_APPLICATION_ID
                )
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(
            Context context
    ) {
        return Collections.emptyList();
    }
}
