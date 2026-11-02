package com.asanni.app.auth;

/**
 * Field names and allowed values for the ASANNI user profile stored in Firestore.
 *
 * Location: app/src/main/java/com/asanni/app/auth/UserProfile.java  (MODIFIED in phase 4)
 *
 * Firestore document:  users/{uid}      (uid = Firebase Auth UID, never the email)
 *
 *   identity      uid, email (both from Firebase Auth, never typed by the user), createdAt
 *   role          role = "diner"                                   (Choose page)
 *   location      country, countryCode, cityId, cityName, areaId, areaName, locationSource,
 *                 onboardingCompleted = true                        (Allow / Manual Location)
 *   registration  firstName, middleName (optional), lastName, displayName = first + " " + last,
 *                 language = "en", appearance = system|light|dark,
 *                 registrationCompleted = true                      (Registration form)
 *   phone         phoneNumber (+923XXXXXXXXX), phoneVerified, phoneVerifiedAt
 *                 (written ONLY after Firebase Phone Auth links the number to this account)
 *   photo         photoUrl (Google photo, or legacy Storage URL), photoSource = google|custom|local,
 *                 photoLocalId (phase 5: on-device picture file name), photoStoragePath (legacy)
 *   updatedAt     server time of the last change
 *
 * These states are separate on purpose: signing in, location onboarding, registration,
 * phone verification and notification permission never imply each other.
 */
public final class UserProfile {

    public static final String COLLECTION = "users";

    public static final String FIELD_UID = "uid";
    public static final String FIELD_DISPLAY_NAME = "displayName";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_PHOTO_URL = "photoUrl";
    public static final String FIELD_PHOTO_SOURCE = "photoSource";
    public static final String FIELD_PHOTO_STORAGE_PATH = "photoStoragePath"; // legacy (Firebase Storage)
    /** Phase 5: file name of a custom picture kept on the phone (see LocalProfileImageStore). */
    public static final String FIELD_PHOTO_LOCAL_ID = "photoLocalId";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_COUNTRY = "country";
    public static final String FIELD_COUNTRY_CODE = "countryCode";
    public static final String FIELD_CITY_ID = "cityId";
    public static final String FIELD_CITY_NAME = "cityName";
    public static final String FIELD_AREA_ID = "areaId";
    public static final String FIELD_AREA_NAME = "areaName";
    public static final String FIELD_LOCATION_SOURCE = "locationSource";
    public static final String FIELD_ONBOARDING_COMPLETED = "onboardingCompleted";
    public static final String FIELD_FIRST_NAME = "firstName";
    public static final String FIELD_MIDDLE_NAME = "middleName";
    public static final String FIELD_LAST_NAME = "lastName";
    public static final String FIELD_LANGUAGE = "language";
    public static final String FIELD_APPEARANCE = "appearance";
    public static final String FIELD_PHONE_NUMBER = "phoneNumber";
    public static final String FIELD_PHONE_VERIFIED = "phoneVerified";
    public static final String FIELD_PHONE_VERIFIED_AT = "phoneVerifiedAt";
    public static final String FIELD_REGISTRATION_COMPLETED = "registrationCompleted";
    public static final String FIELD_CREATED_AT = "createdAt";
    public static final String FIELD_UPDATED_AT = "updatedAt";

    /** The only role in the Android app. Restaurant managers use separate software. */
    public static final String ROLE_DINER = "diner";

    public static final String SOURCE_GPS = "gps";
    public static final String SOURCE_MANUAL = "manual";

    public static final String PHOTO_SOURCE_GOOGLE = "google";
    /** Legacy: custom picture uploaded to Firebase Storage (photoUrl = its URL). */
    public static final String PHOTO_SOURCE_CUSTOM = "custom";
    /** Phase 5: custom picture saved privately on the phone that chose it. */
    public static final String PHOTO_SOURCE_LOCAL = "local";

    /**
     * [CONFIG] Registration requires a phone number verified through Firebase Phone Auth.
     * If you set this to false, ALSO remove the phone requirement from firestore.rules
     * (search for "REQUIRE_VERIFIED_PHONE"), or saving registration will be rejected.
     */
    public static final boolean REQUIRE_VERIFIED_PHONE = true;

    /** Which onboarding step a signed-in user still needs. */
    public enum Status {
        /** No role yet -> Choose page. */
        NEEDS_ROLE,
        /** Diner, but no location saved -> Allow Location. */
        NEEDS_LOCATION,
        /** Location saved, registration form not completed -> Registration. */
        NEEDS_REGISTRATION,
        /** Everything done -> (notification prompt once per device) -> Home. */
        COMPLETE
    }

    private UserProfile() {}
}