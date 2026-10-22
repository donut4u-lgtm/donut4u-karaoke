
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AuthActivity extends Activity {

    private static final int DARK = Color.rgb(13, 20, 17);
    private static final int PANEL = Color.rgb(25, 37, 31);
    private static final int GREEN = Color.rgb(0, 190, 115);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 205, 196);

    private static final String DATABASE_URL =
            "https://donut4u-karaoke-39967-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private static final String DEVICE_PREFS = "donut4u_device_session";
    private static final String DEVICE_ID_KEY = "installation_id";

    private FirebaseAuth auth;
    private EditText emailInput;
    private EditText passwordInput;
    private TextView statusText;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);

        auth = FirebaseAuth.getInstance();
        buildScreen();

        FirebaseUser user = auth.getCurrentUser();

        if (user != null && user.isEmailVerified()) {
            setLoading(true, "Checking your account...");
            user.reload().addOnCompleteListener(this, task -> {
                FirebaseUser refreshed = auth.getCurrentUser();

                if (refreshed != null && refreshed.isEmailVerified()) {
                    createActiveSession(refreshed);
                } else {
                    setLoading(false, "");
                }
            });
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(DARK);

        TextView title = new TextView(this);
        title.setText("Donut4u karaoke");
        title.setTextColor(GREEN);
        title.setTextSize(27);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Welcome back");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView emailLabel = new TextView(this);
        emailLabel.setText("Email address");
        emailLabel.setTextColor(WHITE);
        emailLabel.setTextSize(14);
        root.addView(emailLabel, new LinearLayout.LayoutParams(-1, -2));

        emailInput = new EditText(this);
        emailInput.setSingleLine(true);
        emailInput.setTextColor(WHITE);
        emailInput.setHintTextColor(MUTED);
        emailInput.setHint("Enter your email address");
        emailInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
        emailInput.setPadding(dp(12), 0, dp(12), 0);
        emailInput.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams fieldParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        fieldParams.bottomMargin = dp(14);
        root.addView(emailInput, fieldParams);

        TextView passwordLabel = new TextView(this);
        passwordLabel.setText("Password");
        passwordLabel.setTextColor(WHITE);
        passwordLabel.setTextSize(14);
        root.addView(passwordLabel, new LinearLayout.LayoutParams(-1, -2));

        passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setTextColor(WHITE);
        passwordInput.setHintTextColor(MUTED);
        passwordInput.setHint("Enter your password");
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        passwordInput.setPadding(dp(12), 0, dp(12), 0);
        passwordInput.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams passwordParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        passwordParams.bottomMargin = dp(16);
        root.addView(passwordInput, passwordParams);

        Button signInButton = makeButton("Sign in");
        signInButton.setOnClickListener(v -> signIn());
        root.addView(signInButton, buttonParams());

        Button createButton = makeButton("Create new account");
        createButton.setOnClickListener(v -> createAccount());
        root.addView(createButton, buttonParams());

        Button verifyButton = makeButton("Resend verification email");
        verifyButton.setOnClickListener(v -> resendVerification());
        root.addView(verifyButton, buttonParams());

        Button resetButton = makeButton("Forgot password");
        resetButton.setOnClickListener(v -> resetPassword());
        root.addView(resetButton, buttonParams());

        TextView advertisement = new TextView(this);
        advertisement.setText("Advertisement");
        advertisement.setTextColor(MUTED);
        advertisement.setTextSize(12);
        advertisement.setGravity(Gravity.CENTER);
        advertisement.setPadding(0, dp(16), 0, dp(8));
        root.addView(advertisement, new LinearLayout.LayoutParams(-1, -2));

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-2, dp(42)));

        statusText = new TextView(this);
        statusText.setTextColor(MUTED);
        statusText.setTextSize(13);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        TextView information = new TextView(this);
        information.setText("Email verification is required.");
        information.setTextColor(MUTED);
        information.setTextSize(12);
        information.setGravity(Gravity.CENTER);
        information.setPadding(0, dp(8), 0, 0);
        root.addView(information, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void signIn() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (!validate(email, password, false)) {
            return;
        }

        setLoading(true, "Signing in...");

        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        setLoading(false, "");
                        showError(task.getException(), "Sign-in failed.");
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        setLoading(false, "");
                        showMessage("Unable to retrieve your account.");
                        return;
                    }

                    user.reload().addOnCompleteListener(this, reloadTask -> {
                        FirebaseUser refreshed = auth.getCurrentUser();

                        if (refreshed == null) {
                            setLoading(false, "");
                            showMessage("Unable to retrieve your account.");
                            return;
                        }

                        if (!refreshed.isEmailVerified()) {
                            setLoading(false, "");
                            showMessage(
                                    "Email verification is required. "
                                            + "Check your inbox, then sign in again."
                            );
                            return;
                        }

                        createActiveSession(refreshed);
                    });
                });
    }

    private void createActiveSession(FirebaseUser user) {
        if (user == null || user.getUid() == null) {
            setLoading(false, "");
            showMessage("Unable to identify your account.");
            return;
        }

        String installationId = getInstallationId();

        DatabaseReference sessionRef =
                FirebaseDatabase.getInstance(DATABASE_URL)
                        .getReference("activeSessions")
                        .child(user.getUid());

        Map<String, Object> session = new HashMap<>();
        session.put("sessionId", installationId);
        session.put("email", user.getEmail() == null ? "" : user.getEmail());
        session.put("lastActiveAt", System.currentTimeMillis());

        sessionRef.updateChildren(session)
                .addOnCompleteListener(this, task -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    if (task.isSuccessful()) {
                        openMainScreen();
                    } else {
                        setLoading(false, "");
                        showError(
                                task.getException(),
                                "Could not create your active session. "
                                        + "Check Realtime Database rules and internet access."
                        );
                    }
                });
    }

    private String getInstallationId() {
        SharedPreferences prefs = getSharedPreferences(
                DEVICE_PREFS, MODE_PRIVATE
        );

        String id = prefs.getString(DEVICE_ID_KEY, null);

        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(DEVICE_ID_KEY, id).apply();
        }

        return id;
    }

    private void createAccount() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (!validate(email, password, true)) {
            return;
        }

        setLoading(true, "Creating your account...");

        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        setLoading(false, "");
                        showError(task.getException(), "Account creation failed.");
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        setLoading(false, "");
                        showMessage("Account created, but user details are unavailable.");
                        return;
                    }

                    user.sendEmailVerification()
                            .addOnCompleteListener(this, verifyTask -> {
                                setLoading(false, "");

                                if (verifyTask.isSuccessful()) {
                                    showMessage(
                                            "Account created. Check your email "
                                                    + "and verify your address before signing in."
                                    );
                                } else {
                                    showError(
                                            verifyTask.getException(),
                                            "Account created, but the verification email "
                                                    + "could not be sent. Try resending it."
                                    );
                                }
                            });
                });
    }

    private void resendVerification() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (!validate(email, password, false)) {
            return;
        }

        setLoading(true, "Checking account...");

        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        setLoading(false, "");
                        showError(
                                task.getException(),
                                "Sign in first to resend verification."
                        );
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        setLoading(false, "");
                        showMessage("Unable to retrieve your account.");
                        return;
                    }

                    user.reload().addOnCompleteListener(this, reloadTask -> {
                        FirebaseUser refreshed = auth.getCurrentUser();

                        if (refreshed == null) {
                            setLoading(false, "");
                            showMessage("Unable to retrieve your account.");
                            return;
                        }

                        if (refreshed.isEmailVerified()) {
                            createActiveSession(refreshed);
                            return;
                        }

                        refreshed.sendEmailVerification()
                                .addOnCompleteListener(this, sendTask -> {
                                    setLoading(false, "");

                                    if (sendTask.isSuccessful()) {
                                        showMessage(
                                                "Verification email sent. "
                                                        + "Check your inbox and spam folder."
                                        );
                                    } else {
                                        showError(
                                                sendTask.getException(),
                                                "Could not send verification email."
                                        );
                                    }
                                });
                    });
                });
    }

    private void resetPassword() {
        String email = emailInput.getText().toString().trim();

        if (email.isEmpty()) {
            emailInput.setError("Enter your email address first.");
            emailInput.requestFocus();
            return;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Enter a valid email address.");
            emailInput.requestFocus();
            return;
        }

        setLoading(true, "Sending password-reset email...");

        auth.sendPasswordResetEmail(email)
                .addOnCompleteListener(this, task -> {
                    setLoading(false, "");

                    if (task.isSuccessful()) {
                        showMessage(
                                "If the address is registered, "
                                        + "password-reset instructions have been sent."
                        );
                    } else {
                        showError(
                                task.getException(),
                                "Could not send reset email."
                        );
                    }
                });
    }

    private boolean validate(
            String email,
            String password,
            boolean creatingAccount
    ) {
        if (email.isEmpty()) {
            emailInput.setError("Email is required.");
            emailInput.requestFocus();
            return false;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Enter a valid email address.");
            emailInput.requestFocus();
            return false;
        }

        if (password.isEmpty()) {
            passwordInput.setError("Password is required.");
            passwordInput.requestFocus();
            return false;
        }

        if (creatingAccount && password.length() < 6) {
            passwordInput.setError("Password must contain at least 6 characters.");
            passwordInput.requestFocus();
            return false;
        }

        return true;
    }

    private void setLoading(boolean loading, String message) {
        if (progressBar != null) {
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        }

        if (statusText != null) {
            statusText.setText(message);
        }

        if (emailInput != null) {
            emailInput.setEnabled(!loading);
        }

        if (passwordInput != null) {
            passwordInput.setEnabled(!loading);
        }
    }

    private void showError(Exception exception, String fallback) {
        String message = fallback;

        if (exception != null
                && exception.getLocalizedMessage() != null
                && !exception.getLocalizedMessage().trim().isEmpty()) {
            message = fallback + "\n" + exception.getLocalizedMessage();
        }

        showMessage(message);
    }

    private void showMessage(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void openMainScreen() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );

        startActivity(intent);
        finish();
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(WHITE);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(50));
        params.bottomMargin = dp(8);
        return params;
    }

    private int dp(float value) {
        return (int) (
                value * getResources().getDisplayMetrics().density + 0.5f
        );
    }
}
