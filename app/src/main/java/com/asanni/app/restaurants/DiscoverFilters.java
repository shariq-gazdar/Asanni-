package com.asanni.app.restaurants;

/**
 * The Discover / See All filter state. Kept as one small object so more filters (cuisine,
 * distance, live tables, ...) can be added later without touching the screens' plumbing.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/DiscoverFilters.java  (NEW in phase 6)
 *
 * STATUS semantics (see GUIDE.md):
 *   ACTIVE      the restaurant currently reports that it is OPEN (openNow == true)
 *   NOT_ACTIVE  the restaurant currently reports that it is CLOSED (openNow == false)
 *   ALL         no status restriction (restaurants that don't report open/closed appear only here)
 * Registration is separate: disabled/suspended restaurants are never shown at all.
 */
public final class DiscoverFilters {

    public enum Status { ALL, ACTIVE, NOT_ACTIVE }

    public final Status status;

    // [FUTURE FILTERS] add fields here (e.g. Set<String> cuisines, Double maxDistanceKm,
    // boolean liveTablesOnly), handle them in RestaurantQuery.passes(), and add rows to the sheet.

    private DiscoverFilters(Status status) {
        this.status = status == null ? Status.ALL : status;
    }

    public static DiscoverFilters defaults() {
        return new DiscoverFilters(Status.ALL);
    }

    public DiscoverFilters withStatus(Status s) {
        return new DiscoverFilters(s);
    }

    /** Number of non-default filters (shown as a dot / in the accessibility label). */
    public int activeCount() {
        return status == Status.ALL ? 0 : 1;
    }

    public String encode() {
        return status.name();
    }

    public static DiscoverFilters decode(String s) {
        if (s != null) {
            for (Status st : Status.values()) {
                if (st.name().equals(s)) return new DiscoverFilters(st);
            }
        }
        return defaults();
    }
}