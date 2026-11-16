
package com.donut4u.karaoke;

import android.content.Context;
import android.util.Log;

import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

/**
 * Creates the standard Google Cast device-selection button.
 *
 * The activity supplies its own position and layout.
 * Device discovery and route selection are handled by
 * the Google Cast Sender SDK.
 */
public final class Donut4UCastButton {

    private static final String TAG = "Donut4UCastButton";

    private Donut4UCastButton() {
        // Utility class.
    }

    /**
     * Creates and configures a Google Cast button.
     *
     * Call this method from the Android main thread,
     * after the activity has created its interface.
     */
    public static MediaRouteButton create(Context context) {

        if (context == null) {
            throw new IllegalArgumentException(
                    "Context cannot be null."
            );
        }

        MediaRouteButton button =
                new MediaRouteButton(context);

        button.setContentDescription("Cast to TV");

        try {

            CastContext.getSharedInstance(context);

            CastButtonFactory.setUpMediaRouteButton(
                    context.getApplicationContext(),
                    button
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Google Cast button initialization failed.",
                    e
            );

            button.setEnabled(false);
        }

        return button;
    }
}
