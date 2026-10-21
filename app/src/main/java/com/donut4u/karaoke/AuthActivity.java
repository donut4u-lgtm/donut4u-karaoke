
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
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

public class AuthActivity extends Activity {

    private static final int DARK = Color.rgb(13, 20, 17);
    private static final int PANEL = Color.rgb(25, 37, 31);
    private static final int GREEN = Color.rgb(0, 190, 115);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(190, 205, 196);

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

        FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser != null && currentUser.isEmailVerified()) {
            openMainScreen();
            return;
        }

        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(DARK);

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(GREEN);
        title.setTextSize(27);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Sign in or create your account");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(24));

        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        emailInput = new EditText(this);
        emailInput.setSingleLine(true);
        emailInput.setTextColor(WHITE);
        emailInput.setHintTextColor(MUTED);
        emailInput.setHint("Email address");
        emailInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
        emailInput.setPadding(dp(12), 0, dp(12), 0);
        emailInput.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams fieldParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        fieldParams.bottomMargin = dp(12);
        root.addView(emailInput, fieldParams);

        passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setTextColor(WHITE);
        passwordInput.setHintTextColor(MUTED);
        passwordInput.setHint("Password");
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        passwordInput.setPadding(dp(12), 0, dp(12), 0);
        passwordInput.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams passwordParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        passwordParams.bottomMargin = dp(12);
        root.addView(passwordInput, passwordParams);

        Button signInButton = makeButton("SIGN IN");
        signInButton.setOnClickListener(v -> signIn());
        root.addView(signInButton, buttonParams());

        Button createButton = makeButton("CREATE ACCOUNT");
        createButton.setOnClickListener(v -> createAccount());
        root.addView(createButton, buttonParams());

        Button verifyButton = makeButton("RESEND VERIFICATION EMAIL");
        verifyButton.setOnClickListener(v -> resendVerification());
        root.addView(verifyButton, buttonParams());

        Button resetButton = makeButton("RESET PASSWORD");
        resetButton.setOnClickListener(v -> resetPassword());
        root.addView(resetButton, buttonParams());

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-2, dp(48)));

        statusText = new TextView(this);
        statusText.setTextColor(MUTED);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        TextView information = new TextView(this);
        information.setText(
                "Verify your email address before entering Donut4U Karaoke."
        );
        information.setTextColor(MUTED);
        information.setTextSize(12);
        information.setGravity(Gravity.CENTER);
        information.setPadding(0, dp(12), 0, 0);
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
                        setLoading(false, "");

                        FirebaseUser refreshed = auth.getCurrentUser();

                        if (refreshed != null && refreshed.isEmailVerified()) {
                            openMainScreen();
                        } else {
                            showMessage(
                                    "Your email is not verified yet. "
                                            + "Check your inbox or tap "
                                            + "RESEND VERIFICATION EMAIL."
                            );
                        }
                    });
                });
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
                        showError(
                                task.getException(),
                                "Account creation failed."
                        );
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
                                            "Account created. A verification email "
                                                    + "has been sent. Verify it, then "
                                                    + "sign in again."
                                    );
                                } else {
                                    showError(
                                            verifyTask.getException(),
                                            "Account created, but the verification "
                                                    + "email could not be sent. "
                                                    + "Tap RESEND VERIFICATION EMAIL."
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
                            setLoading(false, "");
                            openMainScreen();
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
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusText.setText(message);

        emailInput.setEnabled(!loading);
        passwordInput.setEnabled(!loading);
    }

    private void showError(Exception exception, String fallback) {
        String message = fallback;

        if (exception != null && exception.getLocalizedMessage() != null) {
            message = exception.getLocalizedMessage();
        }

        showMessage(message);
    }

    private void showMessage(String message) {
        statusText.setText(message);
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
