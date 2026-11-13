
package com.donut4u.karaoke;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Central coordinator for the Donut4U TV session.
 *
 * Owns the application-wide TvKeyboardReceiver listener and
 * forwards incoming phone commands to the active TV stage.
 *
 * IMPORTANT:
 * - The shared receiver belongs to this controller.
 * - Individual stages must never stop or disable the receiver.
 * - Stage changes only register or unregister stage handlers.
 * - Only the session owner should call endSession().
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

    public static TvSessionController getInstance(Context context) {

        if (context == null) {

            throw new IllegalArgumentException(
                    "Context cannot be null."
            );
        }

        if (instance == null) {

            synchronized (TvSessionController.class) {

                if (instance == null) {

                    instance =
                            new TvSessionController(context);
                }
            }
        }

        return instance;
    }

    /**
     * Starts the shared phone-TV session.
     *
     * Calling this again while the shared receiver is already
     * enabled updates the observer without restarting the receiver.
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

        activeStageHandler = null;

        activeStage = 0;

        lastStateSequence = 0L;

        lastPhoneState = null;

        TvKeyboardReceiver.enableSession(
                appContext,
                this
        );
    }

    /**
     * Changes the session observer without restarting
     * or disabling the shared receiver.
     *
     * This observer belongs to the session owner, not
     * to an individual stage.
     */
    public void setObserver(
            Observer sessionObserver
    ) {

        observer = sessionObserver;
    }

    /**
     * Registers the command handler for the currently
     * active TV stage.
     *
     * Only one stage handler is active at a time.
     */
    public synchronized void registerStageHandler(
            TvCommandHandler handler
    ) {

        if (handler == null) {
            return;
        }

        activeStageHandler = handler;
    }

    /**
     * Unregisters a stage only when the supplied handler
     * is still the currently registered handler.
     *
     * This prevents an older Activity from accidentally
     * unregistering a newer stage handler.
     *
     * This method does NOT stop the shared receiver.
     */
    public synchronized void unregisterStageHandler(
            TvCommandHandler handler
    ) {

        if (handler != null
                && activeStageHandler == handler) {

            activeStageHandler = null;

            activeStage = 0;
        }
    }

    /**
     * Sets the currently active TV stage.
     *
     * Valid stages are 1 through 4.
     * Stage 0 means no stage is currently active.
     */
    public synchronized void setActiveStage(int stage) {

        if (stage >= 0 && stage <= 4) {

            activeStage = stage;
        }
    }

    public int getActiveStage() {

        return activeStage;
    }

    /**
     * Returns true only while the controller and
     * shared receiver are both active.
     */
    public boolean isSessionStarted() {

        return sessionStarted
                && TvKeyboardReceiver.isSessionEnabled();
    }

    /**
     * Returns the current six-digit pairing code
     * generated by the shared receiver.
     */
    public String getPairingCode() {

        return TvKeyboardReceiver.getCurrentPairingCode();
    }

    /**
     * Returns a defensive copy of the last accepted
     * phone state.
     */
    public JSONObject getLastPhoneState() {

        return copyJson(lastPhoneState);
    }

    public long getLastStateSequence() {

        return lastStateSequence;
    }

    /**
     * Ends the entire shared phone-TV session.
     *
     * IMPORTANT:
     * Do not call this when changing stages.
     * Only the session owner should call this method.
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

    // ------------------------------------------------
    // RECEIVER CALLBACKS
    // ------------------------------------------------

    @Override
    public void onPairingCodeReady(String code) {

        Observer current = observer;

        if (current != null) {

            current.onPairingCodeReady(code);
        }
    }

    @Override
    public void onStatusChanged(String message) {

        /*
         * Every newly connected phone starts a fresh
         * command sequence.
         *
         * Reset the previous phone's sequence and state
         * without restarting the shared TV session.
         */
        if (message != null
                && message.contains(
                        "Phone controller connected."
                )) {

            synchronized (this) {

                lastStateSequence = 0L;

                lastPhoneState = null;
            }
        }

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

        TvCommandHandler handler =
                activeStageHandler;

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

        TvCommandHandler handler =
                activeStageHandler;

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

        if (state == null || sequence <= 0L) {
            return;
        }

        JSONObject stateCopy =
                copyJson(state);

        if (stateCopy == null) {
            return;
        }

        TvCommandHandler handler;

        Observer current;

        synchronized (this) {

            /*
             * Reject duplicate or older state messages.
             */
            if (sequence <= lastStateSequence) {
                return;
            }

            lastStateSequence = sequence;

            lastPhoneState =
                    copyJson(stateCopy);

            /*
             * Keep the reported stage synchronized with
             * the phone's current stage when valid.
             */
            int requestedStage =
                    stateCopy.optInt("stage", 0);

            if (requestedStage >= 1
                    && requestedStage <= 4) {

                activeStage = requestedStage;
            }

            handler = activeStageHandler;

            current = observer;
        }

        /*
         * Deliver separate JSON copies so that a stage
         * cannot modify the observer's state object.
         */
        if (handler != null) {

            JSONObject handlerState =
                    copyJson(stateCopy);

            if (handlerState != null) {

                handler.onPhoneStateReceived(
                        handlerState,
                        sequence
                );
            }
        }

        if (current != null) {

            JSONObject observerState =
                    copyJson(stateCopy);

            if (observerState != null) {

                current.onPhoneStateReceived(
                        observerState,
                        sequence
                );
            }
        }
    }

    // ------------------------------------------------
    // JSON HELPERS
    // ------------------------------------------------

    /**
     * Creates a defensive JSON copy.
     *
     * Returns null if the source cannot be copied.
     */
    private static JSONObject copyJson(
            JSONObject source
    ) {

        if (source == null) {
            return null;
        }

        try {

            return new JSONObject(
                    source.toString()
            );

        } catch (JSONException ignored) {

            return null;
        }
    }
}
