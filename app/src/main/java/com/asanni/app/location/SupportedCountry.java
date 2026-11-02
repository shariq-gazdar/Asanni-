package com.asanni.app.location;

/**
 * Countries ASANNI supports. This version supports Pakistan only, so the country is fixed.
 *
 * Location: app/src/main/java/com/asanni/app/location/SupportedCountry.java  (NEW)
 *
 * To add a country later: add a constant here, seed its cities/areas with the same countryCode,
 * allow it in firestore.rules, and replace the locked Country field with a picker.
 */
public enum SupportedCountry {

    PAKISTAN("PK", "Pakistan");

    /** [CONFIG] The country used everywhere in this version. */
    public static final SupportedCountry DEFAULT = PAKISTAN;

    /** ISO 3166-1 alpha-2 code, stored as countryCode on catalogue records and profiles. */
    public final String code;
    /** Canonical display/storage name, stored as country on profiles. */
    public final String name;

    SupportedCountry(String code, String name) {
        this.code = code;
        this.name = name;
    }
}