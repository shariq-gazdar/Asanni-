package com.asanni.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.asanni.app.MainActivity;
import com.asanni.app.R;

final class GetStartedNavigator {

    // ======================= [NAVIGATION] CHANGE THESE LATER =======================
    /**
     * [NAVIGATION] Where "Get started" goes.
     * PLACEHOLDER: your existing MainActivity (Kotlin) is used because no role-selection /
     * login screen exists yet. When you build one, replace MainActivity.class with it,
     * e.g. RoleSelectionActivity.class (Customer vs Restaurant Manager).
     */
    private static final Class<? extends Activity> NEXT_SCREEN = MainActivity.class;

    static final boolean SKIP_IF_ALREADY_SEEN = false;
    // ===============================================================================

    private static final String PREFS_NAME = "asanni_prefs";
    private static final String KEY_WELCOME_SEEN = "welcome_seen";

    private GetStartedNavigator() {}

    static boolean hasSeenWelcome(Context context) {
        return prefs(context).getBoolean(KEY_WELCOME_SEEN, false);
    }

    static void openNextScreen(Activity from) {
        prefs(from).edit().putBoolean(KEY_WELCOME_SEEN, true).apply();

        // [INTEGRATION POINT] Authentication / role selection (Customer or Restaurant Manager)
        // will go here later. Nothing is authenticated yet - this only changes screens.
        Intent intent = new Intent(from, NEXT_SCREEN);
        from.startActivity(intent);
        applyTransition(from);
        from.finish(); // so Back doesn't return to the welcome screen
    }

    @SuppressWarnings("deprecation")
    private static void applyTransition(Activity from) {
        if (Build.VERSION.SDK_INT >= 34) {
            from.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,
                    R.anim.screen_enter, R.anim.screen_exit);
        } else {
            from.overridePendingTransition(R.anim.screen_enter, R.anim.screen_exit);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}