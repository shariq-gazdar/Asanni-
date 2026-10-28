package com.asanni.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.google.firebase.auth.FirebaseUser;

/**
 * TEMPORARY screen for returning users whose onboarding is complete.
 * Proves the sign-in worked: shows the real Firebase account name/email and allows signing out.
 * It is NOT the diner or manager dashboard. Replace the destination in AppNavigator.openSignedInHome().
 *
 * Location: app/src/main/java/com/asanni/app/ui/AuthenticatedPlaceholderActivity.java  (NEW)
 */
public class AuthenticatedPlaceholderActivity extends Activity {

    private GoogleAuthClient authClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            // Session gone (signed out elsewhere): never show a fake signed-in state.
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }

        setContentView(R.layout.activity_authenticated_placeholder);
        SystemBarsHelper.apply(this, findViewById(R.id.placeholder_root));
        authClient = new GoogleAuthClient(this);

        String name = user.getDisplayName() != null ? user.getDisplayName() : "";
        String email = user.getEmail() != null ? user.getEmail() : "";
        ((TextView) findViewById(R.id.text_signed_in_name)).setText(name);
        ((TextView) findViewById(R.id.text_signed_in_email)).setText(email);

        findViewById(R.id.button_sign_out).setOnClickListener(v -> {
            v.setEnabled(false);
            authClient.signOut(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    AppNavigator.openGetStartedAfterSignOut(this);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (authClient != null) authClient.cancel();
        super.onDestroy();
    }
}