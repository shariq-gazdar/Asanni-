package com.asanni.app.ui;

import android.app.Activity;
import android.os.Build;
import android.view.View;

import com.asanni.app.R;
import com.asanni.app.appearance.Appearance;

/**
 * Wires the shared System / Light / Dark pills (layout/view_appearance_selector.xml).
 * Used by Registration and Settings so both look and behave the same.
 *
 * Location: app/src/main/java/com/asanni/app/ui/AppearanceSelector.java  (NEW in phase 4)
 */
final class AppearanceSelector {

    interface Listener {
        void onAppearanceChosen(Appearance appearance);
    }

    private final Activity activity;
    private final View system;
    private final View light;
    private final View dark;
    private Appearance selected = Appearance.DEFAULT;

    AppearanceSelector(Activity activity, Listener listener) {
        this.activity = activity;
        system = activity.findViewById(R.id.segment_system);
        light = activity.findViewById(R.id.segment_light);
        dark = activity.findViewById(R.id.segment_dark);
        system.setOnClickListener(v -> choose(Appearance.SYSTEM, listener));
        light.setOnClickListener(v -> choose(Appearance.LIGHT, listener));
        dark.setOnClickListener(v -> choose(Appearance.DARK, listener));
        render();
    }

    private void choose(Appearance a, Listener listener) {
        if (a == selected) return;
        selected = a;
        render();
        listener.onAppearanceChosen(a);
    }

    void setSelected(Appearance a) {
        selected = a;
        render();
    }

    Appearance getSelected() {
        return selected;
    }

    void setEnabled(boolean enabled) {
        system.setEnabled(enabled);
        light.setEnabled(enabled);
        dark.setEnabled(enabled);
    }

    private void render() {
        bind(system, Appearance.SYSTEM, R.string.appearance_system);
        bind(light, Appearance.LIGHT, R.string.appearance_light);
        bind(dark, Appearance.DARK, R.string.appearance_dark);
    }

    private void bind(View pill, Appearance a, int label) {
        boolean on = selected == a;
        pill.setSelected(on); // drives bg_segment + color/segment_content
        String state = activity.getString(on ? R.string.role_selected_label : R.string.state_not_selected);
        if (Build.VERSION.SDK_INT >= 30) {
            pill.setContentDescription(activity.getString(label));
            pill.setStateDescription(state);
        } else {
            pill.setContentDescription(activity.getString(label) + ", " + state);
        }
    }
}