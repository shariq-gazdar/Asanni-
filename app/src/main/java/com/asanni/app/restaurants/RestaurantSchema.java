package com.asanni.app.restaurants;

/**
 * [CONFIG] How the Android app reads the restaurant-owner documents written by the DESKTOP app.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/RestaurantSchema.java
 *
 * SCHEMA STATUS (updated 3 Nov 2026)
 *   The real desktop document has been confirmed. It is FLAT (no nested maps) and looks like:
 *
 *     name                  "Restaurant Name"            (string)
 *     logoUrl               "https://..."                (string)
 *     coverUrl              "coverphoto.png"             (string)   <- see IMAGE NOTE below
 *     cuisines              ["French", "Cafe"]           (array of strings)
 *     cityId                "karachi"                    (string, same IDs as the ASANNI catalogue)
 *     areaId                "karachi-clifton"            (string, same IDs as the ASANNI catalogue)
 *     address               "Block 5, Clifton, Karachi"  (string)
 *     location              [24.81 N, 67.03 E]           (GeoPoint)
 *     isEnabled             true                         (boolean, registration)
 *     isOpen                true                         (boolean, open right now)
 *     acceptingDiners       true                         (boolean)
 *     availableTables       8                            (int64)
 *     totalTables           12                           (int64)
 *     waitMinutes           15                           (int64)
 *     availabilityUpdatedAt 3 Nov 2026 18:13:43 UTC+5    (Timestamp)
 *     phone                 "922135000000"               (string, no '+')
 *
 *   Confirmed fields now have ONE name each, so nothing can be read from the wrong field.
 *   Fields the desktop app does NOT write yet (featured, account status, per-table records, ...)
 *   keep their candidate lists, so they start working automatically if the desktop app adds them.
 *
 * IMAGE NOTE
 *   coverUrl "coverphoto.png" is a bare file name and logoUrl "https://" has no host. Neither can be
 *   downloaded by the phone. Images are only loaded from a full http(s) URL with a host, or from
 *   data:image / base64 text. Ask the desktop developer to store complete public URLs.
 *
 * THE MOBILE APP ONLY READS this collection. It never writes to restaurant documents.
 *
 * PHASE 8: only ADDED price, perHead and the isTestData marker (section "PHASE 8 additions").
 * Nothing above was changed.
 */
public final class RestaurantSchema {

    private RestaurantSchema() {}

    // ------------------------------------------------------------------ collection & query

    /**
     * [CONFIG] Firestore collection the desktop app writes restaurants to.
     * Must match the name shown in the Firebase console EXACTLY (case and spelling).
     */
    public static final String COLLECTION = "restaurantOwner";

    /**
     * [CONFIG] Server-side city filter. The desktop app stores the same city IDs as the ASANNI
     * catalogue (cityId = "karachi"), so Firestore now returns only the user's city.
     * Set to null to go back to reading MAX_DOCUMENTS and matching on the phone.
     */
    public static final String SERVER_CITY_FIELD = "cityId";

    /** [CONFIG] Safety cap on documents read by one listener (bounded reads; no full-database download). */
    public static final int MAX_DOCUMENTS = 500;

    // ------------------------------------------------------------------ meaning / freshness

    /**
     * [CONFIG] Table counts older than this are treated as "not current" and are NOT shown.
     * The desktop app writes availabilityUpdatedAt, so this is active. The desktop app must touch
     * availabilityUpdatedAt at least this often (even when the count has not changed), otherwise
     * a quiet restaurant's tables disappear from the app.
     */
    public static final long AVAILABILITY_FRESH_MS = 30 * 60_000L;

    /**
     * [CONFIG] If true, a table count WITHOUT any timestamp is never shown (strict).
     * The confirmed schema always has availabilityUpdatedAt, so this only affects old documents.
     */
    public static final boolean REQUIRE_AVAILABILITY_TIMESTAMP = false;

    /**
     * [CONFIG] What a plain "isActive" / "active" boolean means. The confirmed schema has no such
     * field (it uses isEnabled + isOpen instead), so this setting currently has no effect.
     */
    public enum ActiveMeaning { REGISTRATION, OPEN_NOW }
    public static final ActiveMeaning ACTIVE_FIELD_MEANING = ActiveMeaning.REGISTRATION;

    // ------------------------------------------------------------------ CONFIRMED field names

    static final String[] NAME = {"name"};
    /** Logo: full https URL, or an image stored as base64 / data:image text. */
    static final String[] LOGO = {"logoUrl"};
    /** Optional wide photo; the top card uses the LOGO as required, the photo only as a backdrop. */
    static final String[] COVER = {"coverUrl"};
    /** List of strings, e.g. ["French", "Cafe"]. A plain string also still works. */
    static final String[] CUISINE = {"cuisines"};

