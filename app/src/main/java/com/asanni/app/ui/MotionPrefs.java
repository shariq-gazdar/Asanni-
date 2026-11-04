package com.asanni.app.ui;

import android.content.Context;
import android.provider.Settings;

/**
 * PHASE 9: one place to respect the phone's "Remove animations" setting (animator scale 0).
 * Location: app/src/main/java/com/asanni/app/ui/MotionPrefs.java  (NEW in phase 9)
 */
final class MotionPrefs {

    private MotionPrefs() {}

    static boolean enabled(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }
}