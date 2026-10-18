
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class AuthActivity extends Activity {

    private static final int GREEN = Color.rgb(0, 210, 105);
    private static final int BG = Color.rgb(16, 19, 21);
    private static final int PANEL = Color.rgb(35, 39, 42);

    private FirebaseAuth auth;
    private EditText emailField;
    private EditText passwordField;
    private TextView message;
    private boolean createMode = false;
    private boolean openingKaraoke = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        auth = FirebaseAuth.getInstance();

        FirebaseUser user = auth.getCurrentUser();

        if (user != null && user.isEmailVerified()) {
            openKaraoke();
        } else if (user != null) {
            showVerification(user, "Please verify your email to continue.");
        } else {
            showLoginForm();
        }
    }

    private void showLoginForm() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));

        scroll.addView(root);

        addText(root, "Donut4U Karaoke", GREEN, 27, true);
        addText(root,
                createMode ? "Create your account" : "Sign in",
                Color.WHITE, 21, true);

        addText(root,
                "Email verification is required before karaoke access.",
                Color.LTGRAY, 14, false);

        emailField = new EditText(this);
        emailField.setSingleLine(true);
        emailField.setHint("Email address");
        emailField.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
        styleField(emailField);

        LinearLayout.LayoutParams emailParams = fullWidth(54);
        emailParams.topMargin = dp(18);
        root.addView(emailField, emailParams);

        passwordField = new EditText(this);
        passwordField.setSingleLine(true);
        passwordField.setHint("Password (minimum 6 characters)");
        passwordField.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        styleField(passwordField);

        LinearLayout.LayoutParams passwordParams = fullWidth(54);
        passwordParams.topMargin = dp(10);
        root.addView(passwordField, passwordParams);

        message = addText(root, "", Color.LTGRAY, 13, false);

        Button primary = makeButton(
                createMode ? "CREATE ACCOUNT" : "SIGN IN"
        );

        LinearLayout.LayoutParams primaryParams = fullWidth(52);
        primaryParams.topMargin = dp(16);
        root.addView(primary, primaryParams);

        primary.setOnClickListener(v -> {
            if (createMode) {
                createAccount();
            } else {
                signIn();
            }
        });

        Button switchButton = makeButton(
                createMode
                        ? "I ALREADY HAVE AN ACCOUNT"
                        : "CREATE NEW ACCOUNT"
        );

        LinearLayout.LayoutParams switchParams = fullWidth(48);
        switchParams.topMargin = dp(8);
        root.addView(switchButton, switchParams);

        switchButton.setOnClickListener(v -> {
            createMode = !createMode;
            showLoginForm();
        });

        Button resetButton = makeButton("RESET PASSWORD");

        LinearLayout.LayoutParams resetParams = fullWidth(48);
        resetParams.topMargin = dp(8);
        root.addView(resetButton, resetParams);

        resetButton.setOnClickListener(v -> resetPassword());

        setContentView(scroll);
    }

    private void createAccount() {
        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString();

        if (!validate(email, password)) {
            return;
        }

        setMessage("Creating account...");

        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {

                    if (!task.isSuccessful()) {
                        setMessage(errorMessage(task.getException()));
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        setMessage("Account created. Please sign in again.");
                        return;
                    }

                    user.sendEmailVerification()
                            .addOnCompleteListener(this, sendTask -> {

                                if (sendTask.isSuccessful()) {
                                    showVerification(
                                            user,
                                            "Verification email sent. Check your inbox and spam folder."
                                    );
                                } else {
                                    showVerification(
                                            user,
                                            "Account created, but email could not be sent. Tap RESEND EMAIL."
                                    );
                                }
                            });
                });
    }

    private void signIn() {
        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString();

        if (!validate(email, password)) {
            return;
        }

        setMessage("Signing in...");

        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {

                    if (!task.isSuccessful()) {
                        setMessage(errorMessage(task.getException()));
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        setMessage("Could not load your account.");
                    } else if (user.isEmailVerified()) {
                        openKaraoke();
                    } else {
                        showVerification(
                                user,
                                "Your email is not verified yet. Verify it to continue."
                        );
                    }
                });
    }

    private boolean validate(String email, String password) {
        if (email.isEmpty()) {
            setMessage("Enter your email address.");
            return false;
        }

        if (password.length() < 6) {
            setMessage("Password must contain at least 6 characters.");
            return false;
        }

        return true;
    }

    private void showVerification(
            FirebaseUser user,
            String initialMessage
    ) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));

        scroll.addView(root);

        addText(root, "Donut4U Karaoke", GREEN, 27, true);
        addText(root, "Verify your email", Color.WHITE, 22, true);

        addText(
                root,
                "Tap the verification link sent to:",
                Color.LTGRAY, 14, false
        );

        addText(
                root,
                user.getEmail() == null ? "" : user.getEmail(),
                GREEN, 16, true
        );

        addText(
                root,
                "After opening the link in your email, return here and tap "
                        + "I VERIFIED MY EMAIL. Check your spam folder if needed.",
                Color.LTGRAY, 14, false
        );

        message = addText(root, initialMessage, Color.LTGRAY, 14, false);

        Button checkButton = makeButton("I VERIFIED MY EMAIL");

        LinearLayout.LayoutParams checkParams = fullWidth(52);
        checkParams.topMargin = dp(16);
        root.addView(checkButton, checkParams);

        checkButton.setOnClickListener(v -> checkVerification());

        Button resendButton = makeButton("RESEND EMAIL");

        LinearLayout.LayoutParams resendParams = fullWidth(48);
        resendParams.topMargin = dp(8);
        root.addView(resendButton, resendParams);

        resendButton.setOnClickListener(v -> resendVerification());

        Button signOutButton = makeButton("SIGN OUT / USE ANOTHER EMAIL");

        LinearLayout.LayoutParams signOutParams = fullWidth(48);
        signOutParams.topMargin = dp(8);
        root.addView(signOutButton, signOutParams);

        signOutButton.setOnClickListener(v -> {
            auth.signOut();
            createMode = false;
            showLoginForm();
        });

        setContentView(scroll);
    }

    private void checkVerification() {
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            showLoginForm();
            return;
        }

        setMessage("Checking email verification...");

        user.reload().addOnCompleteListener(this, task -> {

            FirebaseUser refreshedUser = auth.getCurrentUser();

            if (task.isSuccessful()
                    && refreshedUser != null
                    && refreshedUser.isEmailVerified()) {

                openKaraoke();

            } else if (!task.isSuccessful()) {

                setMessage(
                        "Could not check verification. Check your internet and try again."
                );

            } else {

                setMessage(
                        "Email is not verified yet. Open the email link, then try again."
                );
            }
        });
    }

    private void resendVerification() {
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            showLoginForm();
            return;
        }

        setMessage("Sending verification email...");

        user.sendEmailVerification()
                .addOnCompleteListener(this, task -> {

                    if (task.isSuccessful()) {
                        setMessage(
                                "Email sent. Check your inbox and spam folder."
                        );
                    } else {
                        setMessage(errorMessage(task.getException()));
                    }
                });
    }

    private void resetPassword() {
        String email = emailField.getText().toString().trim();

        if (email.isEmpty()) {
            setMessage("Enter your email address first.");
            return;
        }

        auth.sendPasswordResetEmail(email)
                .addOnCompleteListener(this, task -> {

                    if (task.isSuccessful()) {
                        setMessage(
                                "If the account exists, a password-reset email has been sent."
                        );
                    } else {
                        setMessage(errorMessage(task.getException()));
                    }
                });
    }

    private void openKaraoke() {
        if (openingKaraoke || isFinishing()) {
            return;
        }

        openingKaraoke = true;

        startActivity(
                new Intent(this, OnlineSongActivity.class)
        );

        finish();
    }

    private TextView addText(
            LinearLayout root,
            String value,
            int color,
            int size,
            boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(4), dp(6), dp(4), dp(6));

        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }

        root.addView(view, fullWidthWrap());
        return view;
    }

    private void styleField(EditText field) {
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.LTGRAY);
        field.setTextSize(15);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(roundDrawable(PANEL, dp(8)));
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(13);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(roundDrawable(GREEN, dp(8)));
        return button;
    }

    private GradientDrawable roundDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (
                value * getResources().getDisplayMetrics().density + 0.5f
        );
    }

    private void setMessage(String text) {
        if (message != null) {
            message.setText(text);
        }
    }

    private String errorMessage(Exception error) {
        if (error == null || error.getMessage() == null) {
            return "Something went wrong. Please try again.";
        }

        return error.getMessage();
    }
}
