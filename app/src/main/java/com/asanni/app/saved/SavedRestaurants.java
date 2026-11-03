package com.asanni.app.saved;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The signed-in user's saved restaurants (phase 8).
 *
 * Location: app/src/main/java/com/asanni/app/saved/SavedRestaurants.java  (NEW in phase 8)
 *
 * Storage: users/{uid}/savedRestaurants/{restaurantId}  =  { restaurantId, savedAt }
 *   - keyed by the Firebase UID (never the email); only IDs are stored, never restaurant copies
 *   - the document ID IS the restaurant ID, so a restaurant can't be saved twice
 *   - Firestore rules: only the owner can read/create/delete their own entries (config/firestore.rules)
 *
 * One listener (only while a screen needs it) gives the saved IDs to Restaurant Details and the
 * Saved tab. It includes this phone's pending writes, so the bookmark changes at once; if the
 * server REJECTS a write, Firestore removes the pending change and the listener rolls the UI back
 * by itself (no permanent "saved" after a failed write).
 */
public final class SavedRestaurants {

    private static final String TAG = "AsanniSaved";
    public static final String SUBCOLLECTION = "savedRestaurants";
    public static final String FIELD_RESTAURANT_ID = "restaurantId";
    public static final String FIELD_SAVED_AT = "savedAt";
    /** [CONFIG] Safety cap on saved entries read at once. */
    public static final int MAX_SAVED = 200;
    /** [TIMING] No server answer after this long = "saved on this phone, will sync". */
    private static final long CONFIRM_TIMEOUT_MS = 10_000L;

    public enum Failure { SIGNED_OUT, NOT_ALLOWED, NETWORK, UNKNOWN }

    public interface Listener {
        /** Saved IDs, most recently saved first. */
        void onSavedChanged(List<String> restaurantIds, boolean fromCache);
        void onSavedFailed(Failure failure);
    }

    public interface WriteCallback {
        void onConfirmed();
        /** Not confirmed in time (offline): stays on this phone and syncs later. */
        void onPending();
        void onFailed(Failure failure);
    }

    private static SavedRestaurants instance;

    public static synchronized SavedRestaurants get() {
        if (instance == null) instance = new SavedRestaurants();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> inFlight = new HashSet<>();
    private ListenerRegistration registration;
    private String listeningUid;
    private List<String> latest;          // null = not loaded yet
    private boolean latestFromCache;
    private Failure lastFailure;

    private SavedRestaurants() {}

    // ------------------------------------------------------------------ reading

    public void observe(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            l.onSavedFailed(Failure.SIGNED_OUT);
            return;
        }
        if (registration != null && !user.getUid().equals(listeningUid)) detach(); // account changed
        if (registration == null) attach(user.getUid());
        else if (latest != null) l.onSavedChanged(latest, latestFromCache);
        else if (lastFailure != null) l.onSavedFailed(lastFailure);
    }

    public void stopObserving(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) detach();
    }

    /** Last known state (null = unknown yet). */
    public Boolean isSaved(String restaurantId) {
        return latest == null ? null : latest.contains(restaurantId);
    }

    public boolean isBusy(String restaurantId) {
        return inFlight.contains(restaurantId);
    }

    public void retry() {
        detach();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && !listeners.isEmpty()) attach(user.getUid());
    }

    private void attach(String uid) {
        listeningUid = uid;
        lastFailure = null;
        Query q = collection(uid).orderBy(FIELD_SAVED_AT, Query.Direction.DESCENDING).limit(MAX_SAVED);
        registration = q.addSnapshotListener(MetadataChanges.INCLUDE, (snap, e) -> {
            if (e != null) {
                lastFailure = classify(e);
                Log.w(TAG, "Saved list listener failed: " + e.getClass().getSimpleName());
                registration = null;
                for (Listener l : listeners) l.onSavedFailed(lastFailure);
                return;
            }
            if (snap != null) deliver(snap);
        });
    }

    private void deliver(QuerySnapshot snap) {
        // LinkedHashSet: no duplicate IDs even if a malformed document repeats one.
        Set<String> ids = new LinkedHashSet<>();
        for (DocumentSnapshot d : snap.getDocuments()) {
            String id = d.getId();
            if (id != null && !id.isEmpty()) ids.add(id);
        }
        latest = Collections.unmodifiableList(new ArrayList<>(ids));
        latestFromCache = snap.getMetadata().isFromCache();
        for (Listener l : listeners) l.onSavedChanged(latest, latestFromCache);
    }

    private void detach() {
        if (registration != null) registration.remove();
        registration = null;
        listeningUid = null;
        latest = null;
        lastFailure = null;
    }

    // ------------------------------------------------------------------ writing

    /** Saves (save = true) or removes a restaurant. Repeated taps while busy are ignored. */
    public boolean setSaved(String restaurantId, boolean save, WriteCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            cb.onFailed(Failure.SIGNED_OUT);
            return false;
        }
        if (restaurantId == null || restaurantId.isEmpty() || restaurantId.contains("/")) {
            cb.onFailed(Failure.UNKNOWN);
            return false;
        }
        if (!inFlight.add(restaurantId)) return false; // already saving/removing this one
        final boolean[] done = {false};
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            inFlight.remove(restaurantId);
            cb.onPending();
        };
        main.postDelayed(timeout, CONFIRM_TIMEOUT_MS);
        com.google.android.gms.tasks.Task<Void> task;
        if (save) {
            Map<String, Object> doc = new HashMap<>();
            doc.put(FIELD_RESTAURANT_ID, restaurantId);
            doc.put(FIELD_SAVED_AT, FieldValue.serverTimestamp());
            task = collection(user.getUid()).document(restaurantId).set(doc);
        } else {
            task = collection(user.getUid()).document(restaurantId).delete();
        }
        task.addOnSuccessListener(unused -> {
            main.removeCallbacks(timeout);
            inFlight.remove(restaurantId);
            if (done[0]) return;
            done[0] = true;
            cb.onConfirmed();
        }).addOnFailureListener(e -> {
            main.removeCallbacks(timeout);
            inFlight.remove(restaurantId);
            Log.w(TAG, (save ? "Save" : "Unsave") + " failed: " + e.getClass().getSimpleName());
            if (done[0]) return;
            done[0] = true;
            cb.onFailed(classify(e));
        });
        return true;
    }

    private static CollectionReference collection(String uid) {
        return FirebaseFirestore.getInstance().collection("users").document(uid).collection(SUBCOLLECTION);
    }

    private static Failure classify(Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) e).getCode()) {
                case PERMISSION_DENIED:
                    return Failure.NOT_ALLOWED;
                case UNAUTHENTICATED:
                    return Failure.SIGNED_OUT;
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