package com.asanni.app.restaurants;

/**
 * [CONFIG] How the Android app reads the restaurant-owner documents written by the DESKTOP app.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/RestaurantSchema.java  (NEW in phase 6)
 *
 * WHY THIS FILE EXISTS
 *   The desktop developer owns the restaurant database. Its exact field names were not available
 *   when phase 6 was written, so instead of guessing ONE name per field, each value is looked up
 *   under a short list of common names (first match wins). Missing fields are simply "unknown";
 *   nothing is ever invented.
 *
 *   When the real schema is confirmed, put the real field name FIRST in each list (or delete the
 *   others). On every load the app writes a one-line "schema report" to Logcat (tag
 *   AsanniRestaurants) listing which field names it actually found, so you can check this quickly.
 *
 * THE MOBILE APP ONLY READS this collection. It never writes to restaurant documents.
 */
public final class RestaurantSchema {

    private RestaurantSchema() {}

    // ------------------------------------------------------------------ collection & query

    /** [CONFIG] Firestore collection the desktop app writes restaurants to. */
    public static final String COLLECTION = "resturant-owner";

    /**
     * [CONFIG] Optional server-side city filter. Leave null until the desktop schema is confirmed.
     *   null  -> the app reads at most MAX_DOCUMENTS restaurants once (live listener) and matches
     *            city/area on the phone. Works with any field naming. Fine for hundreds of restaurants.
     *   "cityId" (example) -> the app asks Firestore only for restaurants whose cityId equals the
     *            user's catalogue city ID (e.g. "karachi"). Use this when the collection grows large
     *            AND the desktop app stores the SAME city IDs as the ASANNI catalogue.
     */
    public static final String SERVER_CITY_FIELD = null;

    /** [CONFIG] Safety cap on documents read by one listener (bounded reads; no full-database download). */
    public static final int MAX_DOCUMENTS = 500;

    // ------------------------------------------------------------------ meaning / freshness

    /**
     * [CONFIG] Table counts older than this are treated as "not current" and are NOT shown.
     * Only used when the document has an availability timestamp.
     */
    public static final long AVAILABILITY_FRESH_MS = 30 * 60_000L;

    /**
     * [CONFIG] If true, a table count WITHOUT any timestamp is never shown (strict).
     * false (default): shown, but ranked below counts with a fresh timestamp.
     */
    public static final boolean REQUIRE_AVAILABILITY_TIMESTAMP = false;

    /**
     * [CONFIG] What a plain "isActive" / "active" boolean means in the desktop data. It is ambiguous,
     * so it is configurable:
     *   REGISTRATION (default) -> the restaurant account is enabled in the system (false = hidden).
     *   OPEN_NOW               -> the restaurant is open right now (used by the Active filter).
     */
    public enum ActiveMeaning { REGISTRATION, OPEN_NOW }
    public static final ActiveMeaning ACTIVE_FIELD_MEANING = ActiveMeaning.REGISTRATION;

    // ------------------------------------------------------------------ field names (first match wins)

    static final String[] NAME = {"name", "restaurantName", "restaurant_name", "businessName", "title"};
    /** Logo: https URL, or an image stored as base64 / data:image text. */
    static final String[] LOGO = {"logoUrl", "logo", "logoURL", "logo_url", "logoImage", "logoBase64"};
    /** Optional wide photo; the top card uses the LOGO as required, the photo only as a backdrop. */
    static final String[] COVER = {"coverUrl", "coverImage", "cover", "bannerUrl", "imageUrl", "photoUrl"};
    /** String ("French, Cafe") or list of strings. */
    static final String[] CUISINE = {"cuisines", "cuisine", "cuisineTypes", "cuisineType", "categories", "category"};

    /** City / area values: a catalogue ID ("karachi", "karachi-clifton") or a name ("Clifton"). */
    static final String[] CITY = {"cityId", "city", "cityName", "city_name"};
    static final String[] AREA = {"areaId", "area", "areaName", "area_name", "locality", "neighbourhood", "neighborhood"};
    static final String[] ADDRESS = {"address", "fullAddress", "streetAddress", "addressLine", "address_line"};
    /** Maps that may contain city / area / address / coordinates, e.g. location: {city, area, lat, lng}. */
    static final String[] NESTED = {"location", "address", "addressInfo", "branch"};

