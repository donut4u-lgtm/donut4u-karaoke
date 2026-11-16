
package com.donut4u.karaoke;

import android.content.Context;

import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;

import java.util.Collections;
import java.util.List;

/**
 * Google Cast configuration for Donut4U Karaoke.
 *
 * Replace RECEIVER_APPLICATION_ID after the Donut4U
 * receiver is registered in the Google Cast Developer Console.
 */
public final class Donut4UCastOptionsProvider
        implements OptionsProvider {

    /*
     * Configuration placeholder only.
     * This is not a real Google Cast application ID.
     */
    public static final String RECEIVER_APPLICATION_ID =
            "YOUR_DONUT4U_CAST_APP_ID";

    private static final String TAG =
            "Donut4UCastOptions";

    public static boolean isReceiverConfigured() {
        return RECEIVER_APPLICATION_ID != null
                && !RECEIVER_APPLICATION_ID.trim().isEmpty()
                && !"YOUR_DONUT4U_CAST_APP_ID".equals(
                        RECEIVER_APPLICATION_ID
                );
    }

    @Override
    public CastOptions getCastOptions(Context context) {

        if (!isReceiverConfigured()) {
            android.util.Log.w(
                    TAG,
                    "Donut4U Cast receiver is not registered yet."
            );
        }

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
