package com.asanni.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.UserProfileRepository;
import com.google.firebase.auth.FirebaseUser;

/**
 * Choose page: the user continues as a diner (the only role in the Android app).
 *
 * Location: app/src/main/java/com/asanni/app/ui/ChooseRoleActivity.java  (MODIFIED in phase 3)
 *
 * Phase 3 changes: restaurant manager option and its navigation removed; "Continue as diner"
 * now really saves role = "diner" and moves on to Allow Location.
 *
 * Reached from:
 *   - Get Started (maybe signed out)  -> Continue opens Google Login first
 *   - Google Login (signed in, no role yet) -> Continue saves the role
 */
public class ChooseRoleActivity extends AsanniActivity {

    private EntranceAnimator animator;
    private UserProfileRepository profiles;
    private boolean entrancePlayed;
    private boolean saving;

    private View cardDiner;
    private TextView continueButton;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_choose_role);
        entrancePlayed = savedInstanceState != null; // don't replay the entrance after rotation

        SystemBarsHelper.apply(this, findViewById(R.id.choose_role_root));
        animator = new EntranceAnimator(getContentResolver());
        profiles = new UserProfileRepository();

        cardDiner = findViewById(R.id.card_diner);
        continueButton = findViewById(R.id.button_continue);
        errorText = findViewById(R.id.text_error);

        // The diner card is always shown in its "selected" style (drives the selector drawables).
        cardDiner.setSelected(true);
        findViewById(R.id.icon_diner).setSelected(true);

        findViewById(R.id.button_close).setOnClickListener(v -> finish());
        continueButton.setOnClickListener(v -> onContinueAsDiner());
        animator.attachPressFeedback(continueButton);
    }

    /** [NAVIGATION] "Continue as diner". */
    private void onContinueAsDiner() {
        if (saving) return; // no duplicate saves

        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            // Not signed in yet: sign in first; Google Login saves the diner role afterwards.
            AppNavigator.openGoogleLoginThenContinueAsDiner(this);
            return;
        }

        if (OfflineOverlay.showIfOffline(this, this::onContinueAsDiner, false)) return; // phase 5
        setSaving(true);
        profiles.saveDinerRole(user.getUid(), new UserProfileRepository.SaveCallback() {
            @Override
            public void onSaved() {
                if (isFinishing() || isDestroyed()) return;
                setSaving(false);
                AppNavigator.openAllowLocation(ChooseRoleActivity.this);
            }

            @Override
            public void onSaveFailed(UserProfileRepository.Failure failure, Exception error) {
                if (isFinishing() || isDestroyed()) return;
                setSaving(false);
                if (failure == UserProfileRepository.Failure.NETWORK) {
                    OfflineOverlay.show(ChooseRoleActivity.this, () -> onContinueAsDiner(), false);
                }
                errorText.setText(failure == UserProfileRepository.Failure.NETWORK
                        ? R.string.choose_role_error_network : R.string.choose_role_error_generic);
                errorText.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setSaving(boolean value) {
        saving = value;
        continueButton.setEnabled(!value);
        continueButton.setAlpha(value ? 0.7f : 1f);
        continueButton.setText(value ? R.string.choose_role_saving : R.string.continue_as_diner);
        if (value) errorText.setVisibility(View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!entrancePlayed) {
            animator.play(findViewById(R.id.top_bar), findViewById(R.id.text_title),
                    findViewById(R.id.text_subtitle), cardDiner, continueButton);
            entrancePlayed = true;
        }
    }

    @Override
    protected void onDestroy() {
        animator.cancel();
        super.onDestroy();
    }
}