package com.asanni.app.auth;

import android.util.Log;

import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads/creates the persistent ASANNI profile in Firestore (users/{uid}).
 * This, not a local boolean, decides whether someone is a first-time or returning user.
 *
 * Location: app/src/main/java/com/asanni/app/auth/UserProfileRepository.java  (NEW)
 */
public final class UserProfileRepository {

    private static final String TAG = "AsanniProfile";

    /** Why a profile operation failed, so the screen can show a helpful message. */
    public enum Failure {
        /** Offline or Firestore unreachable. Retry later. */
        NETWORK,
        /** Firestore rules rejected the request (rules not published, or wrong rules). */
        PERMISSION_DENIED,
        /** The Firestore database has not been created in the Firebase console. */
        DATABASE_MISSING,
        UNKNOWN
    }

    public interface ProfileCallback {
        void onProfileReady(UserProfile.Status status);
        void onProfileFailed(Failure failure, Exception error);
    }

    public interface SaveCallback {
        void onSaved();
        void onSaveFailed(Failure failure, Exception error);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /**
     * Makes sure users/{uid} exists, WITHOUT overwriting an existing profile.
     *
     * Runs as a Firestore transaction: read the document, and only if it does not exist,
     * create it with onboardingCompleted=false and role=null. Two sign-ins at the same time
     * cannot create duplicates, and a returning user's role/onboarding are never touched.
     * Transactions need a connection, so offline sign-ins get Failure.NETWORK (not a false success).
     */
    public void ensureProfile(FirebaseUser user, ProfileCallback callback) {
        final DocumentReference ref = db.collection(UserProfile.COLLECTION).document(user.getUid());

        final Map<String, Object> newProfile = new HashMap<>();
        newProfile.put(UserProfile.FIELD_UID, user.getUid());
        newProfile.put(UserProfile.FIELD_DISPLAY_NAME, user.getDisplayName());
        newProfile.put(UserProfile.FIELD_EMAIL, user.getEmail());
        newProfile.put(UserProfile.FIELD_PHOTO_URL,
                user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null);
        newProfile.put(UserProfile.FIELD_ONBOARDING_COMPLETED, false);
        newProfile.put(UserProfile.FIELD_ROLE, null); // stays unset until Choose Your Role saves it
        newProfile.put(UserProfile.FIELD_CREATED_AT, FieldValue.serverTimestamp());

        db.runTransaction(transaction -> {
                    DocumentSnapshot snapshot = transaction.get(ref);
                    if (snapshot.exists()) {
                        Boolean done = snapshot.getBoolean(UserProfile.FIELD_ONBOARDING_COMPLETED);
                        return Boolean.TRUE.equals(done)
                                ? UserProfile.Status.ONBOARDING_COMPLETE
                                : UserProfile.Status.EXISTING_NEEDS_ROLE;
                    }
                    transaction.set(ref, newProfile);
                    return UserProfile.Status.CREATED_NEEDS_ROLE;
                })
                .addOnSuccessListener(callback::onProfileReady)
                .addOnFailureListener(e -> {
                    Log.w(TAG, "ensureProfile failed", e);
                    callback.onProfileFailed(classify(e, true), e);
                });
    }

    /**
     * [INTEGRATION POINT] Saves the chosen role and marks onboarding as complete.
     * NOT called anywhere yet: Choose Your Role is still a placeholder.
     * Call it from ChooseRoleActivity.onContinueAsDiner()/onContinueAsManager() when those are built.
     */
    public void completeOnboarding(String uid, String role, SaveCallback callback) {
        if (!UserProfile.ROLE_DINER.equals(role) && !UserProfile.ROLE_MANAGER.equals(role)) {
            throw new IllegalArgumentException("Unknown role: " + role);
        }
        Map<String, Object> changes = new HashMap<>();
        changes.put(UserProfile.FIELD_ROLE, role);
        changes.put(UserProfile.FIELD_ONBOARDING_COMPLETED, true);

        db.collection(UserProfile.COLLECTION).document(uid)
                .update(changes)
                .addOnSuccessListener(unused -> callback.onSaved())
                .addOnFailureListener(e -> {
                    Log.w(TAG, "completeOnboarding failed", e);
                    // Here NOT_FOUND means the user document is missing, not the database.
                    callback.onSaveFailed(classify(e, false), e);
                });
    }

    /**
     * @param notFoundMeansNoDatabase true for ensureProfile (a transaction on a missing document
     *        never returns NOT_FOUND, so NOT_FOUND there means the database itself doesn't exist).
     */
    static Failure classify(Exception e, boolean notFoundMeansNoDatabase) {
        if (e instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) e).getCode()) {
                case UNAVAILABLE:
                case DEADLINE_EXCEEDED:
                    return Failure.NETWORK;
                case PERMISSION_DENIED:
                case UNAUTHENTICATED:
                    return Failure.PERMISSION_DENIED;
                case NOT_FOUND:
                    return notFoundMeansNoDatabase ? Failure.DATABASE_MISSING : Failure.UNKNOWN;
                default:
                    return Failure.UNKNOWN;
            }
        }
        return Failure.UNKNOWN;
    }
}