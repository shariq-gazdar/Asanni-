package com.asanni.app.ui;

import android.animation.ObjectAnimator;
import android.content.ContentResolver;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

final class GetStartedAnimator {
    private static final long ENTRANCE_DURATION_MS = 520L;
    private static final long ENTRANCE_START_DELAY_MS = 80L;
    private static final long STAGGER_MS = 90L;
    private static final float SLIDE_DISTANCE_DP = 20f;
    private static final float HERO_START_SCALE = 0.96f;
    private static final float BUTTON_PRESSED_SCALE = 0.97f;
    private static final float BUTTON_ELEVATION_DP = 4f;
    private static final float BUTTON_PRESSED_ELEVATION_DP = 1f;
    private static final long DOT_PULSE_MS = 1100L;
    private static final float DOT_MIN_ALPHA = 0.35f;
    // -----------------------------------------------------------------------------------------

    private final List<View> animatedViews = new ArrayList<>();
    private ObjectAnimator dotPulse;
    private final boolean reducedMotion;

    GetStartedAnimator(ContentResolver resolver) {

        float scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        this.reducedMotion = scale == 0f;
    }

    boolean isReducedMotion() {
        return reducedMotion;
    }

    void playEntrance(View logo, View eyebrow, View title, View description, View hero, View button) {
        cancelEntrance();
        if (reducedMotion) {
            showInstantly(logo, eyebrow, title, description, hero, button);
            return;
        }
        float slidePx = SLIDE_DISTANCE_DP * logo.getResources().getDisplayMetrics().density;
        View[] ordered = {logo, eyebrow, title, description, hero, button};

        for (int i = 0; i < ordered.length; i++) {
            View v = ordered[i];
            boolean isHero = v == hero;
            v.setAlpha(0f);
            v.setTranslationY(slidePx);
            if (isHero) {
                v.setScaleX(HERO_START_SCALE);
                v.setScaleY(HERO_START_SCALE);
            }
            animatedViews.add(v);

            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(ENTRANCE_DURATION_MS)
                    .setStartDelay(ENTRANCE_START_DELAY_MS + i * STAGGER_MS)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    void showInstantly(View... views) {
        cancelEntrance();
        for (View v : views) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
    }

    void cancelEntrance() {
        for (View v : animatedViews) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        animatedViews.clear();
    }

    void startDotPulse(View dot) {
        if (reducedMotion || dotPulse != null) return;
        dotPulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, DOT_MIN_ALPHA);
        dotPulse.setDuration(DOT_PULSE_MS);
        dotPulse.setRepeatMode(ObjectAnimator.REVERSE);
        dotPulse.setRepeatCount(ObjectAnimator.INFINITE);
        dotPulse.setInterpolator(new AccelerateDecelerateInterpolator());
        dotPulse.start();
    }

    void stopDotPulse(View dot) {
        if (dotPulse != null) {
            dotPulse.cancel();
            dotPulse = null;
        }
        if (dot != null) dot.setAlpha(1f);
    }

    void attachPressFeedback(final View button) {
        final float density = button.getResources().getDisplayMetrics().density;
        button.setOnTouchListener((v, event) -> {
            if (reducedMotion) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(BUTTON_PRESSED_SCALE).scaleY(BUTTON_PRESSED_SCALE)
                            .translationZ(0f)
                            .setDuration(110L).start();
                    v.setElevation(BUTTON_PRESSED_ELEVATION_DP * density);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160L).start();
                    v.setElevation(BUTTON_ELEVATION_DP * density);
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}