
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.UUID;

public class AuthActivity extends Activity {

    private static final int BG = Color.rgb(10, 12, 15);
    private static final int GREEN = Color.rgb(0, 220, 105);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.LTGRAY;

    private static final String DATABASE_URL =
            "https://donut4u-karaoke-39967-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private static final String DEVICE_PREFS = "donut4u_device_session";
    private static final String DEVICE_ID_KEY = "installation_id";

    private FirebaseAuth auth;
    private EditText emailInput;
    private EditText passwordInput;
    private TextView status;
    private Button signInButton;

    private boolean working = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        auth = FirebaseAuth.getInstance();
        showAuthScreen();

        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.isEmailVerified()) {
            registerSessionAndOpen(user);
        }
    }

    private String getInstallationId() {
        SharedPreferences prefs = getSharedPreferences(
                DEVICE_PREFS, MODE_PRIVATE);

        String id = prefs.getString(DEVICE_ID_KEY, null);

        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(DEVICE_ID_KEY, id).apply();
        }

        return id;
    }

    private void registerSessionAndOpen(FirebaseUser user) {
        if (user == null || !user.isEmailVerified() || working) return;

        working = true;
        setStatus("Registering this phone...");

        String installationId = getInstallationId();

        DatabaseReference sessionRef =
                FirebaseDatabase.getInstance(DATABASE_URL)
                        .getReference("activeSessions")
                        .child(user.getUid());

        java.util.HashMap<String, Object> sessionData =
                new java.util.HashMap<>();
        sessionData.put("sessionId", installationId);
        sessionData.put("updatedAt", System.currentTimeMillis());

        sessionRef.updateChildren(sessionData)
                .addOnSuccessListener(unused -> {
                    if (isFinishing()) return;

                    working = false;

                    Intent intent = new Intent(
                            AuthActivity.this,
                            OnlineSongActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(error -> {
                    working = false;
                    setStatus("Could not register this phone: "
                            + error.getMessage()
                            + "\nCheck your internet connection and Firebase Database rules.");
                    Toast.makeText(this,
                            "Session registration failed.",
                            Toast.LENGTH_LONG).show();
                });
    }

    private void showAuthScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(GREEN);
        title.setTextSize(27);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidthWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("YOUR SONG. YOUR STAGE.");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(28));
        root.addView(subtitle, fullWidthWrap());

        emailInput = new EditText(this);
        emailInput.setSingleLine(true);
        emailInput.setHint("Email address");
        styleInput(emailInput);
        root.addView(emailInput, fullWidth(dp(54)));

        passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setHint("Password");
        passwordInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        styleInput(passwordInput);

        LinearLayout.LayoutParams passwordParams = fullWidth(dp(54));
        passwordParams.topMargin = dp(12);
        root.addView(passwordInput, passwordParams);

        signInButton = makeButton("SIGN IN");
        LinearLayout.LayoutParams signInParams = fullWidth(dp(52));
        signInParams.topMargin = dp(18);
        root.addView(signInButton, signInParams);
        signInButton.setOnClickListener(v -> signIn());

        Button createButton = makeButton("CREATE ACCOUNT");
        LinearLayout.LayoutParams createParams = fullWidth(dp(52));
        createParams.topMargin = dp(10);
        root.addView(createButton, createParams);
        createButton.setOnClickListener(v -> createAccount());

        Button resetButton = makeButton("RESET PASSWORD");
        LinearLayout.LayoutParams resetParams = fullWidth(dp(52));
        resetParams.topMargin = dp(10);
        root.addView(resetButton, resetParams);
        resetButton.setOnClickListener(v -> resetPassword());

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status, fullWidthWrap());

        setContentView(root);
    }

    private void signIn() {
        if (working) return;

        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            setStatus("Enter your email address and password.");
            return;
        }

        working = true;
        setStatus("Signing in...");
        hideKeyboard();

        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        working = false;
                        setStatus("Sign-in failed: "
                                + (task.getException() == null
                                ? "Please check your details."
                                : task.getException().getMessage()));
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        working = false;
                        setStatus("Sign-in failed. Please try again.");
                        return;
                    }

                    user.reload().addOnCompleteListener(this, reloadTask -> {
                        FirebaseUser refreshed = auth.getCurrentUser();

                        if (refreshed == null) {
                            working = false;
                            setStatus("Could not verify your account.");
                            return;
                        }

                        if (!refreshed.isEmailVerified()) {
                            working = false;
                            setStatus("Please verify your email first. Open the verification link sent to your email, then sign in again.");
                            return;
                        }

                        registerSessionAndOpen(refreshed);
                    });
                });
    }

    private void createAccount() {
        if (working) return;

        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            setStatus("Enter an email address and password.");
            return;
        }

        if (password.length() < 6) {
            setStatus("Password must contain at least 6 characters.");
            return;
        }

        working = true;
        setStatus("Creating your account...");
        hideKeyboard();

        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        working = false;
                        setStatus("Account creation failed: "
                                + (task.getException() == null
                                ? "Please try again."
                                : task.getException().getMessage()));
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        working = false;
                        setStatus("Account created, but verification email could not be sent. Please sign in and try again.");
                        return;
                    }

                    user.sendEmailVerification()
                            .addOnCompleteListener(this, emailTask -> {
                                working = false;

                                if (emailTask.isSuccessful()) {
                                    setStatus("Account created. Check your email and tap the verification link before signing in.");
                                    auth.signOut();
                                } else {
                                    setStatus("Account created, but verification email failed to send. Try signing in to resend it.");
                                }
                            });
                });
    }

    private void resetPassword() {
        String email = emailInput.getText().toString().trim();

        if (email.isEmpty()) {
            setStatus("Enter your email address first.");
            return;
        }

        setStatus("Sending password reset email...");

        auth.sendPasswordResetEmail(email)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        setStatus("Password reset email sent. Check your inbox.");
                    } else {
                        setStatus("Could not send reset email: "
                                + (task.getException() == null
                                ? "Please try again."
                                : task.getException().getMessage()));
                    }
                });
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(
                        passwordInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void styleInput(EditText input) {
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackgroundColor(Color.rgb(35, 39, 42));
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(15);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundColor(GREEN);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }
}
