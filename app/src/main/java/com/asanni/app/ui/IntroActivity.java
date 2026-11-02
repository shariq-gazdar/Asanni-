package com.asanni.app.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import com.asanni.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * ASANNI intro: the launch animation from the reference video, then Get Started.
 *
 * Location: app/src/main/java/com/asanni/app/ui/IntroActivity.java  (NEW in phase 5)
 *
 * Launch -> (Android's own splash on 12+, plain red) -> THIS intro (2.6 s) -> Get Started.
 * Shown only when the app is opened from the launcher; it never appears between other screens,
 * and it finishes itself, so Back never returns to it. Get Started keeps its existing logic
 * (signed-in users are sent on to the login router, exactly as before).
 *
 * No fake loading: the duration is the video's. If the user leaves mid-animation, or the screen is
 * recreated, it goes straight to Get Started instead of replaying (never stuck, never twice).
 */
public class IntroActivity extends Activity {

    // ===================== [ANIMATION SETTINGS] (milliseconds / dp), measured from the video ====
    private static final long HG_IN_START = 460;     // big hourglass starts rising + fading in
    private static final long HG_IN_MOVE = 400;      // rise duration
    private static final long HG_IN_FADE = 450;      // fade-in duration
    private static final float HG_RISE_DP = 61f;     // starts 61 dp below centre
    private static final long HG_OUT_START = 1100;   // then leaves upwards
    private static final long HG_OUT_DURATION = 280;
    private static final float HG_EXIT_DP = 64f;     // rises 64 dp while fading out
    private static final long WORDMARK_START = 1600; // "ASANN" appears (fully visible by 1.65 s)
    private static final long WORDMARK_FADE = 50;
    private static final long WORDMARK_MOVE = 130;
    private static final float WORDMARK_RISE_DP = 6f;
    private static final long SMALL_HG_START = 1760; // hourglass "I" fades in
    private static final long SMALL_HG_FADE = 640;
    private static final long TAGLINE_START = 1810;  // tagline appears
    private static final long TAGLINE_FADE = 70;
    private static final long TOTAL_DURATION = 2600; // video length -> Get Started
    /** With "Remove animations" on: show the final logo briefly, then continue. */
    private static final long REDUCED_MOTION_HOLD = 700;
    // =============================================================================================

    private static final String STATE_SHOWN = "intro_shown";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable goNext = this::openGetStarted;
    private AnimatorSet animation;
    private boolean leftMidway;  // user left the app during the intro
    private boolean navigated;   // Get Started already opened (only once)

    private View bigHourglass;
    private View wordmark;
    private View smallHourglass;
    private View tagline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Opened again from the launcher while ASANNI is already running: don't replay the intro.
        if (!isTaskRoot()) {
            Intent i = getIntent();
            if (Intent.ACTION_MAIN.equals(i.getAction()) && i.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_intro);
        setUpSystemBars();
        bigHourglass = findViewById(R.id.intro_big_hourglass);
        wordmark = findViewById(R.id.intro_wordmark);
        smallHourglass = findViewById(R.id.intro_small_hourglass);
        tagline = findViewById(R.id.intro_tagline);

        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_SHOWN)) {
            showFinalFrame(); // recreated (e.g. after process death): no replay
            handler.post(goNext);
            return;
        }
        if (reducedMotion()) {
            showFinalFrame();
            handler.postDelayed(goNext, REDUCED_MOTION_HOLD);
        } else {
            play();
            handler.postDelayed(goNext, TOTAL_DURATION);
        }
    }

    private void play() {
        float d = getResources().getDisplayMetrics().density;
        List<Animator> parts = new ArrayList<>();

        // Stage 1a: big hourglass rises into the centre and fades in (ease-out)
        parts.add(anim(bigHourglass, View.TRANSLATION_Y, HG_RISE_DP * d, 0f,
                HG_IN_START, HG_IN_MOVE, new DecelerateInterpolator(0.75f)));
        parts.add(anim(bigHourglass, View.ALPHA, 0f, 1f,
                HG_IN_START, HG_IN_FADE, new DecelerateInterpolator(2f)));
        // Stage 1b: it keeps rising and fades out (ease-in)
        parts.add(anim(bigHourglass, View.TRANSLATION_Y, 0f, -HG_EXIT_DP * d,
                HG_OUT_START, HG_OUT_DURATION, new AccelerateInterpolator(1.1f)));
        parts.add(anim(bigHourglass, View.ALPHA, 1f, 0f,
                HG_OUT_START, HG_OUT_DURATION, new AccelerateInterpolator(1.5f)));
        // Stage 2: "ASANN" pops in with a tiny lift, the hourglass "I" fades in, then the tagline
        parts.add(anim(wordmark, View.ALPHA, 0f, 1f,
                WORDMARK_START, WORDMARK_FADE, new LinearInterpolator()));
        parts.add(anim(wordmark, View.TRANSLATION_Y, WORDMARK_RISE_DP * d, 0f,
                WORDMARK_START, WORDMARK_MOVE, new DecelerateInterpolator(1.5f)));
        parts.add(anim(smallHourglass, View.ALPHA, 0f, 1f,
                SMALL_HG_START, SMALL_HG_FADE, new DecelerateInterpolator(1f)));
        parts.add(anim(tagline, View.ALPHA, 0f, 1f,
                TAGLINE_START, TAGLINE_FADE, new LinearInterpolator()));

        animation = new AnimatorSet();
        animation.playTogether(parts);
        animation.start();
    }

    private static Animator anim(View v, android.util.Property<View, Float> property, float from,
                                 float to, long delay, long duration, TimeInterpolator interpolator) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, property, from, to);
        a.setStartDelay(delay);
        a.setDuration(duration);
        a.setInterpolator(interpolator);
        return a;
    }

    private void showFinalFrame() {
        bigHourglass.setAlpha(0f);
        wordmark.setAlpha(1f);
        wordmark.setTranslationY(0f);
        smallHourglass.setAlpha(1f);
        tagline.setAlpha(1f);
    }

    /** [NAVIGATION] Intro -> existing Get Started (which keeps its own signed-in routing). */
    private void openGetStarted() {
        if (navigated || isFinishing() || isDestroyed()) return;
        navigated = true;
        Intent intent = new Intent(this, GetStartedActivity.class);
        startActivity(intent, ActivityOptions.makeCustomAnimation(
                this, R.anim.intro_enter, R.anim.intro_exit).toBundle());
        finish(); // Back from Get Started must not show the intro again
    }

    private boolean reducedMotion() {
        return Settings.Global.getFloat(getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    /** Red edge-to-edge with WHITE status-bar icons (the intro is always on red). */
    @SuppressWarnings("deprecation")
    private void setUpSystemBars() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStop() {
        super.onStop();
        if (!navigated && !isFinishing()) {
            // Left during the intro: stop it. Coming back goes straight to Get Started.
            leftMidway = true;
            handler.removeCallbacks(goNext);
            if (animation != null) animation.cancel();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (leftMidway) {
            leftMidway = false;
            showFinalFrame();
            handler.post(goNext);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_SHOWN, true);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(goNext);
        if (animation != null) {
            animation.removeAllListeners();
            animation.cancel();
        }
        super.onDestroy();
    }
}