package com.asanni.app.restaurants;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * READ-ONLY access to the desktop app's restaurant documents, shared by Discover, See All and
 * the detail screen.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/RestaurantRepository.java  (NEW in phase 6)
 *
 * One bounded live listener (at most RestaurantSchema.MAX_DOCUMENTS documents) serves every
 * screen. It is attached when the first screen starts observing and detached a few seconds after
 * the last one stops (so moving Discover -> See All doesn't re-read anything). Firestore then only
 * sends CHANGED documents, so live availability costs little. Typing in search never queries.
 *
 * Results come with fromCache=true when Firestore served its offline copy.
 */
public final class RestaurantRepository {

    private static final String TAG = "AsanniRestaurants";
    /** [TIMING] Keep the listener this long after the last screen stops observing. */
    private static final long DETACH_DELAY_MS = 5_000L;

    public enum Failure {
        /** Offline and nothing cached yet. */
        NETWORK,
        /** Firestore rules don't allow reading the restaurant collection (see GUIDE.md). */
        NOT_ALLOWED,
        UNKNOWN
    }

    public interface Observer {
        void onRestaurants(List<Restaurant> restaurants, boolean fromCache);
        void onRestaurantsFailed(Failure failure);
    }

    private static RestaurantRepository instance;

    public static synchronized RestaurantRepository get() {
        if (instance == null) instance = new RestaurantRepository();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private final Runnable detach = this::detachNow;

    private ListenerRegistration registration;
    private String listeningCity;          // only used with RestaurantSchema.SERVER_CITY_FIELD
    private List<Restaurant> latest;       // last delivered list (null = nothing yet)
    private boolean latestFromCache;
    private Failure lastFailure;
    private boolean schemaReported;

    private RestaurantRepository() {}

    /**
     * Starts delivering results to the observer (immediately if something is already loaded).
     * @param cityId catalogue city ID; used only when SERVER_CITY_FIELD is configured.
     */
    public void observe(Observer o, String cityId) {
        main.removeCallbacks(detach);
        if (!observers.contains(o)) observers.add(o);
        boolean needsNewQuery = RestaurantSchema.SERVER_CITY_FIELD != null
                && cityId != null && !cityId.equals(listeningCity);
        if (needsNewQuery) detachNow();
        if (registration == null) {
            attach(cityId);
        } else if (latest != null) {
            o.onRestaurants(latest, latestFromCache);
        } else if (lastFailure != null) {
            o.onRestaurantsFailed(lastFailure);
        }
    }

    public void stopObserving(Observer o) {
        observers.remove(o);
        if (observers.isEmpty()) main.postDelayed(detach, DETACH_DELAY_MS);
    }

    /** Re-attaches after a failure (Retry button). */
    public void retry(String cityId) {
        detachNow();
        if (!observers.isEmpty()) attach(cityId);
    }

    /** Last loaded restaurant with this ID, or null (e.g. after the app process was restarted). */
    public Restaurant findLoaded(String id) {
        if (latest == null || id == null) return null;
        for (Restaurant r : latest) if (r.id.equals(id)) return r;
        return null;
    }

    /** One document by ID (used by the detail screen when nothing is loaded yet). */
    public void fetchOne(String id, final Observer o) {
        if (DevTestData.active() && DevTestData.isTestId(id)) { // [DEV TEST DATA] local fixture
            List<Restaurant> one = new ArrayList<>();
            one.add(DevTestData.fixture());
            o.onRestaurants(one, false);
            return;
        }
        FirebaseFirestore.getInstance().collection(RestaurantSchema.COLLECTION).document(id).get()
                .addOnSuccessListener(doc -> {
                    Restaurant r = doc.exists() ? parse(doc) : null;
                    List<Restaurant> list = new ArrayList<>();
                    if (r != null && !r.isHiddenFromDiners()) list.add(r);
                    o.onRestaurants(list, doc.getMetadata().isFromCache());
                })
                .addOnFailureListener(e -> o.onRestaurantsFailed(classify(e)));
    }

    /**
     * PHASE 9: the Description page's refresh button - ONE document read straight from the server
     * (never the cache, never the whole collection). Fails offline instead of showing old data
     * as new.
     */
    public void refreshOne(String id, final Observer o) {
        if (DevTestData.active() && DevTestData.isTestId(id)) { // [DEV TEST DATA] local fixture
            List<Restaurant> one = new ArrayList<>();
            one.add(DevTestData.fixture());
            main.post(() -> o.onRestaurants(one, false));
            return;
        }
        FirebaseFirestore.getInstance().collection(RestaurantSchema.COLLECTION).document(id)
                .get(com.google.firebase.firestore.Source.SERVER)
                .addOnSuccessListener(doc -> {
                    Restaurant r = doc.exists() ? parse(doc) : null;
                    List<Restaurant> list = new ArrayList<>();
                    if (r != null && !r.isHiddenFromDiners()) list.add(r);
                    o.onRestaurants(list, false);
                })
                .addOnFailureListener(e -> o.onRestaurantsFailed(classify(e)));
    }

    // ------------------------------------------------------------------ phase 8: single restaurants

    /** Stops a watchOne() listener. */
    public interface Watch {
        void stop();
    }

    /**
     * Live updates for ONE restaurant document (Restaurant Details). Independent of the city
     * listener, so opening a restaurant from another city never disturbs Discover's data.
     * Delivers an empty list when the document doesn't exist or is disabled.
     */
    public Watch watchOne(final String id, final Observer o) {
        if (DevTestData.active() && DevTestData.isTestId(id)) { // [DEV TEST DATA] local fixture
            List<Restaurant> one = new ArrayList<>();
            one.add(DevTestData.fixture());
            main.post(() -> o.onRestaurants(one, false));
            return () -> { };
        }
        final ListenerRegistration reg = FirebaseFirestore.getInstance()
                .collection(RestaurantSchema.COLLECTION).document(id)
                .addSnapshotListener(MetadataChanges.INCLUDE, (doc, e) -> {
                    if (e != null) {
                        o.onRestaurantsFailed(classify(e));
                        return;
                    }
                    if (doc == null) return;
                    List<Restaurant> list = new ArrayList<>();
                    Restaurant r = doc.exists() ? parse(doc) : null;
                    if (r != null && !r.isHiddenFromDiners()) list.add(r);
                    o.onRestaurants(list, doc.getMetadata().isFromCache());
                });
        return reg::remove;
    }

    public interface ManyCallback {
        /** Found restaurants by ID (missing / disabled IDs are simply absent). */
        void onRestaurants(java.util.Map<String, Restaurant> byId, boolean complete);
    }

    /**
     * Several restaurants by ID (Saved tab): uses what is already loaded, then reads the rest in
     * batches of 30 (Firestore's "in" limit) - never the whole collection, never one read per card.
     */
    public void fetchMany(List<String> ids, final ManyCallback cb) {
        final java.util.Map<String, Restaurant> found = new java.util.LinkedHashMap<>();
        final List<String> missing = new ArrayList<>();
        for (String id : ids) {
            Restaurant r = findLoaded(id);
            if (r != null && r.isHiddenFromDiners()) r = null; // disabled: read the server's answer below
            if (r == null && DevTestData.active() && DevTestData.isTestId(id)) r = DevTestData.fixture();
            if (r != null) found.put(id, r);
            else if (!DevTestData.isTestId(id)) missing.add(id);
        }
        if (missing.isEmpty()) {
            cb.onRestaurants(found, true);
            return;
        }
        final int batches = (missing.size() + 29) / 30;
        final int[] left = {batches};
        final boolean[] ok = {true};
        for (int i = 0; i < missing.size(); i += 30) {
            List<String> chunk = missing.subList(i, Math.min(missing.size(), i + 30));
            FirebaseFirestore.getInstance().collection(RestaurantSchema.COLLECTION)
                    .whereIn(com.google.firebase.firestore.FieldPath.documentId(), new ArrayList<Object>(chunk))
                    .get()
                    .addOnSuccessListener(snap -> {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            Restaurant r = parse(d);
                            if (r != null && !r.isHiddenFromDiners()) found.put(r.id, r);
                        }
                        if (--left[0] == 0) cb.onRestaurants(found, ok[0]);
                    })
                    .addOnFailureListener(e -> {
                        ok[0] = false;
                        Log.w(TAG, "Saved restaurants read failed: " + e.getClass().getSimpleName());
                        if (--left[0] == 0) cb.onRestaurants(found, false);
                    });
        }
    }

    // ------------------------------------------------------------------------------------------

    private void attach(String cityId) {
        lastFailure = null;
        Query q = FirebaseFirestore.getInstance().collection(RestaurantSchema.COLLECTION);
        if (RestaurantSchema.SERVER_CITY_FIELD != null && cityId != null) {
            q = q.whereEqualTo(RestaurantSchema.SERVER_CITY_FIELD, cityId);
            listeningCity = cityId;
        }
        q = q.limit(RestaurantSchema.MAX_DOCUMENTS);
        registration = q.addSnapshotListener(MetadataChanges.INCLUDE, (snap, e) -> {
            if (e != null) {
                Failure f = classify(e);
                Log.w(TAG, "Restaurant listener failed: " + e.getClass().getSimpleName()
                        + (f == Failure.NOT_ALLOWED ? " (publish the restaurant read rule)" : ""));
                lastFailure = f;
                registration = null; // a failed listener is dead; Retry attaches a new one
                for (Observer o : observers) o.onRestaurantsFailed(f);
                return;
            }
            if (snap == null) return;
            deliver(snap);
        });
    }

    private void deliver(QuerySnapshot snap) {
        List<Restaurant> list = new ArrayList<>();
        int skipped = 0;
        for (DocumentSnapshot doc : snap.getDocuments()) {
            Restaurant r = parse(doc);
            if (r == null) skipped++;
            else list.add(r);
        }
        if (skipped > 0) Log.w(TAG, skipped + " restaurant document(s) skipped: no usable name field");
        if (snap.size() >= RestaurantSchema.MAX_DOCUMENTS) {
            Log.w(TAG, "Reached MAX_DOCUMENTS (" + RestaurantSchema.MAX_DOCUMENTS
                    + "); set RestaurantSchema.SERVER_CITY_FIELD to filter by city on the server");
        }
        reportSchemaOnce(list, snap.size());
        list = DevTestData.addTo(list); // [DEV TEST DATA] dummy restaurant, debug builds only
        latest = Collections.unmodifiableList(list);
        latestFromCache = snap.getMetadata().isFromCache();
        lastFailure = null;
        for (Observer o : observers) o.onRestaurants(latest, latestFromCache);
    }

    private static Restaurant parse(DocumentSnapshot doc) {
        try {
            return Restaurant.parse(doc.getId(), doc.getData());
        } catch (RuntimeException e) {
            // One malformed document must never break the page; log which one for the developer.
            Log.w(TAG, "Restaurant " + doc.getId() + " could not be read: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Logcat: which field names exist, so the mapping in RestaurantSchema can be confirmed. */
    private void reportSchemaOnce(List<Restaurant> list, int documents) {
        if (schemaReported || documents == 0) return;
        schemaReported = true;
        Map<String, Integer> fields = new TreeMap<>();
        int logo = 0, cuisine = 0, city = 0, area = 0, coords = 0, open = 0, tables = 0, updated = 0;
        for (Restaurant r : list) {
            for (String f : r.fieldNames) {
                Integer n = fields.get(f);
                fields.put(f, n == null ? 1 : n + 1);
            }
            if (r.logo != null) logo++;
            if (!r.cuisines.isEmpty()) cuisine++;
            if (r.cityRaw != null) city++;
            if (r.areaRaw != null) area++;
            if (r.hasCoordinates()) coords++;
            if (r.openNow != null) open++;
            if (r.freeTables != null) tables++;
            if (r.updatedAtMillis != null) updated++;
        }
        Log.i(TAG, "Schema report: " + documents + " docs in '" + RestaurantSchema.COLLECTION
                + "', fields seen " + fields);
        Log.i(TAG, "Recognised: logo " + logo + ", cuisine " + cuisine + ", city " + city + ", area " + area
                + ", coordinates " + coords + ", open/closed " + open + ", free tables " + tables
                + ", updated time " + updated + " (of " + list.size() + ")");
    }

    private void detachNow() {
        main.removeCallbacks(detach);
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        listeningCity = null;
    }

    private static Failure classify(Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) e).getCode()) {
                case PERMISSION_DENIED:
                case UNAUTHENTICATED:
                    return Failure.NOT_ALLOWED;
                case UNAVAILABLE:
                case DEADLINE_EXCEEDED:
                    return Failure.NETWORK;
                default:
                    return Failure.UNKNOWN;
            }
        }
        return Failure.UNKNOWN;
    }
}