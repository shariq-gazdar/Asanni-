package com.asanni.app.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import com.asanni.app.appearance.Appearance;
import com.asanni.app.appearance.AppearanceManager;
import com.asanni.app.network.ConnectivityMonitor;

/**
 * Base class for every ASANNI screen: applies the user's Light / Dark / System appearance.
 *
 * Location: app/src/main/java/com/asanni/app/ui/AsanniActivity.java
 *           (MODIFIED in phase 5: starts the connectivity monitor; Back closes the offline page)
 *
 * If the appearance changes while this screen is in the back stack (e.g. changed in Settings),
 * the screen rebuilds itself the next time it comes to the front, so every page stays consistent.
 */
public abstract class AsanniActivity extends Activity {

    private Appearance appliedAppearance;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppearanceManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedAppearance = AppearanceManager.current(this);
        super.onCreate(savedInstanceState);
        ConnectivityMonitor.get(this); // phase 5: one app-wide network monitor (no polling)
    }

    /** Phase 5: Back closes the offline page first (the screen below stays as it was). */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (OfflineOverlay.isShowing(this)) {
            OfflineOverlay.hide(this);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedAppearance != AppearanceManager.current(this)) {
            recreate(); // the appearance changed on another screen
        }
    }

    /** Call after changing the appearance on THIS screen to apply it immediately. */
    protected void applyAppearanceNow() {
        if (appliedAppearance != AppearanceManager.current(this)) recreate();
    }
}