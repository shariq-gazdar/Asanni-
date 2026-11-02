package com.asanni.app.auth;

import com.asanni.app.appearance.Appearance;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;

/**
 * A read-only snapshot of users/{uid}, with safe defaults for missing fields
 * (older or partially filled profiles never crash the app).
 *
 * Location: app/src/main/java/com/asanni/app/auth/ProfileData.java
 *           (MODIFIED in phase 5: photoLocalId, fromCache)
 */
public final class ProfileData {

    public final String uid;
    public final String email;
    public final String displayName;
    public final String firstName;
    public final String middleName;
    public final String lastName;
    public final String photoUrl;
    public final String photoSource;
    public final String photoStoragePath;
    public final String photoLocalId;
    public final String phoneNumber;
    public final boolean phoneVerified;
    public final String language;
    /** null if the user never chose one (then the device keeps its current appearance). */
    public final String appearanceId;
    public final String role;
    public final String cityId;
    public final String cityName;
    public final String areaId;
    public final String areaName;
    public final String locationSource;
    public final boolean onboardingCompleted;
    public final boolean registrationCompleted;
    /** True when read from the phone's Firestore cache (offline), not confirmed by the server. */
    public final boolean fromCache;

    private ProfileData(Builder b) {
        uid = b.uid;
        email = b.email;
        displayName = b.displayName;
        firstName = b.firstName;
        middleName = b.middleName;
        lastName = b.lastName;
        photoUrl = b.photoUrl;
        photoSource = b.photoSource;
        photoStoragePath = b.photoStoragePath;
        photoLocalId = b.photoLocalId;
        fromCache = b.fromCache;
        phoneNumber = b.phoneNumber;
        phoneVerified = b.phoneVerified;
        language = b.language;
        appearanceId = b.appearanceId;
        role = b.role;
        cityId = b.cityId;
        cityName = b.cityName;
        areaId = b.areaId;
        areaName = b.areaName;
        locationSource = b.locationSource;
        onboardingCompleted = b.onboardingCompleted;
        registrationCompleted = b.registrationCompleted;
    }

    static ProfileData from(DocumentSnapshot s) {
        Builder b = new Builder();
        b.uid = s.getString(UserProfile.FIELD_UID) != null ? s.getString(UserProfile.FIELD_UID) : s.getId();
        b.email = s.getString(UserProfile.FIELD_EMAIL);
        b.displayName = s.getString(UserProfile.FIELD_DISPLAY_NAME);
        b.firstName = s.getString(UserProfile.FIELD_FIRST_NAME);
        b.middleName = s.getString(UserProfile.FIELD_MIDDLE_NAME);
        b.lastName = s.getString(UserProfile.FIELD_LAST_NAME);
        b.photoUrl = s.getString(UserProfile.FIELD_PHOTO_URL);
        b.photoSource = s.getString(UserProfile.FIELD_PHOTO_SOURCE);
        b.photoStoragePath = s.getString(UserProfile.FIELD_PHOTO_STORAGE_PATH);
        b.photoLocalId = s.getString(UserProfile.FIELD_PHOTO_LOCAL_ID);
        b.fromCache = s.getMetadata().isFromCache();
        b.phoneNumber = s.getString(UserProfile.FIELD_PHONE_NUMBER);
        b.phoneVerified = Boolean.TRUE.equals(s.getBoolean(UserProfile.FIELD_PHONE_VERIFIED));
        b.language = s.getString(UserProfile.FIELD_LANGUAGE);
        b.appearanceId = s.getString(UserProfile.FIELD_APPEARANCE);
        b.role = s.getString(UserProfile.FIELD_ROLE);
        b.cityId = s.getString(UserProfile.FIELD_CITY_ID);
        b.cityName = s.getString(UserProfile.FIELD_CITY_NAME);
        b.areaId = s.getString(UserProfile.FIELD_AREA_ID);
        b.areaName = s.getString(UserProfile.FIELD_AREA_NAME);
        b.locationSource = s.getString(UserProfile.FIELD_LOCATION_SOURCE);
        b.onboardingCompleted = Boolean.TRUE.equals(s.getBoolean(UserProfile.FIELD_ONBOARDING_COMPLETED));
        b.registrationCompleted = Boolean.TRUE.equals(s.getBoolean(UserProfile.FIELD_REGISTRATION_COMPLETED));
        return new ProfileData(b);
    }

    /** The profile just created on first sign-in (same values ensureProfile writes). */
    static ProfileData fresh(FirebaseUser user) {
        Builder b = new Builder();
        b.uid = user.getUid();
        b.email = user.getEmail();
        b.displayName = user.getDisplayName();
        b.photoUrl = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
        return new ProfileData(b);
    }

    public UserProfile.Status status() {
        if (!UserProfile.ROLE_DINER.equals(role)) return UserProfile.Status.NEEDS_ROLE;
        if (!onboardingCompleted || areaId == null) return UserProfile.Status.NEEDS_LOCATION;
        if (!registrationCompleted) return UserProfile.Status.NEEDS_REGISTRATION;
        return UserProfile.Status.COMPLETE;
    }

    public boolean hasAppearance() {
        return appearanceId != null;
    }

    public Appearance appearance() {
        return Appearance.fromId(appearanceId);
    }

    /** "Sara Ahmed": always first + last (middle name excluded). Falls back to displayName. */
    public String username() {
        String built = Names.username(firstName, lastName);
        if (!built.isEmpty()) return built;
        return displayName != null ? displayName.trim() : "";
    }

    /** "Clifton, Karachi", or null if no location is saved. */
    public String addressLine() {
        if (areaName == null || cityName == null) return null;
        return areaName + ", " + cityName;
    }

    /** A picture the user chose (on-device, or a legacy Firebase Storage upload). */
    public boolean hasCustomPhoto() {
        return isLocalPhoto() || (UserProfile.PHOTO_SOURCE_CUSTOM.equals(photoSource) && photoUrl != null);
    }

    public boolean isLocalPhoto() {
        return UserProfile.PHOTO_SOURCE_LOCAL.equals(photoSource) && photoLocalId != null;
    }

    private static final class Builder {
        String uid, email, displayName, firstName, middleName, lastName, photoUrl, photoSource,
                photoStoragePath, photoLocalId, phoneNumber, language, appearanceId, role, cityId, cityName,
                areaId, areaName, locationSource;
        boolean phoneVerified, onboardingCompleted, registrationCompleted, fromCache;
    }
}