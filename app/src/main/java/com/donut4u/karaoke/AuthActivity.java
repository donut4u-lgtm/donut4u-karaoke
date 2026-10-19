
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AuthActivity extends Activity {

    private static final int GREEN = Color.rgb(0, 210, 105);
    private static final int BG = Color.rgb(16, 19, 21);
    private static final int PANEL = Color.rgb(35, 39, 42);
    private static final int MUTED = Color.rgb(185, 194, 189);

    private static final String DATABASE_URL =
            "https://donut4u-karaoke-39967-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private static final String DEVICE_PREFS =
            "donut4u_device_session";

    private static final String DEVICE_ID_KEY =
            "installation_id";

    private FirebaseAuth auth;
    private EditText emailField;
    private EditText passwordField;
    private TextView message;

    private boolean createMode = false;
    private boolean openingKaraoke = false;
    private boolean claimingSession = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        auth = FirebaseAuth.getInstance();

        FirebaseUser user = auth.getCurrentUser();

        if (user != null && user.isEmailVerified()) {
            claimActiveSession(user);
        } else if (user != null) {
            showVerification(
                    user,
                    "Please verify your email to continue."
            );
        } else {
            showLoginForm();
        }
    }

    private void showLoginForm() {
        openingKaraoke = false;
        claimingSession = false;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(30), dp(24), dp(28));

        scroll.addView(root);

        View logo = new View(this);
        logo.setBackground(roundDrawable(GREEN, dp(18)));

        LinearLayout.LayoutParams logoParams =
                new LinearLayout.LayoutParams(dp(6), dp(6));
        logoParams.bottomMargin = dp(8);
        root.addView(logo, logoParams);

        addText(root, "Donut4U Karaoke", GREEN, 27, true);

        TextView subtitle = addText(
                root,
                createMode ? "Create your account" : "Welcome back",
                Color.WHITE,
                22,
                true
        );
        subtitle.setPadding(0, dp(12), 0, dp(8));

        addText(
                root,
                createMode
                        ? "Create an account to start singing."
                        : "Sign in to continue to karaoke.",
                MUTED,
                14,
                false
        );

        addLabel(root, "EMAIL ADDRESS");

        emailField = new EditText(this);
        emailField.setSingleLine(true);
        emailField.setHint("you@example.com");
        emailField.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
        emailField.setAutofillHints("emailAddress");
        styleField(emailField);

        LinearLayout.LayoutParams emailParams = fullWidth(dp(58));
        emailParams.topMargin = dp(8);
        emailParams.bottomMargin = dp(24);
        root.addView(emailField, emailParams);

        addLabel(root, "PASSWORD");

        passwordField = new EditText(this);
        passwordField.setSingleLine(true);
        passwordField.setHint("At least 6 characters");
        passwordField.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        passwordField.setAutofillHints("password");
        styleField(passwordField);

        LinearLayout.LayoutParams passwordParams = fullWidth(dp(58));
        passwordParams.topMargin = dp(8);
        passwordParams.bottomMargin = dp(12);
        root.addView(passwordField, passwordParams);

        message = addText(root, "", MUTED, 13, false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(4), dp(6), dp(4), dp(6));

        Button primary = makeButton(
                createMode ? "CREATE ACCOUNT" : "SIGN IN"
        );

        LinearLayout.LayoutParams primaryParams = fullWidth(dp(54));
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

        LinearLayout.LayoutParams switchParams = fullWidth(dp(50));
        switchParams.topMargin = dp(12);
        root.addView(switchButton, switchParams);

        switchButton.setOnClickListener(v -> {
            createMode = !createMode;
            showLoginForm();
        });

        Button resetButton = makeButton("FORGOT PASSWORD?");

        LinearLayout.LayoutParams resetParams = fullWidth(dp(48));
        resetParams.topMargin = dp(8);
        root.addView(resetButton, resetParams);

        resetButton.setOnClickListener(v -> resetPassword());

        addText(
                root,
                "Email verification is required before karaoke access.",
                MUTED,
                12,
                false
        );

        setContentView(scroll);
    }

    private void addLabel(LinearLayout root, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(GREEN);
        view.setTextSize(12);
        view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams params = fullWidth(
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        root.addView(view, params);
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
                        setMessage(
                                "Account created. Please sign in again."
                        );
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
                        claimActiveSession(user);
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
            setMessage(
                    "Password must contain at least 6 characters."
            );
            return false;
        }

        return true;
    }

    /*
     * Each app installation gets a persistent ID.
     * A successful sign-in writes this ID to Firebase.
     * OnlineSongActivity must monitor this record and sign out
     * when the stored ID no longer matches this installation.
     */
    private String getInstallationId() {
        SharedPreferences prefs = getSharedPreferences(
                DEVICE_PREFS,
                MODE_PRIVATE
        );

        String id = prefs.getString(DEVICE_ID_KEY, null);

        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();

            prefs.edit()
                    .putString(DEVICE_ID_KEY, id)
                    .apply();
        }

        return id;
    }

    private void claimActiveSession(FirebaseUser user) {
        if (claimingSession || openingKaraoke || isFinishing()) {
            return;
        }

        if (user == null || !user.isEmailVerified()) {
            showLoginForm();
            return;
        }

        claimingSession = true;
        setMessage("Activating this phone...");

        String installationId = getInstallationId();

        DatabaseReference sessionRef =
                FirebaseDatabase.getInstance(DATABASE_URL)
                        .getReference("activeSessions")
                        .child(user.getUid());

        Map<String, Object> session = new HashMap<>();
        session.put("sessionId", installationId);
        session.put("updatedAt", System.currentTimeMillis());

        sessionRef.setValue(session)
                .addOnCompleteListener(this, task -> {
                    claimingSession = false;

                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    if (task.isSuccessful()) {
                        openKaraoke();
                    } else {
                        setMessage(
                                "Could not activate this phone. Check your internet connection and Firebase Realtime Database rules, then try again. "
                                        + errorMessage(task.getException())
                        );
                    }
                });
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
        root.setPadding(dp(24), dp(32), dp(24), dp(28));

        scroll.addView(root);

        addText(root, "Donut4U Karaoke", GREEN, 27, true);
        addText(root, "Verify your email", Color.WHITE, 22, true);

        addText(
                root,
                "Tap the verification link sent to:",
                MUTED,
                14,
                false
        );

        addText(
                root,
                user.getEmail() == null ? "" : user.getEmail(),
                GREEN,
                16,
                true
        );

        addText(
                root,
                "After opening the link in your email, return here and tap I VERIFIED MY EMAIL. Check your spam folder if needed.",
                MUTED,
                14,
                false
        );

        message = addText(
                root,
                initialMessage,
                MUTED,
                14,
                false
        );

        Button checkButton = makeButton("I VERIFIED MY EMAIL");
        LinearLayout.LayoutParams checkParams = fullWidth(dp(54));
        checkParams.topMargin = dp(20);
        root.addView(checkButton, checkParams);
        checkButton.setOnClickListener(v -> checkVerification());

        Button resendButton = makeButton("RESEND EMAIL");
        LinearLayout.LayoutParams resendParams = fullWidth(dp(50));
        resendParams.topMargin = dp(12);
        root.addView(resendButton, resendParams);
        resendButton.setOnClickListener(v -> resendVerification());

        Button signOutButton = makeButton(
                "SIGN OUT / USE ANOTHER EMAIL"
        );
        LinearLayout.LayoutParams signOutParams = fullWidth(dp(50));
        signOutParams.topMargin = dp(12);
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
                claimActiveSession(refreshedUser);
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
        view.setPadding(dp(4), dp(8), dp(4), dp(8));

        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }

        root.addView(view, fullWidthWrap());
        return view;
    }

    private void styleField(EditText field) {
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(MUTED);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(roundDrawable(PANEL, dp(10)));
        field.setMinHeight(dp(58));
        field.setSelectAllOnFocus(false);

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            field.setTextCursorDrawable(null);
        }
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(13);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(roundDrawable(GREEN, dp(10)));
        button.setStateListAnimator(null);
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
