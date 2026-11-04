package com.asanni.app.premium;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PHASE 9 (groundwork): which features each plan unlocks. Plan IDs are the ones the trusted
 * backend writes into entitlements/{uid}.planId (and, later, the Google Play product IDs).
 * No prices here: prices come from Google Play at purchase time (next phase).
 *
 * Location: app/src/main/java/com/asanni/app/premium/PremiumPlans.java  (NEW in phase 9)
 */
public final class PremiumPlans {

    /** Everyone without a verified, active subscription. */
    public static final String FREE = "free";
    /** [CONFIG] Placeholder plan IDs for the next phase - rename to the real product IDs then. */
    public static final String PREMIUM_MONTHLY = "premium_monthly";
    public static final String PREMIUM_YEARLY = "premium_yearly";

    private static final Map<String, Set<PremiumFeature>> FEATURES = new HashMap<>();

    static {
        FEATURES.put(FREE, Collections.unmodifiableSet(EnumSet.noneOf(PremiumFeature.class)));
        Set<PremiumFeature> all = Collections.unmodifiableSet(EnumSet.allOf(PremiumFeature.class));
        FEATURES.put(PREMIUM_MONTHLY, all);
        FEATURES.put(PREMIUM_YEARLY, all);
    }

    private PremiumPlans() {}

    /** Unknown plan IDs unlock nothing (safe default). */
    public static Set<PremiumFeature> features(String planId) {
        Set<PremiumFeature> f = planId == null ? null : FEATURES.get(planId);
        return f != null ? f : FEATURES.get(FREE);
    }
}