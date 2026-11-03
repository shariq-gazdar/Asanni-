package com.asanni.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ScrollView;

import com.asanni.app.R;

/**
 * First-time tip on Discover that points at the location arrow:
 * "You can change your location here to discover restaurants in other areas."
 *
 * Location: app/src/main/java/com/asanni/app/ui/LocationCoachMark.java  (NEW in phase 6)
 *
 *  - Shown ONCE per account on this phone: it is remembered as seen the moment it appears, so it
 *    never returns on later launches (SharedPreferences "asanni_tips").
 *  - Not a full-screen overlay: the rest of Discover and the bottom navigation stay usable.
 *  - Closes with "Got it", by tapping the location control, or when another tab is opened.
 *  - Follows the arrow while the page scrolls; hides when the arrow scrolls out of view.
 */
final class LocationCoachMark {

    private static final String PREFS = "asanni_tips";
    private static final String KEY_PREFIX = "discover_location_tip_seen_";
    /** [ANIMATION] Tip fade/rise. */
    private static final long ANIM_MS = 220L;

    private final View root;      // discover_root (FrameLayout)
    private final View tip;       // coach_location
    private final View pointer;   // coach_pointer
    private final View arrow;     // discover_location_arrow
    private final ScrollView scroll;
    private boolean showing;

    LocationCoachMark(View discoverRoot) {
        root = discoverRoot;
        tip = discoverRoot.findViewById(R.id.coach_location);
        pointer = discoverRoot.findViewById(R.id.coach_pointer);
        arrow = discoverRoot.findViewById(R.id.discover_location_arrow);
        scroll = discoverRoot.findViewById(R.id.discover_scroll);
        tip.findViewById(R.id.coach_dismiss).setOnClickListener(v -> hide());
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (showing) position();
        });
    }

    static boolean wasSeen(Context c, String uid) {
        return prefs(c).getBoolean(KEY_PREFIX + uid, false);
    }

    /** Shows the tip if this account hasn't seen it on this phone. */
    void showIfFirstTime(final String uid) {
        if (showing || uid == null || wasSeen(root.getContext(), uid)) return;
        prefs(root.getContext()).edit().putBoolean(KEY_PREFIX + uid, true).apply();
        showing = true;
        tip.setVisibility(View.INVISIBLE);
        root.post(() -> {
            if (!showing) return;
            position();
            tip.setVisibility(View.VISIBLE);
            if (motion()) {
                tip.setAlpha(0f);
                tip.setTranslationY(tip.getTranslationY() + 8 * density());
                tip.animate().alpha(1f).translationY(tip.getTranslationY() - 8 * density())
                        .setDuration(ANIM_MS).start();
            }
            tip.findViewById(R.id.coach_title).sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        });
    }

    boolean isShowing() {
        return showing;
    }

    void hide() {
        if (!showing) return;
        showing = false;
        if (motion()) {
            tip.animate().alpha(0f).setDuration(ANIM_MS / 2).withEndAction(() -> tip.setVisibility(View.GONE)).start();
        } else {
            tip.setVisibility(View.GONE);
        }
    }

    /** Places the bubble just under the arrow, with the pointer centred on the arrow. */
    private void position() {
        if (arrow.getWidth() == 0 || root.getWidth() == 0) return;
        int[] rootPos = new int[2];
        int[] arrowPos = new int[2];
        root.getLocationInWindow(rootPos);
        arrow.getLocationInWindow(arrowPos);
        float d = density();
        float arrowCenterX = arrowPos[0] - rootPos[0] + arrow.getWidth() / 2f;
        float arrowBottom = arrowPos[1] - rootPos[1] + arrow.getHeight();

        int tipWidth = tip.getWidth() > 0 ? tip.getWidth() : (int) (280 * d);
        float margin = 16 * d;
        float x = Math.max(margin, Math.min(arrowCenterX - 40 * d, root.getWidth() - tipWidth - margin));
        tip.setTranslationX(x);
        tip.setTranslationY(arrowBottom + 2 * d);

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) pointer.getLayoutParams();
        int start = (int) (arrowCenterX - x - pointer.getLayoutParams().width / 2f);
        start = Math.max((int) (12 * d), Math.min(start, tipWidth - (int) (32 * d)));
        if (lp.getMarginStart() != start) {
            lp.setMarginStart(start);
            pointer.setLayoutParams(lp);
        }
        // Arrow scrolled away (under the top edge): hide the bubble until it's back.
        tip.setVisibility(arrowBottom < 0 ? View.INVISIBLE : View.VISIBLE);
    }

    private float density() {
        return root.getResources().getDisplayMetrics().density;
    }

    private boolean motion() {
        return Settings.Global.getFloat(root.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}