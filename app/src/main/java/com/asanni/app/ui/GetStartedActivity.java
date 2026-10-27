package com.asanni.app.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.TextView;

import com.asanni.app.R;


public class GetStartedActivity extends Activity {

    private static final String STATE_ENTRANCE_PLAYED = "entrance_played";

    private GetStartedAnimator animator;
    private boolean entrancePlayed;

    private View logoBlock;
    private View eyebrow;
    private View title;
    private View description;
    private View heroCard;
    private View button;
    private View liveDot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (GetStartedNavigator.SKIP_IF_ALREADY_SEEN && GetStartedNavigator.hasSeenWelcome(this)) {
            GetStartedNavigator.openNextScreen(this);
            return;
        }

        setContentView(R.layout.activity_get_started);
        entrancePlayed = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ENTRANCE_PLAYED, false);

        bindViews();
        setUpSystemBars();
        setUpLogo();
        setUpButton();

        animator = new GetStartedAnimator(getContentResolver());
    }

    private void bindViews() {
        logoBlock = findViewById(R.id.logo_block);
        eyebrow = findViewById(R.id.text_eyebrow);
        title = findViewById(R.id.text_title);
        description = findViewById(R.id.text_description);
        heroCard = findViewById(R.id.hero_card);
        button = findViewById(R.id.button_get_started);
        liveDot = findViewById(R.id.chip_dot);
    }

    @SuppressWarnings("deprecation")
    private void setUpSystemBars() {
        final View root = findViewById(R.id.get_started_root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        // The background is light, so ask for dark status/navigation bar icons.
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            root.setSystemUiVisibility(root.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void setUpLogo() {
        int logoRes = getResources().getIdentifier("asanni_logo", "drawable", getPackageName());
        if (logoRes != 0) {
            ImageView logo = findViewById(R.id.logo_image);
            logo.setImageResource(logoRes);
            logo.setVisibility(View.VISIBLE);
            findViewById(R.id.logo_wordmark).setVisibility(View.GONE);
        }
    }

    private void setUpButton() {
        // [NAVIGATION] "Get started" -> GetStartedNavigator.NEXT_SCREEN (placeholder: MainActivity)
        button.setOnClickListener(v -> {
            button.setEnabled(false); // stop accidental double-taps opening two screens
            GetStartedNavigator.openNextScreen(this);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator == null) return; // activity was skipped in onCreate

        if (!entrancePlayed) {
            animator.playEntrance(logoBlock, eyebrow, title, description, heroCard, button);
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