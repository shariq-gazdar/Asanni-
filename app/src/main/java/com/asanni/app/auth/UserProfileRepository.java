package com.asanni.app.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.asanni.app.appearance.Appearance;
import com.asanni.app.location.LocationSelection;
import com.asanni.app.location.SupportedCountry;
import com.asanni.app.profile.SupportedLanguage;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads and updates the ASANNI profile in Firestore (users/{uid}).
 *
 * Location: app/src/main/java/com/asanni/app/auth/UserProfileRepository.java
 *           (MODIFIED in phase 5: saveLocalPhoto replaces the Firebase Storage savePhoto)
 *
 * Every write is a PARTIAL update of named fields: changing the photo never touches the address,
 * changing the address never touches the phone, and uid/email/createdAt are never rewritten.
 * Success callbacks fire only after Firestore's server confirms the write.
 */
public final class UserProfileRepository {

    private static final String TAG = "AsanniProfile";

    public enum Failure {
        /** Offline or Firestore unreachable. Retry later. */
        NETWORK,
        /** Rules rejected the request (rules not published, invalid data, or signed out). */
        PERMISSION_DENIED,
        /** The Firestore database has not been created in the Firebase console. */
        DATABASE_MISSING,
        UNKNOWN
    }

    public interface ProfileCallback {
        void onProfileReady(ProfileData profile);
        void onProfileFailed(Failure failure, Exception error);
    }

    public interface SaveCallback {
        void onSaved();
        void onSaveFailed(Failure failure, Exception error);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private DocumentReference userDoc(String uid) {
        return db.collection(UserProfile.COLLECTION).document(uid);
    }

    /**
     * Makes sure users/{uid} exists WITHOUT overwriting an existing profile, and returns it.
     * A transaction: read, and only if missing, create the minimal profile. Transactions need a
     * connection, so offline sign-ins get Failure.NETWORK (no false success).
     */
    public void ensureProfile(FirebaseUser user, ProfileCallback callback) {
        final DocumentReference ref = userDoc(user.getUid());

        final Map<String, Object> newProfile = new HashMap<>();
        newProfile.put(UserProfile.FIELD_UID, user.getUid());
        newProfile.put(UserProfile.FIELD_DISPLAY_NAME, user.getDisplayName());
        newProfile.put(UserProfile.FIELD_EMAIL, user.getEmail());
        newProfile.put(UserProfile.FIELD_PHOTO_URL,
                user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null);
        newProfile.put(UserProfile.FIELD_ONBOARDING_COMPLETED, false);
        newProfile.put(UserProfile.FIELD_ROLE, null);
        newProfile.put(UserProfile.FIELD_CREATED_AT, FieldValue.serverTimestamp());

        db.runTransaction(transaction -> {
                    DocumentSnapshot snapshot = transaction.get(ref);
                    if (!snapshot.exists()) {
                        transaction.set(ref, newProfile);
                        return ProfileData.fresh(user);
                    }
                    return ProfileData.from(snapshot);
                })
                .addOnSuccessListener(callback::onProfileReady)
                .addOnFailureListener(e -> {
                    Log.w(TAG, "ensureProfile failed", e);
                    callback.onProfileFailed(classify(e, true), e);
                });
    }

    /** Reads the current profile (from the server when online, local cache when offline). */
    public void loadProfile(String uid, ProfileCallback callback) {
        userDoc(uid).get()
                .addOnSuccessListener(snap -> {
                    if (!snap.exists()) {
                        callback.onProfileFailed(Failure.UNKNOWN, null);
                        return;
                    }
                    callback.onProfileReady(ProfileData.from(snap));
                })
                .addOnFailureListener(e -> callback.onProfileFailed(classify(e, false), e));
    }

