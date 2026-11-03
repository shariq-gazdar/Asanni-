package com.asanni.app.map;

/**
 * Routing provider abstraction (phase 7). The Map screen only talks to this interface, so the
 * provider can be swapped (e.g. a traffic-aware paid service, or your own server) in one place.
 * Location: app/src/main/java/com/asanni/app/map/RouteService.java  (NEW in phase 7)
 */
public interface RouteService {

    enum Failure { NOT_CONFIGURED, OFFLINE, NO_ROUTE, LIMIT_REACHED, UNKNOWN }

    interface Callback {
        void onRoutes(RouteResult result);
        void onRouteFailed(Failure failure);
    }

    /** Asynchronous; the callback runs on the main thread. */
    void route(double fromLat, double fromLng, double toLat, double toLng, TravelMode mode, Callback callback);

    void cancel();
}