package com.donut4u.karaoke;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

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

        default JSONObject onPhoneRemoteRequest(
                JSONObject request
        ) {
            return null;
        }

        default void onPhoneMediaFileReceived(
                File file,
                String mimeType,
                String transferId
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

        default JSONObject onPhoneRemoteRequest(
                JSONObject request
        ) {
            return null;
        }

        default void onPhoneMediaFileReceived(
                File file,
                String mimeType,
                String transferId
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
     */
    public void setObserver(Observer sessionObserver) {
        observer = sessionObserver;
    }

    /**
     * Registers the command handler for the active TV stage.
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

    public boolean isSessionStarted() {

        return sessionStarted
                && TvKeyboardReceiver.isSessionEnabled();
    }

    public String getPairingCode() {
        return TvKeyboardReceiver.getCurrentPairingCode();
    }

    public JSONObject getLastPhoneState() {
        return copyJson(lastPhoneState);
    }

    public long getLastStateSequence() {
        return lastStateSequence;
    }

    /**
     * Ends the entire shared phone-TV session.
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

        if (state == null || sequence <= 0L) {
            return;
        }

        JSONObject stateCopy = copyJson(state);

        if (stateCopy == null) {
            return;
        }

        TvCommandHandler handler;
        Observer current;

        synchronized (this) {

            if (sequence <= lastStateSequence) {
                return;
            }

            lastStateSequence = sequence;
            lastPhoneState = copyJson(stateCopy);

            int requestedStage =
                    stateCopy.optInt("stage", 0);

            if (requestedStage >= 1
                    && requestedStage <= 4) {
                activeStage = requestedStage;
            }

            handler = activeStageHandler;
            current = observer;
        }

        if (handler != null) {

            JSONObject handlerState = copyJson(stateCopy);

            if (handlerState != null) {
                handler.onPhoneStateReceived(
                        handlerState,
                        sequence
                );
            }
        }

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
     * Receives a successfully completed image from the TV receiver.
     * The receiver invokes this callback on the main thread.
     */
    @Override
    public void onMediaFileReceived(
            File file,
            String mimeType,
            String transferId
    ) {

        if (file == null
                || !file.isFile()
                || !file.exists()
                || mimeType == null
                || transferId == null) {
            return;
        }

        TvCommandHandler handler = activeStageHandler;
        Observer current = observer;

        if (handler != null) {
            try {
                handler.onPhoneMediaFileReceived(
                        file,
                        mimeType,
                        transferId
                );
            } catch (Exception e) {
                android.util.Log.e(
                        "TvSessionController",
                        "Active TV stage could not receive image.",
                        e
                );
            }
        }

        if (current != null) {
            try {
                current.onPhoneMediaFileReceived(
                        file,
                        mimeType,
                        transferId
                );
            } catch (Exception e) {
                android.util.Log.e(
                        "TvSessionController",
                        "TV session observer could not receive image.",
                        e
                );
            }
        }
    }

    /**
     * Gives the active stage first opportunity to handle
     * a phone request. If it returns null, the session
     * observer gets an opportunity.
     */
    @Override
    public JSONObject onRemoteRequest(JSONObject request) {

        if (request == null) {
            return null;
        }

        JSONObject requestCopy = copyJson(request);

        if (requestCopy == null) {
            return null;
        }

        TvCommandHandler handler = activeStageHandler;

        if (handler != null) {
            try {
                JSONObject result =
                        handler.onPhoneRemoteRequest(
                                copyJson(requestCopy)
                        );

                if (result != null) {
                    return copyJson(result);
                }

            } catch (Exception e) {

                android.util.Log.e(
                        "TvSessionController",
                        "Active stage failed to handle phone request.",
                        e
                );

                return createErrorResponse(
                        "Active TV stage could not process the request."
                );
            }
        }

        Observer current = observer;

        if (current != null) {
            try {
                JSONObject result =
                        current.onPhoneRemoteRequest(
                                copyJson(requestCopy)
                        );

                if (result != null) {
                    return copyJson(result);
                }

            } catch (Exception e) {

                android.util.Log.e(
                        "TvSessionController",
                        "Session observer failed to handle phone request.",
                        e
                );

                return createErrorResponse(
                        "TV session could not process the request."
                );
            }
        }

        return createErrorResponse(
                "No active TV stage can handle this request."
        );
    }

    // ------------------------------------------------
    // JSON HELPERS
    // ------------------------------------------------

    private static JSONObject createErrorResponse(
            String message
    ) {

        JSONObject response = new JSONObject();

        try {
            response.put("ok", false);
            response.put("message", message);
        } catch (JSONException ignored) {
        }

        return response;
    }

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
