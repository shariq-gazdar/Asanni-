package com.asanni.app.routing;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * GraphHopper Directions API (cloud). Free plan: 500 credits/day, non-commercial use;
 * a simple A-to-B route costs 1 credit.
 *
 * Location: app/src/main/java/com/asanni/app/routing/GraphHopperRouteProvider.java  (NEW)
 */
final class GraphHopperRouteProvider implements RouteProvider {

    private static final String BASE = "https://graphhopper.com/api/1/route";

    @Override public String name() { return "GraphHopper"; }

    @Override public boolean isConfigured() {
        return !Routeconfig.GRAPHHOPPER_API_KEY.trim().isEmpty();
    }

    @Override public boolean supports(Routeconfig.Profile profile) { return true; }

    @Override
    public Route route(double fromLat, double fromLng, double toLat, double toLng,
                       Routeconfig.Profile profile) throws Exception {
        String ghProfile = profile == Routeconfig.Profile.WALKING ? "foot" : "car";
        String url = BASE
                + "?point=" + RoutingHttp.num(fromLat) + "," + RoutingHttp.num(fromLng)
                + "&point=" + RoutingHttp.num(toLat) + "," + RoutingHttp.num(toLng)
                + "&profile=" + ghProfile
                + "&points_encoded=false&instructions=false&calc_points=true"
                + "&key=" + URLEncoder.encode(Routeconfig.GRAPHHOPPER_API_KEY.trim(), "UTF-8");

        JSONObject json = RoutingHttp.getJson(url, null);
        JSONObject path = json.getJSONArray("paths").getJSONObject(0);
        return new Route(
                path.getDouble("distance"),
                path.getDouble("time") / 1000.0,   // GraphHopper returns milliseconds
                RoutingHttp.lngLatToPoints(path.getJSONObject("points").getJSONArray("coordinates")),
                name(),
                "Routes © GraphHopper · Map data © OpenStreetMap contributors",
                false);
    }
}