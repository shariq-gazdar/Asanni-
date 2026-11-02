package com.asanni.app.ui;

import android.app.Activity;

import com.asanni.app.appearance.AppearanceManager;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.profile.NotificationPrompt;

/**
 * One place that decides where a signed-in user goes next, from their Firestore profile.
 *
 * Location: app/src/main/java/com/asanni/app/ui/OnboardingRouter.java  (NEW in phase 4)
 *
 *   no role               -> Choose
 *   no location           -> Allow Location
 *   not registered        -> Registration
 *   all done              -> Notification explanation (once per device, if needed) -> Home
 */
final class OnboardingRouter {

    private OnboardingRouter() {}

    /** Opens the next required screen and clears the back stack (no loops back to login). */
    static void route(Activity from, ProfileData profile) {
        // Bring this device's appearance in line with the saved preference (e.g. new phone).
        // (Skipped while a choice made on this phone is still waiting to be saved.)
        if (profile.hasAppearance() && !AppearanceManager.isPendingSync(from)) {
            AppearanceManager.setLocal(from, profile.appearance());
        }

        switch (profile.status()) {
            case NEEDS_ROLE:
                AppNavigator.openChooseRoleAfterSignIn(from);
                break;
            case NEEDS_LOCATION:
                AppNavigator.openAllowLocationAfterSignIn(from);
                break;
            case NEEDS_REGISTRATION:
                AppNavigator.openRegistration(from);
                break;
            case COMPLETE:
            default:
                openHomeOrNotificationPrompt(from, profile.uid);
                break;
        }
    }

    static void openHomeOrNotificationPrompt(Activity from, String uid) {
        if (NotificationPrompt.shouldShow(from, uid)) {
            AppNavigator.openNotificationPrompt(from);
        } else {
            AppNavigator.openHome(from);
        }
    }
}