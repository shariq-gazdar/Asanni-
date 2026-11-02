package com.asanni.app.profile;

/**
 * Where custom profile pictures are kept. Screens only talk to this interface, so the storage
 * can change later (e.g. to a cloud provider) without touching Registration, Info, Settings or Home.
 *
 * Location: app/src/main/java/com/asanni/app/profile/ProfileImageStore.java  (NEW in phase 5)
 *
 * Current implementation: LocalProfileImageStore (app-private files on this phone). Chosen
 * because Firebase Storage needs the Blaze billing plan, which couldn't be activated.
 * To switch later: write e.g. CloudinaryProfileImageStore implements ProfileImageStore and return
 * it from ProfileImages.store(). Never put a provider's secret key in the app.
 */
public interface ProfileImageStore {

    enum Failure {
        /** The phone couldn't write the file (e.g. storage full). */
        WRITE_FAILED,
        /** Firestore rejected the picture reference (rules), so nothing changed. */
        PROFILE_REJECTED,
        UNKNOWN
    }

    interface Callback {
        /**
         * @param confirmedByServer true when Firestore confirmed the new picture reference;
         *        false when it is saved on this phone and the profile update is still queued
         *        (offline). Screens must say which one happened.
         */
        void onSaved(boolean confirmedByServer);
        void onFailed(Failure failure, Exception error);
    }

    /** Saves a processed avatar (square JPEG) as the user's custom picture. */
    void saveCustomPhoto(String uid, byte[] jpeg, Callback callback);
}