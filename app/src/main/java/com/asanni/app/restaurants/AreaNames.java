package com.asanni.app.restaurants;

import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.LocationCatalogueRepository;

import java.util.List;

/**
 * Turns a restaurant's areaId ("karachi-clifton") into its catalogue name ("Clifton") using the
 * EXISTING areas catalogue and its session cache (no second area list, no read per card).
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/AreaNames.java  (NEW in phase 8)
 */
public final class AreaNames {

    public interface Callback {
        /** areaName / cityName may be null when unknown. */
        void onNames(String areaName, String cityName);
    }

    private final LocationCatalogueRepository catalogue = new LocationCatalogueRepository();

    public void resolve(final String cityId, final String areaId, final Callback cb) {
        if (cityId == null || cityId.isEmpty()) {
            cb.onNames(null, null);
            return;
        }
        catalogue.loadCities(new LocationCatalogueRepository.Callback() {
            @Override
            public void onLoaded(List<CatalogueItem> cities) {
                final String cityName = nameOf(cities, cityId);
                if (areaId == null || areaId.isEmpty()) {
                    cb.onNames(null, cityName);
                    return;
                }
                catalogue.loadAreas(cityId, new LocationCatalogueRepository.Callback() {
                    @Override
                    public void onLoaded(List<CatalogueItem> areas) {
                        cb.onNames(nameOf(areas, areaId), cityName);
                    }

                    @Override
                    public void onFailed(LocationCatalogueRepository.Failure f, Exception e) {
                        cb.onNames(null, cityName);
                    }
                });
            }

            @Override
            public void onFailed(LocationCatalogueRepository.Failure f, Exception e) {
                cb.onNames(null, null);
            }
        });
    }

    private static String nameOf(List<CatalogueItem> items, String id) {
        if (items == null) return null;
        for (CatalogueItem i : items) if (i.id.equals(id)) return i.name;
        return null;
    }
}