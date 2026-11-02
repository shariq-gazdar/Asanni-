package com.asanni.app.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

import com.asanni.app.R;

/**
 * Every screen-to-screen move in one place, so destinations are easy to change later.
 *
 * Location: app/src/main/java/com/asanni/app/ui/AppNavigator.java  (MODIFIED in phase 4)
 *
 *   Get Started --"Get started"--> Choose (diner)
 *   Get Started --"Log in"-------> Google Login --> OnboardingRouter decides:
 *        Choose -> Allow Location (-> Manual Location) -> Registration (-> Phone verification)
 *        -> Notification explanation -> Home
 *   Home --menu--> drawer --Settings--> Settings --profile row--> Info
 *                                                --address row--> Manual Location (edit mode)
 *   Info --Sign out--> Get Started
 */
final class AppNavigator {

    static final String EXTRA_CONTINUE_AS_DINER = "com.asanni.app.extra.CONTINUE_AS_DINER";
    static final String EXTRA_PREFILL_CITY_ID = "com.asanni.app.extra.PREFILL_CITY_ID";
    static final String EXTRA_PREFILL_AREA_ID = "com.asanni.app.extra.PREFILL_AREA_ID";
    /** Manual Location opened from Settings: after saving, just go back. */
    static final String EXTRA_EDIT_MODE = "com.asanni.app.extra.EDIT_MODE";

    static final int REQUEST_EDIT_ADDRESS = 5101;
    static final int REQUEST_VERIFY_PHONE = 5102;

    private AppNavigator() {}

    static void openChooseRole(Activity from) {
        start(from, new Intent(from, ChooseRoleActivity.class));
    }

    static void openGoogleLogin(Activity from) {
        start(from, new Intent(from, GoogleLoginActivity.class));
    }

    static void openGoogleLoginThenContinueAsDiner(Activity from) {
        Intent intent = new Intent(from, GoogleLoginActivity.class);
        intent.putExtra(EXTRA_CONTINUE_AS_DINER, true);
        start(from, intent);
    }

    static void openChooseRoleAfterSignIn(Activity from) {
        startClearingBackStack(from, new Intent(from, ChooseRoleActivity.class));
    }

    static void openAllowLocation(Activity from) {
        start(from, new Intent(from, AllowLocationActivity.class));
    }

    static void openAllowLocationAfterSignIn(Activity from) {
        startClearingBackStack(from, new Intent(from, AllowLocationActivity.class));
    }

    /** Onboarding: pick a location (optionally pre-selecting a GPS-detected city). */
    static void openManualLocation(Activity from, String prefillCityId) {
        Intent intent = new Intent(from, ManualLocationActivity.class);
        if (prefillCityId != null) intent.putExtra(EXTRA_PREFILL_CITY_ID, prefillCityId);
        start(from, intent);
    }

    /** Settings: change the saved address with the SAME manual form; returns to Settings. */
    static void openEditAddress(Activity from, String cityId, String areaId) {
        Intent intent = new Intent(from, ManualLocationActivity.class);
        intent.putExtra(EXTRA_EDIT_MODE, true);
        if (cityId != null) intent.putExtra(EXTRA_PREFILL_CITY_ID, cityId);
        if (areaId != null) intent.putExtra(EXTRA_PREFILL_AREA_ID, areaId);
        from.startActivityForResult(intent, REQUEST_EDIT_ADDRESS, animation(from));
    }

    /** After the location step of onboarding: Registration decides what's left. */
    static void openRegistration(Activity from) {
        startClearingBackStack(from, new Intent(from, RegistrationActivity.class));
    }

    /** Phone verification for a number (E.164 or null). Result: RESULT_OK when verified + saved. */
    static void openPhoneVerification(Activity from, String initialE164) {
        Intent intent = new Intent(from, PhoneVerificationActivity.class);
        if (initialE164 != null) intent.putExtra(PhoneVerificationActivity.EXTRA_INITIAL_NUMBER, initialE164);
        from.startActivityForResult(intent, REQUEST_VERIFY_PHONE, animation(from));
    }

    static void openNotificationPrompt(Activity from) {
        startClearingBackStack(from, new Intent(from, NotificationPermissionActivity.class));
    }

    /** [NAVIGATION] The diner home screen (blank for now, with the menu). */
    static void openHome(Activity from) {
        startClearingBackStack(from, new Intent(from, HomeActivity.class));
    }

    static void openSettings(Activity from) {
        start(from, new Intent(from, SettingsActivity.class));
    }

    static void openInfo(Activity from) {
        start(from, new Intent(from, InfoActivity.class));
    }

    static void openGetStartedAfterSignOut(Activity from) {
        startClearingBackStack(from, new Intent(from, GetStartedActivity.class));
    }

    private static void startClearingBackStack(Activity from, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        start(from, intent);
    }

    /** [ANIMATION] Uses res/anim/screen_enter.xml + screen_exit.xml for every screen change. */
    private static void start(Activity from, Intent intent) {
        from.startActivity(intent, animation(from));
    }

    private static android.os.Bundle animation(Activity from) {
        return ActivityOptions.makeCustomAnimation(from, R.anim.screen_enter, R.anim.screen_exit).toBundle();
    }
}