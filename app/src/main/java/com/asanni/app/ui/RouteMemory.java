package com.asanni.app.ui;

import com.asanni.app.routing.Route;

import java.util.HashMap;
import java.util.Map;

/**
 * PHASE 9: the last REAL road route per restaurant, kept in memory after an explicit directions
 * press (Details "Get directions", Description "Open directions", or the Map's own buttons).
 * The Description page reuses it to show "2.1 km · around 9 min by car" WITHOUT a new routing
 * request - but only when it's recent and the phone is still near where the route started.
 * Straight-line (approximate) results are never stored.
 *
 * Location: app/src/main/java/com/asanni/app/ui/RouteMemory.java  (NEW in phase 9)
 * The routing package itself (routing/*) is untouched.
 */
final class RouteMemory {

    /** [CONFIG] Same lifetime as Routeservice's own cache. */
    static final long MAX_AGE_MS = 10 * 60_000L;
    /** [CONFIG] The phone may have moved this far from the route's start and still reuse it. */
    static final double MAX_MOVE_METERS = 150;

    static final class Entry {
        final Route route;
        final double fromLat;
        final double fromLng;
        final long at;

        Entry(Route route, double fromLat, double fromLng, long at) {
            this.route = route;
            this.fromLat = fromLat;
            this.fromLng = fromLng;
            this.at = at;
        }
    }

    private static final Map<String, Entry> BY_RESTAURANT = new HashMap<>();

    private RouteMemory() {}

    static synchronized void remember(String restaurantId, Route route, double fromLat, double fromLng) {
        if (restaurantId == null || route == null || route.approximate) return;
        BY_RESTAURANT.put(restaurantId, new Entry(route, fromLat, fromLng, System.currentTimeMillis()));
    }

    /** A reusable route from (about) this position, or null. Never guesses without a position. */
    static synchronized Entry find(String restaurantId, Double lat, Double lng, long now) {
        Entry e = restaurantId == null ? null : BY_RESTAURANT.get(restaurantId);
        if (e == null || lat == null || lng == null) return null;
        if (now - e.at > MAX_AGE_MS || now < e.at) return null;
        return meters(lat, lng, e.fromLat, e.fromLng) <= MAX_MOVE_METERS ? e : null;
    }

    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}