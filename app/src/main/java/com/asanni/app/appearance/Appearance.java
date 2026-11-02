package com.asanni.app.appearance;

/**
 * The three appearance choices. The id is what's stored in Firestore (users/{uid}.appearance).
 *
 * Location: app/src/main/java/com/asanni/app/appearance/Appearance.java  (NEW)
 */
public enum Appearance {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** [CONFIG] Default for new users. */
    public static final Appearance DEFAULT = SYSTEM;

    public final String id;

    Appearance(String id) {
        this.id = id;
    }

    /** Unknown or missing values fall back to the default instead of crashing. */
    public static Appearance fromId(String id) {
        for (Appearance a : values()) {
            if (a.id.equals(id)) return a;
        }
        return DEFAULT;
    }
}