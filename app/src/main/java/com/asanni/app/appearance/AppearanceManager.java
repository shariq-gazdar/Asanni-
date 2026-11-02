package com.asanni.app.appearance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Applies Light / Dark / System to every ASANNI screen.
 *
 * Location: app/src/main/java/com/asanni/app/appearance/AppearanceManager.java  (NEW)
 *
 * How it works (no AppCompat needed):
 *   - Every screen extends AsanniActivity, which wraps its Context with a Configuration whose
 *     "night" flag is forced to light or dark (or left alone for System).
 *   - Android then picks colors from res/values (light) or res/values-night (dark) automatically.
 *
 * Source of truth is the user's Firestore profile (users/{uid}.appearance). A copy is kept on
 * the device so the right theme is used instantly at app start, before Firestore answers.
 */
public final class AppearanceManager {

    private static final String PREFS = "asanni_appearance";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PENDING_SYNC = "pending_sync";

    private AppearanceManager() {}

    public static Appearance current(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return Appearance.fromId(p.getString(KEY_MODE, Appearance.DEFAULT.id));
    }

    /** Saves the device copy. Returns true if it changed (screens then need recreate()). */
    public static boolean setLocal(Context context, Appearance appearance) {
        Appearance old = current(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, appearance.id).apply();
        return old != appearance;
    }

    /**
     * True while a choice made on this phone hasn't reached the Firestore profile yet (offline).
     * While set, the phone's choice wins and Settings retries saving it, instead of the older
     * profile value silently undoing what the user picked.
     */
    public static boolean isPendingSync(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING_SYNC, false);
    }

    public static void setPendingSync(Context context, boolean pending) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PENDING_SYNC, pending).apply();
    }

    /** Wraps a base Context so its resources use the chosen light/dark mode. */
    public static Context wrap(Context base) {
        Appearance a = current(base);
        if (a == Appearance.SYSTEM) return base;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int night = a == Appearance.DARK
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(config);
    }

    /** True when the given (already wrapped) context is showing the dark theme. */
    public static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}