package com.asanni.app.restaurants;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One restaurant, read from a desktop-app document. Every field except id and name may be null
 * (= unknown). Unknown is never turned into a made-up value: no "0 tables", no default cuisine.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/Restaurant.java  (NEW in phase 6)
 *
 * Three separate ideas are kept apart on purpose:
 *   enabled     the restaurant account is registered / allowed in the system (false = hidden)
 *   openNow     the restaurant says it is open right now
 *   freeTables  live table availability (+ availabilityUpdatedAt for freshness)
 */
public final class Restaurant {

    public final String id;
    public final String name;
    /** https URL or base64 / data:image text; null = use the placeholder. */
    public final String logo;
    public final String cover;
    public final List<String> cuisines;
    /** Raw location values as stored by the desktop app (ID or name); used for matching. */
    public final String cityRaw;
    public final String areaRaw;
    public final String address;
    public final Double latitude;
    public final Double longitude;
    /** null = unknown. false = explicitly disabled, never shown to diners. */
    public final Boolean enabled;
    public final Boolean openNow;
    public final Boolean acceptingDiners;
    /** Number of free tables reported by the restaurant; null = not reported. */
    public final Integer freeTables;
    public final Integer totalTables;
    public final Integer waitMinutes;
    public final boolean featured;
    /** Epoch millis of the last availability/document update; null = unknown. */
    public final Long updatedAtMillis;
    public final String phone;
    /** PHASE 8: price in PKR (null = not given / invalid). */
    public final Double price;
    /** PHASE 8: true = per person, false = not per-head pricing, null = not given. */
    public final Boolean perHead;
    /** PHASE 8: development test data (fixture or isTestData = true). Never a real business. */
    public final boolean isTestData;
    /** Field names found in the document (for the Logcat schema report). */
    final Set<String> fieldNames;

    private Restaurant(Builder b) {
        id = b.id;
        name = b.name;
        logo = b.logo;
        cover = b.cover;
        cuisines = Collections.unmodifiableList(b.cuisines);
        cityRaw = b.cityRaw;
        areaRaw = b.areaRaw;
        address = b.address;
        latitude = b.latitude;
        longitude = b.longitude;
        enabled = b.enabled;
        openNow = b.openNow;
        acceptingDiners = b.accepting;
        freeTables = b.freeTables;
        totalTables = b.totalTables;
        waitMinutes = b.waitMinutes;
        featured = b.featured;
        updatedAtMillis = b.updatedAt;
        phone = b.phone;
        price = b.price;
        perHead = b.perHead;
        isTestData = b.testData;
        fieldNames = b.fieldNames;
    }

    // ------------------------------------------------------------------ derived

    /** Explicitly disabled / suspended accounts are never shown to diners. */
    public boolean isHiddenFromDiners() {
        return Boolean.FALSE.equals(enabled);
    }

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    /** True when a free-table count exists and is recent enough to show (see RestaurantSchema). */
    public boolean hasCurrentAvailability(long nowMillis) {
        if (freeTables == null) return false;
        if (updatedAtMillis == null) return !RestaurantSchema.REQUIRE_AVAILABILITY_TIMESTAMP;
        long age = nowMillis - updatedAtMillis;
        return age >= -5 * 60_000L && age <= RestaurantSchema.AVAILABILITY_FRESH_MS; // small clock skew ok
    }

    /** Fresh availability WITH a timestamp: the strongest "live" signal. */
    public boolean hasTimestampedLiveAvailability(long nowMillis) {
        return updatedAtMillis != null && hasCurrentAvailability(nowMillis);
    }

    /** "French · Cafe", or null when unknown. */
    public String cuisineText() {
        if (cuisines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cuisines.size() && i < 3; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(cuisines.get(i));
        }
        return sb.toString();
    }

    /** Human area/city text as stored ("Clifton"), never an internal ID like "karachi-clifton". */
    public String areaLabel() {
        String a = readable(areaRaw);
        return a != null ? a : readable(cityRaw);
    }

