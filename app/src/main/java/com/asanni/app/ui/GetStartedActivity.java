package com.asanni.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;

/**
 * ASANNI Get Started / Welcome screen.  (MODIFIED in phase 2)
 *
 * Location: app/src/main/java/com/asanni/app/ui/GetStartedActivity.java
 *
 * Phase 2 changes:
 *   - "Get started" -> Choose Your Role; new "Log in" link -> Google Login
 *   - If a Firebase session already exists, the user is routed on instead of seeing this screen
 *   - System bar code moved to the shared SystemBarsHelper
 * Visual design is unchanged apart from the small Login link under the button.
 */
public class GetStartedActivity extends Activity {

    /** Remembers across rotation that the entrance already played, so it never replays/stacks. */
    private static final String STATE_ENTRANCE_PLAYED = "entrance_played";

    private GetStartedAnimator animator;
    private boolean entrancePlayed;

    private View logoBlock;
    private View eyebrow;
    private View title;
    private View description;
    private View heroCard;
    private View button;
    private View bottomActions;
    private View liveDot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // [NAVIGATION] Already signed in -> don't show Get Started again; continue the session.
        if (GoogleAuthClient.currentUser() != null) {
            GetStartedNavigator.resumeSignedInSession(this);
            return;
        }

        setContentView(R.layout.activity_get_started);
        entrancePlayed = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ENTRANCE_PLAYED, false);

        bindViews();
        SystemBarsHelper.apply(this, findViewById(R.id.get_started_root));
        setUpLogo();
        setUpActions();

        animator = new GetStartedAnimator(getContentResolver());
    }

    private void bindViews() {
        logoBlock = findViewById(R.id.logo_block);
        eyebrow = findViewById(R.id.text_eyebrow);
        title = findViewById(R.id.text_title);
        description = findViewById(R.id.text_description);
        heroCard = findViewById(R.id.hero_card);
        button = findViewById(R.id.button_get_started);
        bottomActions = findViewById(R.id.bottom_actions);
        liveDot = findViewById(R.id.chip_dot);
    }

    /**
     * [UI ASSET] Optional logo image.
     * If you add res/drawable/asanni_logo.(png|webp|xml) it replaces the text wordmark.
     * Looked up by name at runtime, so a missing file simply keeps the text (no crash).
     */
    private void setUpLogo() {
        int logoRes = getResources().getIdentifier("asanni_logo", "drawable", getPackageName());
        if (logoRes != 0) {
            ImageView logo = findViewById(R.id.logo_image);
            logo.setImageResource(logoRes);
            logo.setVisibility(View.VISIBLE);
            findViewById(R.id.logo_wordmark).setVisibility(View.GONE);
        }
    }

    private void setUpActions() {
        // [NAVIGATION] "Get started" -> Choose Your Role
        button.setOnClickListener(v -> GetStartedNavigator.onGetStartedClicked(this));
        // [NAVIGATION] "Log in" -> Google Login
        findViewById(R.id.button_login).setOnClickListener(v -> GetStartedNavigator.onLoginClicked(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator == null) return; // activity was redirected in onCreate

        if (!entrancePlayed) {
            animator.playEntrance(logoBlock, eyebrow, title, description, heroCard, bottomActions);
            entrancePlayed = true;
        }
        animator.attachPressFeedback(button);
        animator.startDotPulse(liveDot);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (animator == null) return;
        animator.stopDotPulse(liveDot); // no background work while the screen isn't visible
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_ENTRANCE_PLAYED, entrancePlayed);
    }

    @Override
    protected void onDestroy() {
        if (animator != null) {
            animator.cancelEntrance();
            animator.stopDotPulse(liveDot);
        }
        super.onDestroy();
    }
}