
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.mediarouter.app.MediaRouteButton;

public final class Donut4UKaraokeApplication
        extends Application {

    private static final String STAGE2_ACTIVITY =
            "com.donut4u.karaoke.KaraokeStudioActivity";

    private static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final int CAST_BUTTON_TAG =
            0x444F4E;

    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(
                new ActivityLifecycleCallbacks() {

            @Override
            public void onActivityResumed(Activity activity) {

                addCastButton(activity);
            }

            @Override
            public void onActivityCreated(
                    Activity activity,
                    Bundle savedInstanceState
            ) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(
                    Activity activity,
                    Bundle outState
            ) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }

    private void addCastButton(Activity activity) {

        if (activity == null
                || !STAGE2_ACTIVITY.equals(
                        activity.getClass().getName()
                )) {
            return;
        }

        // Never show the phone Cast control in TV ONLY mode.
        if (activity.getIntent() != null
                && activity.getIntent().getBooleanExtra(
                        EXTRA_TV_MODE,
                        false
                )) {
            return;
        }

        try {

            View decor =
                    activity.getWindow().getDecorView();

            if (!(decor instanceof FrameLayout)) {
                return;
            }

            FrameLayout decorLayout =
                    (FrameLayout) decor;

            View existing =
                    decorLayout.findViewWithTag(
                            CAST_BUTTON_TAG
                    );

            if (existing != null) {
                return;
            }

            MediaRouteButton castButton =
                    Donut4UCastButton.create(activity);

            castButton.setTag(CAST_BUTTON_TAG);

            castButton.setContentDescription(
                    "Cast to TV"
            );

            int buttonSize = dp(activity, 48);

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(
                            buttonSize,
                            buttonSize,
                            Gravity.TOP | Gravity.END
                    );

            params.topMargin = dp(activity, 8);
            params.rightMargin = dp(activity, 8);

            castButton.setElevation(
                    dp(activity, 8)
            );

            decorLayout.addView(
                    castButton,
                    params
            );

        } catch (Exception e) {

            android.util.Log.e(
                    "Donut4UCast",
                    "Could not add Google Cast button.",
                    e
            );
        }
    }

    private int dp(Activity activity, float value) {

        return (int) (
                value
                        * activity.getResources()
                        .getDisplayMetrics().density
                        + 0.5f
        );
    }
}