    private static String readable(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String t = raw.trim();
        // Looks like a catalogue ID (all lowercase, no spaces): the screen shows the catalogue name instead.
        if (t.matches("[a-z0-9]+(-[a-z0-9]+)*")) return null; // e.g. "karachi" / "karachi-clifton"
        return t;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Builds a restaurant from a document's data. Returns null when the document has no usable
     * name (logged and skipped by the repository; one bad document never breaks the page).
     */
    public static Restaurant parse(String id, Map<String, Object> data) {
        if (id == null || data == null) return null;
        Builder b = new Builder();
        b.id = id;
        b.fieldNames = new LinkedHashSet<>(data.keySet());
        b.name = text(first(data, RestaurantSchema.NAME));
        if (b.name == null) return null;
        if (b.name.length() > 80) b.name = b.name.substring(0, 80).trim();

        b.logo = image(first(data, RestaurantSchema.LOGO));
        b.cover = image(first(data, RestaurantSchema.COVER));
        b.cuisines = cuisines(first(data, RestaurantSchema.CUISINE));

        // Location: top-level first, then inside nested maps such as location{} / address{}.
        b.cityRaw = text(first(data, RestaurantSchema.CITY));
        b.areaRaw = text(first(data, RestaurantSchema.AREA));
        Object addr = first(data, RestaurantSchema.ADDRESS);
        b.address = addr instanceof String ? text(addr) : null;
        for (String key : RestaurantSchema.NESTED) {
            Object nested = data.get(key);
            if (!(nested instanceof Map)) continue;
            Map<String, Object> m = asMap(nested);
            if (b.cityRaw == null) b.cityRaw = text(first(m, RestaurantSchema.CITY));
            if (b.areaRaw == null) b.areaRaw = text(first(m, RestaurantSchema.AREA));
            if (b.address == null) {
                Object a = first(m, RestaurantSchema.ADDRESS);
                if (a == null) a = first(m, new String[]{"line1", "street", "formatted"});
                b.address = text(a);
            }
            if (b.latitude == null) readCoordinates(m, b);
        }
        readGeo(data, b);
        if (b.latitude == null) readCoordinates(data, b);

        // Registration / enabled (never confused with "open now").
        Boolean enabled = bool(first(data, RestaurantSchema.ENABLED));
        Boolean disabled = bool(first(data, RestaurantSchema.DISABLED));
        Boolean active = bool(first(data, RestaurantSchema.ACTIVE));
        Boolean accountWord = word(first(data, RestaurantSchema.ACCOUNT_STATUS),
                RestaurantSchema.WORDS_ACCOUNT_ON, RestaurantSchema.WORDS_ACCOUNT_OFF);
        if (Boolean.TRUE.equals(disabled) || Boolean.FALSE.equals(enabled) || Boolean.FALSE.equals(accountWord)) {
            b.enabled = false;
        } else if (Boolean.TRUE.equals(enabled) || Boolean.TRUE.equals(accountWord)) {
            b.enabled = true;
        }

        // Open right now.
        b.openNow = bool(first(data, RestaurantSchema.OPEN_NOW));
        if (b.openNow == null) {
            b.openNow = word(first(data, RestaurantSchema.OPEN_STATUS),
                    RestaurantSchema.WORDS_OPEN, RestaurantSchema.WORDS_CLOSED);
        }
        if (active != null) {
            if (RestaurantSchema.ACTIVE_FIELD_MEANING == RestaurantSchema.ActiveMeaning.OPEN_NOW) {
                if (b.openNow == null) b.openNow = active;
            } else if (!active) {
                b.enabled = false;
            } else if (b.enabled == null) {
                b.enabled = true;
            }
        }
        b.accepting = bool(first(data, RestaurantSchema.ACCEPTING));

        // Tables: an aggregate count, or table records inside the document.
        b.freeTables = count(first(data, RestaurantSchema.FREE_TABLES));
        b.totalTables = count(first(data, RestaurantSchema.TOTAL_TABLES));
        if (b.freeTables == null) countTableList(first(data, RestaurantSchema.TABLE_LIST), b);
        if (b.freeTables != null && b.totalTables != null && b.freeTables > b.totalTables) {
            b.totalTables = null; // inconsistent: keep the free count, drop the total
        }

        Integer wait = count(first(data, RestaurantSchema.WAIT_MINUTES));
        b.waitMinutes = wait != null && wait <= 24 * 60 ? wait : null;
        b.featured = Boolean.TRUE.equals(bool(first(data, RestaurantSchema.FEATURED)));
        b.updatedAt = millis(first(data, RestaurantSchema.UPDATED_AT));
        b.phone = phone(first(data, RestaurantSchema.PHONE));
        // PHASE 8
        Double price = number(first(data, RestaurantSchema.PRICE));
        b.price = price != null && !price.isNaN() && !price.isInfinite() && price >= 0 && price < 10_000_000 ? price : null;
        b.perHead = bool(first(data, RestaurantSchema.PER_HEAD)); // missing stays null (never "true")
        b.testData = Boolean.TRUE.equals(bool(first(data, RestaurantSchema.TEST_MARKER)));
        return new Restaurant(b);
    }

    // ------------------------------------------------------------------ safe readers

    private static Object first(Map<String, Object> m, String[] keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !(v instanceof String && ((String) v).trim().isEmpty())) return v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    static String text(Object o) {
        if (o instanceof String) {
            String s = ((String) o).trim().replaceAll("\\s+", " ");
            return s.isEmpty() ? null : s;
        }
        if (o instanceof Map) { // e.g. city: {id, name}
            Map<String, Object> m = asMap(o);
            Object v = first(m, new String[]{"name", "id", "value", "title"});
            return v instanceof String ? text(v) : null;
        }
        return null;
    }

    private static String image(Object o) {
        if (o instanceof Map) o = first(asMap(o), new String[]{"url", "downloadUrl", "src", "base64", "data"});
        if (!(o instanceof String)) return null;
        String s = ((String) o).trim();
        if (s.startsWith("data:image/")) return s;
        // A full https URL WITH a host ("https://" alone or "coverphoto.png" can't be downloaded).
        if (s.startsWith("https://")) return s.length() > 8 && s.substring(8).matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}([:/?#].*)?") ? s : null;
        if (s.startsWith("http://")) return null; // cleartext is blocked on Android 9+; don't try
        if (s.length() > 100 && s.matches("[A-Za-z0-9+/=\\r\\n]+")) return s; // raw base64
        return null; // e.g. gs:// paths or local file names: can't be loaded without Storage access
    }

    private static List<String> cuisines(Object o) {
        Set<String> out = new LinkedHashSet<>();
        if (o instanceof String) {
            for (String part : ((String) o).split("[,/|;·]")) addCuisine(out, part);
        } else if (o instanceof List) {
            for (Object item : (List<?>) o) {
                String t = text(item);
                if (t != null) addCuisine(out, t);
            }
        }
        return new ArrayList<>(out);
    }

    private static void addCuisine(Set<String> out, String raw) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > 30) return;
        // "italian" -> "Italian"; keep "BBQ" / "Pan-Asian" as written.
        if (t.equals(t.toLowerCase(Locale.ROOT))) {
            t = t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
        }
        out.add(t);
    }

