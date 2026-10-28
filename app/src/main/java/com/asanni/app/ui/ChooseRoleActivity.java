package com.asanni.app.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.asanni.app.R;

/**
 * "Choose Your Role" screen: pick Diner or Restaurant Manager, then continue.
 *
 * Location: app/src/main/java/com/asanni/app/ui/ChooseRoleActivity.java  (NEW)
 *
 * Reached from:
 *   - Get Started -> "Get started" (user may NOT be signed in; Back / X returns to Get Started)
 *   - Google Login -> first-time user or onboarding not finished (signed in; this screen is the
 *     root of the back stack, so X closes the app instead of returning to the login page)
 *
 * IMPORTANT: this phase is a PLACEHOLDER. Continuing only shows a message. The choice is NOT saved,
 * and onboardingCompleted is NOT set. See the two [INTEGRATION POINT] methods below.
 * Because of that, a signed-in user keeps landing here after each login until role saving exists.
 */
public class ChooseRoleActivity extends Activity {

    private static final String STATE_SELECTED_ROLE = "selected_role";

    private enum Role { DINER, MANAGER }

    private Role selectedRole = Role.DINER; // reference design opens with the diner card selected
    private EntranceAnimator animator;
    private boolean entrancePlayed;

    private View topBar;
    private View title;
    private View subtitle;
    private View cardDiner;
    private View cardManager;
    private View dinerChevron;
    private View dinerSelectedRow;
    private View managerChevron;
    private View managerSelectedRow;
    private TextView continueButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_choose_role);

        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(STATE_SELECTED_ROLE);
            if (saved != null) selectedRole = Role.valueOf(saved);
            entrancePlayed = true; // don't replay the entrance after rotation
        }

        bindViews();
        SystemBarsHelper.apply(this, findViewById(R.id.choose_role_root));
        animator = new EntranceAnimator(getContentResolver());

        findViewById(R.id.button_close).setOnClickListener(v -> finish());
        cardDiner.setOnClickListener(v -> select(Role.DINER));
        cardManager.setOnClickListener(v -> select(Role.MANAGER));
        continueButton.setOnClickListener(v -> onContinueClicked());
        animator.attachPressFeedback(continueButton);

        render();
    }

    private void bindViews() {
        topBar = findViewById(R.id.top_bar);
        title = findViewById(R.id.text_title);
        subtitle = findViewById(R.id.text_subtitle);
        cardDiner = findViewById(R.id.card_diner);
        cardManager = findViewById(R.id.card_manager);
        dinerChevron = findViewById(R.id.diner_chevron);
        dinerSelectedRow = findViewById(R.id.diner_selected_row);
        managerChevron = findViewById(R.id.manager_chevron);
        managerSelectedRow = findViewById(R.id.manager_selected_row);
        continueButton = findViewById(R.id.button_continue);
    }

    private void select(Role role) {
        if (role == selectedRole) return;
        selectedRole = role;
        render();
    }

    /** Applies the current selection to both cards and the bottom button. */
    private void render() {
        boolean diner = selectedRole == Role.DINER;

        cardDiner.setSelected(diner);
        cardManager.setSelected(!diner);
        // [UI] Elevation gives the selected card the soft shadow seen in the reference.
        cardDiner.setElevation(diner ? dp(4) : 0f);
        cardManager.setElevation(diner ? 0f : dp(4));

        setIconSelected(R.id.icon_diner, diner);
        setIconSelected(R.id.icon_manager, !diner);

        dinerSelectedRow.setVisibility(diner ? View.VISIBLE : View.GONE);
        dinerChevron.setVisibility(diner ? View.GONE : View.VISIBLE);
        managerSelectedRow.setVisibility(diner ? View.GONE : View.VISIBLE);
        managerChevron.setVisibility(diner ? View.VISIBLE : View.GONE);

        // [EDIT TEXT] button label comes from strings: continue_as_diner / continue_as_manager
        continueButton.setText(diner ? R.string.continue_as_diner : R.string.continue_as_manager);

        // Accessibility: TalkBack announces "Selected" / "Not selected" for each card (Android 11+).
        if (Build.VERSION.SDK_INT >= 30) {
            String on = getString(R.string.role_selected_label);
            String off = getString(R.string.role_not_selected_label);
            cardDiner.setStateDescription(diner ? on : off);
            cardManager.setStateDescription(diner ? off : on);
        }
    }

    private void setIconSelected(int viewId, boolean selected) {
        findViewById(viewId).setSelected(selected); // drives bg_role_icon_tile and role_icon_tint
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void onContinueClicked() {
        if (selectedRole == Role.DINER) {
            onContinueAsDiner();
        } else {
            onContinueAsManager();
        }
    }

    /**
     * [INTEGRATION POINT - DINER]
     * PLACEHOLDER: only shows a message. Later:
     *   1. If signed in, call new UserProfileRepository().completeOnboarding(uid, UserProfile.ROLE_DINER, ...)
     *      (already written, not called yet). If not signed in, send the user to GoogleLoginActivity first.
     *   2. Start your DinerHomeActivity (clear the back stack).
     */
    private void onContinueAsDiner() {
        Toast.makeText(this, R.string.role_placeholder_diner, Toast.LENGTH_LONG).show();
    }

    /**
     * [INTEGRATION POINT - RESTAURANT MANAGER]
     * PLACEHOLDER: only shows a message. Later:
     *   1. Same as above with UserProfile.ROLE_MANAGER. (Real manager verification should be done
     *      on a server later; a client-chosen role alone should not unlock restaurant data.)
     *   2. Start your ManagerHomeActivity (clear the back stack).
     */
    private void onContinueAsManager() {
        Toast.makeText(this, R.string.role_placeholder_manager, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!entrancePlayed) {
            animator.play(topBar, title, subtitle, cardDiner, cardManager, continueButton);
            entrancePlayed = true;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SELECTED_ROLE, selectedRole.name());
    }

    @Override
    protected void onDestroy() {
        animator.cancel();
        super.onDestroy();
    }
}