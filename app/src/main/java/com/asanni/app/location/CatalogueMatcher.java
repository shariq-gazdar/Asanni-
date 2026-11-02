package com.asanni.app.location;

import android.location.Address;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches reverse-geocoded addresses to catalogue records.
 *
 * Location: app/src/main/java/com/asanni/app/location/CatalogueMatcher.java  (NEW)
 *
 * The result is only a SUGGESTION: the Allow Location page always asks the user to confirm
 * or change it before anything is saved. "All of <city>" records are never auto-suggested.
 */
public final class CatalogueMatcher {

    /** Shorter names are not used for partial matches (avoids "F-6" matching everything). */
    private static final int MIN_PARTIAL_LENGTH = 4;

    private CatalogueMatcher() {}

    /** True if any address is in the supported country. */
    public static boolean isInSupportedCountry(List<Address> addresses) {
        for (Address a : addresses) {
            if (SupportedCountry.DEFAULT.code.equalsIgnoreCase(a.getCountryCode())) return true;
        }
        return false;
    }

    /** City from locality / sub-admin area / admin area, or null. */
    public static CatalogueItem matchCity(List<Address> addresses, List<CatalogueItem> cities) {
        List<String> candidates = new ArrayList<>();
        for (Address a : addresses) {
            add(candidates, a.getLocality());
            add(candidates, a.getSubAdminArea());
            add(candidates, a.getAdminArea());
        }
        return bestMatch(candidates, cities, true);
    }

    /** Area of the already-matched city, or null. */
    public static CatalogueItem matchArea(List<Address> addresses, List<CatalogueItem> areas) {
        List<CatalogueItem> specific = new ArrayList<>();
        for (CatalogueItem area : areas) {
            if (!area.isCityWide) specific.add(area);
        }
        List<String> candidates = new ArrayList<>();
        for (Address a : addresses) {
            add(candidates, a.getSubLocality());
            add(candidates, a.getFeatureName());
            add(candidates, a.getThoroughfare());
            for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                String line = a.getAddressLine(i);
                if (line == null) continue;
                for (String part : line.split(",")) add(candidates, part);
            }
        }
        return bestMatch(candidates, specific, false);
    }

    private static void add(List<String> out, String raw) {
        String n = TextNormalizer.normalize(raw);
        if (!n.isEmpty() && !out.contains(n)) out.add(n);
    }

    /**
     * Exact name/alias matches win. Otherwise a candidate that CONTAINS a record's name
     * ("block5clifton" contains "clifton"); the longest matched name wins, so
     * "gulshaneiqbal" beats a shorter name it also contains.
     */
    private static CatalogueItem bestMatch(List<String> candidates, List<CatalogueItem> items,
                                           boolean allowNameContainsCandidate) {
        for (String c : candidates) {
            for (CatalogueItem item : items) {
                if (item.matchesExactly(c)) return item;
            }
        }
        CatalogueItem best = null;
        int bestLength = 0;
        for (String c : candidates) {
            for (CatalogueItem item : items) {
                List<String> names = new ArrayList<>(item.normalizedAliases);
                names.add(item.normalizedName);
                for (String n : names) {
                    if (n.length() < MIN_PARTIAL_LENGTH) continue;
                    boolean hit = c.contains(n)
                            || (allowNameContainsCandidate && c.length() >= MIN_PARTIAL_LENGTH
                            && n.contains(c));
                    if (hit && n.length() > bestLength) {
                        best = item;
                        bestLength = n.length();
                    }
                }
            }
        }
        return best;
    }
}