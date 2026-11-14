
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
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

public class PhoneRemoteActivity extends Activity {

    private static final int BG = Color.rgb(10, 12, 15);
    private static final int PANEL = Color.rgb(27, 32, 35);
    private static final int GREEN = Color.rgb(0, 220, 105);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.LTGRAY;

    private static final int PAIRING_TIMEOUT_MS = 8000;
    private static final int SOCKET_TIMEOUT_MS = 15000;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor();

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private InetAddress tvAddress;
    private int tvPort = -1;

    private volatile boolean discovering;
    private volatile boolean resolving;
    private volatile boolean connecting;
    private volatile boolean closing;
    private volatile boolean connectionTransferred;

    private EditText pairingCodeInput;
    private TextView tvFoundText;
    private TextView statusText;
    private Button connectButton;

    private String discoveredServiceName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        if (PhoneTvConnectionManager.getInstance().isConnected()) {
            Toast.makeText(
                    this,
                    "Your phone is already paired with the TV.",
                    Toast.LENGTH_SHORT
            ).show();
            finish();
            return;
        }

        buildScreen();
        startDiscovery();
    }

    private void buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(24));

        scrollView.addView(root);

        TextView heading = new TextView(this);
        heading.setText("DONUT4U PHONE + TV");
        heading.setTextColor(GREEN);
        heading.setTextSize(24);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);

        root.addView(heading, fullWidthWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "Connect your phone to your Donut4U TV."
        );
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(12), 0, dp(24));

        root.addView(subtitle, fullWidthWrap());

        TextView instructions = new TextView(this);
        instructions.setText(
                "1. Open Donut4U on your TV.\n\n"
                        + "2. Find the six-digit pairing code "
                        + "displayed on the TV.\n\n"
                        + "3. Make sure your phone and TV use "
                        + "the same Wi-Fi network or hotspot.\n\n"
                        + "4. Enter the six-digit code below.\n\n"
                        + "5. Tap CONNECT TO TV.\n\n"
                        + "After successful pairing, this screen "
                        + "will close automatically. Continue using "
                        + "Donut4U normally on your phone."
        );
        instructions.setTextColor(WHITE);
        instructions.setTextSize(15);
        instructions.setPadding(
                dp(16), dp(18), dp(16), dp(18)
        );
        instructions.setBackground(round(PANEL, dp(12)));

        root.addView(instructions, fullWidthWrap());

        tvFoundText = new TextView(this);
        tvFoundText.setText("Searching for Donut4U TV...");
        tvFoundText.setTextColor(GREEN);
        tvFoundText.setTextSize(15);
        tvFoundText.setTypeface(null, Typeface.BOLD);
        tvFoundText.setGravity(Gravity.CENTER);
        tvFoundText.setPadding(0, dp(24), 0, dp(16));

        root.addView(tvFoundText, fullWidthWrap());

        pairingCodeInput = new EditText(this);
        pairingCodeInput.setSingleLine(true);
        pairingCodeInput.setHint("Enter 6-digit TV code");
        pairingCodeInput.setTextColor(WHITE);
        pairingCodeInput.setHintTextColor(MUTED);
        pairingCodeInput.setTextSize(21);
        pairingCodeInput.setGravity(Gravity.CENTER);
        pairingCodeInput.setLetterSpacing(0.20f);
        pairingCodeInput.setInputType(
                InputType.TYPE_CLASS_NUMBER
        );
        pairingCodeInput.setFilters(
                new InputFilter[]{
                        new InputFilter.LengthFilter(6)
                }
        );
        pairingCodeInput.setPadding(
                dp(14), dp(12), dp(14), dp(12)
        );
        pairingCodeInput.setBackground(round(PANEL, dp(10)));

        root.addView(
                pairingCodeInput,
                fullWidth(dp(60))
        );

        connectButton = makeButton("CONNECT TO TV");

        LinearLayout.LayoutParams connectParams =
                fullWidth(dp(56));
        connectParams.topMargin = dp(16);

        root.addView(connectButton, connectParams);
        connectButton.setOnClickListener(v -> connectToTv());

        statusText = new TextView(this);
        statusText.setText(
                "Waiting to find your Donut4U TV..."
        );
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
                    public void onDiscoveryStarted(
                            String serviceType
                    ) {
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

                        if (resolving || connecting
                                || connectionTransferred) {
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
                        if (tvFoundText != null
                                && tvAddress != null
                                && !connecting) {
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
                    public void onDiscoveryStopped(
                            String serviceType
                    ) {
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
                                        + "Check your network and try again."
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
    private void resolveTvService(
            NsdServiceInfo serviceInfo
    ) {
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
                                "Found TV, but could not connect. "
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
                            if (!closing && !connectionTransferred) {
                                if (tvFoundText != null) {
                                    tvFoundText.setText(
                                            "FOUND: "
                                                    + discoveredServiceName
                                    );
                                }

                                setStatus(
                                        "TV found. Enter the code "
                                                + "displayed on the TV."
                                );
                            }
                        });
                    }
                }
        );
    }

    private void connectToTv() {
        if (connectionTransferred
                || PhoneTvConnectionManager.getInstance().isConnected()) {
            finish();
            return;
        }

        if (connecting) {
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
        pairingCodeInput.setEnabled(false);
        setStatus("Connecting to Donut4U TV...");

        final InetAddress address = tvAddress;
        final int port = tvPort;

        networkExecutor.execute(() -> {
            Socket newSocket = null;

            try {
                newSocket = TvPairingTls.connect(
                        address,
                        port,
                        code,
                        PAIRING_TIMEOUT_MS
                );

                newSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                newSocket.setTcpNoDelay(true);
                newSocket.setKeepAlive(true);

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

                JSONObject pairingRequest = new JSONObject();
                pairingRequest.put("code", code);

                newWriter.write(pairingRequest.toString());
                newWriter.newLine();
                newWriter.flush();

                String responseLine = newReader.readLine();

                if (responseLine == null
                        || responseLine.length() > 100000) {
                    throw new Exception(
                            "TV did not return a valid pairing response."
                    );
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

                boolean adopted =
                        PhoneTvConnectionManager.getInstance()
                                .adoptConnection(
                                        newSocket,
                                        newReader,
                                        newWriter
                                );

                if (!adopted) {
                    throw new Exception(
                            "Could not keep the TV connection active."
                    );
                }

                // The application-wide manager now owns this socket.
                // Do not close it when this Activity is destroyed.
                connectionTransferred = true;
                newSocket = null;

                mainHandler.post(() -> {
                    connecting = false;

                    stopDiscovery();

                    if (!closing) {
                        Toast.makeText(
                                PhoneRemoteActivity.this,
                                "Phone successfully paired with Donut4U TV.",
                                Toast.LENGTH_LONG
                        ).show();

                        finish();
                    }
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
                        pairingCodeInput.setEnabled(true);

                        setStatus(
                                "Pairing failed: " + safeMessage(e)
                        );
                    }
                });
            }
        });
    }

    private void stopDiscovery() {
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

        discovering = false;
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
        button.setTextSize(16);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(round(GREEN, dp(10)));
        return button;
    }

    private GradientDrawable round(
            int color,
            int radius
    ) {
        GradientDrawable drawable =
                new GradientDrawable();

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
        connecting = false;

        stopDiscovery();

        // The socket is intentionally NOT closed here after a
        // successful transfer. PhoneTvConnectionManager owns it.
        networkExecutor.shutdownNow();

        super.onDestroy();
    }
}