    /** Catalogue IDs: "karachi", "karachi-clifton". */
    static final String[] CITY = {"cityId"};
    static final String[] AREA = {"areaId"};
    static final String[] ADDRESS = {"address"};
    /**
     * Maps that may contain city / area / address / coordinates. The confirmed document is flat
     * ("location" is a GeoPoint and "address" is a string, not maps), so nothing is searched.
     */
    static final String[] NESTED = {};

    /** GeoPoint. */
    static final String[] GEO = {"location"};
    /** Only used if a coordinate is ever stored as a map {lat, lng} instead of a GeoPoint. */
    static final String[] LAT = {"latitude", "lat"};
    static final String[] LNG = {"longitude", "lng", "lon", "long"};

    /** Registration boolean (true = shown to diners). */
    static final String[] ENABLED = {"isEnabled"};
    /** Open-right-now boolean. */
    static final String[] OPEN_NOW = {"isOpen"};
    /** Accepting diners / walk-ins right now. */
    static final String[] ACCEPTING = {"acceptingDiners"};

    /** Aggregate number of free tables right now. */
    static final String[] FREE_TABLES = {"availableTables"};
    static final String[] TOTAL_TABLES = {"totalTables"};

    /** Current waiting time in minutes. */
    static final String[] WAIT_MINUTES = {"waitMinutes"};
    /** When availability was last updated (Firestore Timestamp). */
    static final String[] UPDATED_AT = {"availabilityUpdatedAt"};
    /** Stored without '+', e.g. "922135000000". Add '+' before dialing. */
    static final String[] PHONE = {"phone"};

    // ------------------------------------------------------------------ PHASE 8 additions (only these lines are new)

    /** Price amount (number, or numeric text such as "2500"). PKR. Missing = not shown. */
    static final String[] PRICE = {"price"};
    /** Boolean: true = price is per person; false = not per-head pricing (shown as "Variable pricing"). */
    static final String[] PER_HEAD = {"perHead"};
    /**
     * Development test marker. Documents (or local fixtures) with isTestData = true are labelled
     * "TEST DATA" everywhere and are hidden in release builds (see DevTestData.java).
     */
    static final String[] TEST_MARKER = {"isTestData"};

    // ------------------------------------------------------------------ NOT in the desktop schema yet
    // Kept as candidate lists so they work automatically if the desktop app adds them later.

    /** Inverted booleans (true = hidden). */
    static final String[] DISABLED = {"isDisabled", "disabled", "isSuspended", "suspended", "isBlocked", "isDeleted"};
    /** Ambiguous booleans, interpreted by ACTIVE_FIELD_MEANING. */
    static final String[] ACTIVE = {"isActive", "active"};
    /** Account status strings (e.g. "approved", "suspended"). */
    static final String[] ACCOUNT_STATUS = {"accountStatus", "approvalStatus", "registrationStatus"};
    /** Open/closed strings (e.g. "open", "closed"). */
    static final String[] OPEN_STATUS = {"operationalStatus", "openStatus", "businessStatus"};
    /** Table records stored INSIDE the restaurant document: a list of maps (counted on the phone). */
    static final String[] TABLE_LIST = {"tables"};
    /** Inside each table map: a boolean meaning free, or a status string. */
    static final String[] TABLE_FREE_FLAG = {"isAvailable", "available", "isFree", "free"};
    static final String[] TABLE_BUSY_FLAG = {"isOccupied", "occupied", "isReserved", "reserved"};
    static final String[] TABLE_STATUS = {"status", "state"};
    static final String[] FEATURED = {"isFeatured", "featured"};

    // ------------------------------------------------------------------ status words (lowercase)

    static final String[] WORDS_ACCOUNT_ON = {"approved", "active", "enabled", "verified", "live", "published"};
    static final String[] WORDS_ACCOUNT_OFF = {"suspended", "disabled", "rejected", "blocked", "deleted",
            "inactive", "banned", "pending", "draft"};
    static final String[] WORDS_OPEN = {"open", "opened", "open_now", "opennow"};
    static final String[] WORDS_CLOSED = {"closed", "close", "closed_now", "temporarily_closed", "closedtemporarily"};
    static final String[] WORDS_TABLE_FREE = {"free", "available", "vacant", "open", "empty"};
    static final String[] WORDS_TABLE_BUSY = {"occupied", "reserved", "busy", "taken", "booked", "full", "unavailable"};
}