package com.asanni.app.routing;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * openrouteservice v2 directions. Kept so your existing key is used again automatically once
 * ORS is back online; while it is down, its errors are logged and the next provider is tried.
 *
 * Location: app/src/main/java/com/asanni/app/routing/OrsRouteProvider.java  (NEW)
 */
final class OrsRouteProvider implements RouteProvider {

    private static final String BASE = "https://api.openrouteservice.org/v2/directions/";

    @Override public String name() { return "openrouteservice"; }

    @Override public boolean isConfigured() {
        return !Routeconfig.ORS_API_KEY.trim().isEmpty();
    }

    @Override public boolean supports(Routeconfig.Profile profile) { return true; }

    @Override
    public Route route(double fromLat, double fromLng, double toLat, double toLng,
                       Routeconfig.Profile profile) throws Exception {
        String orsProfile = profile == Routeconfig.Profile.WALKING ? "foot-walking" : "driving-car";
        // ORS wants longitude,latitude
        String url = BASE + orsProfile
                + "?start=" + RoutingHttp.num(fromLng) + "," + RoutingHttp.num(fromLat)
                + "&end=" + RoutingHttp.num(toLng) + "," + RoutingHttp.num(toLat);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", Routeconfig.ORS_API_KEY.trim());
        headers.put("Accept", "application/geo+json, application/json");

        JSONObject json = RoutingHttp.getJson(url, headers);
        JSONObject feature = json.getJSONArray("features").getJSONObject(0);
        JSONObject summary = feature.getJSONObject("properties").getJSONObject("summary");
        return new Route(
                summary.optDouble("distance", 0),
                summary.optDouble("duration", -1),
                RoutingHttp.lngLatToPoints(feature.getJSONObject("geometry").getJSONArray("coordinates")),
                name(),
                "Routes © openrouteservice.org · Map data © OpenStreetMap contributors",
                false);
    }
}