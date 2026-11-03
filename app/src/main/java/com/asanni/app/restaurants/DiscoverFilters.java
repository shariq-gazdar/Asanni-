package com.asanni.app.restaurants;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * The ONE filter state shared by Discover, See All and Map (immutable; "with..." returns a copy).
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/DiscoverFilters.java
 *           (NEW in phase 6, MODIFIED in phase 7: Available now, Under 3 km, Cuisine)
 *
 * STATUS (see GUIDE.md):
 *   ACTIVE      the restaurant currently reports that it is OPEN (openNow == true)
 *   NOT_ACTIVE  the restaurant currently reports that it is CLOSED (openNow == false)
 *   ALL         no status restriction (restaurants that don't report open/closed appear only here)
 * Registration is separate: disabled/suspended restaurants are never shown at all.
 *
 * Phase 7 filters (each one only offered when real data supports it):
 *   availableNow   a CURRENT free-table count above 0 (same freshness rule as the badges)
 *   maxDistanceKm  within this distance of the phone's GPS position (only when GPS is known)
 *   cuisine        the restaurant lists this cuisine (case-insensitive)
 */
public final class DiscoverFilters {

    public enum Status { ALL, ACTIVE, NOT_ACTIVE }

    /** [CONFIG] Distance used by the "Under 3 km" chip. */
    public static final double NEAR_KM = 3.0;

    public final Status status;
    public final boolean availableNow;
    /** null = no distance limit. */
    public final Double maxDistanceKm;
    /** null = any cuisine. */
    public final String cuisine;

    // [FUTURE FILTERS] add fields here (e.g. price range when the desktop data has it), handle them
    // in RestaurantQuery.passes(), and add a row to FilterSheet / a chip on Discover.

    private DiscoverFilters(Status status, boolean availableNow, Double maxDistanceKm, String cuisine) {
        this.status = status == null ? Status.ALL : status;
        this.availableNow = availableNow;
        this.maxDistanceKm = maxDistanceKm;
        this.cuisine = cuisine == null || cuisine.trim().isEmpty() ? null : cuisine.trim();
    }

    public static DiscoverFilters defaults() {
        return new DiscoverFilters(Status.ALL, false, null, null);
    }

    public DiscoverFilters withStatus(Status s) {
        return new DiscoverFilters(s, availableNow, maxDistanceKm, cuisine);
    }

    public DiscoverFilters withAvailableNow(boolean on) {
        return new DiscoverFilters(status, on, maxDistanceKm, cuisine);
    }

    public DiscoverFilters withMaxDistanceKm(Double km) {
        return new DiscoverFilters(status, availableNow, km, cuisine);
    }

    public DiscoverFilters withCuisine(String c) {
        return new DiscoverFilters(status, availableNow, maxDistanceKm, c);
    }

    /** Number of non-default filters (shown as a dot / in the accessibility label). */
    public int activeCount() {
        int n = status == Status.ALL ? 0 : 1;
        if (availableNow) n++;
        if (maxDistanceKm != null) n++;
        if (cuisine != null) n++;
        return n;
    }

    public boolean isDefault() {
        return activeCount() == 0;
    }

    /** For Intent extras / saved state, e.g. "ACTIVE|1|3.0|French". */
    public String encode() {
        return status.name() + "|" + (availableNow ? "1" : "0") + "|"
                + (maxDistanceKm == null ? "" : String.valueOf(maxDistanceKm)) + "|"
                + (cuisine == null ? "" : enc(cuisine));
    }

    public static DiscoverFilters decode(String s) {
        if (s == null || s.isEmpty()) return defaults();
        String[] p = s.split("\\|", -1);
        Status st = Status.ALL;
        for (Status x : Status.values()) if (x.name().equals(p[0])) st = x;
        boolean live = p.length > 1 && "1".equals(p[1]);
        Double km = null;
        if (p.length > 2 && !p[2].isEmpty()) {
            try {
                km = Double.parseDouble(p[2]);
            } catch (NumberFormatException ignored) {
                km = null;
            }
        }
        String c = p.length > 3 && !p[3].isEmpty() ? dec(p[3]) : null;
        return new DiscoverFilters(st, live, km, c);
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String dec(String v) {
        try {
            return URLDecoder.decode(v, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }
}