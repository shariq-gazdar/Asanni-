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
     * [NAVIGATION] App opened while already signed in. The login screen sees the existing
     * session, skips Google, checks the profile and routes on. Get Started is closed so it
     * can't bounce the user back and forth.
     */
    static void resumeSignedInSession(Activity from) {
        AppNavigator.openGoogleLogin(from);
        from.finish();
    }
}