package com.asanni.app.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.location.Location;
import android.provider.Settings;

import com.asanni.app.R;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.routing.Routeconfig;
import com.asanni.app.routing.Routeservice;

/**
 * PHASE 9: the explicit "Open directions" flow of the Description page, using the SAME existing
 * routing package as Restaurant Details (routing/Routeservice -> GraphHopper first). No second
 * routing system: this class only calls Routeservice.get().request(...).
 *
 * Location: app/src/main/java/com/asanni/app/ui/DirectionsFlow.java  (NEW in phase 9)
 *
 * Order: restaurant coordinates -> location permission -> phone GPS on -> online -> one fresh
 * GPS fix -> route (or a recent identical route from RouteMemory, no new request) -> Map opens
 * with the restaurant selected and the route drawn. A straight-line fallback (approximate) is
 * never presented as a road route. Leaving the screen cancels a running request.
 */
final class DirectionsFlow {

    interface Ui {
        void onDirectionsBusy(boolean busy);

        /** @param action / onAction null = no button */
        void onDirectionsProblem(String message, String action, Runnable onAction);

        void onDirectionsClear();

        /** A real road route is known (for "2.1 km · around 9 min by car"). */
        void onRouteKnown(RouteMemory.Entry entry);
    }

    private final Activity activity;
    private final DeviceLocationHelper location;
    private final int permissionRequestCode;
    private final Ui ui;
    private Routeservice.Request request;
    private boolean busy;
    private int generation;
    private Restaurant pendingRestaurant;
    private LocationScope pendingScope;

    DirectionsFlow(Activity activity, DeviceLocationHelper location, int permissionRequestCode, Ui ui) {
        this.activity = activity;
        this.location = location;
        this.permissionRequestCode = permissionRequestCode;
        this.ui = ui;
    }

    boolean isBusy() {
        return busy;
    }

    /** Called ONLY from the button. Repeated taps while working are ignored. */
    void start(final Restaurant r, final LocationScope scope) {
        if (r == null || busy) return;
        ui.onDirectionsClear();
        if (!r.hasCoordinates() || !validCoordinate(r.latitude, r.longitude)) {
            ui.onDirectionsProblem(activity.getString(R.string.directions_no_restaurant_location), null, null);
            return; // GraphHopper is NOT called without real coordinates
        }
        if (!DeviceLocationHelper.hasLocationPermission(activity)) {
            pendingRestaurant = r;
            pendingScope = scope;
            ui.onDirectionsProblem(activity.getString(R.string.directions_no_user_location),
                    activity.getString(R.string.map_location_allow),
                    () -> activity.requestPermissions(DeviceLocationHelper.LOCATION_PERMISSIONS, permissionRequestCode));
            return;
        }
        if (!DeviceLocationHelper.isLocationEnabled(activity)) {
            ui.onDirectionsProblem(activity.getString(R.string.directions_gps_off),
                    activity.getString(R.string.map_gps_turn_on), () -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        } catch (ActivityNotFoundException ignored) {
                            // no settings screen on this device
                        }
                    });
            return;
        }
        if (!ConnectivityMonitor.get(activity).mayHaveInternet()) {
            ui.onDirectionsProblem(activity.getString(R.string.directions_offline),
                    activity.getString(R.string.directions_retry), () -> start(r, scope));
            return;
        }
        setBusy(true);
        final int gen = ++generation;
        UserPosition.request(activity, location, position -> {
            if (activity.isDestroyed() || gen != generation) return;
            if (position == null) {
                setBusy(false);
                ui.onDirectionsProblem(activity.getString(R.string.directions_no_user_location),
                        activity.getString(R.string.directions_retry), () -> start(r, scope));
                return;
            }
            route(r, scope, position, gen);
        });
    }

    private void route(final Restaurant r, final LocationScope scope, final Location from, final int gen) {
        final double la = from.getLatitude();
        final double lo = from.getLongitude();
        RouteMemory.Entry known = RouteMemory.find(r.id, la, lo, System.currentTimeMillis());
        if (known != null) { // same trip a moment ago: no new GraphHopper request
            setBusy(false);
            ui.onRouteKnown(known);
            AppNavigator.openMapWithRoute(activity, r.id, scope, known.fromLat, known.fromLng);
            return;
        }
        request = Routeservice.get().request(la, lo, r.latitude, r.longitude, Routeconfig.Profile.DRIVING, result -> {
            if (activity.isDestroyed() || gen != generation) return; // cancelled (screen left)
            request = null;
            setBusy(false);
            if (result == null || result.approximate || result.points == null || result.points.size() < 2) {
                // Every routing service failed (Routeservice returned a straight line): no fake route.
                ui.onDirectionsProblem(activity.getString(R.string.directions_failed),
                        activity.getString(R.string.directions_retry), () -> start(r, scope));
                return;
            }
            RouteMemory.remember(r.id, result, la, lo);
            ui.onRouteKnown(RouteMemory.find(r.id, la, lo, System.currentTimeMillis()));
            AppNavigator.openMapWithRoute(activity, r.id, scope, la, lo);
        });
    }

    /**
     * Permission answer from the Activity; continues only if it was granted. Denied with
     * "Don't ask again" (Android shows no dialog any more): offer the app's settings page.
     */
    void onPermissionResult(boolean granted, boolean answered) {
        Restaurant r = pendingRestaurant;
        LocationScope s = pendingScope;
        pendingRestaurant = null;
        pendingScope = null;
        if (granted && r != null) {
            start(r, s);
        } else if (answered && r != null
                && !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            ui.onDirectionsProblem(activity.getString(R.string.locinfo_blocked),
                    activity.getString(R.string.locinfo_open_settings),
                    () -> SystemSettingsLinks.openAppDetails(activity));
        }
    }

    /** onStop: a cancelled location fix never calls back, and the Map must not open over another app. */
    void cancel() {
        if (!busy) return;
        generation++;
        location.cancel();
        if (request != null) request.cancel();
        request = null;
        setBusy(false);
    }

    private void setBusy(boolean b) {
        busy = b;
        ui.onDirectionsBusy(b);
    }

    static boolean validCoordinate(double la, double lo) {
        return !Double.isNaN(la) && !Double.isNaN(lo) && la >= -90 && la <= 90 && lo >= -180 && lo <= 180
                && !(la == 0 && lo == 0);
    }
}