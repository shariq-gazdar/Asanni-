package com.asanni.app.profile;

import android.util.Log;

import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfileRepository;
import com.google.firebase.auth.FirebaseUser;

/**
 * Keeps the profile's phone fields consistent with Firebase Authentication (the source of truth).
 *
 * Location: app/src/main/java/com/asanni/app/profile/PhoneVerificationSync.java  (NEW in phase 4)
 *
 * Handles the rare case where Firebase linked a number but the Firestore write afterwards
 * failed (or the reverse): run on Registration / Settings / Info load. It never marks a number
 * verified that Firebase Auth doesn't have on the account.
 */
public final class PhoneVerificationSync {

    private static final String TAG = "AsanniPhoneSync";

    public interface Listener {
        /** Called only when the stored profile was actually changed. */
        void onProfilePhoneChanged();
    }

    private PhoneVerificationSync() {}

    public static void reconcile(FirebaseUser user, ProfileData profile, Listener listener) {
        if (user == null || profile == null) return;
        final String authPhone = PakistanPhone.normalize(user.getPhoneNumber());
        final UserProfileRepository repo = new UserProfileRepository();

        if (authPhone != null && (!profile.phoneVerified || !authPhone.equals(profile.phoneNumber))) {
            // Verified in Auth but not (correctly) in the profile: repair it. Refresh the ID token
            // first so Firestore rules see the phone number claim.
            user.getIdToken(true).addOnSuccessListener(t ->
                    repo.savePhoneVerified(user.getUid(), authPhone, new UserProfileRepository.SaveCallback() {
                        @Override public void onSaved() { listener.onProfilePhoneChanged(); }
                        @Override public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                            Log.w(TAG, "Could not repair phone fields: " + f);
                        }
                    }));
        } else if (authPhone == null && profile.phoneVerified) {
            // Profile claims a verified number the account doesn't have: stop claiming it.
            repo.clearPhoneVerified(user.getUid(), new UserProfileRepository.SaveCallback() {
                @Override public void onSaved() { listener.onProfilePhoneChanged(); }
                @Override public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                    Log.w(TAG, "Could not clear phone verification: " + f);
                }
            });
        }
    }

    /** What screens should show: verified only if Auth and the profile agree. */
    public static boolean isVerified(FirebaseUser user, ProfileData profile) {
        if (user == null || profile == null || !profile.phoneVerified) return false;
        String authPhone = PakistanPhone.normalize(user.getPhoneNumber());
        return authPhone != null && authPhone.equals(profile.phoneNumber);
    }
}