    /**
     * Phase 7: reads ONLY the copy saved on this phone (no network). Used at launch when the
     * server is slow or unreachable, so a signed-in user still reaches Discover quickly.
     */
    public void loadCachedProfile(String uid, ProfileCallback callback) {
        userDoc(uid).get(com.google.firebase.firestore.Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (!snap.exists()) {
                        callback.onProfileFailed(Failure.UNKNOWN, null);
                        return;
                    }
                    callback.onProfileReady(ProfileData.from(snap));
                })
                .addOnFailureListener(e -> callback.onProfileFailed(Failure.NETWORK, e));
    }

    /** Choose page: role = diner. */
    public void saveDinerRole(String uid, SaveCallback callback) {
        Map<String, Object> c = changes();
        c.put(UserProfile.FIELD_ROLE, UserProfile.ROLE_DINER);
        updateWithTimeout(uid, c, callback, "saveDinerRole");
    }

    /** Location onboarding / Settings address: a confirmed catalogue city + area. */
    public void saveLocation(String uid, LocationSelection selection, SaveCallback callback) {
        SupportedCountry country = SupportedCountry.DEFAULT;
        Map<String, Object> c = changes();
        c.put(UserProfile.FIELD_ROLE, UserProfile.ROLE_DINER);
        c.put(UserProfile.FIELD_COUNTRY, country.name);
        c.put(UserProfile.FIELD_COUNTRY_CODE, country.code);
        c.put(UserProfile.FIELD_CITY_ID, selection.cityId);
        c.put(UserProfile.FIELD_CITY_NAME, selection.cityName);
        c.put(UserProfile.FIELD_AREA_ID, selection.areaId);
        c.put(UserProfile.FIELD_AREA_NAME, selection.areaName);
        c.put(UserProfile.FIELD_LOCATION_SOURCE, selection.source);
        if (UserProfile.SOURCE_GPS.equals(selection.source)) {
            // PHASE 9: "detected at" time, so a saved location is never shown as freshly detected.
            c.put(UserProfile.FIELD_LOCATION_UPDATED_AT, FieldValue.serverTimestamp());
        }
        c.put(UserProfile.FIELD_ONBOARDING_COMPLETED, true);
        updateWithTimeout(uid, c, callback, "saveLocation");
    }

    /**
     * Registration form. Marks registration complete in the SAME write as the validated fields,
     * so it can never be "complete" with missing data. The phone fields are NOT written here:
     * only PhoneVerificationActivity writes them, after Firebase verified the number.
     */
    public void saveRegistration(String uid, String first, String middle, String last,
                                 SupportedLanguage language, Appearance appearance,
                                 SaveCallback callback) {
        Map<String, Object> c = nameChanges(first, middle, last);
        c.put(UserProfile.FIELD_LANGUAGE, language.id);
        c.put(UserProfile.FIELD_APPEARANCE, appearance.id);
        c.put(UserProfile.FIELD_REGISTRATION_COMPLETED, true);
        updateWithTimeout(uid, c, callback, "saveRegistration");
    }

    /** Info page: names (and the derived displayName) only. */
    public void saveNames(String uid, String first, String middle, String last, SaveCallback callback) {
        updateWithTimeout(uid, nameChanges(first, middle, last), callback, "saveNames");
    }

    public void saveAppearance(String uid, Appearance appearance, SaveCallback callback) {
        Map<String, Object> c = changes();
        c.put(UserProfile.FIELD_APPEARANCE, appearance.id);
        update(uid, c, callback, "saveAppearance");
    }

    /**
     * Call ONLY after Firebase Auth linked/updated this number on the signed-in account and the
     * ID token was refreshed: Firestore rules accept phoneVerified = true only when phoneNumber
     * equals the phone number inside the user's Firebase ID token.
     */
    public void savePhoneVerified(String uid, String e164, SaveCallback callback) {
        Map<String, Object> c = changes();
        c.put(UserProfile.FIELD_PHONE_NUMBER, e164);
        c.put(UserProfile.FIELD_PHONE_VERIFIED, true);
        c.put(UserProfile.FIELD_PHONE_VERIFIED_AT, FieldValue.serverTimestamp());
        update(uid, c, callback, "savePhoneVerified");
    }

