package com.asanni.app.map;

import android.content.Context;

/**
 * [CONFIG] Map + routing providers for the Map screen (phase 7). Change providers HERE only.
 *
 * Location: app/src/main/java/com/asanni/app/map/MapConfig.java  (NEW in phase 7)
 *
 * MAP ........ MapLibre Native for Android (open source, no account, no key, no billing)
 * TILES ...... OpenFreeMap public instance (OpenStreetMap data, no key, no card, commercial use
 *              allowed, attribution required and shown automatically by MapLibre, no SLA).
 *              To switch (e.g. MapTiler with a key) change STYLE_URL; nothing else changes.
 * ROUTING .... openrouteservice (free "Standard" key: 2,000 directions/day, 40/min; up to 3
 *              alternative routes for trips up to 100 km; car / foot / bike). NO live traffic.
 *              The key is read from the string resource "ors_api_key", which Gradle creates from
 *              local.properties (never committed). Without a key, in-app routes are switched off and
 *              "Open in maps app" is offered instead (see GUIDE.md).
 */
public final class MapConfig {

    private MapConfig() {}

    /** [CONFIG] Vector map style (light, clean; recoloured slightly in MapActivity). */
    public static final String STYLE_URL = "https://tiles.openfreemap.org/styles/positron";
    /** [CONFIG] Used if the first style fails to load. */
    public static final String FALLBACK_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

    /** [CONFIG] Directions endpoint (GeoJSON output). */
    public static final String ORS_DIRECTIONS_URL = "https://api.openrouteservice.org/v2/directions/%s/geojson";
    /** openrouteservice only returns alternative routes for trips up to this length. */
    public static final double ORS_ALTERNATIVES_MAX_KM = 100.0;
    public static final int ORS_MAX_ALTERNATIVES = 3;

    /** Map camera limits. */
    public static final double MIN_ZOOM = 3.0;
    public static final double MAX_ZOOM = 19.0;
    public static final double RESTAURANT_ZOOM = 15.5;
    public static final double USER_ZOOM = 14.0;
    /** Wide view of Pakistan, used only when nothing better is known (never shown as "you"). */
    public static final double COUNTRY_LAT = 30.3753;
    public static final double COUNTRY_LNG = 69.3451;
    public static final double COUNTRY_ZOOM = 4.6;

    /**
     * The openrouteservice key, or null when not configured. Looked up by NAME so the app builds
     * even before the key is added.
     */
    public static String routingKey(Context c) {
        int id = c.getResources().getIdentifier("ors_api_key", "string", c.getPackageName());
        if (id == 0) return null;
        String key = c.getString(id).trim();
        return key.isEmpty() || key.equals("null") ? null : key;
    }
}