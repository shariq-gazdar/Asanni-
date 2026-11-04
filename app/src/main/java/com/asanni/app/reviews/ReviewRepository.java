package com.asanni.app.reviews;

import android.util.Log;

import com.asanni.app.auth.ProfileData;
import com.asanni.app.restaurants.RestaurantSchema;
import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE 9: customer reviews (write own review, read published reviews, rating statistics).
 *
 * Location: app/src/main/java/com/asanni/app/reviews/ReviewRepository.java  (NEW in phase 9)
 *
 * DATA (under the EXISTING restaurant document - no new top-level collection):
 *   restaurantOwner/{restaurantId}/reviews/{customerUid}
 *     authorUid        string   = the document ID = request.auth.uid (checked by the rules)
 *     authorName       string   public name, e.g. "Anas A." (first name + last initial)
 *     authorPhotoUrl   string?  https profile picture URL, or null
 *     rating           int 1-5
 *     text             string 1-5000 characters, not only spaces
 *     status           "published" (only an admin can set "hidden" - console / Admin SDK)
 *     createdAt        server timestamp (never changes)
 *     updatedAt        server timestamp (every edit)
 *     ownerReplyText   string 1-2000  - written ONLY by the restaurant's owner (desktop app)
 *     ownerReplyAt     server timestamp of the reply
 * One review per customer per restaurant (document ID = UID): submitting again EDITS it.
 *
 * STATISTICS: five server-side count() queries (rating == 1..5) = 5 small aggregation reads.
 * No review is downloaded to calculate the average, and no Cloud Function / paid service is
 * needed. Cached for 2 minutes; refreshed after submitting and on the page's refresh button.
 */
public final class ReviewRepository {

    private static final String TAG = "AsanniReviews";

    public static final String SUBCOLLECTION = "reviews";
    public static final String F_AUTHOR_UID = "authorUid";
    public static final String F_AUTHOR_NAME = "authorName";
    public static final String F_AUTHOR_PHOTO = "authorPhotoUrl";
    public static final String F_RATING = "rating";
    public static final String F_TEXT = "text";
    public static final String F_STATUS = "status";
    public static final String F_CREATED_AT = "createdAt";
    public static final String F_UPDATED_AT = "updatedAt";
    public static final String F_REPLY_TEXT = "ownerReplyText";
    public static final String F_REPLY_AT = "ownerReplyAt";
    public static final String STATUS_PUBLISHED = "published";

    /** [CONFIG] Same limits as config/firestore.rules. */
    public static final int MAX_TEXT = 5000;
    public static final int MAX_NAME = 60;
    public static final int TOP_COUNT = 5;
    public static final int PAGE_SIZE = 20;
    private static final long STATS_CACHE_MS = 2 * 60_000L;

    public enum Failure { SIGNED_OUT, NOT_ALLOWED, OFFLINE, INVALID, UNKNOWN }

    public interface StatsCallback {
        /** @param stats null = couldn't be loaded (never shown as "0 reviews") */
        void onStats(ReviewStats stats);
    }

    public interface ListCallback {
        /** @param last cursor for the next page (null = no more) */
        void onReviews(List<Review> reviews, DocumentSnapshot last);

        void onFailed(Failure f);
    }

    public interface MineCallback {
        /** @param mine null = this customer hasn't reviewed the restaurant yet */
        void onMine(Review mine);

        void onFailed(Failure f);
    }

    public interface SubmitCallback {
        void onSubmitted(boolean edited);

        void onFailed(Failure f);
    }

    private static final Map<String, ReviewStats> STATS = new HashMap<>();
    private static final Map<String, Long> STATS_AT = new HashMap<>();

    private ReviewRepository() {}

    static Query reviews(String restaurantId) {
        return FirebaseFirestore.getInstance().collection(RestaurantSchema.COLLECTION)
                .document(restaurantId).collection(SUBCOLLECTION);
    }

    private static Query published(String restaurantId) {
        return reviews(restaurantId).whereEqualTo(F_STATUS, STATUS_PUBLISHED);
    }

