package com.asanni.app.profile;

/**
 * Languages the app supports. This version: English only (stored as "en").
 *
 * Location: app/src/main/java/com/asanni/app/profile/SupportedLanguage.java  (NEW in phase 4)
 *
 * To add a language later: add a constant, add translated res/values-xx/strings*.xml,
 * allow its id in firestore.rules, and turn the locked Language field into a picker.
 */
public enum SupportedLanguage {

    ENGLISH("en", "English");

    /** [CONFIG] Default and only language for now. */
    public static final SupportedLanguage DEFAULT = ENGLISH;

    public final String id;
    /** Shown in its own language (so a speaker can always find it). */
    public final String nativeName;

    SupportedLanguage(String id, String nativeName) {
        this.id = id;
        this.nativeName = nativeName;
    }

    public static SupportedLanguage fromId(String id) {
        for (SupportedLanguage l : values()) {
            if (l.id.equals(id)) return l;
        }
        return DEFAULT;
    }
}