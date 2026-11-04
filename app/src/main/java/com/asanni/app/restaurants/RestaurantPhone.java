package com.asanni.app.restaurants;

/**
 * PHASE 9: the restaurant's `phone` field -> a number the dialer can use, or null when it isn't
 * a believable phone number (then the phone row is hidden). Pure logic, unit-tested.
 * The desktop app stores numbers without '+', e.g. 922135000000 or 923001234567.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/RestaurantPhone.java  (NEW in phase 9)
 */
public final class RestaurantPhone {

    private RestaurantPhone() {}

    /** "+922135000000", "+923001234567", "02135000000"... or null when invalid. */
    public static String dialable(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("[\\s\\-().]", "");
        if (s.isEmpty()) return null;
        boolean plus = s.startsWith("+");
        String digits = plus ? s.substring(1) : s;
        if (digits.startsWith("00")) { digits = digits.substring(2); plus = true; } // 0092... form
        if (!digits.matches("\\d+")) return null;
        if (digits.startsWith("92")) {
            // Pakistan: 92 + 9-10 digit national number (landline or mobile), no leading 0.
            String national = digits.substring(2);
            return national.matches("[1-9]\\d{8,9}") ? "+" + digits : null;
        }
        if (plus) return digits.length() >= 8 && digits.length() <= 15 ? "+" + digits : null; // other country
        if (digits.startsWith("0")) return digits.matches("0[1-9]\\d{8,9}") ? digits : null;   // local 0...
        if (digits.matches("3\\d{9}")) return "+92" + digits;                                  // 3001234567
        return null;
    }

    /** Readable form: "+92 300 1234567", "+92 21 35000000", local numbers unchanged. */
    public static String display(String dialable) {
        if (dialable == null) return null;
        if (dialable.startsWith("+923") && dialable.length() == 13) {
            return "+92 " + dialable.substring(3, 6) + " " + dialable.substring(6);
        }
        if (dialable.startsWith("+9221") || dialable.startsWith("+9242") || dialable.startsWith("+9251")) {
            return "+92 " + dialable.substring(3, 5) + " " + dialable.substring(5);
        }
        if (dialable.startsWith("+92")) return "+92 " + dialable.substring(3);
        return dialable;
    }
}