    // ------------------------------------------------------------------ statistics

    public static void loadStats(final String restaurantId, boolean force, final StatsCallback cb) {
        Long at = STATS_AT.get(restaurantId);
        if (!force && at != null && System.currentTimeMillis() - at < STATS_CACHE_MS) {
            cb.onStats(STATS.get(restaurantId));
            return;
        }
        final long[] counts = new long[6];
        final int[] left = {5};
        final boolean[] failed = {false};
        for (int s = 1; s <= 5; s++) {
            final int stars = s;
            published(restaurantId).whereEqualTo(F_RATING, stars).count().get(AggregateSource.SERVER)
                    .addOnSuccessListener(snap -> {
                        counts[stars] = snap.getCount();
                        if (--left[0] == 0) finishStats(restaurantId, counts, failed[0], cb);
                    })
                    .addOnFailureListener(e -> {
                        failed[0] = true;
                        Log.w(TAG, "Rating count failed: " + e.getClass().getSimpleName());
                        if (--left[0] == 0) finishStats(restaurantId, counts, true, cb);
                    });
        }
    }

    private static void finishStats(String restaurantId, long[] counts, boolean failed, StatsCallback cb) {
        if (failed) {
            cb.onStats(null);
            return;
        }
        ReviewStats s = new ReviewStats(counts);
        STATS.put(restaurantId, s);
        STATS_AT.put(restaurantId, System.currentTimeMillis());
        cb.onStats(s);
    }

    /** The phase-8 Details summary uses the same statistics. */
    public static ReviewStats cachedStats(String restaurantId) {
        return STATS.get(restaurantId);
    }

    // ------------------------------------------------------------------ reading

    /** Top five: highest rating first, newest first among equal ratings (no helpful votes yet). */
    public static void loadTop(String restaurantId, boolean fromServer, final ListCallback cb) {
        Query q = published(restaurantId)
                .orderBy(F_RATING, Query.Direction.DESCENDING)
                .orderBy(F_UPDATED_AT, Query.Direction.DESCENDING)
                .limit(TOP_COUNT);
        run(q, fromServer, TOP_COUNT, cb);
    }

    /** All published reviews, newest first, PAGE_SIZE at a time. */
    public static void loadPage(String restaurantId, DocumentSnapshot after, final ListCallback cb) {
        Query q = published(restaurantId).orderBy(F_UPDATED_AT, Query.Direction.DESCENDING);
        if (after != null) q = q.startAfter(after);
        run(q.limit(PAGE_SIZE), true, PAGE_SIZE, cb); // server: a short CACHED page isn't "the end"
    }

