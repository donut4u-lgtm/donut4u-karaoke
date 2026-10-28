
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
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
    private WebView webView;

    private android.content.SharedPreferences preferences;

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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • SONGHUB CONNECTION");
        subtitle.setTextColor(WHITE);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setTextColor(WHITE);
        hostInput.setHintTextColor(Color.LTGRAY);
        hostInput.setHint("SongHub host IP address");
        hostInput.setText(preferences.getString("host", "127.0.0.1"));
        hostInput.setSelectAllOnFocus(true);
        hostInput.setPadding(dp(12), 0, dp(12), 0);
        root.addView(hostInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button connect = makeButton("CONNECT");
        connect.setOnClickListener(v -> connectToSongHub());
        buttons.addView(connect, new LinearLayout.LayoutParams(
                0, dp(48), 1));

        Button reload = makeButton("RELOAD");
        reload.setOnClickListener(v -> {
            if (webView != null) {
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
                "SongHub must be installed and its Remote Server Host enabled."
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
                    WebView view, String url,
                    android.graphics.Bitmap favicon) {
                status.setText("Connecting to: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                status.setText("Page loaded: " + url);
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    status.setText(
                            "Connection failed. Check the SongHub app, " +
                            "Remote Server Host, host IP and port 48081."
                    );
                }
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button back = makeButton("BACK TO STAGE 3");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        backParams.topMargin = dp(8);
        root.addView(back, backParams);

        ScrollView container = new ScrollView(this);
        container.setFillViewport(true);
        container.addView(root);

        setContentView(container);
    }

    private void connectToSongHub() {
        String host = hostInput.getText().toString().trim();

        if (host.isEmpty()) {
            hostInput.setError("Enter the SongHub host IP address");
            return;
        }

        if (host.contains("://")
                || host.contains("/")
                || host.contains(" ")) {
            hostInput.setError("Enter only the host or IP address");
            return;
        }

        preferences.edit().putString("host", host).apply();

        String url = "http://" + host + ":48081/";

        status.setText("Trying to connect to " + url);
        webView.loadUrl(url);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
