
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class Stage3SongHubRemoteActivity extends Activity {

    private static final int BG = Color.rgb(18, 22, 25);
    private static final int GREEN = Color.rgb(0, 200, 120);
    private static final int WHITE = Color.WHITE;

    private EditText hostInput;
    private TextView status;
    private TextView pairingCodeView;
    private WebView webView;

    private SharedPreferences preferences;

    private final TvKeyboardReceiver.Listener keyboardListener =
            new TvKeyboardReceiver.Listener() {

        @Override
        public void onPairingCodeReady(String code) {
            if (pairingCodeView != null) {
                pairingCodeView.setText(
                        "TV PAIRING CODE: " + code
                );
            }
        }

        @Override
        public void onSearchRequested(String songTitle) {
            if (status != null) {
                status.setText(
                        "SongHub connection screen is active. "
                                + "Use the host/IP field for the "
                                + "SongHub server address."
                );
            }
        }

        @Override
        public void onStatusChanged(String message) {
            if (status != null) {
                status.setText(message);
            }
        }

        @Override
        public void onError(String message) {
            if (status != null) {
                status.setText(message);
            }
        }

        @Override
        public void onTextInput(
                String target,
                String text,
                String action
        ) {
            applyPhoneKeyboardInput(target, text, action);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(
                "donut4u_stage3_songhub_remote",
                MODE_PRIVATE
        );

        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(GREEN);
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • SONGHUB CONNECTION");
        subtitle.setTextColor(WHITE);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);

        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(32)
        ));

        pairingCodeView = new TextView(this);
        pairingCodeView.setText("TV PAIRING CODE: PREPARING...");
        pairingCodeView.setTextColor(GREEN);
        pairingCodeView.setTextSize(19);
        pairingCodeView.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        pairingCodeView.setGravity(Gravity.CENTER);
        pairingCodeView.setPadding(
                dp(4), dp(10), dp(4), dp(10)
        );

        root.addView(pairingCodeView);

        TextView keyboardHint = new TextView(this);
        keyboardHint.setText(
                "Connect your phone using the code above. "
                        + "Select the host/IP field, then type "
                        + "and submit from your phone."
        );
        keyboardHint.setTextColor(Color.LTGRAY);
        keyboardHint.setTextSize(12);
        keyboardHint.setGravity(Gravity.CENTER);
        keyboardHint.setPadding(
                dp(4), dp(4), dp(4), dp(10)
        );

        root.addView(keyboardHint);

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setTextColor(WHITE);
        hostInput.setHintTextColor(Color.LTGRAY);
        hostInput.setHint("SongHub host IP address");
        hostInput.setText(
                preferences.getString("host", "127.0.0.1")
        );
        hostInput.setSelectAllOnFocus(true);
        hostInput.setPadding(dp(12), 0, dp(12), 0);

        hostInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && status != null) {
                status.setText(
                        "SongHub host/IP field selected. "
                                + "You can type from the phone."
                );
            }
        });

        root.addView(hostInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button connect = makeButton("CONNECT");
        connect.setOnClickListener(v -> connectToSongHub());

        buttons.addView(connect, new LinearLayout.LayoutParams(
                0, dp(48), 1
        ));

        Button reload = makeButton("RELOAD");
        reload.setOnClickListener(v -> {
            if (webView != null && !webView.getUrl().isEmpty()) {
                webView.reload();
            } else {
                connectToSongHub();
            }
        });

        LinearLayout.LayoutParams reloadParams =
                new LinearLayout.LayoutParams(0, dp(48), 1);

        reloadParams.leftMargin = dp(6);
        buttons.addView(reload, reloadParams);

        root.addView(buttons);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(12);
        status.setPadding(dp(4), dp(8), dp(4), dp(8));
        status.setText(
                "SongHub must be installed and its "
                        + "Remote Server Host enabled."
        );

        root.addView(status);

        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                if (status != null) {
                    status.setText("Connecting to: " + url);
                }
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                if (status != null) {
                    status.setText("Page loaded: " + url);
                }
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error
            ) {
                if (request.isForMainFrame() && status != null) {
                    status.setText(
                            "Connection failed. Check the SongHub app, "
                                    + "Remote Server Host, host IP "
                                    + "and port 48081."
                    );
                }
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        Button back = makeButton("BACK TO STAGE 3");
        back.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                );

        backParams.topMargin = dp(8);
        root.addView(back, backParams);

        ScrollView container = new ScrollView(this);
        container.setFillViewport(true);
        container.addView(root);

        setContentView(container);
    }

    private void applyPhoneKeyboardInput(
            String target,
            String text,
            String action
    ) {
        if (hostInput == null) {
            return;
        }

        String normalizedTarget = target == null
                ? ""
                : target.trim().toLowerCase(java.util.Locale.US);

        boolean acceptedTarget =
                normalizedTarget.isEmpty()
                        || "active".equals(normalizedTarget)
                        || "host".equals(normalizedTarget)
                        || "songhubhost".equals(normalizedTarget)
                        || "songhub_host".equals(normalizedTarget);

        if (!acceptedTarget) {
            return;
        }

        String current = hostInput.getText().toString();
        String incoming = text == null ? "" : text;

        String normalizedAction = action == null
                ? "replace"
                : action.trim().toLowerCase(
                        java.util.Locale.US
                );

        switch (normalizedAction) {
            case "replace":
                hostInput.setText(incoming);
                break;

            case "append":
                hostInput.setText(current + incoming);
                break;

            case "backspace":
                if (!current.isEmpty()) {
                    hostInput.setText(
                            current.substring(0, current.length() - 1)
                    );
                }
                break;

            case "clear":
                hostInput.setText("");
                break;

            case "submit":
                connectToSongHub();
                return;

            default:
                return;
        }

        hostInput.setSelection(
                hostInput.getText().length()
        );

        hostInput.requestFocus();

        if (status != null) {
            status.setText(
                    "Phone keyboard updated the SongHub host/IP field."
            );
        }
    }

    private void connectToSongHub() {
        if (hostInput == null || webView == null) {
            return;
        }

        String host = hostInput.getText().toString().trim();

        if (host.isEmpty()) {
            hostInput.setError(
                    "Enter the SongHub host IP address"
            );
            return;
        }

        if (host.contains("://")
                || host.contains("/")
                || host.contains(" ")
                || host.contains(":")) {
            hostInput.setError(
                    "Enter only the host or IP address"
            );
            return;
        }

        preferences.edit()
                .putString("host", host)
                .apply();

        String url = "http://" + host + ":48081/";

        status.setText("Trying to connect to " + url);
        webView.loadUrl(url);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(12);
        button.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(GREEN)
        );
        return button;
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();

        TvKeyboardReceiver.enableSession(
                getApplicationContext(),
                keyboardListener
        );

        String code =
                TvKeyboardReceiver.getCurrentPairingCode();

        if (pairingCodeView != null && code != null
                && !code.isEmpty()) {
            pairingCodeView.setText(
                    "TV PAIRING CODE: " + code
            );
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
