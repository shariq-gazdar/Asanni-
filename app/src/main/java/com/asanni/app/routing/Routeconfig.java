package com.asanni.app.routing;

/**
 * [CONFIG] Settings for in-app routes (the line drawn from the diner to a restaurant).
 *
 * Location: app/src/main/java/com/asanni/app/routing/RouteConfig.java  (NEW)
 *
 * WHY THIS EXISTS
 *   openrouteservice.org was shut down (Oct-Nov 2026, PHP security issues). The app no longer
 *   depends on ONE routing service: RouteService tries each provider in PROVIDER_ORDER and uses
 *   the first that answers. If every provider fails, the app still shows a straight-line distance
 *   and the "Directions" button still works, because turn-by-turn navigation is handed to the
 *   Google Maps app (no API key, no billing) - see ExternalNavigation.
 *
 * API KEYS
 *   Do NOT commit real keys to a public GitHub repo. Leave a key "" to skip that provider.
 */
public final class Routeconfig {

    private Routeconfig() {}

    public enum Profile { DRIVING, WALKING }

    public enum ProviderId { GRAPHHOPPER, OSRM, OPENROUTESERVICE }

    /** [CONFIG] Tried in this order. Providers without a key are skipped automatically. */
    public static final ProviderId[] PROVIDER_ORDER = {
            ProviderId.GRAPHHOPPER,      // free key, 500 credits/day, 1 credit per simple route
            ProviderId.OSRM,             // no key, public demo server, max 1 request per second
            ProviderId.OPENROUTESERVICE  // your old key; used again automatically once ORS is back
    };

    /** [CONFIG] Free key from https://graphhopper.com/dashboard/signup  ("" = skip GraphHopper). */
    public static final String GRAPHHOPPER_API_KEY = "736ef37a-965f-44f9-861a-03feb1939773";

    /** [CONFIG] Your existing openrouteservice key ("" = skip ORS). */
    public static final String ORS_API_KEY = "";

    /** Public services ask apps to identify themselves. Do not copy another app's User-Agent. */
    public static final String USER_AGENT = "ASANNI-Android/1.0 (com.asanni.app)";

    static final int CONNECT_TIMEOUT_MS = 8_000;
    static final int READ_TIMEOUT_MS = 12_000;

    /** Same request within this time (and ~100 m) is answered from memory, saving API quota. */
    static final long CACHE_TTL_MS = 10 * 60_000L;
    static final int CACHE_SIZE = 32;

    /** OSRM demo server rule: no more than 1 request per second. */
    static final long OSRM_MIN_INTERVAL_MS = 1_100L;

    static final String LOG_TAG = "AsanniRouting";
}