    static Boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) {
            long v = ((Number) o).longValue();
            if (v == 0) return false;
            if (v == 1) return true;
            return null;
        }
        if (o instanceof String) {
            String s = ((String) o).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("yes") || s.equals("1")) return true;
            if (s.equals("false") || s.equals("no") || s.equals("0")) return false;
        }
        return null;
    }

    private static Boolean word(Object o, String[] yes, String[] no) {
        if (!(o instanceof String)) return null;
        String s = ((String) o).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        for (String w : yes) if (s.equals(w)) return true;
        for (String w : no) if (s.equals(w)) return false;
        return null; // unexpected value: unknown, not a guess
    }

    /** Non-negative whole number (Number or numeric text), else null. */
    static Integer count(Object o) {
        long v;
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || d < 0 || d > 10_000 || d != Math.floor(d)) return null;
            v = (long) d;
        } else if (o instanceof String && ((String) o).trim().matches("\\d{1,5}")) {
            v = Long.parseLong(((String) o).trim());
        } else {
            return null;
        }
        return v <= 10_000 ? (int) v : null;
    }

    private static void countTableList(Object o, Builder b) {
        if (!(o instanceof List) || ((List<?>) o).isEmpty()) return;
        int free = 0;
        int known = 0;
        int total = 0;
        for (Object item : (List<?>) o) {
            if (!(item instanceof Map)) continue;
            total++;
            Map<String, Object> t = asMap(item);
            Boolean isFree = bool(first(t, RestaurantSchema.TABLE_FREE_FLAG));
            if (isFree == null) {
                Boolean busy = bool(first(t, RestaurantSchema.TABLE_BUSY_FLAG));
                if (busy != null) isFree = !busy;
            }
            if (isFree == null) {
                isFree = word(first(t, RestaurantSchema.TABLE_STATUS),
                        RestaurantSchema.WORDS_TABLE_FREE, RestaurantSchema.WORDS_TABLE_BUSY);
            }
            if (isFree != null) {
                known++;
                if (isFree) free++;
            }
        }
        // Only trust the count when EVERY table record says whether it is free.
        if (total > 0 && known == total) {
            b.freeTables = free;
            if (b.totalTables == null) b.totalTables = total;
        }
    }

    private static void readGeo(Map<String, Object> data, Builder b) {
        for (String k : RestaurantSchema.GEO) {
            Object v = data.get(k);
            if (v instanceof GeoPoint) {
                GeoPoint g = (GeoPoint) v;
                setCoordinates(b, g.getLatitude(), g.getLongitude());
            } else if (v instanceof Map) {
                readCoordinates(asMap(v), b);
            }
            if (b.latitude != null) return;
        }
    }

    private static void readCoordinates(Map<String, Object> m, Builder b) {
        Object lat = first(m, RestaurantSchema.LAT);
        Object lng = first(m, RestaurantSchema.LNG);
        Double la = number(lat);
        Double lo = number(lng);
        if (la != null && lo != null) setCoordinates(b, la, lo);
    }

    private static Double number(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static void setCoordinates(Builder b, double lat, double lng) {
        boolean valid = !Double.isNaN(lat) && !Double.isNaN(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(lat == 0 && lng == 0); // 0,0 is almost always a missing value
        if (valid) {
            b.latitude = lat;
            b.longitude = lng;
        }
    }

    static Long millis(Object o) {
        if (o instanceof Timestamp) return ((Timestamp) o).toDate().getTime();
        if (o instanceof Date) return ((Date) o).getTime();
        if (o instanceof Number) {
            long v = ((Number) o).longValue();
            if (v <= 0) return null;
            return v < 100_000_000_000L ? v * 1000L : v; // seconds or millis
        }
        return null;
    }

    private static String phone(Object o) {
        if (o instanceof Number) o = String.valueOf(((Number) o).longValue());
        if (!(o instanceof String)) return null;
        String s = ((String) o).trim();
        String digits = s.replaceAll("[^0-9+]", "");
        return digits.replace("+", "").length() >= 7 && digits.length() <= 16 ? digits : null;
    }

    private static final class Builder {
        String id;
        String name;
        String logo;
        String cover;
        List<String> cuisines = new ArrayList<>();
        String cityRaw;
        String areaRaw;
        String address;
        Double latitude;
        Double longitude;
        Boolean enabled;
        Boolean openNow;
        Boolean accepting;
        Integer freeTables;
        Integer totalTables;
        Integer waitMinutes;
        boolean featured;
        Long updatedAt;
        String phone;
        Double price;
        Boolean perHead;
        boolean testData;
        Set<String> fieldNames;
    }
}