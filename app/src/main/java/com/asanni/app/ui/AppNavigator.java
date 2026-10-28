package com.asanni.app.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

import com.asanni.app.R;

/**
 * Every screen-to-screen move in one place, so destinations are easy to change later.
 *
 * Location: app/src/main/java/com/asanni/app/ui/AppNavigator.java  (NEW)
 *
 *   Get Started --"Get started"--> Choose Your Role        (Get Started stays underneath)
 *   Get Started --"Log in"-------> Google Login            (Get Started stays underneath)
 *   Google Login --new / not onboarded--> Choose Your Role (back stack cleared)
 *   Google Login --onboarding complete--> Signed-in placeholder (back stack cleared)
 *   Signed-in placeholder --Sign out--> Get Started        (back stack cleared)
 */
final class AppNavigator {

    private AppNavigator() {}

    static void openChooseRole(Activity from) {
        start(from, new Intent(from, ChooseRoleActivity.class));
    }

    static void openGoogleLogin(Activity from) {
        start(from, new Intent(from, GoogleLoginActivity.class));
    }

    /**
     * After a successful sign-in. Clearing the back stack means Back can never return
     * to the login page or Get Started, which prevents login loops.
     */
    static void openChooseRoleAfterSignIn(Activity from) {
        startClearingBackStack(from, ChooseRoleActivity.class);
    }

    /**
     * [NAVIGATION] Returning user with onboarding complete.
     * PLACEHOLDER destination for now. Later, read the saved role and open DinerHomeActivity
     * or ManagerHomeActivity here instead.
     */
    static void openSignedInHome(Activity from) {
        startClearingBackStack(from, AuthenticatedPlaceholderActivity.class);
    }

    static void openGetStartedAfterSignOut(Activity from) {
        startClearingBackStack(from, GetStartedActivity.class);
    }

    private static void startClearingBackStack(Activity from, Class<? extends Activity> target) {
        Intent intent = new Intent(from, target);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        start(from, intent);
    }

    /** [ANIMATION] Uses res/anim/screen_enter.xml + screen_exit.xml for every screen change. */
    private static void start(Activity from, Intent intent) {
        ActivityOptions options = ActivityOptions.makeCustomAnimation(
                from, R.anim.screen_enter, R.anim.screen_exit);
        from.startActivity(intent, options.toBundle());
    }
}