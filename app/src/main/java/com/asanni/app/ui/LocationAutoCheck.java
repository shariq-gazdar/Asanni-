package com.asanni.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import com.asanni.app.R;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfile;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.location.LiveLocationResolver;
import com.asanni.app.location.LocationSelection;
import com.asanni.app.network.ConnectivityMonitor;

/**
 * PHASE 9: "the user moved to another area or city". When Home opens, at most once every few
 * hours, and ONLY if location permission is already granted (never asks here), the city/area is
 * detected again. If it changed, it's saved (source gps + server time) and a short message says
 * so. Failures are silent: the saved location keeps being used and stays labelled with its
 * detection time in the "Your location" panel - never presented as freshly detected.
 *
 * Location: app/src/main/java/com/asanni/app/ui/LocationAutoCheck.java  (NEW in phase 9)
 */
final class LocationAutoCheck {

    /** [CONFIG] Minimum time between automatic checks. */
    static final long INTERVAL_MS = 3 * 60 * 60_000L;
    private static final String PREFS = "asanni_location_auto";
    private static final String KEY_LAST = "last_check";

    interface Listener {
        void onLocationChanged();
    }

    private LocationAutoCheck() {}

    static void maybeRun(final Activity a, final DeviceLocationHelper device, final ProfileData profile,
                         final Listener listener) {
        if (profile == null || profile.uid == null || profile.cityId == null) return;
        if (!DeviceLocationHelper.hasLocationPermission(a) || !DeviceLocationHelper.isLocationEnabled(a)) return;
        if (!ConnectivityMonitor.get(a).mayHaveInternet()) return;
        final SharedPreferences prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_LAST, 0) < INTERVAL_MS) return;
        prefs.edit().putLong(KEY_LAST, now).apply();
        new LiveLocationResolver(a, device).resolve(new LiveLocationResolver.Callback() {
            @Override public void onResolved(LiveLocationResolver.Result r) {
                if (a.isFinishing() || a.isDestroyed()) return;
                boolean same = r.city.id.equals(profile.cityId) && r.area.id.equals(profile.areaId);
                // Same place: nothing to write. A city-wide guess never replaces a known area of
                // the same city (it would make the saved location LESS precise).
                if (same || (!r.areaMatched && r.city.id.equals(profile.cityId))) return;
                final LocationSelection sel = r.selection(UserProfile.SOURCE_GPS);
                new UserProfileRepository().saveLocation(profile.uid, sel, new UserProfileRepository.SaveCallback() {
                    @Override public void onSaved() {
                        if (a.isFinishing() || a.isDestroyed()) return;
                        Toast.makeText(a, a.getString(R.string.locinfo_updated, sel.displayName()), Toast.LENGTH_LONG).show();
                        listener.onLocationChanged();
                    }

                    @Override public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                        // Keep the saved location; the panel lets the user retry.
                    }
                });
            }

            @Override public void onFailed(LiveLocationResolver.Failure f) {
                // Silent: no permission prompt, no guess.
            }
        });
    }
}