    private static void run(Query q, boolean fromServer, final int pageSize, final ListCallback cb) {
        (fromServer ? q.get(Source.SERVER) : q.get())
                .addOnSuccessListener(snap -> cb.onReviews(parseAll(snap), lastOf(snap, pageSize)))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Reviews read failed: " + e.getClass().getSimpleName()
                            + (classify(e) == Failure.NOT_ALLOWED ? " (publish the review rules / index)" : ""));
                    cb.onFailed(classify(e));
                });
    }

    private static List<Review> parseAll(QuerySnapshot snap) {
        List<Review> out = new ArrayList<>();
        for (DocumentSnapshot d : snap.getDocuments()) {
            Review r = Review.parse(d.getId(), d.getData());
            if (r != null) out.add(r);
        }
        return out;
    }

    /** A full page means there may be more; a shorter one is the end. */
    private static DocumentSnapshot lastOf(QuerySnapshot snap, int pageSize) {
        List<DocumentSnapshot> docs = snap.getDocuments();
        return docs.size() >= pageSize && !docs.isEmpty() ? docs.get(docs.size() - 1) : null;
    }

    /** This customer's own review (to edit it instead of creating a duplicate). */
    public static void loadMine(String restaurantId, String uid, final MineCallback cb) {
        mine(restaurantId, uid).get()
                .addOnSuccessListener(doc -> cb.onMine(doc.exists() ? Review.parse(doc.getId(), doc.getData()) : null))
                .addOnFailureListener(e -> cb.onFailed(classify(e)));
    }

    private static DocumentReference mine(String restaurantId, String uid) {
        return FirebaseFirestore.getInstance().collection(RestaurantSchema.COLLECTION)
                .document(restaurantId).collection(SUBCOLLECTION).document(uid);
    }

    // ------------------------------------------------------------------ writing

    /** Public name: "Anas A." from the profile (never the e-mail or phone number). */
    public static String publicName(ProfileData p, String fallback) {
        String first = p != null ? clean(p.firstName) : null;
        String last = p != null ? clean(p.lastName) : null;
        String name;
        if (first != null) name = last != null ? first + " " + last.substring(0, 1).toUpperCase() + "." : first;
        else name = clean(fallback);
        if (name == null) return null;
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** Only an https picture (Google or a public URL); on-device pictures stay private. */
    public static String publicPhoto(ProfileData p) {
        if (p == null || p.isLocalPhoto() || p.photoUrl == null) return null;
        return p.photoUrl.startsWith("https://") && p.photoUrl.length() < 2048 ? p.photoUrl : null;
    }

    /** Validation shared with the UI (the security rules check the same things on the server). */
    public static boolean isValid(int rating, String text) {
        return rating >= 1 && rating <= 5 && text != null && !text.trim().isEmpty() && text.trim().length() <= MAX_TEXT;
    }

    /**
     * Creates or edits the signed-in customer's review in ONE transaction: the document ID is
     * the customer's own UID, so nobody can write for someone else (the rules enforce it too).
     * A restaurant owner's reply is never touched by the customer.
     */
    public static void submit(final String restaurantId, final String uid, final int rating, final String rawText,
                              final String authorName, final String authorPhoto, final SubmitCallback cb) {
        if (uid == null) {
            cb.onFailed(Failure.SIGNED_OUT);
            return;
        }
        final String text = rawText == null ? null : rawText.trim();
        if (!isValid(rating, text) || authorName == null) {
            cb.onFailed(Failure.INVALID);
            return;
        }
        final DocumentReference ref = mine(restaurantId, uid);
        FirebaseFirestore.getInstance().runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(ref);
            Map<String, Object> d = new HashMap<>();
            d.put(F_AUTHOR_UID, uid);
            d.put(F_AUTHOR_NAME, authorName);
            d.put(F_AUTHOR_PHOTO, authorPhoto);
            d.put(F_RATING, rating);
            d.put(F_TEXT, text);
            d.put(F_UPDATED_AT, FieldValue.serverTimestamp());
            if (existing.exists()) {
                // createdAt, status (an admin may have hidden it) and the owner's reply stay as they are
                tx.update(ref, d);
                return Boolean.TRUE;
            }
            d.put(F_STATUS, STATUS_PUBLISHED);
            d.put(F_CREATED_AT, FieldValue.serverTimestamp());
            tx.set(ref, d);
            return Boolean.FALSE;
        }).addOnSuccessListener(edited -> {
            STATS_AT.remove(restaurantId); // statistics are recalculated on the next load
            cb.onSubmitted(Boolean.TRUE.equals(edited));
        }).addOnFailureListener(e -> {
            Log.w(TAG, "Review submit failed: " + e.getClass().getSimpleName());
            cb.onFailed(classify(e));
        });
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    static Failure classify(Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) e).getCode()) {
                case PERMISSION_DENIED:
                    return Failure.NOT_ALLOWED;
                case UNAVAILABLE:
                case DEADLINE_EXCEEDED:
                    return Failure.OFFLINE;
                case UNAUTHENTICATED:
                    return Failure.SIGNED_OUT;
                case FAILED_PRECONDITION: // usually a missing composite index (see GUIDE.md)
                    Log.w(TAG, "Missing Firestore index? Deploy config/firestore.indexes.json");
                    return Failure.NOT_ALLOWED;
                case INVALID_ARGUMENT:
                    return Failure.INVALID;
                default:
                    return Failure.UNKNOWN;
            }
        }
        return Failure.UNKNOWN;
    }
}