    /** Marks the stored number as NOT verified (e.g. it no longer matches the Auth account). */
    public void clearPhoneVerified(String uid, SaveCallback callback) {
        Map<String, Object> c = changes();
        c.put(UserProfile.FIELD_PHONE_VERIFIED, false);
        update(uid, c, callback, "clearPhoneVerified");
    }

    /**
     * Phase 5: records that the user's custom picture is the on-device file `localId`.
     * Only the file NAME is stored (never image bytes). photoUrl is left alone, so it keeps the
     * Google photo, which other phones show because the file only exists on this phone.
     */
    public void saveLocalPhoto(String uid, String localId, SaveCallback callback) {
        Map<String, Object> c = changes();
        c.put(UserProfile.FIELD_PHOTO_SOURCE, UserProfile.PHOTO_SOURCE_LOCAL);
        c.put(UserProfile.FIELD_PHOTO_LOCAL_ID, localId);
        // Old Firebase Storage pictures are no longer used: point photoUrl back at the Google photo
        // (what other phones show) and clear the legacy Storage path.
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        c.put(UserProfile.FIELD_PHOTO_URL,
                u != null && u.getPhotoUrl() != null ? u.getPhotoUrl().toString() : null);
        c.put(UserProfile.FIELD_PHOTO_STORAGE_PATH, null);
        update(uid, c, callback, "saveLocalPhoto");
    }

    // ------------------------------------------------------------------------------------------

    private static Map<String, Object> changes() {
        Map<String, Object> c = new HashMap<>();
        c.put(UserProfile.FIELD_UPDATED_AT, FieldValue.serverTimestamp());
        return c;
    }

    private static Map<String, Object> nameChanges(String first, String middle, String last) {
        Map<String, Object> c = changes();
        String f = Names.clean(first);
        String m = Names.clean(middle);
        String l = Names.clean(last);
        c.put(UserProfile.FIELD_FIRST_NAME, f);
        c.put(UserProfile.FIELD_MIDDLE_NAME, m.isEmpty() ? null : m);
        c.put(UserProfile.FIELD_LAST_NAME, l);
        c.put(UserProfile.FIELD_DISPLAY_NAME, Names.username(f, l)); // derived, never separate
        return c;
    }

    /**
     * [TIMING] How long a screen waits for the server before treating a save as "offline".
     * Offline, Firestore never fails a write: it queues it and waits. Screens that block the user
     * while saving use this so the spinner can't hang forever. The queued write may still land
     * later; that's harmless because these saves only set the same values again.
     */
    private static final long SAVE_TIMEOUT_MS = 12_000L;
    private final Handler main = new Handler(Looper.getMainLooper());

    private void updateWithTimeout(String uid, Map<String, Object> changes, SaveCallback callback,
                                   String what) {
        final boolean[] done = {false};
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            Log.w(TAG, what + " not confirmed in time (offline?)");
            callback.onSaveFailed(Failure.NETWORK, null);
        };
        main.postDelayed(timeout, SAVE_TIMEOUT_MS);
        update(uid, changes, new SaveCallback() {
            @Override public void onSaved() {
                main.removeCallbacks(timeout);
                if (done[0]) return;
                done[0] = true;
                callback.onSaved();
            }

            @Override public void onSaveFailed(Failure failure, Exception error) {
                main.removeCallbacks(timeout);
                if (done[0]) return;
                done[0] = true;
                callback.onSaveFailed(failure, error);
            }
        }, what);
    }

    private void update(String uid, Map<String, Object> changes, SaveCallback callback, String what) {
        userDoc(uid).update(changes)
                .addOnSuccessListener(unused -> callback.onSaved())
                .addOnFailureListener(e -> {
                    Log.w(TAG, what + " failed: " + e.getClass().getSimpleName());
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