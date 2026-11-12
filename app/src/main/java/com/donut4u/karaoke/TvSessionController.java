
package com.donut4u.karaoke;

import android.content.Context;

import org.json.JSONObject;

/**
 * Central coordinator for the Donut4U TV session.
 *
 * TvSessionController owns the application-wide listener for
 * TvKeyboardReceiver and forwards incoming phone commands to
 * the currently active TV stage.
 *
 * Stage changes must not stop the encrypted connection.
 */
public final class TvSessionController
        implements TvKeyboardReceiver.Listener {

    private static volatile TvSessionController instance;

    private final Context appContext;

    private volatile Observer observer;

    private volatile TvCommandHandler activeStageHandler;

    private volatile boolean sessionStarted = false;

    private volatile int activeStage = 0;

    private volatile long lastStateSequence = 0L;

    private volatile JSONObject lastPhoneState;

    /**
     * Receives session information and commands that are not
     * currently handled by an active stage.
     */
    public interface Observer {

        default void onPairingCodeReady(String code) {
        }

        default void onStatusChanged(String message) {
        }

        default void onError(String message) {
        }

        default void onPhoneSearchRequested(String songTitle) {
        }

        default void onPhoneTextInput(
                String target,
                String text,
                String action
        ) {
        }

        default void onPhoneStateReceived(
                JSONObject state,
                long sequence
        ) {
        }
    }

    /**
     * Receives commands intended for the currently active stage.
     *
     * Stage activities can implement this interface without
     * becoming the TvKeyboardReceiver listener themselves.
     */
    public interface TvCommandHandler {

        default void onPhoneSearchRequested(String songTitle) {
        }

        default void onPhoneTextInput(
                String target,
                String text,
                String action
        ) {
        }

        default void onPhoneStateReceived(
                JSONObject state,
                long sequence
        ) {
        }
    }

    private TvSessionController(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * Returns the single application-wide controller instance.
     */
    public static TvSessionController getInstance(
            Context context
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Context cannot be null."
            );
        }

        if (instance == null) {
            synchronized (TvSessionController.class) {
                if (instance == null) {
                    instance = new TvSessionController(context);
                }
            }
        }

        return instance;
    }

    /**
     * Starts the shared encrypted phone-TV session.
     *
     * Call this from the TV session/menu owner.
     * Do not call it independently from individual stages.
     */
    public synchronized void startSession(
            Observer sessionObserver
    ) {
        observer = sessionObserver;

        if (sessionStarted
                && TvKeyboardReceiver.isSessionEnabled()) {
            return;
        }

        sessionStarted = true;

        lastStateSequence = 0L;
        lastPhoneState = null;
        activeStage = 0;

        TvKeyboardReceiver.enableSession(
                appContext,
                this
        );
    }

    /**
     * Updates the screen observing session status.
     *
     * This does not restart the connection or replace the
     * TvKeyboardReceiver listener.
     */
    public void setObserver(Observer sessionObserver) {
        observer = sessionObserver;
    }

    /**
     * Registers the command handler for the currently visible stage.
     *
     * Only one stage handler is active at a time.
     */
    public void registerStageHandler(
            TvCommandHandler handler
    ) {
        activeStageHandler = handler;
    }

    /**
     * Removes a stage handler only if it is still the active handler.
     *
     * A previous stage cannot accidentally unregister a newer stage.
     */
    public void unregisterStageHandler(
            TvCommandHandler handler
    ) {
        if (handler != null
                && activeStageHandler == handler) {
            activeStageHandler = null;
        }
    }

    /**
     * Updates the active stage.
     *
     * Valid stage numbers are 1 through 4.
     */
    public void setActiveStage(int stage) {
        if (stage < 1 || stage > 4) {
            return;
        }

        activeStage = stage;
    }

    public int getActiveStage() {
        return activeStage;
    }

    /**
     * Returns whether the shared connection session is enabled.
     */
    public boolean isSessionStarted() {
        return sessionStarted
                && TvKeyboardReceiver.isSessionEnabled();
    }

    /**
     * Returns the current TV pairing code.
     */
    public String getPairingCode() {
        return TvKeyboardReceiver.getCurrentPairingCode();
    }

    /**
     * Returns a defensive copy of the last accepted phone state.
     */
    public JSONObject getLastPhoneState() {
        JSONObject state = lastPhoneState;

        if (state == null) {
            return null;
        }

        try {
            return new JSONObject(state.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    public long getLastStateSequence() {
        return lastStateSequence;
    }

    /**
     * Ends the entire shared session.
     *
     * Do not call this when changing stages.
     */
    public synchronized void endSession() {
        sessionStarted = false;

        activeStageHandler = null;
        observer = null;

        activeStage = 0;
        lastStateSequence = 0L;
        lastPhoneState = null;

        TvKeyboardReceiver.disableSession();
    }

    /**
     * TvKeyboardReceiver.Listener implementation.
     *
     * The receiver already dispatches these callbacks on the
     * Android main thread.
     */

    @Override
    public void onPairingCodeReady(String code) {
        Observer current = observer;

        if (current != null) {
            current.onPairingCodeReady(code);
        }
    }

    @Override
    public void onStatusChanged(String message) {
        Observer current = observer;

        if (current != null) {
            current.onStatusChanged(message);
        }
    }

    @Override
    public void onError(String message) {
        Observer current = observer;

        if (current != null) {
            current.onError(message);
        }
    }

    @Override
    public void onSearchRequested(String songTitle) {
        TvCommandHandler handler = activeStageHandler;

        if (handler != null) {
            handler.onPhoneSearchRequested(songTitle);
            return;
        }

        Observer current = observer;

        if (current != null) {
            current.onPhoneSearchRequested(songTitle);
        }
    }

    @Override
    public void onTextInput(
            String target,
            String text,
            String action
    ) {
        TvCommandHandler handler = activeStageHandler;

        if (handler != null) {
            handler.onPhoneTextInput(
                    target,
                    text,
                    action
            );
            return;
        }

        Observer current = observer;

        if (current != null) {
            current.onPhoneTextInput(
                    target,
                    text,
                    action
            );
        }
    }

    @Override
    public void onStateReceived(
            JSONObject state,
            long sequence
    ) {
        if (state == null || sequence <= lastStateSequence) {
            return;
        }

        JSONObject stateCopy;

        try {
            stateCopy = new JSONObject(state.toString());
        } catch (Exception ignored) {
            return;
        }

        lastStateSequence = sequence;
        lastPhoneState = stateCopy;

        int requestedStage = stateCopy.optInt("stage", 0);

        if (requestedStage >= 1 && requestedStage <= 4) {
            activeStage = requestedStage;
        }

        TvCommandHandler handler = activeStageHandler;

        if (handler != null) {
            handler.onPhoneStateReceived(
                    new JSONObject(stateCopy.toString()),
                    sequence
            );
        }

        Observer current = observer;

        if (current != null) {
            current.onPhoneStateReceived(
                    new JSONObject(stateCopy.toString()),
                    sequence
            );
        }
    }
}
