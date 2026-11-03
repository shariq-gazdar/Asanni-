package com.asanni.app.restaurants;

import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.TextNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure logic shared by Discover and See All: location matching, status filter, search and the
 * deterministic ranking. No Android or network code here (unit-testable).
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/RestaurantQuery.java  (NEW in phase 6)
 */
public final class RestaurantQuery {

    private RestaurantQuery() {}

    // ------------------------------------------------------------------ location

    /** Restaurants in the selected city/area, without disabled accounts or duplicate IDs. */
    public static List<Restaurant> inScope(List<Restaurant> all, LocationScope scope) {
        List<Restaurant> out = new ArrayList<>();
        if (all == null || scope == null) return out;
        Set<String> seen = new HashSet<>();
        for (Restaurant r : all) {
            if (r == null || r.isHiddenFromDiners() || !seen.add(r.id)) continue;
            if (!matchesCity(r, scope.city)) continue;
            if (!scope.isWholeCity() && !matchesArea(r, scope.city, scope.area)) continue;
            out.add(r);
        }
        return out;
    }

    /**
     * City match on the restaurant's city value (catalogue ID, name or alias, any capitalisation).
     * Legacy records without a city field match only if their address names the city.
     */
    static boolean matchesCity(Restaurant r, CatalogueItem city) {
        if (city == null) return false;
        if (r.cityRaw != null) return matchesItem(TextNormalizer.normalize(r.cityRaw), city, null);
        return r.address != null && containsName(TextNormalizer.normalize(r.address), city);
    }

    /**
     * Area match on the restaurant's area value ("karachi-clifton", "clifton", "Clifton").
     * Legacy records without an area field match only if their address names the area.
     */
    static boolean matchesArea(Restaurant r, CatalogueItem city, CatalogueItem area) {
        if (area == null) return true;
        if (r.areaRaw != null) return matchesItem(TextNormalizer.normalize(r.areaRaw), area, city);
        return r.address != null && containsName(TextNormalizer.normalize(r.address), area);
    }

    private static boolean matchesItem(String value, CatalogueItem item, CatalogueItem parentCity) {
        if (value.isEmpty()) return false;
        if (value.equals(TextNormalizer.normalize(item.id))) return true;
        if (parentCity != null && item.id.startsWith(parentCity.id + "-")
                && value.equals(TextNormalizer.normalize(item.id.substring(parentCity.id.length() + 1)))) {
            return true;
        }
        return item.matchesExactly(value);
    }

