package com.asanni.app.restaurants;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DEVELOPMENT-ONLY dummy restaurant ("ASANNI Test Restaurant") for testing Restaurant Details,
 * cards, Saved, availability and GraphHopper routes before real restaurants exist.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/DevTestData.java  (NEW in phase 8)
 *
 * ISOLATION
 *   - It is a LOCAL fixture: nothing is written to Firestore, no owner account, no real record.
 *   - It is only added in DEBUGGABLE builds (Run from Android Studio). Release builds
 *     (android:debuggable=false) never show it, even if ENABLED stays true.
 *   - It goes through the real parser (Restaurant.parse) and the real repository, so the same
 *     screens and code paths are exercised as with a real document.
 *   - It is marked isTestData = true: every screen labels it "TEST DATA".
 *
 * REMOVE IT (see GUIDE.md): set ENABLED = false, or delete this file and the 3 lines marked
 * "[DEV TEST DATA]" in RestaurantRepository.java and AsanniActivity.java.
 *
 * LOCATION: University of Karachi campus, where UBIT (Department of Computer Science) is.
 *   24.9400 N, 67.1200 E = the campus coordinate published by latitude.to; cross-checked with the
 *   KU Observatory on the same campus (Wikipedia: 24.93056 N, 67.11528 E, about 1.1 km away).
 *   The exact UBIT building was NOT verifiable from here: to pin it precisely, long-press the UBIT
 *   building in Google Maps, copy the two numbers and paste them into LAT / LNG.
 */
public final class DevTestData {

    /** [CONFIG] Master switch for the dummy restaurant (false = gone everywhere). */
    public static final boolean ENABLED = true;

    /** Fixture ID (never used by real documents). Saved entries with this ID are test data too. */
    public static final String DOC_ID = "dev-test-asanni-ubit";

    /** [CONFIG] Test position (see LOCATION above). */
    public static final double LAT = 24.9400;
    public static final double LNG = 67.1200;

    private static volatile boolean debuggable;

    private DevTestData() {}

    /** Called once from AsanniActivity: remembers whether this is a debuggable (development) build. */
    public static void install(Context c) {
        debuggable = (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public static boolean active() {
        return ENABLED && debuggable;
    }

    public static boolean isTestId(String id) {
        return DOC_ID.equals(id);
    }

    /** The fixture, freshly stamped ("updated 1 min ago") so its TEST availability stays current. */
    public static Restaurant fixture() {
        Map<String, Object> d = new HashMap<>();
        d.put("name", "ASANNI Test Restaurant");
        d.put("cuisines", Arrays.asList("Desi", "Fast food"));
        d.put("cityId", "karachi");
        d.put("areaId", "karachi-university-road"); // existing catalogue area (seed/catalogue.json)
        d.put("address", "TEST DATA - near UBIT, Department of Computer Science,\nUniversity of Karachi, University Road, Karachi");
        d.put("location", new GeoPoint(LAT, LNG));
        d.put("isEnabled", true);
        d.put("isOpen", true);
        d.put("acceptingDiners", true);
        d.put("availableTables", 8L);
        d.put("totalTables", 12L);
        d.put("waitMinutes", 0L);
        d.put("availabilityUpdatedAt", new Timestamp(new Date(System.currentTimeMillis() - 60_000L)));
        d.put("price", 1500L);
        d.put("perHead", true);
        d.put("isTestData", true);
        // No coverUrl / logoUrl on purpose: shows the XML cover placeholder.
        return Restaurant.parse(DOC_ID, d);
    }

    /** Real list + the fixture (debug builds only); never duplicates the ID. */
    static List<Restaurant> addTo(List<Restaurant> real) {
        if (!active()) return real;
        List<Restaurant> out = new ArrayList<>(real.size() + 1);
        for (Restaurant r : real) if (!DOC_ID.equals(r.id)) out.add(r);
        out.add(fixture());
        return out;
    }
}