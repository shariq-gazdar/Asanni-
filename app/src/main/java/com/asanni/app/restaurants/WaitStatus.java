package com.asanni.app.restaurants;

/**
 * PHASE 9: the dine-in state shown on the Description page ("Dine-in availability").
 * Pure logic (no Android), unit-tested.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/WaitStatus.java  (NEW in phase 9)
 *
 * Rules (from the phase 9 brief, using the real fields waitMinutes, availableTables,
 * availabilityUpdatedAt, isOpen, acceptingDiners):
 *   AVAILABLE_NOW   waitMinutes == 0 (a real zero, never a missing value)
 *   SHORT_WAIT      1 - 20 minutes
 *   CURRENTLY_FULL  more than 20 minutes
 * Never invented:
 *   UNKNOWN         waitMinutes missing/invalid, no update time, a future update time,
 *                   or contradictory data (0 min wait but 0 free tables reported)
 *   STALE           the restaurant's last update is older than the freshness window
 *   NOT_ACCEPTING   the restaurant says it is closed or not accepting diners
 * availableTables = 0 alone is NOT treated as "full" (only the wait time decides the state).
 */
public final class WaitStatus {

    public enum State { AVAILABLE_NOW, SHORT_WAIT, CURRENTLY_FULL, NOT_ACCEPTING, STALE, UNKNOWN }

    /** [CONFIG] Waits up to this many minutes are a "short wait". */
    public static final int SHORT_WAIT_MAX_MINUTES = 20;
    /** Allowed clock difference between the restaurant's computer and the phone. */
    static final long CLOCK_SKEW_MS = 5 * 60_000L;

    private WaitStatus() {}

    public static State of(Restaurant r, long now) {
        if (r == null) return State.UNKNOWN;
        Long at = r.updatedAtMillis;
        if (at == null || at - now > CLOCK_SKEW_MS) return State.UNKNOWN; // missing / invalid time
        if (now - at > RestaurantSchema.AVAILABILITY_FRESH_MS) return State.STALE;
        if (Boolean.FALSE.equals(r.openNow) || Boolean.FALSE.equals(r.acceptingDiners)) return State.NOT_ACCEPTING;
        Integer w = r.waitMinutes;
        if (w == null || w < 0) return State.UNKNOWN;
        if (w == 0) {
            // "No wait" while the restaurant also reports zero free tables contradicts itself.
            if (r.freeTables != null && r.freeTables == 0) return State.UNKNOWN;
            return State.AVAILABLE_NOW;
        }
        return w <= SHORT_WAIT_MAX_MINUTES ? State.SHORT_WAIT : State.CURRENTLY_FULL;
    }

    /** The three "availability language" states the reference shows (others = none highlighted). */
    public static boolean isReported(State s) {
        return s == State.AVAILABLE_NOW || s == State.SHORT_WAIT || s == State.CURRENTLY_FULL;
    }
}