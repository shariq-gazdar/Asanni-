package com.asanni.app.map;

/**
 * [CONFIG] Map + routing providers for the Map screen (phase 7). Change providers HERE only.
 *
 * Location: app/src/main/java/com/asanni/app/map/MapConfig.java  (NEW in phase 7)
 *
 * MAP ........ MapLibre Native for Android (open source, no account, no key, no billing)
 * TILES ...... OpenFreeMap public instance (OpenStreetMap data, no key, no card, commercial use
 *              allowed, attribution required and shown automatically by MapLibre, no SLA).
 *              To switch (e.g. MapTiler with a key) change STYLE_URL; nothing else changes.
 * ROUTING .... PHASE 8: NOT configured here any more. Routes come only from the existing
 *              routing package (com.asanni.app.routing: Routeconfig / Routeservice, GraphHopper
 *              first). The phase-7 openrouteservice client was removed.
 */
public final class MapConfig {

    private MapConfig() {}

    /** [CONFIG] Vector map style (light, clean; recoloured slightly in MapActivity). */
    public static final String STYLE_URL = "https://tiles.openfreemap.org/styles/positron";
    /** [CONFIG] Used if the first style fails to load. */
    public static final String FALLBACK_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

    /** Map camera limits. */
    public static final double MIN_ZOOM = 3.0;
    public static final double MAX_ZOOM = 19.0;
    public static final double RESTAURANT_ZOOM = 15.5;
    public static final double USER_ZOOM = 14.0;
    /** Wide view of Pakistan, used only when nothing better is known (never shown as "you"). */
    public static final double COUNTRY_LAT = 30.3753;
    public static final double COUNTRY_LNG = 69.3451;
    public static final double COUNTRY_ZOOM = 4.6;
}