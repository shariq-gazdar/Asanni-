package com.asanni.app.profile;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Notification permission state, kept separate from registration.
 *
 * Location: app/src/main/java/com/asanni/app/profile/NotificationPrompt.java  (NEW in phase 4)
 *
 * The permission belongs to the DEVICE, so "already asked" is remembered on the device (per user).
 * The explanation screen is shown at most once per user per device; a denial isn't treated as
 * final, because the user can still turn notifications on in Android settings (Settings row).
 */
public final class NotificationPrompt {

    private static final String PREFS = "asanni_notifications";

    private NotificationPrompt() {}

    /** True when ASANNI may currently post notifications on this device. */
    public static boolean notificationsAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm == null || nm.areNotificationsEnabled();
    }

    /** Show the explanation screen after registration? (Once per user per device, if useful.) */
    public static boolean shouldShow(Context c, String uid) {
        if (notificationsAllowed(c)) return false;        // nothing to ask for
        return !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(uid), false);
    }

    /** Remember that this user has seen the explanation on this device (allowed or not). */
    public static void markShown(Context c, String uid) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key(uid), true).apply();
    }

    /** Android 13+ needs a runtime permission; older versions allow notifications by default. */
    public static boolean needsRuntimePermission() {
        return Build.VERSION.SDK_INT >= 33;
    }

    private static String key(String uid) {
        return "shown_" + uid;
    }
}