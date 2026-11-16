
package com.donut4u.karaoke;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

/**
 * Compact Google Cast mini-controller for Donut4U.
 *
 * Displays current receiver media and playback controls.
 */
public final class Donut4UMiniCastControllerView
        extends LinearLayout {

    private final TextView titleView;
    private final TextView subtitleView;
    private final Button playbackButton;
    private final Button expandedButton;

    private RemoteMediaClient remoteClient;

    private final RemoteMediaClient.Callback mediaCallback =
            new RemoteMediaClient.Callback() {

        @Override
        public void onStatusUpdated() {
            refreshController();
        }

        @Override
        public void onMetadataUpdated() {
            refreshController();
        }
    };

    public Donut4UMiniCastControllerView(Context context) {
        super(context);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8)
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(Color.rgb(20, 42, 38));
        background.setCornerRadius(dp(12));
        setBackground(background);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                14
        );
        titleView.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        titleView.setSingleLine(true);
        titleView.setEllipsize(
                android.text.TextUtils.TruncateAt.END
        );
        titleView.setText("Google Cast");

        subtitleView = new TextView(context);
        subtitleView.setTextColor(
                Color.rgb(190, 210, 205)
        );
        subtitleView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                11
        );
        subtitleView.setSingleLine(true);
        subtitleView.setText("Not playing");

        LinearLayout textContainer =
                new LinearLayout(context);

        textContainer.setOrientation(VERTICAL);
        textContainer.setGravity(Gravity.CENTER_VERTICAL);

        textContainer.addView(
                titleView,
                new LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                )
        );

        textContainer.addView(
                subtitleView,
                new LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                )
        );

        addView(
                textContainer,
                new LayoutParams(
                        0,
                        LayoutParams.WRAP_CONTENT,
                        1
                )
        );

        playbackButton = new Button(context);
        playbackButton.setText("Play");
        playbackButton.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                12
        );

        playbackButton.setOnClickListener(
                view -> togglePlayback()
        );

        addView(
                playbackButton,
                new LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        dp(44)
                )
        );

        expandedButton = new Button(context);
        expandedButton.setText("Open");
        expandedButton.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                12
        );

        expandedButton.setOnClickListener(
                view -> openExpandedController()
        );

        addView(
                expandedButton,
                new LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        dp(44)
                )
        );

        setVisibility(GONE);
    }

    /**
     * Connects the mini-controller to the active Cast session.
     */
    public void connect() {

        disconnect();

        try {

            CastSession session =
                    CastContext.getSharedInstance(
                            getContext()
                    )
                    .getSessionManager()
                    .getCurrentCastSession();

            if (session == null || !session.isConnected()) {
                setVisibility(GONE);
                return;
            }

            remoteClient =
                    session.getRemoteMediaClient();

            if (remoteClient == null) {
                setVisibility(GONE);
                return;
            }

            remoteClient.registerCallback(
                    mediaCallback
            );

            refreshController();

        } catch (Exception e) {

            remoteClient = null;
            setVisibility(GONE);
        }
    }

    /**
     * Unregisters the media callback.
     */
    public void disconnect() {

        if (remoteClient != null) {

            try {
                remoteClient.unregisterCallback(
                        mediaCallback
                );
            } catch (Exception ignored) {
                // Session may already have ended.
            }
        }

        remoteClient = null;
        setVisibility(GONE);
    }

    private void refreshController() {

        if (remoteClient == null) {
            setVisibility(GONE);
            return;
        }

        MediaStatus status =
                remoteClient.getMediaStatus();

        if (status == null) {
            setVisibility(GONE);
            return;
        }

        MediaInfo mediaInfo =
                status.getMediaInfo();

        if (mediaInfo == null) {
            setVisibility(GONE);
            return;
        }

        MediaMetadata metadata =
                mediaInfo.getMetadata();

        String title = "Now playing";
        String subtitle = "Donut4U Karaoke";

        if (metadata != null) {

            String receivedTitle =
                    metadata.getString(
                            MediaMetadata.KEY_TITLE
                    );

            String receivedSubtitle =
                    metadata.getString(
                            MediaMetadata.KEY_SUBTITLE
                    );

            if (receivedTitle != null
                    && !receivedTitle.trim().isEmpty()) {
                title = receivedTitle;
            }

            if (receivedSubtitle != null
                    && !receivedSubtitle.trim().isEmpty()) {
                subtitle = receivedSubtitle;
            }
        }

        titleView.setText(title);
        subtitleView.setText(subtitle);

        int playerState =
                status.getPlayerState();

        if (playerState == MediaStatus.PLAYER_STATE_PLAYING) {
            playbackButton.setText("Pause");
        } else {
            playbackButton.setText("Play");
        }

        setVisibility(VISIBLE);
    }

    private void togglePlayback() {

        if (remoteClient == null) {
            connect();
        }

        if (remoteClient == null) {
            return;
        }

        MediaStatus status =
                remoteClient.getMediaStatus();

        if (status == null) {
            return;
        }

        if (status.getPlayerState()
                == MediaStatus.PLAYER_STATE_PLAYING) {

            remoteClient.pause();

        } else {

            remoteClient.play();
        }
    }

    private void openExpandedController() {

        Context context = getContext();

        Intent intent = new Intent(
                context,
                Donut4UExpandedCastControllerActivity.class
        );

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
        );

        context.startActivity(intent);
    }

    private int dp(float value) {

        return (int) (
                value
                        * getResources()
                        .getDisplayMetrics().density
                        + 0.5f
        );
    }
}