    private static boolean containsName(String normalizedText, CatalogueItem item) {
        if (item.normalizedName.length() >= 4 && normalizedText.contains(item.normalizedName)) return true;
        for (String a : item.normalizedAliases) {
            if (a.length() >= 4 && normalizedText.contains(a)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ filters + search

    /** True when at least one restaurant reports open/closed, so Active/Not active mean something. */
    public static boolean supportsStatusFilter(List<Restaurant> scoped) {
        for (Restaurant r : scoped) if (r.openNow != null) return true;
        return false;
    }

    public static List<Restaurant> apply(List<Restaurant> scoped, DiscoverFilters f, String query) {
        List<Restaurant> out = new ArrayList<>();
        List<String> tokens = tokens(query);
        for (Restaurant r : scoped) {
            if (passes(r, f) && matchesSearch(r, tokens)) out.add(r);
        }
        return out;
    }

    static boolean passes(Restaurant r, DiscoverFilters f) {
        if (f == null) return true;
        switch (f.status) {
            case ACTIVE:
                return Boolean.TRUE.equals(r.openNow);
            case NOT_ACTIVE:
                return Boolean.FALSE.equals(r.openNow);
            default:
                return true;
        }
    }

    private static List<String> tokens(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String part : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            String n = TextNormalizer.normalize(part);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** Every word of the query must appear in the name, a cuisine, the area or the address. */
    static boolean matchesSearch(Restaurant r, List<String> tokens) {
        if (tokens.isEmpty()) return true;
        StringBuilder hay = new StringBuilder(TextNormalizer.normalize(r.name)).append('|');
        for (String c : r.cuisines) hay.append(TextNormalizer.normalize(c)).append('|');
        if (r.areaRaw != null) hay.append(TextNormalizer.normalize(r.areaRaw)).append('|');
        if (r.address != null) hay.append(TextNormalizer.normalize(r.address));
        String h = hay.toString();
        for (String t : tokens) if (!h.contains(t)) return false;
        return true;
    }

    // ------------------------------------------------------------------ ranking

    /**
     * Deterministic order (same data = same order, never random). Most relevant first:
     *   1. current live table availability with at least one free table (timestamped first)
     *   2. open now (known open > unknown > known closed)
     *   3. featured flag from the desktop data
     *   4. more free tables (only counts that are current)
     *   5. closer to the user's GPS position (only when known)
     *   6. more recently updated availability
     *   7. name, then document ID (stable tie-break)
     */
    public static void rank(List<Restaurant> list, final Double userLat, final Double userLng, final long now) {
        Collections.sort(list, new Comparator<Restaurant>() {
            @Override
            public int compare(Restaurant a, Restaurant b) {
                int c = Integer.compare(liveTier(b, now), liveTier(a, now));
                if (c != 0) return c;
                c = Integer.compare(openTier(b), openTier(a));
                if (c != 0) return c;
                c = Boolean.compare(b.featured, a.featured);
                if (c != 0) return c;
                c = Integer.compare(currentFree(b, now), currentFree(a, now));
                if (c != 0) return c;
                Double da = distanceKm(a, userLat, userLng);
                Double db = distanceKm(b, userLat, userLng);
                if (da != null || db != null) {
                    if (da == null) return 1;
                    if (db == null) return -1;
                    c = Double.compare(da, db);
                    if (c != 0) return c;
                }
                c = Long.compare(freshness(b, now), freshness(a, now));
                if (c != 0) return c;
                c = a.name.compareToIgnoreCase(b.name);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
    }

    private static int liveTier(Restaurant r, long now) {
        if (!r.hasCurrentAvailability(now) || r.freeTables == null || r.freeTables <= 0) return 0;
        return r.hasTimestampedLiveAvailability(now) ? 2 : 1;
    }

    private static int openTier(Restaurant r) {
        if (r.openNow == null) return 1;
        return r.openNow ? 2 : 0;
    }

    private static int currentFree(Restaurant r, long now) {
        return r.hasCurrentAvailability(now) && r.freeTables != null ? r.freeTables : -1;
    }

    private static long freshness(Restaurant r, long now) {
        return r.hasCurrentAvailability(now) && r.updatedAtMillis != null ? r.updatedAtMillis : 0L;
    }

    // ------------------------------------------------------------------ distance

    /** Great-circle distance in km from the user's GPS position; null when either side is unknown. */
    public static Double distanceKm(Restaurant r, Double userLat, Double userLng) {
        if (userLat == null || userLng == null || !r.hasCoordinates()) return null;
        double dLat = Math.toRadians(r.latitude - userLat);
        double dLng = Math.toRadians(r.longitude - userLng);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(userLat)) * Math.cos(Math.toRadians(r.latitude))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    // ------------------------------------------------------------------ report

    /** Numbers for the live report card. All counted from real documents in the selected area. */
    public static final class Stats {
        /** Registered (not disabled) restaurants in the selected area. */
        public final int total;
        /** Of those, how many say they are open right now / how many report open-or-closed at all. */
        public final int openNow;
        public final int reportingOpenStatus;
        /** Of those, how many report a CURRENT free-table count. */
        public final int withLiveAvailability;

        Stats(int total, int openNow, int reportingOpenStatus, int withLiveAvailability) {
            this.total = total;
            this.openNow = openNow;
            this.reportingOpenStatus = reportingOpenStatus;
            this.withLiveAvailability = withLiveAvailability;
        }
    }

    public static Stats stats(List<Restaurant> scoped, long now) {
        int open = 0;
        int reporting = 0;
        int live = 0;
        for (Restaurant r : scoped) {
            if (r.openNow != null) {
                reporting++;
                if (r.openNow) open++;
            }
            if (r.hasCurrentAvailability(now)) live++;
        }
        return new Stats(scoped.size(), open, reporting, live);
    }
}