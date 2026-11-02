package com.asanni.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.ContentResolver;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable staggered fade + slide-up entrance and button press feedback.
 * Used by the Choose Your Role and Login screens.
 *
 * Location: app/src/main/java/com/asanni/app/ui/EntranceAnimator.java
 *           (MODIFIED in phase 3: map reveal + pin halo pulse for Allow Location)
 */
final class EntranceAnimator {

    // ---------------- [ANIMATION SETTINGS] milliseconds / dp ----------------
    private static final long DURATION_MS = 480L;      // each element's fade/slide time
    private static final long START_DELAY_MS = 60L;    // wait before the first element
    private static final long STAGGER_MS = 80L;        // extra delay per following element
    private static final float SLIDE_DP = 18f;         // upward travel distance
    private static final float PRESSED_SCALE = 0.98f;  // button size while pressed
    private static final float MAP_START_SCALE = 1.06f; // map settles from slightly zoomed in
    private static final long MAP_DURATION_MS = 900L;
    private static final long PULSE_DURATION_MS = 1400L; // one halo pulse
    private static final int PULSE_COUNT = 3;           // then it stops (no endless motion)
    private static final long AVATAR_POP_MS = 260L;     // new profile picture appears
    // ------------------------------------------------------------------------

    private final boolean reducedMotion;
    private final List<View> running = new ArrayList<>();
    private ObjectAnimator pulse;

    EntranceAnimator(ContentResolver resolver) {
        // "Remove animations" sets the animator scale to 0.
        float scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        reducedMotion = scale == 0f;
    }

    /** Plays the entrance for the views, in the order given. Never blocks touches. */
    void play(View... ordered) {
        cancel();
        if (reducedMotion || ordered.length == 0) return; // views simply stay visible
        float slidePx = SLIDE_DP * ordered[0].getResources().getDisplayMetrics().density;
        for (int i = 0; i < ordered.length; i++) {
            View v = ordered[i];
            v.setAlpha(0f);
            v.setTranslationY(slidePx);
            running.add(v);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(DURATION_MS)
                    .setStartDelay(START_DELAY_MS + i * STAGGER_MS)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    /** Small "pop" when a new profile picture appears (phase 4). Skipped with reduced motion. */
    static void popIn(View view) {
        float scale = Settings.Global.getFloat(view.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale == 0f) return;
        view.animate().cancel();
        view.setScaleX(0.85f);
        view.setScaleY(0.85f);
        view.setAlpha(0.4f);
        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(AVATAR_POP_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    /** Map illustration: fades in while settling from a slight zoom. */
    void revealMap(View map) {
        if (reducedMotion) return;
        map.setAlpha(0f);
        map.setScaleX(MAP_START_SCALE);
        map.setScaleY(MAP_START_SCALE);
        running.add(map);
        map.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(MAP_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    /** Pin halo grows and fades a few times, then rests. Call stopPulse() in onStop. */
    void pulse(View halo) {
        if (reducedMotion || pulse != null) return;
        PropertyValuesHolder sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1.15f);
        PropertyValuesHolder sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1.15f);
        PropertyValuesHolder a = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f);
        pulse = ObjectAnimator.ofPropertyValuesHolder(halo, sx, sy, a);
        pulse.setDuration(PULSE_DURATION_MS);
        pulse.setRepeatCount(PULSE_COUNT - 1);
        pulse.setInterpolator(new DecelerateInterpolator());
        pulse.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                halo.setScaleX(1f);
                halo.setScaleY(1f);
                halo.setAlpha(1f); // rest as a still halo, like the reference
            }
        });
        pulse.start();
    }

    void stopPulse() {
        if (pulse != null) {
            pulse.cancel(); // onAnimationEnd restores the resting halo
            pulse = null;
        }
    }

    /** Stops animations and puts every view in its final state (safe to call repeatedly). */
    void cancel() {
        for (View v : running) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        running.clear();
        stopPulse();
    }

    /** Slight shrink while a view is held down. Returns false so clicks and ripples still work. */
    void attachPressFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            if (reducedMotion) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).setDuration(100L).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}