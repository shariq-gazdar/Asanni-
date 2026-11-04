package com.asanni.app.reviews;

/**
 * PHASE 9: rating statistics calculated from real stored reviews (Play Store style: average,
 * total and how many reviews have 5, 4, 3, 2 and 1 stars). Pure logic, unit-tested.
 *
 * Location: app/src/main/java/com/asanni/app/reviews/ReviewStats.java  (NEW in phase 9)
 */
public final class ReviewStats {

    /** counts[1] .. counts[5]; counts[0] unused. */
    private final long[] counts;
    public final long total;
    /** 1.0 - 5.0, or null when there are no reviews. */
    public final Double average;

    public ReviewStats(long[] byStars) {
        counts = new long[6];
        long t = 0;
        long sum = 0;
        for (int s = 1; s <= 5; s++) {
            long c = byStars != null && byStars.length > s ? Math.max(0, byStars[s]) : 0;
            counts[s] = c;
            t += c;
            sum += c * s;
        }
        total = t;
        average = t == 0 ? null : (double) sum / t;
    }

    public long count(int stars) {
        return stars >= 1 && stars <= 5 ? counts[stars] : 0;
    }

    /** 0.0 - 1.0: the bar length for a star level. */
    public float fraction(int stars) {
        return total == 0 ? 0f : (float) count(stars) / total;
    }

    /** "4.3" (one decimal, Western digits). */
    public String averageText() {
        if (average == null) return "–";
        double rounded = Math.round(average * 10) / 10.0;
        return String.format(java.util.Locale.US, "%.1f", rounded);
    }

    public boolean hasReviews() {
        return total > 0;
    }
}