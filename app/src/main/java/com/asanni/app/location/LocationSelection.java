package com.asanni.app.location;

import com.asanni.app.auth.UserProfile;

/**
 * A complete, catalogue-backed location the user confirmed: a city plus an area of that city.
 *
 * Location: app/src/main/java/com/asanni/app/location/LocationSelection.java  (NEW)
 *
 * Can only be built from catalogue records, never from free text, so a typed search query
 * can't become a "location".
 */
public final class LocationSelection {

    public final String cityId;
    public final String cityName;
    public final String areaId;
    public final String areaName;
    /** UserProfile.SOURCE_GPS or UserProfile.SOURCE_MANUAL */
    public final String source;

    private LocationSelection(CatalogueItem city, CatalogueItem area, String source) {
        this.cityId = city.id;
        this.cityName = city.name;
        this.areaId = area.id;
        this.areaName = area.name;
        this.source = source;
    }

    /**
     * @throws IllegalArgumentException if the area doesn't belong to the city or the source is unknown.
     */
    public static LocationSelection of(CatalogueItem city, CatalogueItem area, String source) {
        if (city == null || area == null || !city.id.equals(area.cityId)) {
            throw new IllegalArgumentException("Area must belong to the selected city");
        }
        if (!UserProfile.SOURCE_GPS.equals(source) && !UserProfile.SOURCE_MANUAL.equals(source)) {
            throw new IllegalArgumentException("Unknown location source: " + source);
        }
        return new LocationSelection(city, area, source);
    }

    /** "Clifton, Karachi" */
    public String displayName() {
        return areaName + ", " + cityName;
    }
}