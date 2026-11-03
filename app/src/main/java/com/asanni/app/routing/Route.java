package com.asanni.app.routing;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One route from the diner to a restaurant.
 *
 * Location: app/src/main/java/com/asanni/app/routing/Route.java  (NEW)
 *
 * points: each element is {latitude, longitude}, already in the right order for drawing.
 * approximate = true means no routing service answered: points is just the straight line between
 * the two ends, distance is straight-line distance and duration is unknown (-1).
 */
public final class Route {

    public final double distanceMeters;
    /** Seconds, or -1 when unknown. */
    public final double durationSeconds;
    public final List<double[]> points;
    /** Which service produced it, e.g. "GraphHopper", "OSRM", "openrouteservice", "straight line". */
    public final String source;
    /** Text that must be shown near the map (licence requirement of the routing data). */
    public final String attribution;
    public final boolean approximate;

    Route(double distanceMeters, double durationSeconds, List<double[]> points,
          String source, String attribution, boolean approximate) {
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.points = Collections.unmodifiableList(points);
        this.source = source;
        this.attribution = attribution;
        this.approximate = approximate;
    }

    /** "850 m", "3.4 km" (prefixed with "≈ " for a straight-line estimate). */
    public String distanceText() {
        String text = distanceMeters < 1000
                ? String.format(Locale.US, "%d m", Math.round(distanceMeters))
                : String.format(Locale.US, "%.1f km", distanceMeters / 1000.0);
        return approximate ? "≈ " + text : text;
    }

    /** "12 min", "1 h 5 min", or "" when unknown. */
    public String durationText() {
        if (durationSeconds < 0) return "";
        long minutes = Math.max(1, Math.round(durationSeconds / 60.0));
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}