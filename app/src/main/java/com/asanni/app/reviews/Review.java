package com.asanni.app.reviews;

import com.google.firebase.Timestamp;

import java.util.Date;
import java.util.Map;

/**
 * PHASE 9: one customer review, read from
 *   restaurantOwner/{restaurantId}/reviews/{customerUid}
 * Malformed fields are dropped, never "fixed" with invented values; a document without a valid
 * 1-5 rating or text is not shown at all.
 *
 * Location: app/src/main/java/com/asanni/app/reviews/Review.java  (NEW in phase 9)
 */
public final class Review {

    public final String authorUid;      // = document ID
    public final String authorName;     // public display name, e.g. "Anas A."
    public final String authorPhotoUrl; // https or null
    public final int rating;            // 1-5
    public final String text;
    public final Long createdAt;
    public final Long updatedAt;
    public final String ownerReplyText; // null = no reply yet
    public final Long ownerReplyAt;
    public final String status;

    private Review(String uid, String name, String photo, int rating, String text, Long createdAt,
                   Long updatedAt, String replyText, Long replyAt, String status) {
        this.authorUid = uid;
        this.authorName = name;
        this.authorPhotoUrl = photo;
        this.rating = rating;
        this.text = text;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.ownerReplyText = replyText;
        this.ownerReplyAt = replyAt;
        this.status = status;
    }

    /** null when the document can't be shown honestly (no valid rating or text). */
    public static Review parse(String id, Map<String, Object> d) {
        if (id == null || d == null) return null;
        Object r = d.get(ReviewRepository.F_RATING);
        if (!(r instanceof Number)) return null;
        double rv = ((Number) r).doubleValue();
        if (rv != Math.floor(rv) || rv < 1 || rv > 5) return null;
        String text = string(d.get(ReviewRepository.F_TEXT));
        if (text == null) return null;
        if (text.length() > ReviewRepository.MAX_TEXT) text = text.substring(0, ReviewRepository.MAX_TEXT);
        String name = string(d.get(ReviewRepository.F_AUTHOR_NAME));
        String photo = string(d.get(ReviewRepository.F_AUTHOR_PHOTO));
        if (photo != null && !photo.startsWith("https://")) photo = null;
        String reply = string(d.get(ReviewRepository.F_REPLY_TEXT));
        Object st = d.get(ReviewRepository.F_STATUS);
        return new Review(id, name, photo, (int) rv, text, millis(d.get(ReviewRepository.F_CREATED_AT)),
                millis(d.get(ReviewRepository.F_UPDATED_AT)), reply, millis(d.get(ReviewRepository.F_REPLY_AT)),
                st instanceof String ? (String) st : null);
    }

    /** The date shown on the card: last edit, else creation. */
    public Long shownDate() {
        return updatedAt != null ? updatedAt : createdAt;
    }

    public boolean hasReply() {
        return ownerReplyText != null;
    }

    private static String string(Object o) {
        if (!(o instanceof String)) return null;
        String s = ((String) o).trim();
        return s.isEmpty() ? null : s;
    }

    static Long millis(Object o) {
        if (o instanceof Timestamp) {
            Date d = ((Timestamp) o).toDate();
            return d != null ? d.getTime() : null;
        }
        if (o instanceof Date) return ((Date) o).getTime();
        return null; // numbers/strings are not trusted as times
    }
}