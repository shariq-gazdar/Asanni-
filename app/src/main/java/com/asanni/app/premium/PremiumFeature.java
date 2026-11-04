package com.asanni.app.premium;

/**
 * PHASE 9 (groundwork): every feature that MAY become Premium-only, named in ONE place. Screens
 * ask Entitlements.has(feature) instead of checking "is premium" themselves, so plans can be
 * added, renamed or split later without touching the screens.
 *
 * Location: app/src/main/java/com/asanni/app/premium/PremiumFeature.java  (NEW in phase 9)
 * Nothing is locked yet: no screen calls has() for a real feature in phase 9.
 */
public enum PremiumFeature {
    /** Example: alerts when a saved restaurant gets free tables. */
    LIVE_TABLE_ALERTS,
    /** Example: more than the basic distance radius in Discover. */
    EXTENDED_RADIUS,
    /** Example: no promotional content. */
    AD_FREE
}