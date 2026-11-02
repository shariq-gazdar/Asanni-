package com.asanni.app.profile;

/**
 * Pakistani mobile numbers: validation and normalization to E.164 (+923XXXXXXXXX).
 * Pure Java, unit-tested. Validation only: it says nothing about whether the number is verified.
 *
 * Location: app/src/main/java/com/asanni/app/profile/PakistanPhone.java  (NEW in phase 4)
 *
 * Accepted input (spaces, dashes, dots and brackets are ignored):
 *   3001234567   03001234567   923001234567   +923001234567   00923001234567
 * Rejected: landlines, other country codes, wrong length, letters.
 */
public final class PakistanPhone {

    /** [CONFIG] Shown in front of the number field. Other countries are not supported yet. */
    public static final String COUNTRY_CODE = "+92";

    /** A mobile number after the country code: 3 + 9 digits (e.g. 300 1234567). */
    private static final String LOCAL_MOBILE = "3\\d{9}";

    private PakistanPhone() {}

    /** Returns "+923XXXXXXXXX", or null if the input isn't a valid Pakistani mobile number. */
    public static String normalize(String input) {
        if (input == null) return null;
        String s = input.trim().replaceAll("[\\s\\-().]", "");
        if (s.isEmpty()) return null;

        String local;
        if (s.startsWith("+")) {
            if (!s.startsWith("+92")) return null;     // other country codes are rejected
            local = s.substring(3);
        } else if (s.startsWith("0092")) {
            local = s.substring(4);
        } else if (s.startsWith("92") && s.length() == 12) {
            local = s.substring(2);
        } else if (s.startsWith("0") && s.length() == 11) {
            local = s.substring(1);
        } else {
            local = s;
        }
        return local.matches(LOCAL_MOBILE) ? COUNTRY_CODE + local : null;
    }

    public static boolean isValid(String input) {
        return normalize(input) != null;
    }

    /** "+923001234567" -> "3001234567" (for the field next to the +92 box). */
    public static String localPart(String e164) {
        String n = normalize(e164);
        return n == null ? "" : n.substring(COUNTRY_CODE.length());
    }

    /** "+923001234567" -> "+92 300 1234567". Returns the input unchanged if it isn't valid. */
    public static String display(String e164) {
        String n = normalize(e164);
        if (n == null) return e164 == null ? "" : e164;
        String local = n.substring(3);
        return COUNTRY_CODE + " " + local.substring(0, 3) + " " + local.substring(3);
    }
}