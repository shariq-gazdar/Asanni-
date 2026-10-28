package com.asanni.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.UserProfile;
import com.asanni.app.auth.UserProfileRepository;
import com.google.firebase.auth.FirebaseUser;

/**
 * Google Login screen: real Google sign-in through Firebase Authentication, then
 * first-time vs returning routing from the Firestore profile.
 *
 * Location: app/src/main/java/com/asanni/app/ui/GoogleLoginActivity.java  (NEW)
 *
 * Steps:
 *   1. Tap the Google button -> Google's own account sheet (GoogleAuthClient)
 *   2. Firebase session created
 *   3. users/{uid} read or created (UserProfileRepository)
 *   4. not onboarded -> Choose Your Role | onboarded -> signed-in placeholder
 *
 * If a Firebase session already exists (returning user, or the screen was recreated),
 * step 1 is skipped. A second, duplicate sign-in never starts.
 */
public class GoogleLoginActivity extends Activity {

    private static final String TAG = "AsanniLogin";

    /** What the screen is currently doing. */
    private enum State { IDLE, SIGNING_IN, LOADING_PROFILE, SIGNING_OUT, SIGN_IN_ERROR, PROFILE_ERROR }

    private GoogleAuthClient authClient;
    private UserProfileRepository profiles;
    private EntranceAnimator animator;

    private State state = State.IDLE;
    private boolean entrancePlayed;

