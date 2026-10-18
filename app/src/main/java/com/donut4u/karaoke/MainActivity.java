
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();

        if (user != null && user.isEmailVerified()) {
            startActivity(new Intent(
                    MainActivity.this,
                    OnlineSongActivity.class
            ));
        } else {
            startActivity(new Intent(
                    MainActivity.this,
                    AuthActivity.class
            ));
        }

        finish();
    }
}
