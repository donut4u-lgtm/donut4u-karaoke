
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

public class PhoneRemoteActivity extends Activity {

    private static final int BG = Color.rgb(10, 12, 15);
    private static final int PANEL = Color.rgb(27, 32, 35);
    private static final int GREEN = Color.rgb(0, 220, 105);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.LTGRAY;

    private static final String ACTIVE_TARGET = "active";

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor();

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private InetAddress tvAddress;
    private int tvPort = -1;

    private volatile SSLSocket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;

    private volatile boolean discovering = false;
    private volatile boolean connected = false;
    private volatile boolean closing = false;
    private volatile boolean resolving = false;
    private volatile boolean connecting = false;

    private EditText pairingCodeInput;
    private EditText songInput;
    private EditText textInput;

    private TextView statusText;
    private TextView tvFoundText;

    private Button connectButton;
    private Button searchButton;
    private Button sendTextButton;
    private Button backspaceButton;
    private Button clearButton;
    private Button submitButton;
    private Button disconnectButton;

    private String discoveredServiceName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        buildScreen();
        startDiscovery();
    }

    private void buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(24));

        scrollView.addView(root);

        TextView heading = new TextView(this);
        heading.setText("DONUT4U PHONE KEYBOARD");
        heading.setTextColor(GREEN);
        heading.setTextSize(23);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);

        root.addView(heading, fullWidthWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "Your phone is the keyboard and controller. "
                        + "The TV displays and plays karaoke."
        );
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(22));

        root.addView(subtitle, fullWidthWrap());

        TextView instruction = new TextView(this);
        instruction.setText(
                "1. Open Donut4U on your TV.\n\n"
                        + "2. The TV displays a six-digit pairing code.\n\n"
                        + "3. Keep both devices on the same Wi-Fi "
                        + "network or phone hotspot.\n\n"
                        + "4. Enter the TV code below and connect.\n\n"
                        + "5. Select the desired input field on the TV. "
                        + "Type on your phone and send the text.\n\n"
                        + "6. Use SUBMIT when you want the TV to "
                        + "finish the current input."
        );
        instruction.setTextColor(WHITE);
        instruction.setTextSize(14);
        instruction.setPadding(
                dp(14), dp(14), dp(14), dp(14)
        );
        instruction.setBackground(round(PANEL, dp(12)));

        root.addView(instruction, fullWidthWrap());

        tvFoundText = new TextView(this);
        tvFoundText.setText("Searching for Donut4U TV...");
        tvFoundText.setTextColor(GREEN);
        tvFoundText.setTextSize(15);
        tvFoundText.setTypeface(null, Typeface.BOLD);
        tvFoundText.setGravity(Gravity.CENTER);
        tvFoundText.setPadding(0, dp(20), 0, dp(12));

        root.addView(tvFoundText, fullWidthWrap());

        pairingCodeInput = createInput(
                "Enter 6-digit TV code",
                InputType.TYPE_CLASS_NUMBER
        );
        pairingCodeInput.setGravity(Gravity.CENTER);
        pairingCodeInput.setTextSize(19);
        pairingCodeInput.setLetterSpacing(0.20f);
        pairingCodeInput.setFilters(
                new InputFilter[]{
                        new InputFilter.LengthFilter(6)
                }
        );

        root.addView(
                pairingCodeInput,
                fullWidth(dp(58))
        );

        connectButton = makeButton("CONNECT TO TV");

        LinearLayout.LayoutParams connectParams =
                fullWidth(dp(54));
        connectParams.topMargin = dp(12);

        root.addView(connectButton, connectParams);
        connectButton.setOnClickListener(v -> connectToTv());

        addSeparator(root);

        TextView songHeading = makeHeading("SONG SEARCH");
        root.addView(songHeading, fullWidthWrap());

        songInput = createInput(
                "Song title or artist",
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );

        root.addView(songInput, fullWidth(dp(58)));

        searchButton = makeButton("SEARCH ON TV");

        LinearLayout.LayoutParams searchParams =
                fullWidth(dp(58));
        searchParams.topMargin = dp(12);

        root.addView(searchButton, searchParams);

        searchButton.setEnabled(false);
        searchButton.setOnClickListener(v -> sendSearch());

        addSeparator(root);

        TextView keyboardHeading =
                makeHeading("PHONE KEYBOARD FOR TV");

        root.addView(keyboardHeading, fullWidthWrap());

        TextView keyboardInstructions = new TextView(this);
        keyboardInstructions.setText(
                "Select the input field on the TV first. "
                        + "Then type here using your phone keyboard. "
                        + "The phone sends text to the TV's active "
                        + "input field."
        );
        keyboardInstructions.setTextColor(MUTED);
        keyboardInstructions.setTextSize(13);
        keyboardInstructions.setPadding(
                0, dp(8), 0, dp(12)
        );

        root.addView(keyboardInstructions, fullWidthWrap());

        textInput = createInput(
                "Type text for the active TV field",
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );

        root.addView(
                textInput,
                fullWidth(dp(58))
        );

        sendTextButton = makeButton("SEND TEXT TO TV");

        LinearLayout.LayoutParams sendParams =
                fullWidth(dp(54));
        sendParams.topMargin = dp(10);

        root.addView(sendTextButton, sendParams);
        sendTextButton.setEnabled(false);
        sendTextButton.setOnClickListener(
                v -> sendText("replace")
        );

        LinearLayout editActions = new LinearLayout(this);
        editActions.setOrientation(LinearLayout.HORIZONTAL);
        editActions.setGravity(Gravity.CENTER);
        editActions.setPadding(0, dp(8), 0, 0);

        backspaceButton = makeSmallButton("BACKSPACE");
        clearButton = makeSmallButton("CLEAR");
        submitButton = makeSmallButton("SUBMIT");

        editActions.addView(
                backspaceButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1
                )
        );

        LinearLayout.LayoutParams actionParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1
                );
        actionParams.leftMargin = dp(6);

        editActions.addView(clearButton, actionParams);

        LinearLayout.LayoutParams submitParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1
                );
        submitParams.leftMargin = dp(6);

        editActions.addView(submitButton, submitParams);

        root.addView(editActions, fullWidthWrap());

        backspaceButton.setEnabled(false);
        clearButton.setEnabled(false);
        submitButton.setEnabled(false);

        backspaceButton.setOnClickListener(
                v -> sendText("backspace")
        );

        clearButton.setOnClickListener(
                v -> sendText("clear")
        );

        submitButton.setOnClickListener(
                v -> sendText("submit")
        );

        disconnectButton = makeButton("DISCONNECT");

        LinearLayout.LayoutParams disconnectParams =
                fullWidth(dp(48));
        disconnectParams.topMargin = dp(18);

        root.addView(disconnectButton, disconnectParams);

        disconnectButton.setVisibility(View.GONE);
        disconnectButton.setOnClickListener(v -> disconnect());

        statusText = new TextView(this);
        statusText.setText("Looking for your TV...");
        statusText.setTextColor(MUTED);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(20), 0, dp(10));

        root.addView(statusText, fullWidthWrap());

        setContentView(scrollView);
        setConnectedControls(false);
    }

    private void addSeparator(LinearLayout root) {
        View separator = new View(this);
        separator.setBackgroundColor(Color.DKGRAY);

        LinearLayout.LayoutParams params =
                fullWidth(dp(1));
        params.topMargin = dp(22);
        params.bottomMargin = dp(18);

        root.addView(separator, params);
    }

    private TextView makeHeading(String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextColor(GREEN);
        heading.setTextSize(17);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(10));
        return heading;
    }

    private EditText createInput(
            String hint,
            int inputType
    ) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setTextSize(17);
        input.setInputType(inputType);
        input.setPadding(
                dp(14), dp(12), dp(14), dp(12)
        );
        input.setBackground(round(PANEL, dp(10)));
        return input;
    }

    private void startDiscovery() {
        nsdManager = (NsdManager)
                getSystemService(Context.NSD_SERVICE);

        if (nsdManager == null) {
            setStatus("Network discovery is not available.");
            return;
        }

        if (discovering) {
            return;
        }

        discoveryListener =
                new NsdManager.DiscoveryListener() {

                    @Override
                    public void onDiscoveryStarted(String serviceType) {
                        discovering = true;
                        setStatus("Searching for Donut4U TV...");
                    }

                    @Override
                    public void onServiceFound(
                            NsdServiceInfo serviceInfo
                    ) {
                        if (!TvKeyboardReceiver.SERVICE_TYPE.equals(
                                serviceInfo.getServiceType()
                        )) {
                            return;
                        }

                        if (resolving || connected || connecting) {
                            return;
                        }

                        resolving = true;
                        discoveredServiceName =
                                serviceInfo.getServiceName();

                        resolveTvService(serviceInfo);
                    }

                    @Override
                    public void onServiceLost(
                            NsdServiceInfo serviceInfo
                    ) {
                        if (!connected) {
                            tvAddress = null;
                            tvPort = -1;

                            mainHandler.post(() -> {
                                if (!closing && tvFoundText != null) {
                                    tvFoundText.setText(
                                            "TV is not currently visible."
                                    );
                                }
                            });
                        }
                    }

                    @Override
                    public void onDiscoveryStopped(String serviceType) {
                        discovering = false;
                    }

                    @Override
                    public void onStartDiscoveryFailed(
                            String serviceType,
                            int errorCode
                    ) {
                        discovering = false;
                        setStatus(
                                "Could not search for TV. "
                                        + "Please reopen this screen."
                        );

                        try {
                            nsdManager.stopServiceDiscovery(this);
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onStopDiscoveryFailed(
                            String serviceType,
                            int errorCode
                    ) {
                        try {
                            nsdManager.stopServiceDiscovery(this);
                        } catch (Exception ignored) {
                        }
                    }
                };

        try {
            nsdManager.discoverServices(
                    TvKeyboardReceiver.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
            );
        } catch (Exception e) {
            setStatus("Unable to start TV discovery.");
        }
    }

    @SuppressWarnings("deprecation")
    private void resolveTvService(NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(
                serviceInfo,
                new NsdManager.ResolveListener() {

                    @Override
                    public void onResolveFailed(
                            NsdServiceInfo serviceInfo,
                            int errorCode
                    ) {
                        resolving = false;
                        setStatus(
                                "Found TV, but could not connect to it. "
                                        + "Check the Wi-Fi connection."
                        );
                    }

                    @Override
                    public void onServiceResolved(
                            NsdServiceInfo resolvedInfo
                    ) {
                        resolving = false;

                        tvAddress = resolvedInfo.getHost();
                        tvPort = resolvedInfo.getPort();

                        mainHandler.post(() -> {
                            if (!closing && !connected) {
                                tvFoundText.setText(
                                        "FOUND: " + discoveredServiceName
                                );

                                setStatus(
                                        "TV found. Enter the code shown "
                                                + "on the TV."
                                );
                            }
                        });
                    }
                }
        );
    }

    private void connectToTv() {
        if (connected || connecting) {
            setStatus("Already connected or connecting.");
            return;
        }

        if (tvAddress == null || tvPort <= 0) {
            setStatus(
                    "TV not found yet. Keep both devices "
                            + "on the same network."
            );
            return;
        }

        String code = pairingCodeInput.getText()
                .toString().trim();

        if (!code.matches("[0-9]{6}")) {
            pairingCodeInput.setError(
                    "Enter the six-digit code shown on the TV."
            );
            return;
        }

        connecting = true;
        connectButton.setEnabled(false);
        setStatus("Establishing encrypted connection...");

        final InetAddress address = tvAddress;
        final int port = tvPort;

        networkExecutor.execute(() -> {
            SSLSocket newSocket = null;

            try {
                /*
                 * TvPairingTls verifies the TV certificate against
                 * the six-digit pairing code before returning.
                 */
                newSocket = TvPairingTls.connect(
                        address,
                        port,
                        code,
                        8000
                );

                newSocket.setSoTimeout(15000);

                BufferedReader newReader =
                        new BufferedReader(
                                new InputStreamReader(
                                        newSocket.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        );

                BufferedWriter newWriter =
                        new BufferedWriter(
                                new OutputStreamWriter(
                                        newSocket.getOutputStream(),
                                        StandardCharsets.UTF_8
                                )
                        );

                JSONObject request = new JSONObject();
                request.put("code", code);

                newWriter.write(request.toString());
                newWriter.newLine();
                newWriter.flush();

                String responseLine = newReader.readLine();

                if (responseLine == null) {
                    throw new Exception("TV did not respond.");
                }

                JSONObject response =
                        new JSONObject(responseLine);

                if (!response.optBoolean("ok", false)) {
                    throw new Exception(
                            response.optString(
                                    "message",
                                    "TV rejected the pairing code."
                            )
                    );
                }

                socket = newSocket;
                reader = newReader;
                writer = newWriter;
                connected = true;

                socket.setSoTimeout(0);

                mainHandler.post(() -> {
                    connecting = false;

                    if (closing) {
                        disconnect();
                        return;
                    }

                    connectButton.setEnabled(false);
                    pairingCodeInput.setEnabled(false);
                    disconnectButton.setVisibility(View.VISIBLE);

                    setConnectedControls(true);

                    setStatus(
                            "ENCRYPTED CONNECTION ESTABLISHED. "
                                    + "Select a TV input field, "
                                    + "then type on your phone."
                    );

                    showPhoneKeyboard(textInput);
                });

            } catch (Exception e) {
                if (newSocket != null) {
                    try {
                        newSocket.close();
                    } catch (Exception ignored) {
                    }
                }

                mainHandler.post(() -> {
                    connecting = false;

                    if (!closing) {
                        connectButton.setEnabled(true);

                        setStatus(
                                "Secure connection failed: "
                                        + safeMessage(e)
                        );
                    }
                });
            }
        });
    }

    private void sendSearch() {
        if (!connected) {
            setStatus("Connect to the TV first.");
            return;
        }

        String songTitle = songInput.getText()
                .toString().trim();

        if (songTitle.isEmpty()) {
            songInput.setError("Type a song title first.");
            return;
        }

        if (songTitle.length() > 200) {
            songInput.setError(
                    "Please use 200 characters or less."
            );
            return;
        }

        searchButton.setEnabled(false);
        setStatus("Sending song title to TV...");

        networkExecutor.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("type", "search");
                request.put("text", songTitle);

                JSONObject response = sendRequestAndRead(request);

                boolean success =
                        response.optBoolean("ok", false);

                mainHandler.post(() -> {
                    if (closing) {
                        return;
                    }

                    searchButton.setEnabled(connected);

                    setStatus(
                            success
                                    ? "Search sent to TV: " + songTitle
                                    : "TV could not accept that search."
                    );
                });

            } catch (Exception e) {
                handleConnectionFailure();
            }
        });
    }

    private void sendText(String action) {
        if (!connected) {
            setStatus("Connect to the TV first.");
            return;
        }

        String text = textInput.getText().toString();

        if ("replace".equals(action)
                && text.trim().isEmpty()) {
            textInput.setError("Type text first.");
            return;
        }

        if (text.length() > 2000) {
            textInput.setError(
                    "Please use 2,000 characters or less."
            );
            return;
        }

        setTextButtonsEnabled(false);
        setStatus("Sending text to TV...");

        networkExecutor.execute(() -> {
            try {
                JSONObject request = new JSONObject();

                request.put("type", "input");
                request.put("target", ACTIVE_TARGET);
                request.put("action", action);

                request.put(
                        "text",
                        "backspace".equals(action)
                                || "clear".equals(action)
                                || "submit".equals(action)
                                ? ""
                                : text
                );

                JSONObject response = sendRequestAndRead(request);

                boolean success =
                        response.optBoolean("ok", false);

                String message =
                        response.optString(
                                "message",
                                success
                                        ? "Text sent"
                                        : "TV rejected the text"
                        );

                mainHandler.post(() -> {
                    if (closing) {
                        return;
                    }

                    setTextButtonsEnabled(connected);

                    setStatus(
                            success
                                    ? "TV: " + message
                                    : "Text was not accepted: " + message
                    );
                });

            } catch (Exception e) {
                handleConnectionFailure();
            }
        });
    }

    private JSONObject sendRequestAndRead(
            JSONObject request
    ) throws Exception {

        BufferedWriter currentWriter = writer;
        BufferedReader currentReader = reader;

        if (!connected
                || currentWriter == null
                || currentReader == null) {
            throw new Exception("TV is disconnected.");
        }

        currentWriter.write(request.toString());
        currentWriter.newLine();
        currentWriter.flush();

        String responseLine = currentReader.readLine();

        if (responseLine == null) {
            throw new Exception("TV disconnected.");
        }

        return new JSONObject(responseLine);
    }

    private void handleConnectionFailure() {
        mainHandler.post(() -> {
            if (!closing) {
                setStatus(
                        "Connection lost. Please reconnect "
                                + "to the TV."
                );
                disconnect();
            }
        });
    }

    private void setConnectedControls(boolean enabled) {
        if (searchButton != null) {
            searchButton.setEnabled(enabled);
        }

        setTextButtonsEnabled(enabled);
    }

    private void setTextButtonsEnabled(boolean enabled) {
        if (sendTextButton != null) {
            sendTextButton.setEnabled(enabled);
        }

        if (backspaceButton != null) {
            backspaceButton.setEnabled(enabled);
        }

        if (clearButton != null) {
            clearButton.setEnabled(enabled);
        }

        if (submitButton != null) {
            submitButton.setEnabled(enabled);
        }
    }

    private void disconnect() {
        connected = false;
        connecting = false;

        try {
            networkExecutor.execute(() -> {
                try {
                    BufferedWriter currentWriter = writer;

                    if (currentWriter != null) {
                        JSONObject request = new JSONObject();
                        request.put("type", "disconnect");

                        currentWriter.write(request.toString());
                        currentWriter.newLine();
                        currentWriter.flush();
                    }
                } catch (Exception ignored) {
                }

                closeSocket();
            });
        } catch (Exception ignored) {
            closeSocket();
        }

        setConnectedControls(false);

        if (connectButton != null) {
            connectButton.setEnabled(true);
        }

        if (pairingCodeInput != null) {
            pairingCodeInput.setEnabled(true);
        }

        if (disconnectButton != null) {
            disconnectButton.setVisibility(View.GONE);
        }

        setStatus("Disconnected from TV.");
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }

        socket = null;
        reader = null;
        writer = null;
    }

    private void showPhoneKeyboard(EditText input) {
        input.requestFocus();

        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE
                );

        if (keyboard != null) {
            input.post(() ->
                    keyboard.showSoftInput(
                            input,
                            InputMethodManager.SHOW_IMPLICIT
                    )
            );
        }
    }

    private void setStatus(String message) {
        mainHandler.post(() -> {
            if (statusText != null && !closing) {
                statusText.setText(message);
            }
        });
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();

        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(15);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(round(GREEN, dp(10)));
        return button;
    }

    private Button makeSmallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(WHITE);
        button.setTextSize(11);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(round(PANEL, dp(8)));
        return button;
    }

    private android.graphics.drawable.GradientDrawable round(
            int color,
            int radius
    ) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(radius);

        return drawable;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (
                value
                        * getResources().getDisplayMetrics().density
                        + 0.5f
        );
    }

    @Override
    protected void onDestroy() {
        closing = true;
        connected = false;
        connecting = false;

        if (discovering
                && nsdManager != null
                && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(
                        discoveryListener
                );
            } catch (Exception ignored) {
            }
        }

        closeSocket();
        networkExecutor.shutdownNow();

        super.onDestroy();
    }
}