    private View googleButton;
    private TextView googleButtonLabel;
    private View googleProgress;
    private TextView statusText;
    private View useOtherAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_google_login);
        entrancePlayed = savedInstanceState != null;

        authClient = new GoogleAuthClient(this);
        profiles = new UserProfileRepository();
        animator = new EntranceAnimator(getContentResolver());

        SystemBarsHelper.apply(this, findViewById(R.id.login_root));
        bindViews();
        setUpOptionalImages();

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        googleButton.setOnClickListener(v -> onGoogleButtonClicked());
        useOtherAccount.setOnClickListener(v -> signOutAndReset());
        animator.attachPressFeedback(googleButton);

        // Already signed in (returning user, or the activity was recreated mid-flow): skip Google.
        FirebaseUser existing = GoogleAuthClient.currentUser();
        if (existing != null) {
            loadProfileAndRoute(existing);
        } else {
            render(State.IDLE, null);
        }
    }

    private void bindViews() {
        googleButton = findViewById(R.id.button_google);
        googleButtonLabel = findViewById(R.id.button_google_label);
        googleProgress = findViewById(R.id.progress_google);
        statusText = findViewById(R.id.text_status);
        useOtherAccount = findViewById(R.id.button_use_other_account);
    }

    /**
     * [UI ASSET] Both images are optional and looked up by name, so nothing crashes if missing:
     *   res/drawable/ic_google_logo.(xml|png)              official Google "G" logo
     *   res/drawable-nodpi/login_illustration.(webp|jpg)   login illustration
     */
    private void setUpOptionalImages() {
        int logo = getResources().getIdentifier("ic_google_logo", "drawable", getPackageName());
        ImageView logoView = findViewById(R.id.google_logo);
        if (logo != 0) {
            logoView.setImageResource(logo);
            logoView.setVisibility(View.VISIBLE);
        } else {
            logoView.setVisibility(View.GONE);
            Log.w(TAG, "ic_google_logo missing: add the official Google logo (see GUIDE.md)");
        }
        int art = getResources().getIdentifier("login_illustration", "drawable", getPackageName());
        if (art != 0) {
            ((ImageView) findViewById(R.id.login_illustration)).setImageResource(art);
        }
    }

    // ---------------------------------------------------------------- sign-in flow

    private void onGoogleButtonClicked() {
        if (state == State.SIGNING_IN || state == State.LOADING_PROFILE
                || state == State.SIGNING_OUT) return; // no double taps

        // Already signed in to Firebase (profile step failed, or sign-in finished while the screen
        // was being recreated): never start a second Google sign-in, just retry the profile step.
        FirebaseUser existing = GoogleAuthClient.currentUser();
        if (existing != null) {
            loadProfileAndRoute(existing);
            return;
        }

        render(State.SIGNING_IN, null);
        authClient.signIn(this, new GoogleAuthClient.SignInCallback() {
            @Override
            public void onSignedIn(FirebaseUser user, boolean isNewFirebaseAccount) {
                if (!isAlive()) return;
                Log.i(TAG, "Signed in. New Firebase account: " + isNewFirebaseAccount);
                loadProfileAndRoute(user);
            }

            @Override
            public void onCancelled() {
                if (!isAlive()) return;
                render(State.IDLE, null); // stay here quietly, no success or error message
            }

            @Override
            public void onFailed(GoogleAuthClient.Failure failure, Exception error) {
                if (!isAlive()) return;
                render(State.SIGN_IN_ERROR, getString(messageFor(failure)));
            }
        });
    }

    private void loadProfileAndRoute(FirebaseUser user) {
        render(State.LOADING_PROFILE, null);
        profiles.ensureProfile(user, new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(UserProfile.Status status) {
                if (!isAlive()) return;
                if (status == UserProfile.Status.ONBOARDING_COMPLETE) {
                    AppNavigator.openSignedInHome(GoogleLoginActivity.this);   // Case B: returning
                } else {
                    AppNavigator.openChooseRoleAfterSignIn(GoogleLoginActivity.this); // Case A
                }
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure failure, Exception error) {
                if (!isAlive()) return;
                // Signed in, but we can't tell if onboarding is done. Don't guess: offer a retry.
                render(State.PROFILE_ERROR, getString(messageFor(failure)));
            }
        });
    }

    private void signOutAndReset() {
        render(State.SIGNING_OUT, null);
        authClient.signOut(() -> {
            if (isAlive()) render(State.IDLE, null);
        });
    }

    // ---------------------------------------------------------------- UI state

    private void render(State newState, String message) {
        state = newState;
        boolean busy = newState == State.SIGNING_IN || newState == State.LOADING_PROFILE
                || newState == State.SIGNING_OUT;

        googleButton.setEnabled(!busy);
        googleButton.setAlpha(busy ? 0.7f : 1f);
        googleProgress.setVisibility(busy ? View.VISIBLE : View.GONE);

        int label;
        switch (newState) {
            case SIGNING_IN:      label = R.string.login_signing_in; break;
            case LOADING_PROFILE: label = R.string.login_preparing_profile; break;
            case SIGNING_OUT:     label = R.string.login_signing_out; break;
            case SIGN_IN_ERROR:
            case PROFILE_ERROR:   label = R.string.login_try_again; break;
            default:              label = R.string.login_google_button; break;
        }
        googleButtonLabel.setText(label);
        googleButton.setContentDescription(getString(label));

        statusText.setText(message);
        statusText.setVisibility(message == null ? View.GONE : View.VISIBLE);
        if (message != null) statusText.announceForAccessibility(message);

        // Only offered when signed in but the profile step failed, so nobody gets stuck.
        useOtherAccount.setVisibility(newState == State.PROFILE_ERROR ? View.VISIBLE : View.GONE);
    }

    private static int messageFor(GoogleAuthClient.Failure failure) {
        switch (failure) {
            case MISSING_CONFIGURATION:   return R.string.login_error_not_configured;
            case DEVELOPER_CONFIGURATION: return R.string.login_error_developer;
            case NO_GOOGLE_ACCOUNT:       return R.string.login_error_no_account;
            case PLAY_SERVICES:           return R.string.login_error_play_services;
            case NETWORK:                 return R.string.login_error_network;
            case INTERRUPTED:
            case INVALID_RESPONSE:
            case UNKNOWN:
            default:                      return R.string.login_error_generic;
        }
    }

    private static int messageFor(UserProfileRepository.Failure failure) {
        switch (failure) {
            case NETWORK:           return R.string.login_profile_error_network;
            case PERMISSION_DENIED: return R.string.login_profile_error_permission;
            case DATABASE_MISSING:  return R.string.login_profile_error_database;
            case UNKNOWN:
            default:                return R.string.login_profile_error_generic;
        }
    }

    /** Callbacks can arrive after the screen closed; never touch a dead Activity. */
    private boolean isAlive() {
        return !isFinishing() && !isDestroyed();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        if (!entrancePlayed) {
            animator.play(findViewById(R.id.top_bar), findViewById(R.id.text_title),
                    findViewById(R.id.text_description), findViewById(R.id.login_illustration_card),
                    findViewById(R.id.bottom_actions));
            entrancePlayed = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Firebase may have finished signing in while this screen was being recreated.
        if (state == State.IDLE) {
            FirebaseUser user = GoogleAuthClient.currentUser();
            if (user != null) loadProfileAndRoute(user);
        }
    }

    @Override
    protected void onDestroy() {
        authClient.cancel();
        animator.cancel();
        super.onDestroy();
    }
}