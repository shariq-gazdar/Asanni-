package com.asanni.app.restaurants;

import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.LocationCatalogueRepository;

import java.util.List;

/**
 * Turns the city/area saved in the user's profile into catalogue records (with aliases), so a
 * restaurant stored as "Defence" still matches the area "DHA". Uses the existing catalogue
 * repository and its session cache; no second catalogue.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/LocationScopeLoader.java  (NEW in phase 6)
 *
 * If the catalogue can't be read (offline, first launch), it falls back to the IDs and names
 * stored in the profile, so Discover still works (only alias matching is lost).
 */
public final class LocationScopeLoader {

    public interface Callback {
        void onScope(LocationScope scope);
    }

    private final LocationCatalogueRepository catalogue = new LocationCatalogueRepository();

    /** Calls back with null only when the profile has no city at all. */
    public void load(final String cityId, final String cityName, final String areaId, final String areaName,
                     final Callback callback) {
        if (cityId == null || cityId.isEmpty()) {
            callback.onScope(null);
            return;
        }
        final CatalogueItem fallbackCity = new CatalogueItem(cityId, nonEmpty(cityName, cityId), null, null, null, false);
        final CatalogueItem fallbackArea = areaId == null || areaId.isEmpty() ? null
                : new CatalogueItem(areaId, nonEmpty(areaName, areaId), null, null, cityId,
                areaId.equals(cityId + "-all")); // seed's "All of <City>" record (idSuffix "all")

        catalogue.loadCities(new LocationCatalogueRepository.Callback() {
            @Override
            public void onLoaded(List<CatalogueItem> cities) {
                final CatalogueItem city = find(cities, cityId, fallbackCity);
                // PHASE 9: always load the city's areas (cached) so city-wide lists can name
                // each restaurant's own area.
                catalogue.loadAreas(cityId, new LocationCatalogueRepository.Callback() {
                    @Override
                    public void onLoaded(List<CatalogueItem> areas) {
                        callback.onScope(new LocationScope(city,
                                fallbackArea == null ? null : find(areas, areaId, fallbackArea), areas));
                    }

                    @Override
                    public void onFailed(LocationCatalogueRepository.Failure failure, Exception error) {
                        callback.onScope(new LocationScope(city, fallbackArea));
                    }
                });
            }

            @Override
            public void onFailed(LocationCatalogueRepository.Failure failure, Exception error) {
                callback.onScope(new LocationScope(fallbackCity, fallbackArea));
            }
        });
    }

    private static CatalogueItem find(List<CatalogueItem> items, String id, CatalogueItem fallback) {
        if (items != null) {
            for (CatalogueItem i : items) if (i.id.equals(id)) return i;
        }
        return fallback;
    }

    private static String nonEmpty(String s, String other) {
        return s != null && !s.trim().isEmpty() ? s.trim() : other;
    }
}