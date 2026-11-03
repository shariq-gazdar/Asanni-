package com.asanni.app.routing;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.Locale;

/**
 * Turn-by-turn navigation handed off to the Google Maps app.
 * Needs NO API key and NO billing, and keeps working even if every routing API is down.
 *
 * Location: app/src/main/java/com/asanni/app/routing/ExternalNavigation.java  (NEW)
 *
 * Usage:  ExternalNavigation.openDirections(requireContext(), r.lat, r.lng, RouteConfig.Profile.DRIVING);
 */
public final class ExternalNavigation {

    private ExternalNavigation() {}

    public static void openDirections(Context context, double lat, double lng, Routeconfig.Profile profile) {
        String mode = profile == Routeconfig.Profile.WALKING ? "walking" : "driving";
        String dest = String.format(Locale.US, "%.6f,%.6f", lat, lng);

        // Universal Maps URL: opens the Google Maps app if installed, otherwise the browser.
        Intent maps = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + dest + "&travelmode=" + mode));
        if (!(context instanceof Activity)) maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(maps);
            return;
        } catch (ActivityNotFoundException ignored) {
            // fall through to geo:
        }

        // Any other map app (e.g. OsmAnd, Organic Maps).
        Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + dest + "?q=" + dest));
        if (!(context instanceof Activity)) geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(geo);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No maps app found on this phone", Toast.LENGTH_SHORT).show();
        }
    }
}