package com.asanni.app.ui;

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
 * Location: app/src/main/java/com/asanni/app/ui/EntranceAnimator.java  (NEW)
 */
final class EntranceAnimator {

    // ---------------- [ANIMATION SETTINGS] milliseconds / dp ----------------
    private static final long DURATION_MS = 480L;      // each element's fade/slide time
    private static final long START_DELAY_MS = 60L;    // wait before the first element
    private static final long STAGGER_MS = 80L;        // extra delay per following element
    private static final float SLIDE_DP = 18f;         // upward travel distance
    private static final float PRESSED_SCALE = 0.98f;  // button size while pressed
    // ------------------------------------------------------------------------

    private final boolean reducedMotion;
    private final List<View> running = new ArrayList<>();

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

    /** Stops animations and puts every view in its final state (safe to call repeatedly). */
    void cancel() {
        for (View v : running) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setTranslationY(0f);
        }
        running.clear();
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