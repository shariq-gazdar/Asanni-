package com.asanni.app.ui;

import android.app.Activity;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.network.ConnectivityMonitor;

/**
 * The offline page, shown ON TOP of the current screen (layout/view_offline.xml).
 *
 * Location: app/src/main/java/com/asanni/app/ui/OfflineOverlay.java  (NEW in phase 5)
 *
 * Why an overlay and not a new screen: the user stays exactly where they were, the pending
 * action can be retried in place, and repeated failures can never stack several offline pages
 * (there is at most one per screen; showing it again only updates its retry action).
 *
 *   Try again -> checks the network now. Still offline: says so and stays. Online: closes and
 *                runs the retry action (an action the user just confirmed by tapping).
 *   Go back / Back key -> closes the page; the screen underneath is unchanged.
 *   Connection returns while showing:
 *     autoRetry = true  (safe reads, e.g. loading the profile or the city list) -> retries itself
 *     autoRetry = false (sends data, sign-in, SMS codes) -> only says "back online"; never resends
 *                        anything without the user's tap.
 */
final class OfflineOverlay {

    /** [ANIMATION] Entrance timing. */
    private static final long ENTER_MS = 260L;

    private OfflineOverlay() {}

    /**
     * Shows the offline page with the given retry action (may be null: Try again then only closes
     * the page once the connection is back).
     */
    static void show(Activity activity, Runnable retry, boolean autoRetry) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        View existing = content.findViewById(R.id.offline_root);
        if (existing != null) {
            existing.setTag(R.id.offline_retry, new Pending(retry, autoRetry)); // no stacking
            return;
        }

        final View page = LayoutInflater.from(activity).inflate(R.layout.view_offline, content, false);
        page.setTag(R.id.offline_retry, new Pending(retry, autoRetry));
        content.addView(page);
        // The screen below is hidden from screen readers while this is up.
        if (content.getChildCount() > 1) {
            content.getChildAt(0).setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        final TextView status = page.findViewById(R.id.offline_status);
        final TextView retryLabel = page.findViewById(R.id.offline_retry_label);
        final ConnectivityMonitor monitor = ConnectivityMonitor.get(activity);

        View retryButton = page.findViewById(R.id.offline_retry);
        new EntranceAnimator(activity.getContentResolver()).attachPressFeedback(retryButton);
        retryButton.setOnClickListener(v -> {
            retryLabel.setText(R.string.offline_checking);
            if (monitor.isOnline() || monitor.mayHaveInternet()) {
                runAndClose(activity, page);
            } else {
                retryLabel.setText(R.string.offline_retry);
                status.setText(R.string.offline_still_offline);
                status.setVisibility(View.VISIBLE);
                nudge(page.findViewById(R.id.offline_icon_circle));
            }
        });
        page.findViewById(R.id.offline_back).setOnClickListener(v -> hide(activity));

        // Recover automatically when the connection returns (listener removed with the page).
        final ConnectivityMonitor.Listener listener = isOnline -> {
            if (!isOnline || page.getParent() == null) return;
            Pending p = (Pending) page.getTag(R.id.offline_retry);
            if (p != null && p.autoRetry) {
                runAndClose(activity, page);
            } else {
                status.setText(R.string.offline_back_online);
                status.setVisibility(View.VISIBLE);
            }
        };
        monitor.addListener(listener);
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { monitor.removeListener(listener); }
        });

        animateIn(page);
        page.findViewById(R.id.offline_title).sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    /** Shows the page only if the device is offline right now. Returns true if it was shown. */
    static boolean showIfOffline(Activity activity, Runnable retry, boolean autoRetry) {
        if (ConnectivityMonitor.get(activity).mayHaveInternet()) return false;
        show(activity, retry, autoRetry);
        return true;
    }

    static boolean isShowing(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        return content != null && content.findViewById(R.id.offline_root) != null;
    }

    static void hide(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        View page = content.findViewById(R.id.offline_root);
        if (page == null) return;
        content.removeView(page);
        if (content.getChildCount() > 0) {
            content.getChildAt(0).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
    }

    private static void runAndClose(Activity activity, View page) {
        Pending p = (Pending) page.getTag(R.id.offline_retry);
        hide(activity);
        if (p != null && p.retry != null && !activity.isFinishing() && !activity.isDestroyed()) {
            p.retry.run();
        }
    }

    private static boolean motion(View v) {
        return Settings.Global.getFloat(v.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private static void animateIn(View page) {
        if (!motion(page)) return;
        View content = page.findViewById(R.id.offline_content);
        View actions = page.findViewById(R.id.offline_actions);
        float d = page.getResources().getDisplayMetrics().density;
        page.setAlpha(0f);
        page.animate().alpha(1f).setDuration(ENTER_MS).start();
        content.setTranslationY(16 * d);
        content.animate().translationY(0f).setDuration(ENTER_MS + 120)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        actions.setAlpha(0f);
        actions.animate().alpha(1f).setStartDelay(120).setDuration(ENTER_MS).start();
    }

    /** Small side-to-side shake when Retry finds no connection (skipped with reduced motion). */
    private static void nudge(View v) {
        if (!motion(v)) return;
        float d = v.getResources().getDisplayMetrics().density;
        v.animate().cancel();
        v.setTranslationX(0f);
        v.animate().translationX(8 * d).setDuration(60).withEndAction(() ->
                v.animate().translationX(-6 * d).setDuration(80).withEndAction(() ->
                        v.animate().translationX(0f).setDuration(80).start()).start()).start();
    }

    private static final class Pending {
        final Runnable retry;
        final boolean autoRetry;

        Pending(Runnable retry, boolean autoRetry) {
            this.retry = retry;
            this.autoRetry = autoRetry;
        }
    }
}