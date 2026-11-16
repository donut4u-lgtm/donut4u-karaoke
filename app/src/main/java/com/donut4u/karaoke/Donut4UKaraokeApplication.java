
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;

public final class Donut4UKaraokeApplication
        extends Application {

    private static final String TAG =
            "Donut4UCast";

    private static final String STAGE1_ACTIVITY =
            "com.donut4u.karaoke.OnlineSongActivity";

    private static final String STAGE2_ACTIVITY =
            "com.donut4u.karaoke.KaraokeStudioActivity";

    private static final String EXTRA_TV_MODE =
            "com.donut4u.karaoke.EXTRA_TV_MODE";

    private static final int CAST_BUTTON_TAG =
            0x444F4E;

    private static final int MINI_CONTROLLER_TAG =
            0x444F31;

    private Activity resumedActivity;

    private SessionManager sessionManager;

    private final SessionManagerListener<CastSession>
            sessionManagerListener =
            new SessionManagerListener<CastSession>() {

        @Override
        public void onSessionStarting(CastSession session) {
        }

        @Override
        public void onSessionStarted(
                CastSession session,
                String sessionId
        ) {
            refreshMiniController();
        }

        @Override
        public void onSessionStartFailed(
                CastSession session,
                int error
        ) {
            hideMiniController();
        }

        @Override
        public void onSessionEnding(CastSession session) {
        }

        @Override
        public void onSessionEnded(
                CastSession session,
                int error
        ) {
            hideMiniController();
        }

        @Override
        public void onSessionResuming(
                CastSession session,
                String sessionId
        ) {
        }

        @Override
        public void onSessionResumed(
                CastSession session,
                boolean wasSuspended
        ) {
            refreshMiniController();
        }

        @Override
        public void onSessionResumeFailed(
                CastSession session,
                int error
        ) {
            hideMiniController();
        }

        @Override
        public void onSessionSuspended(
                CastSession session,
                int reason
        ) {
            hideMiniController();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(
                new ActivityLifecycleCallbacks() {

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
            public void onActivityResumed(Activity activity) {

                resumedActivity = activity;

                addCastButton(activity);
                addMiniController(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {

                if (resumedActivity == activity) {
                    resumedActivity = null;
                }

                removeMiniControllerCallback(activity);
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

                removeMiniControllerCallback(activity);
            }
        });

        try {

            CastContext castContext =
                    CastContext.getSharedInstance(this);

            sessionManager =
                    castContext.getSessionManager();

            sessionManager.addSessionManagerListener(
                    sessionManagerListener,
                    CastSession.class
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not register Cast session listener.",
                    e
            );
        }
    }

    private boolean isSupportedPhoneActivity(
            Activity activity
    ) {

        if (activity == null) {
            return false;
        }

        String activityName =
                activity.getClass().getName();

        boolean supportedActivity =
                STAGE1_ACTIVITY.equals(activityName)
                        || STAGE2_ACTIVITY.equals(activityName);

        if (!supportedActivity) {
            return false;
        }

        if (activity.getIntent() != null
                && activity.getIntent().getBooleanExtra(
                        EXTRA_TV_MODE,
                        false
                )) {
            return false;
        }

        return !activity.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK
        );
    }

    private FrameLayout getDecorLayout(Activity activity) {

        if (activity == null || activity.isFinishing()) {
            return null;
        }

        View decor =
                activity.getWindow().getDecorView();

        if (!(decor instanceof FrameLayout)) {
            return null;
        }

        return (FrameLayout) decor;
    }

    private void addCastButton(Activity activity) {

        if (!isSupportedPhoneActivity(activity)) {
            return;
        }

        try {

            FrameLayout decorLayout =
                    getDecorLayout(activity);

            if (decorLayout == null) {
                return;
            }

            if (decorLayout.findViewWithTag(
                    CAST_BUTTON_TAG
            ) != null) {
                return;
            }

            MediaRouteButton castButton =
                    Donut4UCastButton.create(activity);

            castButton.setTag(CAST_BUTTON_TAG);

            castButton.setContentDescription(
                    "Cast to TV"
            );

            int buttonSize =
                    dp(activity, 48);

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

            Log.i(
                    TAG,
                    "Google Cast button added to "
                            + activity.getClass().getSimpleName()
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not add Google Cast button.",
                    e
            );
        }
    }

    private void addMiniController(Activity activity) {

        if (!isSupportedPhoneActivity(activity)) {
            return;
        }

        try {

            FrameLayout decorLayout =
                    getDecorLayout(activity);

            if (decorLayout == null) {
                return;
            }

            View existing =
                    decorLayout.findViewWithTag(
                            MINI_CONTROLLER_TAG
                    );

            Donut4UMiniCastControllerView miniController;

            if (existing instanceof Donut4UMiniCastControllerView) {

                miniController =
                        (Donut4UMiniCastControllerView) existing;

            } else {

                miniController =
                        new Donut4UMiniCastControllerView(activity);

                miniController.setTag(
                        MINI_CONTROLLER_TAG
                );

                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                        );

                params.leftMargin = dp(activity, 12);
                params.rightMargin = dp(activity, 12);
                params.bottomMargin = dp(activity, 12);

                miniController.setElevation(
                        dp(activity, 12)
                );

                decorLayout.addView(
                        miniController,
                        params
                );
            }

            miniController.connect();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not add Cast mini-controller.",
                    e
            );
        }
    }

    private void refreshMiniController() {

        Activity activity = resumedActivity;

        if (activity == null) {
            return;
        }

        if (!isSupportedPhoneActivity(activity)) {
            return;
        }

        addMiniController(activity);
    }

    private void hideMiniController() {

        Activity activity = resumedActivity;

        if (activity == null) {
            return;
        }

        removeMiniControllerCallback(activity);
    }

    private void removeMiniControllerCallback(
            Activity activity
    ) {

        FrameLayout decorLayout =
                getDecorLayout(activity);

        if (decorLayout == null) {
            return;
        }

        View existing =
                decorLayout.findViewWithTag(
                        MINI_CONTROLLER_TAG
                );

        if (existing instanceof Donut4UMiniCastControllerView) {

            ((Donut4UMiniCastControllerView) existing)
                    .disconnect();
        }
    }

    private int dp(
            Activity activity,
            float value
    ) {

        return (int) (
                value
                        * activity.getResources()
                        .getDisplayMetrics().density
                        + 0.5f
        );
    }
}
