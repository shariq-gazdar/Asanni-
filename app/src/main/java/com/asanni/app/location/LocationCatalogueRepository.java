package com.asanni.app.location;

import android.util.Log;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the city and area catalogues from Firestore (the only source of truth; no hardcoded list).
 *
 * Location: app/src/main/java/com/asanni/app/location/LocationCatalogueRepository.java  (NEW)
 *
 *   cities  where countryCode == "PK" and isActive == true
 *   areas   where cityId == <id>      and isActive == true
 *
 * Results are kept in memory for the app session, so reopening a picker is instant.
 * Sorting happens on the phone, so no composite Firestore index is needed.
 */
public final class LocationCatalogueRepository {

    private static final String TAG = "AsanniCatalogue";

    public static final String CITIES = "cities";
    public static final String AREAS = "areas";

    public enum Failure {
        /** Offline and nothing cached. */
        NETWORK,
        /** Rules rejected the read (rules not published / user signed out). */
        PERMISSION_DENIED,
        /** The server answered but has no cities at all: the catalogue was never seeded. */
        NOT_SEEDED,
        UNKNOWN
    }

    public interface Callback {
        /** Items are sorted; for areas the list may be empty (a real "no areas yet" state). */
        void onLoaded(List<CatalogueItem> items);
        void onFailed(Failure failure, Exception error);
    }

    // In-memory session cache (small lists; cleared when the app process ends).
    private static List<CatalogueItem> cachedCities;
    private static final Map<String, List<CatalogueItem>> cachedAreas = new HashMap<>();

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public void loadCities(Callback callback) {
        if (cachedCities != null) {
            callback.onLoaded(cachedCities);
            return;
        }
        db.collection(CITIES)
                .whereEqualTo("countryCode", SupportedCountry.DEFAULT.code)
                .whereEqualTo("isActive", true)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty()) {
                        // Empty from the local cache = we never reached the server (offline).
                        // Empty from the server = nobody has seeded the catalogue yet.
                        callback.onFailed(snap.getMetadata().isFromCache()
                                ? Failure.NETWORK : Failure.NOT_SEEDED, null);
                        return;
                    }
                    List<CatalogueItem> items = toItems(snap);
                    if (!snap.getMetadata().isFromCache()) cachedCities = items;
                    callback.onLoaded(items);
                })
                .addOnFailureListener(e -> fail(callback, e));
    }

    public void loadAreas(String cityId, Callback callback) {
        List<CatalogueItem> cached = cachedAreas.get(cityId);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        db.collection(AREAS)
                .whereEqualTo("cityId", cityId)
                .whereEqualTo("isActive", true)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty() && snap.getMetadata().isFromCache()) {
                        callback.onFailed(Failure.NETWORK, null); // can't tell if it's really empty
                        return;
                    }
                    List<CatalogueItem> items = toItems(snap);
                    if (!snap.getMetadata().isFromCache()) cachedAreas.put(cityId, items);
                    callback.onLoaded(items);
                })
                .addOnFailureListener(e -> fail(callback, e));
    }

    private static List<CatalogueItem> toItems(QuerySnapshot snap) {
        List<CatalogueItem> items = new ArrayList<>();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            CatalogueItem item = CatalogueItem.fromSnapshot(doc);
            if (item != null) items.add(item);
        }
        // Alphabetical; the "All of <city>" option goes last.
        Collections.sort(items, (a, b) -> {
            if (a.isCityWide != b.isCityWide) return a.isCityWide ? 1 : -1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return Collections.unmodifiableList(items);
    }

    private static void fail(Callback callback, Exception e) {
        Log.w(TAG, "Catalogue load failed", e);
        Failure f = Failure.UNKNOWN;
        if (e instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) e).getCode()) {
                case UNAVAILABLE:
                case DEADLINE_EXCEEDED:
                    f = Failure.NETWORK;
                    break;
                case PERMISSION_DENIED:
                case UNAUTHENTICATED:
                    f = Failure.PERMISSION_DENIED;
                    break;
                default:
                    break;
            }
        }
        callback.onFailed(f, e);
    }
}