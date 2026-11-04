package com.asanni.app.location;

import android.content.Context;
import android.location.Address;
import android.location.Location;

import java.util.List;

/**
 * PHASE 9: the ONLY way ASANNI decides a user's city and area now (manual editing was removed).
 *   one current GPS fix -> Android Geocoder (reverse geocoding) -> matched to the Firestore
 *   cities / areas catalogue.
 * GPS coordinates alone never decide a city: they are turned into an address first. Coordinates
 * are never saved - only the matched catalogue city + area (+ the time it was detected).
 *
 * Location: app/src/main/java/com/asanni/app/location/LiveLocationResolver.java  (NEW in phase 9)
 *
 * Geocoder: built into Android (Google Play services backend on most phones), free, no API key,
 * needs internet. If a phone has no geocoder, the result is GEOCODE_UNAVAILABLE (never a guess).
 * If the city is recognised but the area isn't, the city's "All of <city>" catalogue area is used
 * (honest: the whole city, not a made-up neighbourhood). Search is city-wide anyway.
 * Permission is NOT requested here (the screen does it, with an explanation).
 */
public final class LiveLocationResolver {

    public enum Failure {
        NO_PERMISSION, SERVICES_OFF, NO_FIX, GEOCODE_UNAVAILABLE, OUTSIDE_COUNTRY, CITY_NOT_SUPPORTED,
        CATALOGUE_OFFLINE, CATALOGUE_ERROR
    }

    public static final class Result {
        public final CatalogueItem city;
        public final CatalogueItem area;
        /** false = the area wasn't recognised, so the city-wide "All of <city>" area is used. */
        public final boolean areaMatched;
        /** false = the user allowed only approximate location (area may be less exact). */
        public final boolean precise;

        Result(CatalogueItem city, CatalogueItem area, boolean areaMatched, boolean precise) {
            this.city = city;
            this.area = area;
            this.areaMatched = areaMatched;
            this.precise = precise;
        }

        public LocationSelection selection(String source) {
            return LocationSelection.of(city, area, source);
        }
    }

    public interface Callback {
        void onResolved(Result result);

        void onFailed(Failure failure);
    }

    private final Context context;
    private final DeviceLocationHelper device;
    private final LocationCatalogueRepository catalogue = new LocationCatalogueRepository();
    private int generation;

    public LiveLocationResolver(Context context, DeviceLocationHelper device) {
        this.context = context.getApplicationContext();
        this.device = device;
    }

    public void cancel() {
        generation++;
        device.cancel();
    }

    public void resolve(final Callback cb) {
        final int gen = ++generation;
        if (!DeviceLocationHelper.hasLocationPermission(context)) {
            cb.onFailed(Failure.NO_PERMISSION);
            return;
        }
        if (!DeviceLocationHelper.isLocationEnabled(context)) {
            cb.onFailed(Failure.SERVICES_OFF);
            return;
        }
        device.getCurrentLocation(new DeviceLocationHelper.LocationCallback() {
            @Override public void onLocation(Location location) {
                if (gen == generation) geocode(location, gen, cb);
            }

            @Override public void onLocationUnavailable() {
                if (gen == generation) cb.onFailed(Failure.NO_FIX);
            }

            @Override public void onLocationFailed(Exception error) {
                if (gen == generation) cb.onFailed(Failure.NO_FIX);
            }
        });
    }

    private void geocode(Location location, final int gen, final Callback cb) {
        device.reverseGeocode(location, new DeviceLocationHelper.GeocodeCallback() {
            @Override public void onAddresses(final List<Address> addresses) {
                if (gen != generation) return;
                if (!CatalogueMatcher.isInSupportedCountry(addresses)) {
                    cb.onFailed(Failure.OUTSIDE_COUNTRY);
                    return;
                }
                catalogue.loadCities(new LocationCatalogueRepository.Callback() {
                    @Override public void onLoaded(List<CatalogueItem> cities) {
                        if (gen != generation) return;
                        CatalogueItem city = CatalogueMatcher.matchCity(addresses, cities);
                        if (city == null) cb.onFailed(Failure.CITY_NOT_SUPPORTED);
                        else matchArea(addresses, city, gen, cb);
                    }

                    @Override public void onFailed(LocationCatalogueRepository.Failure f, Exception e) {
                        if (gen == generation) cb.onFailed(catalogueFailure(f));
                    }
                });
            }

            @Override public void onGeocodeUnavailable() {
                if (gen == generation) cb.onFailed(Failure.GEOCODE_UNAVAILABLE);
            }
        });
    }

    private void matchArea(final List<Address> addresses, final CatalogueItem city, final int gen, final Callback cb) {
        catalogue.loadAreas(city.id, new LocationCatalogueRepository.Callback() {
            @Override public void onLoaded(List<CatalogueItem> areas) {
                if (gen != generation) return;
                CatalogueItem area = CatalogueMatcher.matchArea(addresses, areas);
                boolean matched = area != null;
                if (area == null) area = cityWide(areas);
                if (area == null) {
                    cb.onFailed(Failure.CATALOGUE_ERROR); // catalogue has no "All of <city>" area
                    return;
                }
                cb.onResolved(new Result(city, area, matched, DeviceLocationHelper.hasPreciseLocation(context)));
            }

            @Override public void onFailed(LocationCatalogueRepository.Failure f, Exception e) {
                if (gen == generation) cb.onFailed(catalogueFailure(f));
            }
        });
    }

    static CatalogueItem cityWide(List<CatalogueItem> areas) {
        for (CatalogueItem a : areas) if (a.isCityWide) return a;
        return null;
    }

    private static Failure catalogueFailure(LocationCatalogueRepository.Failure f) {
        return f == LocationCatalogueRepository.Failure.NETWORK ? Failure.CATALOGUE_OFFLINE : Failure.CATALOGUE_ERROR;
    }
}