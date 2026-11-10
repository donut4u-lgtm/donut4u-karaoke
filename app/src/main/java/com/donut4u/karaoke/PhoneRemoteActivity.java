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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone-side remote keyboard for Donut4U Karaoke.
 *
 * Finds a TV running TvKeyboardReceiver on the same
 * local network, pairs with its six-digit code, and
 * sends song titles to Stage 1.
 */
public class PhoneRemoteActivity extends Activity {

    private static final int BG = Color.rgb(10, 12, 15);
    private static final int PANEL = Color.rgb(27, 32, 35);
    private static final int GREEN = Color.rgb(0, 220, 105);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.LTGRAY;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor();

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private InetAddress tvAddress;
    private int tvPort = -1;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private boolean discovering = false;
    private boolean connected = false;
    private boolean closing = false;
    private boolean resolving = false;

    private EditText pairingCodeInput;
    private EditText songInput;
    private TextView statusText;
    private TextView tvFoundText;
    private Button connectButton;
    private Button searchButton;
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
                "Use your phone to search songs on your TV."
        );
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(22));
        root.addView(subtitle, fullWidthWrap());

        TextView instruction = new TextView(this);
        instruction.setText(
                "1. Connect your phone and TV to the same Wi-Fi "
                        + "or phone hotspot.\n\n"
                        + "2. Open Stage 1 on the TV.\n\n"
                        + "3. Wait for Donut4U TV to appear below.\n\n"
                        + "4. Enter the six-digit code shown on the TV."
        );
        instruction.setTextColor(WHITE);
        instruction.setTextSize(14);
        instruction.setPadding(dp(14), dp(14), dp(14), dp(14));
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

        pairingCodeInput = new EditText(this);
        pairingCodeInput.setSingleLine(true);
        pairingCodeInput.setHint("Enter 6-digit TV code");
        pairingCodeInput.setTextColor(WHITE);
        pairingCodeInput.setHintTextColor(MUTED);
        pairingCodeInput.setTextSize(19);
        pairingCodeInput.setGravity(Gravity.CENTER);
        pairingCodeInput.setInputType(
                InputType.TYPE_CLASS_NUMBER
        );
        pairingCodeInput.setLetterSpacing(0.20f);
        pairingCodeInput.setPadding(
                dp(12), dp(12), dp(12), dp(12)
        );
        pairingCodeInput.setBackground(round(PANEL, dp(10)));
        root.addView(pairingCodeInput, fullWidth(dp(58)));

        connectButton = makeButton("CONNECT TO TV");
        LinearLayout.LayoutParams connectParams =
                fullWidth(dp(54));
        connectParams.topMargin = dp(12);
        root.addView(connectButton, connectParams);

        connectButton.setOnClickListener(v -> connectToTv());

        View separator = new View(this);
        separator.setBackgroundColor(Color.DKGRAY);
        LinearLayout.LayoutParams separatorParams =
                fullWidth(dp(1));
        separatorParams.topMargin = dp(22);
        separatorParams.bottomMargin = dp(18);
        root.addView(separator, separatorParams);

        TextView songHeading = new TextView(this);
        songHeading.setText("TYPE YOUR SONG");
        songHeading.setTextColor(GREEN);
        songHeading.setTextSize(17);
        songHeading.setTypeface(null, Typeface.BOLD);
        root.addView(songHeading, fullWidthWrap());

        songInput = new EditText(this);
        songInput.setSingleLine(true);
        songInput.setHint("Song title or artist");
        songInput.setTextColor(WHITE);
        songInput.setHintTextColor(MUTED);
        songInput.setTextSize(18);
        songInput.setPadding(
                dp(14), dp(12), dp(14), dp(12)
        );
        songInput.setBackground(round(PANEL, dp(10)));
        root.addView(songInput, fullWidth(dp(58)));

        searchButton = makeButton("SEARCH ON TV");
        LinearLayout.LayoutParams searchParams =
                fullWidth(dp(58));
        searchParams.topMargin = dp(12);
        root.addView(searchButton, searchParams);

        searchButton.setEnabled(false);
        searchButton.setOnClickListener(v -> sendSearch());

        disconnectButton = makeButton("DISCONNECT");
        LinearLayout.LayoutParams disconnectParams =
                fullWidth(dp(48));
        disconnectParams.topMargin = dp(10);
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
                            NsdServiceInfo serviceInfo) {

                        if (!TvKeyboardReceiver.SERVICE_TYPE.equals(
                                serviceInfo.getServiceType())) {
                            return;
                        }

                        if (resolving || connected) {
                            return;
                        }

                        resolving = true;
                        discoveredServiceName =
                                serviceInfo.getServiceName();

                        resolveTvService(serviceInfo);
                    }

                    @Override
                    public void onServiceLost(
                            NsdServiceInfo serviceInfo) {
                        if (!connected) {
                            tvAddress = null;
                            tvPort = -1;
                            mainHandler.post(() -> {
                                tvFoundText.setText(
                                        "TV is not currently visible."
                                );
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
                            int errorCode) {
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
                            int errorCode) {
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
                            int errorCode) {
                        resolving = false;
                        setStatus(
                                "Found TV, but could not connect to it. "
                                        + "Check the Wi-Fi connection."
                        );
                    }

                    @Override
                    public void onServiceResolved(
                            NsdServiceInfo resolvedInfo) {
                        resolving = false;

                        tvAddress = resolvedInfo.getHost();
                        tvPort = resolvedInfo.getPort();

                        mainHandler.post(() -> {
                            if (!closing && !connected) {
                                tvFoundText.setText(
                                        "FOUND: " + discoveredServiceName
                                );
                                setStatus(
                                        "TV found. Enter the code shown on the TV."
                                );
                            }
                        });
                    }
                }
        );
    }

    private void connectToTv() {
        if (connected) {
            setStatus("Already connected to TV.");
            return;
        }

        if (tvAddress == null || tvPort <= 0) {
            setStatus(
                    "TV not found yet. Keep both devices on the same network."
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

        connectButton.setEnabled(false);
        setStatus("Connecting to TV...");

        final InetAddress address = tvAddress;
        final int port = tvPort;

        networkExecutor.execute(() -> {
            try {
                Socket newSocket = new Socket();
                newSocket.connect(
                        new java.net.InetSocketAddress(address, port),
                        8000
                );
                newSocket.setSoTimeout(15000);

                BufferedReader newReader = new BufferedReader(
                        new InputStreamReader(
                                newSocket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );

                BufferedWriter newWriter = new BufferedWriter(
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
                    newSocket.close();

                    mainHandler.post(() -> {
                        connectButton.setEnabled(true);
                        setStatus(
                                "Incorrect or expired code. "
                                        + "Check the TV screen."
                        );
                    });
                    return;
                }

                socket = newSocket;
                reader = newReader;
                writer = newWriter;
                connected = true;

                socket.setSoTimeout(0);

                mainHandler.post(() -> {
                    if (closing) {
                        disconnect();
                        return;
                    }

                    connectButton.setEnabled(false);
                    pairingCodeInput.setEnabled(false);
                    searchButton.setEnabled(true);
                    disconnectButton.setVisibility(View.VISIBLE);
                    setStatus("CONNECTED. Type a song and tap SEARCH ON TV.");
                    songInput.requestFocus();
                    showPhoneKeyboard();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    connectButton.setEnabled(true);
                    setStatus(
                            "Connection failed. Check Wi-Fi and the TV code."
                    );
                });
            }
        });
    }

    private void sendSearch() {
        if (!connected || writer == null) {
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
            songInput.setError("Please use 200 characters or less.");
            return;
        }

        searchButton.setEnabled(false);
        setStatus("Sending song title to TV...");

        networkExecutor.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("type", "search");
                request.put("text", songTitle);

                writer.write(request.toString());
                writer.newLine();
                writer.flush();

                String responseLine = reader.readLine();

                if (responseLine == null) {
                    throw new Exception("TV disconnected.");
                }

                JSONObject response =
                        new JSONObject(responseLine);

                boolean success =
                        response.optBoolean("ok", false);

                mainHandler.post(() -> {
                    searchButton.setEnabled(connected);

                    if (success) {
                        setStatus(
                                "Search sent to TV: " + songTitle
                        );
                    } else {
                        setStatus("TV could not accept that search.");
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    setStatus(
                            "Connection lost. Please reconnect to the TV."
                    );
                    disconnect();
                });
            }
        });
    }

    private void disconnect() {
        connected = false;

        networkExecutor.execute(() -> {
            try {
                if (writer != null) {
                    JSONObject request = new JSONObject();
                    request.put("type", "disconnect");

                    writer.write(request.toString());
                    writer.newLine();
                    writer.flush();
                }
            } catch (Exception ignored) {
            }

            closeSocket();
        });

        if (searchButton != null) {
            searchButton.setEnabled(false);
        }

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

    private void showPhoneKeyboard() {
        songInput.requestFocus();

        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE
                );

        if (keyboard != null) {
            songInput.post(() ->
                    keyboard.showSoftInput(
                            songInput,
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

    private android.graphics.drawable.GradientDrawable round(
            int color,
            int radius) {

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
        return (int) (value
                * getResources().getDisplayMetrics().density
                + 0.5f);
    }

    @Override
    protected void onDestroy() {
        closing = true;
        connected = false;

        if (discovering && nsdManager != null
                && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }

        closeSocket();
        networkExecutor.shutdownNow();

        super.onDestroy();
    }
}
