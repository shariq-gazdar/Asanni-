package com.asanni.app.map;

import java.util.Collections;
import java.util.List;

/**
 * Routes returned by the routing provider, best first. Distance/duration are null when the
 * provider didn't send them (then they're not shown).
 * Location: app/src/main/java/com/asanni/app/map/RouteResult.java  (NEW in phase 7)
 */
public final class RouteResult {

    public static final class Route {
        /** [lng, lat] pairs along the road, exactly as returned. */
        public final List<double[]> coordinates;
        public final Double distanceMeters;
        public final Double durationSeconds;

        public Route(List<double[]> coordinates, Double distanceMeters, Double durationSeconds) {
            this.coordinates = Collections.unmodifiableList(coordinates);
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }
    }

    public final TravelMode mode;
    /** Sorted fastest first (by provider duration); index 0 is the recommended route. */
    public final List<Route> routes;
    /** Always false for openrouteservice: travel times don't include live traffic. */
    public final boolean trafficAware;

    public RouteResult(TravelMode mode, List<Route> routes, boolean trafficAware) {
        this.mode = mode;
        this.routes = Collections.unmodifiableList(routes);
        this.trafficAware = trafficAware;
    }
}