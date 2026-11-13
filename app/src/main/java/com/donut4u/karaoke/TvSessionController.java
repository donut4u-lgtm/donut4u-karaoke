
package com.donut4u.karaoke;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Central coordinator for the Donut4U TV session.
 *
 * Owns the application-wide TvKeyboardReceiver listener and
 * forwards incoming phone commands to the active TV stage.
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
                    instance = new TvSessionController(context);
                }
            }
        }

        return instance;
    }

    /**
     * Starts the shared phone-TV session.
     */
    public synchronized void startSession(Observer sessionObserver) {
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
     * Changes the observer without restarting the connection.
     */
    public void setObserver(Observer sessionObserver) {
        observer = sessionObserver;
    }

    /**
     * Registers the currently active stage.
     */
    public void registerStageHandler(TvCommandHandler handler) {
        activeStageHandler = handler;
    }

    /**
     * Unregisters a stage only if it is still active.
     */
    public void unregisterStageHandler(TvCommandHandler handler) {
        if (handler != null && activeStageHandler == handler) {
            activeStageHandler = null;
        }
    }

    public void setActiveStage(int stage) {
        if (stage >= 1 && stage <= 4) {
            activeStage = stage;
        }
    }

    public int getActiveStage() {
        return activeStage;
    }

    public boolean isSessionStarted() {
        return sessionStarted
                && TvKeyboardReceiver.isSessionEnabled();
    }

    public String getPairingCode() {
        return TvKeyboardReceiver.getCurrentPairingCode();
    }

    /**
     * Returns a defensive copy of the last accepted state.
     */
    public JSONObject getLastPhoneState() {
        return copyJson(lastPhoneState);
    }

    public long getLastStateSequence() {
        return lastStateSequence;
    }

    /**
     * Ends the entire shared session.
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
         * A newly connected phone starts its command sequence
         * from 1. Reset the previous phone's sequence so that
         * commands from the new connection are accepted.
         *
         * This does not end or restart the shared TV session.
         */
        if (message != null
                && message.contains("Phone controller connected.")) {
            lastStateSequence = 0L;
            lastPhoneState = null;
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

        JSONObject stateCopy = copyJson(state);

        if (stateCopy == null) {
            return;
        }

        lastStateSequence = sequence;
        lastPhoneState = copyJson(stateCopy);

        int requestedStage = stateCopy.optInt("stage", 0);

        if (requestedStage >= 1 && requestedStage <= 4) {
            activeStage = requestedStage;
        }

        TvCommandHandler handler = activeStageHandler;

        if (handler != null) {
            JSONObject handlerState = copyJson(stateCopy);

            if (handlerState != null) {
                handler.onPhoneStateReceived(
                        handlerState,
                        sequence
                );
            }
        }

        Observer current = observer;

        if (current != null) {
            JSONObject observerState = copyJson(stateCopy);

            if (observerState != null) {
                current.onPhoneStateReceived(
                        observerState,
                        sequence
                );
            }
        }
    }

    /**
     * Creates a defensive JSON copy without exposing JSONException
     * to callers.
     */
    private static JSONObject copyJson(JSONObject source) {
        if (source == null) {
            return null;
        }

        try {
            return new JSONObject(source.toString());
        } catch (JSONException ignored) {
            return null;
        }
    }
}
