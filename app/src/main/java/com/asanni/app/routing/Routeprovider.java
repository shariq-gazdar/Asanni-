package com.asanni.app.routing;

/**
 * One routing service. Called on a BACKGROUND thread only (it does network I/O).
 *
 * Location: app/src/main/java/com/asanni/app/routing/RouteProvider.java  (NEW)
 */
interface RouteProvider {

    String name();

    /** False when a required key is missing; the provider is then skipped. */
    boolean isConfigured();

    boolean supports(Routeconfig.Profile profile);

    /** Returns a route or throws; never returns null. */
    Route route(double fromLat, double fromLng, double toLat, double toLng,
                Routeconfig.Profile profile) throws Exception;
}