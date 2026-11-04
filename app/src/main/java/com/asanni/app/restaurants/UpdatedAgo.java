package com.asanni.app.restaurants;

/**
 * PHASE 9: how long ago the restaurant updated its availability (availabilityUpdatedAt).
 * Pure logic; the screen turns the result into "Just now", "5 mins ago", "3 hours ago" or a
 * local date/time. Missing or future (invalid) timestamps never become "Just now".
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/UpdatedAgo.java  (NEW in phase 9)
 */
public final class UpdatedAgo {

    public enum Unit { MISSING, INVALID, JUST_NOW, MINUTES, HOURS, DATE }

    public final Unit unit;
    /** Minutes or hours for MINUTES / HOURS; the timestamp (epoch millis) for DATE. */
    public final long value;

    private UpdatedAgo(Unit unit, long value) {
        this.unit = unit;
        this.value = value;
    }

    /** [CONFIG] After this many hours, an exact local date + time is shown instead. */
    public static final int HOURS_BEFORE_DATE = 12;

    public static UpdatedAgo of(Long updatedAt, long now) {
        if (updatedAt == null || updatedAt <= 0) return new UpdatedAgo(Unit.MISSING, 0);
        long age = now - updatedAt;
        if (age < -WaitStatus.CLOCK_SKEW_MS) return new UpdatedAgo(Unit.INVALID, 0);
        if (age < 60_000L) return new UpdatedAgo(Unit.JUST_NOW, 0);
        long minutes = age / 60_000L;
        if (minutes < 60) return new UpdatedAgo(Unit.MINUTES, minutes);
        long hours = minutes / 60;
        if (hours < HOURS_BEFORE_DATE) return new UpdatedAgo(Unit.HOURS, hours);
        return new UpdatedAgo(Unit.DATE, updatedAt);
    }
}