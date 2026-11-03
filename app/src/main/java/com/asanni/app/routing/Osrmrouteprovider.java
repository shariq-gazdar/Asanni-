package com.asanni.app.routing;

import org.json.JSONObject;

/**
 * OSRM public demo server (FOSSGIS). No key. Rules: at most 1 request per second, identify the
 * app with a User-Agent, show OSRM + OpenStreetMap attribution, reasonable non-commercial use.
 * No uptime guarantee, so it is a fallback, not the only provider.
 *
 * Location: app/src/main/java/com/asanni/app/routing/OsrmRouteProvider.java  (NEW)
 */
final class OsrmRouteProvider implements RouteProvider {

    private static final String BASE = "https://router.project-osrm.org/route/v1/driving/";

    private static final Object LOCK = new Object();
    private static long lastRequestAt = 0L;

    @Override public String name() { return "OSRM"; }

    @Override public boolean isConfigured() { return true; }

    /** The demo server's main host only routes cars reliably. */
    @Override public boolean supports(Routeconfig.Profile profile) {
        return profile == Routeconfig.Profile.DRIVING;
    }

    @Override
    public Route route(double fromLat, double fromLng, double toLat, double toLng,
                       Routeconfig.Profile profile) throws Exception {
        throttle();
        // OSRM wants longitude,latitude
        String url = BASE
                + RoutingHttp.num(fromLng) + "," + RoutingHttp.num(fromLat) + ";"
                + RoutingHttp.num(toLng) + "," + RoutingHttp.num(toLat)
                + "?overview=full&geometries=geojson&alternatives=false&steps=false";

        JSONObject json = RoutingHttp.getJson(url, null);
        if (!"Ok".equals(json.optString("code"))) {
            throw new IllegalStateException("OSRM: " + json.optString("code") + " " + json.optString("message"));
        }
        JSONObject r = json.getJSONArray("routes").getJSONObject(0);
        return new Route(
                r.getDouble("distance"),
                r.getDouble("duration"),
                RoutingHttp.lngLatToPoints(r.getJSONObject("geometry").getJSONArray("coordinates")),
                name(),
                "Routes © OSRM · Map data © OpenStreetMap contributors (ODbL)",
                false);
    }

    /** Runs on the background thread, so sleeping here never freezes the UI. */
    private static void throttle() throws InterruptedException {
        synchronized (LOCK) {
            long wait = lastRequestAt + Routeconfig.OSRM_MIN_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastRequestAt = System.currentTimeMillis();
        }
    }
}