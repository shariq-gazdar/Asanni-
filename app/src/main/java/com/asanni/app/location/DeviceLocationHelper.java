package com.asanni.app.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Device location: permission checks, one-time current location, and reverse geocoding.
 *
 * Location: app/src/main/java/com/asanni/app/location/DeviceLocationHelper.java  (NEW)
 *
 * - Asks Android for ONE fresh location (no background tracking, no repeated updates).
 * - Works with approximate (coarse) permission; precise is used only if the user granted it.
 * - Coordinates are used on the phone to find the city/area and are never saved or uploaded
 *   by ASANNI. (Android's Geocoder may send them to Google's geocoding service.)
 */
public final class DeviceLocationHelper {

    private static final String TAG = "AsanniDeviceLocation";

    // ---------------- [CONFIG] location request settings ----------------
    /** Give up waiting for a location after this long. */
    private static final long LOCATION_TIMEOUT_MS = 20_000L;
    /** A location this recent (e.g. from another app) is good enough and returns instantly. */
    private static final long MAX_LOCATION_AGE_MS = 2 * 60_000L;
    /** How many addresses to ask the geocoder for (more = better area matching). */
    private static final int MAX_GEOCODE_RESULTS = 3;
    // ---------------------------------------------------------------------

    /**
     * [CONFIG] Requested together, so Android 12+ shows its "Precise / Approximate" choice and
     * the user decides. Everything works with approximate only.
     * To force approximate only, remove ACCESS_FINE_LOCATION here AND from the manifest.
     */
    public static final String[] LOCATION_PERMISSIONS = {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    public interface LocationCallback {
        void onLocation(Location location);
        /** The device couldn't determine a location in time. */
        void onLocationUnavailable();
        void onLocationFailed(Exception error);
    }

    public interface GeocodeCallback {
        void onAddresses(List<Address> addresses);
        /** Geocoder missing, offline, or no result. */
        void onGeocodeUnavailable();
    }

    private final Context appContext;
    private final FusedLocationProviderClient fused;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private CancellationTokenSource cancellation;
    private volatile boolean released;

    public DeviceLocationHelper(Context context) {
        appContext = context.getApplicationContext();
        fused = LocationServices.getFusedLocationProviderClient(appContext);
    }

    public static boolean hasLocationPermission(Context c) {
        return granted(c, Manifest.permission.ACCESS_COARSE_LOCATION)
                || granted(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public static boolean hasPreciseLocation(Context c) {
        return granted(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private static boolean granted(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** False when the phone's Location switch is off. */
    @SuppressWarnings("deprecation")
    public static boolean isLocationEnabled(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /** One current location. Call only after hasLocationPermission() is true. */
    @SuppressLint("MissingPermission")
    public void getCurrentLocation(LocationCallback callback) {
        cancel();
        if (!hasLocationPermission(appContext)) {
            callback.onLocationFailed(new SecurityException("Location permission not granted"));
            return;
        }
        cancellation = new CancellationTokenSource();
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY) // city/area level is enough
                .setDurationMillis(LOCATION_TIMEOUT_MS)
                .setMaxUpdateAgeMillis(MAX_LOCATION_AGE_MS)
                .build();
        fused.getCurrentLocation(request, cancellation.getToken())
                .addOnSuccessListener(location -> {
                    if (released) return;
                    if (location == null) callback.onLocationUnavailable();
                    else callback.onLocation(location);
                })
                .addOnFailureListener(e -> {
                    if (released) return;
                    Log.w(TAG, "getCurrentLocation failed", e);
                    callback.onLocationFailed(e);
                });
    }

    /** Address(es) for a location, delivered on the main thread. Uses English names. */
    @SuppressWarnings("deprecation")
    public void reverseGeocode(Location location, GeocodeCallback callback) {
        if (!Geocoder.isPresent()) {
            callback.onGeocodeUnavailable();
            return;
        }
        final Geocoder geocoder = new Geocoder(appContext, Locale.ENGLISH);
        final double lat = location.getLatitude();
        final double lng = location.getLongitude();

        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lng, MAX_GEOCODE_RESULTS, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    deliver(addresses, callback);
                }

                @Override
                public void onError(String errorMessage) {
                    Log.w(TAG, "Geocoder error: " + errorMessage);
                    deliver(Collections.<Address>emptyList(), callback);
                }
            });
        } else {
            background.execute(() -> {
                List<Address> result;
                try {
                    result = geocoder.getFromLocation(lat, lng, MAX_GEOCODE_RESULTS);
                } catch (IOException | IllegalArgumentException e) {
                    Log.w(TAG, "Geocoder failed", e);
                    result = null;
                }
                deliver(result, callback);
            });
        }
    }

    private void deliver(List<Address> addresses, GeocodeCallback callback) {
        main.post(() -> {
            if (released) return;
            if (addresses == null || addresses.isEmpty()) callback.onGeocodeUnavailable();
            else callback.onAddresses(addresses);
        });
    }

    /** Stops a pending location request. */
    public void cancel() {
        if (cancellation != null) {
            cancellation.cancel();
            cancellation = null;
        }
    }

    /** Call from onDestroy: cancels work and drops late callbacks. */
    public void release() {
        released = true;
        cancel();
        background.shutdownNow();
    }
}