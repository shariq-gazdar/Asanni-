package com.asanni.app.restaurants;

/**
 * ONE definition of a restaurant's availability state, used by the badges (Discover, See All,
 * details) AND the map markers, so a green "8 free" badge and a green "8" marker always agree.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/Availability.java  (NEW in phase 7)
 *
 *   FREE      current free-table count > 0
 *   WAIT      no free table (or no count) but a fresh waiting time is reported
 *   FULL      current free-table count is exactly 0 (and open / open status unknown)
 *   CLOSED    the restaurant reports it is closed
 *   UNKNOWN   no live data (registered restaurant, nothing reported yet)
 */
public final class Availability {

    public enum Kind { FREE, WAIT, FULL, CLOSED, UNKNOWN }

    private Availability() {}

    public static Kind of(Restaurant r, long now) {
        if (r.hasCurrentAvailability(now)) {
            if (r.freeTables > 0) return Kind.FREE;
            if (r.waitMinutes != null && r.waitMinutes > 0) return Kind.WAIT;
            return Boolean.FALSE.equals(r.openNow) ? Kind.CLOSED : Kind.FULL;
        }
        if (Boolean.FALSE.equals(r.openNow)) return Kind.CLOSED;
        if (r.waitMinutes != null && r.waitMinutes > 0 && isFresh(r, now)) return Kind.WAIT;
        return Kind.UNKNOWN;
    }

    /** No timestamp = can't tell (allowed); a timestamp older than the window = stale. */
    public static boolean isFresh(Restaurant r, long now) {
        return r.updatedAtMillis == null || now - r.updatedAtMillis <= RestaurantSchema.AVAILABILITY_FRESH_MS;
    }
}