package com.asanni.app.premium;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * PHASE 9 (groundwork): the ONE central place that answers "may this user use feature X?".
 *
 * Location: app/src/main/java/com/asanni/app/premium/Entitlements.java  (NEW in phase 9)
 *
 * Reads entitlements/{uid} (read-only for the app). Until it's loaded, or if it's missing or
 * can't be read, the answer is FREE. The app never writes it, so a modified app can't make
 * itself Premium in Firestore; real enforcement of paid server features must also happen on
 * the backend (next phase).
 */
public final class Entitlements {

    public static final String COLLECTION = "entitlements";
    private static final String TAG = "AsanniPremium";

    public interface Callback {
        void onEntitlement(Entitlement e);
    }

    private static Entitlement current = Entitlement.FREE;
    private static String loadedFor;

    private Entitlements() {}

    /** Synchronous check for screens: FREE until a verified entitlement was loaded. */
    public static boolean has(PremiumFeature feature) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || !u.getUid().equals(loadedFor)) return false;
        return current.has(feature, System.currentTimeMillis());
    }

    public static Entitlement current() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u != null && u.getUid().equals(loadedFor) ? current : Entitlement.FREE;
    }

    /** Loads (or reloads) the signed-in user's entitlement. Errors -> FREE. */
    public static void refresh(final Callback cb) {
        final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            if (cb != null) cb.onEntitlement(Entitlement.FREE);
            return;
        }
        FirebaseFirestore.getInstance().collection(COLLECTION).document(u.getUid()).get()
                .addOnSuccessListener(doc -> {
                    current = doc.exists() ? Entitlement.parse(doc.getData()) : Entitlement.FREE;
                    loadedFor = u.getUid();
                    if (cb != null) cb.onEntitlement(current);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Entitlement unavailable (treated as free): " + e.getClass().getSimpleName());
                    current = Entitlement.FREE;
                    loadedFor = u.getUid();
                    if (cb != null) cb.onEntitlement(Entitlement.FREE);
                });
    }

    /** Sign-out: forget everything. */
    public static void clear() {
        current = Entitlement.FREE;
        loadedFor = null;
    }
}