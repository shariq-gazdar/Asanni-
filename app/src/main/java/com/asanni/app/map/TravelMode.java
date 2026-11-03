package com.asanni.app.map;

/**
 * Travel modes the routing provider ACTUALLY supports (openrouteservice profiles).
 * Location: app/src/main/java/com/asanni/app/map/TravelMode.java  (NEW in phase 7)
 * To add one, add it here with its provider profile name; the Map screen shows every entry.
 */
public enum TravelMode {
    DRIVING("driving-car"),
    WALKING("foot-walking"),
    CYCLING("cycling-regular");

    public final String orsProfile;

    TravelMode(String orsProfile) {
        this.orsProfile = orsProfile;
    }
}