    /** GeoPoint, or a map {lat|latitude, lng|longitude}. */
    static final String[] GEO = {"geo", "geopoint", "geoPoint", "coordinates", "location", "position", "latLng"};
    static final String[] LAT = {"latitude", "lat"};
    static final String[] LNG = {"longitude", "lng", "lon", "long"};

    /** Registration / enabled booleans (true = shown to diners). */
    static final String[] ENABLED = {"isEnabled", "enabled", "isApproved", "approved"};
    /** Inverted booleans (true = hidden). */
    static final String[] DISABLED = {"isDisabled", "disabled", "isSuspended", "suspended", "isBlocked", "isDeleted"};
    /** Ambiguous booleans, interpreted by ACTIVE_FIELD_MEANING. */
    static final String[] ACTIVE = {"isActive", "active"};
    /** Account status strings (e.g. "approved", "suspended"). */
    static final String[] ACCOUNT_STATUS = {"accountStatus", "approvalStatus", "registrationStatus", "status"};

    /** Open-right-now booleans. */
    static final String[] OPEN_NOW = {"isOpen", "isOpenNow", "openNow", "open", "currentlyOpen"};
    /** Open/closed strings (e.g. "open", "closed"). */
    static final String[] OPEN_STATUS = {"operationalStatus", "openStatus", "businessStatus", "status"};
    /** Accepting diners / walk-ins right now. */
    static final String[] ACCEPTING = {"acceptingDiners", "isAcceptingDiners", "acceptingGuests", "isAccepting", "acceptingWalkIns"};

    /** Aggregate number of free tables right now. */
    static final String[] FREE_TABLES = {"availableTables", "freeTables", "tablesAvailable", "available_tables",
            "free_tables", "availableTableCount", "vacantTables"};
    static final String[] TOTAL_TABLES = {"totalTables", "tableCount", "tablesTotal", "total_tables"};
    /** Table records stored INSIDE the restaurant document: a list of maps (counted on the phone). */
    static final String[] TABLE_LIST = {"tables"};
    /** Inside each table map: a boolean meaning free, or a status string. */
    static final String[] TABLE_FREE_FLAG = {"isAvailable", "available", "isFree", "free"};
    static final String[] TABLE_BUSY_FLAG = {"isOccupied", "occupied", "isReserved", "reserved"};
    static final String[] TABLE_STATUS = {"status", "state"};

    /** Current waiting time in minutes. */
    static final String[] WAIT_MINUTES = {"waitMinutes", "waitTime", "waitingTime", "estimatedWait", "wait_minutes"};
    static final String[] FEATURED = {"isFeatured", "featured"};
    /** When availability / the document was last updated: Timestamp, Date, or epoch millis/seconds. */
    static final String[] UPDATED_AT = {"availabilityUpdatedAt", "tablesUpdatedAt", "lastAvailabilityUpdate",
            "liveUpdatedAt", "updatedAt", "lastUpdated", "updated_at"};
    static final String[] PHONE = {"phone", "phoneNumber", "contactNumber", "contactPhone"};

    // ------------------------------------------------------------------ status words (lowercase)

    static final String[] WORDS_ACCOUNT_ON = {"approved", "active", "enabled", "verified", "live", "published"};
    static final String[] WORDS_ACCOUNT_OFF = {"suspended", "disabled", "rejected", "blocked", "deleted",
            "inactive", "banned", "pending", "draft"};
    static final String[] WORDS_OPEN = {"open", "opened", "open_now", "opennow"};
    static final String[] WORDS_CLOSED = {"closed", "close", "closed_now", "temporarily_closed", "closedtemporarily"};
    static final String[] WORDS_TABLE_FREE = {"free", "available", "vacant", "open", "empty"};
    static final String[] WORDS_TABLE_BUSY = {"occupied", "reserved", "busy", "taken", "booked", "full", "unavailable"};
}