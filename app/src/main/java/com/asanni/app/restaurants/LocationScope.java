package com.asanni.app.restaurants;

import com.asanni.app.location.CatalogueItem;

/**
 * The BROWSING location the user selected (catalogue city + area). This is NOT the phone's GPS
 * position; distances use the GPS position separately (see DiscoverSection / UserPosition).
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/LocationScope.java  (NEW in phase 6)
 */
public final class LocationScope {

    public final CatalogueItem city;
    /** Null or a city-wide record ("All of Karachi") means: the whole city. */
    public final CatalogueItem area;

    public LocationScope(CatalogueItem city, CatalogueItem area) {
        this.city = city;
        this.area = area;
    }

    public boolean isWholeCity() {
        return area == null || area.isCityWide;
    }

    /** What the user sees: "Clifton" (area) or "Karachi" (whole city). */
    public String shortLabel() {
        return isWholeCity() ? city.name : area.name;
    }

    /** "Clifton, Karachi" or "Karachi". */
    public String displayName() {
        return isWholeCity() ? city.name : area.name + ", " + city.name;
    }

    public String key() {
        return city.id + "/" + (area == null ? "" : area.id);
    }
}