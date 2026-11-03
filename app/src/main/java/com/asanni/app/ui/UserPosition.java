package com.asanni.app.ui;

import android.content.Context;
import android.location.Location;

import com.asanni.app.location.DeviceLocationHelper;

/**
 * The phone's PHYSICAL position, used only to show distances. It is never confused with the
 * browsing location (the city/area the user selected), and it is never requested: if location
 * permission wasn't already granted, or the phone's Location switch is off, there is simply no
 * position and screens show the area name instead of a distance.
 *
 * Location: app/src/main/java/com/asanni/app/ui/UserPosition.java  (NEW in phase 6)
 */
final class UserPosition {

    /** [CONFIG] Reuse a position for this long before asking the phone again. */
    private static final long REUSE_MS = 2 * 60_000L;

    interface Callback {
        /** @param position null = unknown (no permission, Location off, or no fix in time) */
        void onPosition(Location position);
    }

    private static Location cached;
    private static long cachedAt;

    private UserPosition() {}

    static Location cachedPosition() {
        return cached != null && System.currentTimeMillis() - cachedAt < REUSE_MS * 5 ? cached : null;
    }

    static void request(Context c, DeviceLocationHelper helper, final Callback callback) {
        if (!DeviceLocationHelper.hasLocationPermission(c) || !DeviceLocationHelper.isLocationEnabled(c)) {
            callback.onPosition(null);
            return;
        }
        if (cached != null && System.currentTimeMillis() - cachedAt < REUSE_MS) {
            callback.onPosition(cached);
            return;
        }
        helper.getCurrentLocation(new DeviceLocationHelper.LocationCallback() {
            @Override
            public void onLocation(Location location) {
                cached = location;
                cachedAt = System.currentTimeMillis();
                callback.onPosition(location);
            }

            @Override
            public void onLocationUnavailable() {
                callback.onPosition(cachedPosition());
            }

            @Override
            public void onLocationFailed(Exception error) {
                callback.onPosition(cachedPosition());
            }
        });
    }
}