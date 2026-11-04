package com.asanni.app.saved;

/**
 * READ-ONLY restaurant reviews summary (phase 8). The page where diners WRITE reviews comes in a
 * later phase; nothing here can create a review.
 *
 * Location: app/src/main/java/com/asanni/app/saved/RestaurantFeedback.java  (NEW in phase 8)
 *
 * PHASE 9: reviews moved to restaurantOwner/{restaurantId}/reviews/{userUid} (nothing had been
 * written to the phase-8 path). Old phase-8 contract, kept for reference:
 *   restaurantReviews/{restaurantId}/reviews/{userUid}
 *       rating     int 1-5        (required)
 *       text       string ≤ 1000  (optional)
 *       createdAt  Timestamp      (server time)
 *       updatedAt  Timestamp      (server time, when edited)
 *   The document ID is the reviewer's UID, so each user has at most ONE review per restaurant.
 *
 * The summary uses Firestore's server-side count + average (one small read, no download of every
 * review). No reviews, rules not published yet, or any error -> "No reviews yet" (never invented).
 */
public final class RestaurantFeedback {

    public static final class Summary {
        public final long count;
        /** 1.0 - 5.0, or null when there are no reviews. */
        public final Double average;

        /** PHASE 9: true = couldn't be loaded (shown as such, never as "No reviews yet"). */
        public final boolean failed;

        Summary(long count, Double average) {
            this(count, average, false);
        }

        Summary(long count, Double average, boolean failed) {
            this.count = count;
            this.average = average;
            this.failed = failed;
        }

        public boolean hasReviews() {
            return count > 0 && average != null;
        }
    }

    public interface Callback {
        void onSummary(Summary summary);
    }

    private RestaurantFeedback() {}

    /**
     * PHASE 9: the same real statistics as the Description page (reviews now live in
     * restaurantOwner/{id}/reviews, see reviews/ReviewRepository). Failure -> "No reviews yet".
     */
    public static void load(final String restaurantId, final Callback cb) {
        com.asanni.app.reviews.ReviewRepository.loadStats(restaurantId, false, stats ->
                cb.onSummary(stats == null ? new Summary(0, null, true)
                        : !stats.hasReviews() ? new Summary(0, null)
                        : new Summary(stats.total, stats.average)));
    }
}