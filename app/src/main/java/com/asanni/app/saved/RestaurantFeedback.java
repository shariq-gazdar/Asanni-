package com.asanni.app.saved;

import android.util.Log;

import com.google.firebase.firestore.AggregateField;
import com.google.firebase.firestore.AggregateQuerySnapshot;
import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * READ-ONLY restaurant reviews summary (phase 8). The page where diners WRITE reviews comes in a
 * later phase; nothing here can create a review.
 *
 * Location: app/src/main/java/com/asanni/app/saved/RestaurantFeedback.java  (NEW in phase 8)
 *
 * Data contract (recommended, see GUIDE.md):
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

    private static final String TAG = "AsanniReviews";
    public static final String COLLECTION = "restaurantReviews";
    public static final String SUBCOLLECTION = "reviews";
    public static final String FIELD_RATING = "rating";
    private static final long CACHE_MS = 2 * 60_000L;

    public static final class Summary {
        public final long count;
        /** 1.0 - 5.0, or null when there are no reviews. */
        public final Double average;

        Summary(long count, Double average) {
            this.count = count;
            this.average = average;
        }

        public boolean hasReviews() {
            return count > 0 && average != null;
        }
    }

    public interface Callback {
        void onSummary(Summary summary);
    }

    private static final Map<String, Summary> CACHE = new HashMap<>();
    private static final Map<String, Long> CACHED_AT = new HashMap<>();

    private RestaurantFeedback() {}

    public static void load(final String restaurantId, final Callback cb) {
        Long at = CACHED_AT.get(restaurantId);
        if (at != null && System.currentTimeMillis() - at < CACHE_MS) {
            cb.onSummary(CACHE.get(restaurantId));
            return;
        }
        final AggregateField.AverageAggregateField avg = AggregateField.average(FIELD_RATING);
        FirebaseFirestore.getInstance().collection(COLLECTION).document(restaurantId).collection(SUBCOLLECTION)
                .aggregate(AggregateField.count(), avg)
                .get(AggregateSource.SERVER)
                .addOnSuccessListener(snap -> {
                    Summary s = summarize(snap, avg);
                    CACHE.put(restaurantId, s);
                    CACHED_AT.put(restaurantId, System.currentTimeMillis());
                    cb.onSummary(s);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Reviews summary unavailable: " + e.getClass().getSimpleName());
                    cb.onSummary(new Summary(0, null)); // shown as "No reviews yet"
                });
    }

    private static Summary summarize(AggregateQuerySnapshot snap, AggregateField.AverageAggregateField avg) {
        long count = snap.getCount();
        Double average = snap.get(avg);
        // Ratings outside 1-5 can't come from valid reviews: don't show a broken number.
        if (average == null || average.isNaN() || average < 1 || average > 5) average = null;
        return new Summary(count, average);
    }
}