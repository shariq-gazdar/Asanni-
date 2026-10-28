package com.asanni.app.auth;

/**
 * Field names and allowed values for the ASANNI user profile stored in Firestore.
 *
 * Location: app/src/main/java/com/asanni/app/auth/UserProfile.java  (NEW)
 *
 * Firestore document:  users/{uid}
 *   uid                  String   Firebase Auth UID (the ONLY account identifier; never the email)
 *   displayName          String?  from the Google account
 *   email                String?  from the Google account
 *   photoUrl             String?  from the Google account, may be null
 *   onboardingCompleted  boolean  false until the user finishes Choose Your Role
 *   role                 String?  null until a role is saved ("diner" or "manager")
 *   createdAt            Timestamp  set by the server when the document is first created
 *
 * The Google password is never seen, requested, or stored by the app.
 * To add a field later: add a constant here, write it in UserProfileRepository,
 * and allow it in firestore.rules (keys().hasOnly list).
 */
public final class UserProfile {

    public static final String COLLECTION = "users";

    public static final String FIELD_UID = "uid";
    public static final String FIELD_DISPLAY_NAME = "displayName";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_PHOTO_URL = "photoUrl";
    public static final String FIELD_ONBOARDING_COMPLETED = "onboardingCompleted";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_CREATED_AT = "createdAt";

    public static final String ROLE_DINER = "diner";
    public static final String ROLE_MANAGER = "manager";

    /** Where the app should send a signed-in user. */
    public enum Status {
        /** Profile was just created on this sign-in -> Choose Your Role. */
        CREATED_NEEDS_ROLE,
        /** Profile existed but onboarding is not finished -> Choose Your Role. */
        EXISTING_NEEDS_ROLE,
        /** Onboarding finished earlier -> returning-user destination. */
        ONBOARDING_COMPLETE
    }

    private UserProfile() {}
}