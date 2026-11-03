package com.asanni.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import com.asanni.app.R;

/**
 * The four-item bottom navigation of Home: Discover, Map, Saved, Profile (in that order).
 *
 * Location: app/src/main/java/com/asanni/app/ui/BottomNavBar.java  (NEW in phase 6)
 *
 * Selected item = pink pill behind the icon + accent color + medium-weight label, so the state
 * is visible without relying on color alone. Screen readers hear "Discover, tab 1 of 4, selected".
 * [ANIMATION] The pill fades/grows in 160 ms; skipped when animations are turned off.
 */
final class BottomNavBar {

    interface Listener {
        void onTabSelected(int index);
    }

    /** [UI ASSET] Tab icons: res/drawable/ic_nav_discover.xml, ic_nav_map.xml, ic_nav_saved.xml, ic_nav_profile.xml */
    private static final int[] ICONS = {R.drawable.ic_nav_discover, R.drawable.ic_nav_map,
            R.drawable.ic_nav_saved, R.drawable.ic_nav_profile};
    private static final int[] LABELS = {R.string.nav_discover, R.string.nav_map,
            R.string.nav_saved, R.string.nav_profile};
    private static final long ANIM_MS = 160L;

    private final View[] items;
    private final int activeColor;
    private final int inactiveColor;
    private final boolean animate;
    private int selected = -1;

    BottomNavBar(View[] items, final Listener listener) {
        this.items = items;
        Context c = items[0].getContext();
        activeColor = c.getColor(R.color.asanni_welcome_accent);
        inactiveColor = c.getColor(R.color.asanni_nav_inactive);
        animate = Settings.Global.getFloat(c.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            ((ImageView) items[i].findViewById(R.id.nav_icon)).setImageResource(ICONS[i]);
            ((TextView) items[i].findViewById(R.id.nav_label)).setText(LABELS[i]);
            items[i].setOnClickListener(v -> listener.onTabSelected(index));
            items[i].setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setSelected(index == selected);
                }
            });
        }
    }

    int selected() {
        return selected;
    }

    void select(int index, boolean withAnimation) {
        if (index == selected) return;
        selected = index;
        Context c = items[0].getContext();
        for (int i = 0; i < items.length; i++) {
            boolean on = i == index;
            View pill = items[i].findViewById(R.id.nav_indicator);
            ImageView icon = items[i].findViewById(R.id.nav_icon);
            TextView label = items[i].findViewById(R.id.nav_label);
            icon.setColorFilter(on ? activeColor : inactiveColor);
            label.setTextColor(on ? activeColor : inactiveColor);
            label.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            pill.animate().cancel();
            if (withAnimation && animate) {
                if (on) {
                    pill.setScaleX(0.6f);
                    pill.animate().alpha(1f).scaleX(1f).setDuration(ANIM_MS).start();
                } else {
                    pill.animate().alpha(0f).setDuration(ANIM_MS).start();
                }
            } else {
                pill.setAlpha(on ? 1f : 0f);
                pill.setScaleX(1f);
            }
            String name = c.getString(LABELS[i]);
            items[i].setContentDescription(i == 3 ? c.getString(R.string.nav_profile_description)
                    : on ? c.getString(R.string.nav_item_selected, name, i + 1)
                    : c.getString(R.string.nav_item_description, name, i + 1));
        }
    }
}