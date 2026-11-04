package com.asanni.app.premium;

import com.google.firebase.Timestamp;

import java.util.Date;
import java.util.Map;

/**
 * PHASE 9 (groundwork): a user's subscription state as written by a TRUSTED BACKEND into
 *   entitlements/{uid}
 *     planId        string   e.g. "premium_monthly" (see PremiumPlans)   - absent = free
 *     status        string   "active" | "grace" | "cancelled" | "expired" | "paused"
 *     provider      string   e.g. "google_play"
 *     startedAt     Timestamp
 *     expiresAt     Timestamp   access ends here (cancelled plans run until expiry)
 *     autoRenew     boolean
 *     cancelledAt   Timestamp?
 *     updatedAt     Timestamp
 *   entitlements/{uid}/history/{eventId}   purchase / renewal / cancellation events
 * The Firestore rules make this READ-ONLY for the app: only the Firebase Admin SDK on a server
 * (after verifying the purchase with Google Play) can write it. Pure logic, unit-tested.
 *
 * Location: app/src/main/java/com/asanni/app/premium/Entitlement.java  (NEW in phase 9)
 */
public final class Entitlement {

    public static final Entitlement FREE = new Entitlement(PremiumPlans.FREE, "none", null, null, null, false, null);

    public final String planId;
    public final String status;
    public final String provider;
    public final Long startedAt;
    public final Long expiresAt;
    public final boolean autoRenew;
    public final Long cancelledAt;

    private Entitlement(String planId, String status, String provider, Long startedAt, Long expiresAt,
                        boolean autoRenew, Long cancelledAt) {
        this.planId = planId;
        this.status = status;
        this.provider = provider;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.autoRenew = autoRenew;
        this.cancelledAt = cancelledAt;
    }

    /** Missing / malformed document = FREE (never Premium by accident). */
    public static Entitlement parse(Map<String, Object> d) {
        if (d == null) return FREE;
        Object plan = d.get("planId");
        Object status = d.get("status");
        if (!(plan instanceof String) || !(status instanceof String)) return FREE;
        Object provider = d.get("provider");
        return new Entitlement((String) plan, (String) status, provider instanceof String ? (String) provider : null,
                millis(d.get("startedAt")), millis(d.get("expiresAt")), Boolean.TRUE.equals(d.get("autoRenew")),
                millis(d.get("cancelledAt")));
    }

    /**
     * Premium access right now: a known paid plan, an active / grace / cancelled-but-not-expired
     * status, AND an expiry date in the future. No expiry date = no access.
     */
    public boolean isActive(long now) {
        if (PremiumPlans.FREE.equals(planId) || expiresAt == null || expiresAt <= now) return false;
        if (PremiumPlans.features(planId).isEmpty()) return false; // unknown plan = not Premium
        return "active".equals(status) || "grace".equals(status) || "cancelled".equals(status);
    }

    public boolean has(PremiumFeature feature, long now) {
        return isActive(now) && PremiumPlans.features(planId).contains(feature);
    }

    private static Long millis(Object o) {
        if (o instanceof Timestamp) {
            Date d = ((Timestamp) o).toDate();
            return d != null ? d.getTime() : null;
        }
        return null;
    }
}