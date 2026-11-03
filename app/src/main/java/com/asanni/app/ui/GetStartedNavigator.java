package com.asanni.app.ui;

import android.app.Activity;

/**
 * Navigation for the Get Started screen.  (MODIFIED in phase 2)
 *
 * Location: app/src/main/java/com/asanni/app/ui/GetStartedNavigator.java
 *
 * Phase 1 sent "Get started" to MainActivity and closed this screen.
 * Phase 2 sends it to Choose Your Role and keeps this screen underneath, so Back / X returns here.
 * The old "welcome seen" local flag is gone: Firebase's signed-in session is used instead.
 */
final class GetStartedNavigator {

    private GetStartedNavigator() {}

    /** [NAVIGATION] "Get started" button -> Choose Your Role. */
    static void onGetStartedClicked(Activity from) {
        AppNavigator.openChooseRole(from);
    }

    /** [NAVIGATION] "Log in" link -> Google Login. */
    static void onLoginClicked(Activity from) {
        AppNavigator.openGoogleLogin(from);
    }

    /**
     * [NAVIGATION] Get Started opened while already signed in (e.g. Android restored this screen).
     * Phase 7: no detour through the login screen and its "Setting up your profile" message; the
     * same LaunchRouter as the intro decides (Discover, or the missing onboarding step).
     */
    static LaunchRouter resumeSignedInSession(LaunchRouter.Callback callback) {
        LaunchRouter router = new LaunchRouter();
        router.start(callback);
        return router;
    }
}