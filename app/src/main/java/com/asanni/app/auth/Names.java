package com.asanni.app.auth;

/**
 * Name helpers: cleaning, validation, the derived username, and a best-effort Google prefill.
 * Pure Java (no Android), so it is unit-tested.
 *
 * Location: app/src/main/java/com/asanni/app/auth/Names.java  (NEW in phase 4)
 */
public final class Names {

    /** [CONFIG] Longest allowed name part (must match firestore.rules). */
    public static final int MAX_LENGTH = 50;

    private Names() {}

    /** Trims and collapses inner whitespace: "  Sara   Ahmed " -> "Sara Ahmed". Null -> "". */
    public static String clean(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("\\s+", " ");
    }

    /** Required name part: non-empty after cleaning, not too long, contains a letter. */
    public static boolean isValidRequired(String raw) {
        String c = clean(raw);
        return !c.isEmpty() && c.length() <= MAX_LENGTH && containsLetter(c);
    }

    /** Optional name part (middle name): empty is fine, otherwise same rules. */
    public static boolean isValidOptional(String raw) {
        String c = clean(raw);
        return c.isEmpty() || (c.length() <= MAX_LENGTH && containsLetter(c));
    }

    private static boolean containsLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        return false;
    }

    /** The displayed username: first + " " + last. The middle name is never included. */
    public static String username(String first, String last) {
        return clean(clean(first) + " " + clean(last));
    }

    /**
     * Best-effort split of a Google display name into {first, last} for PREFILL only.
     * "Sara Ahmed" -> {Sara, Ahmed}; "Sara" -> {Sara, ""}; "Muhammad Ali Khan" -> {Muhammad, Khan}
     * (middle parts are not guessed). The user always reviews and can correct it.
     */
    public static String[] splitForPrefill(String displayName) {
        String c = clean(displayName);
        if (c.isEmpty()) return new String[] {"", ""};
        String[] parts = c.split(" ");
        if (parts.length == 1) return new String[] {parts[0], ""};
        return new String[] {parts[0], parts[parts.length - 1]};
    }

    /** Up to two initials for the avatar: "Sara Ahmed" -> "SA". */
    public static String initials(String username) {
        String c = clean(username);
        if (c.isEmpty()) return "";
        String[] parts = c.split(" ");
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(Character.toUpperCase(parts[0].codePointAt(0)));
        if (parts.length > 1) {
            sb.appendCodePoint(Character.toUpperCase(parts[parts.length - 1].codePointAt(0)));
        }
        return sb.toString();
    }
}