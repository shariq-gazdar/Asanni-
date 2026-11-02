package com.asanni.app.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Opens Android's own settings pages for ASANNI.
 *
 * Location: app/src/main/java/com/asanni/app/ui/SystemSettingsLinks.java  (NEW in phase 4)
 */
final class SystemSettingsLinks {

    private SystemSettingsLinks() {}

    /** ASANNI's notification settings (Android 8+), or its App info page. False if neither opens. */
    static boolean openNotificationSettings(Activity a) {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            if (tryStart(a, i)) return true;
        }
        return openAppDetails(a);
    }

    static boolean openAppDetails(Activity a) {
        return tryStart(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.getPackageName(), null)));
    }

    private static boolean tryStart(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}