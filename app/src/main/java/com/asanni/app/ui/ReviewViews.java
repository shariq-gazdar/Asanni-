package com.asanni.app.ui;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.profile.AvatarLoader;
import com.asanni.app.profile.InitialsDrawable;
import com.asanni.app.reviews.Review;
import com.asanni.app.reviews.ReviewStats;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * PHASE 9: shared drawing of rating statistics and review cards (Description page + Reviews page),
 * so both pages show reviews identically.
 *
 * Location: app/src/main/java/com/asanni/app/ui/ReviewViews.java  (NEW in phase 9)
 */
final class ReviewViews {

    /** [CONFIG] Long reviews are cut to this many lines until "Read more" is tapped. */
    static final int COLLAPSED_LINES = 6;

    private ReviewViews() {}

    /** "★★★★☆" (text, so it scales with the font size; the card's description says "4 out of 5"). */
    static String stars(int rating) {
        StringBuilder b = new StringBuilder(5);
        for (int i = 1; i <= 5; i++) b.append(i <= rating ? '★' : '☆');
        return b.toString();
    }

    static String number(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    /** Local date: "4 Nov" this year, "4 Nov 2025" otherwise. */
    static String date(Context c, Long millis) {
        if (millis == null) return "";
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH;
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        if (then.get(Calendar.YEAR) != Calendar.getInstance().get(Calendar.YEAR)) flags |= DateUtils.FORMAT_SHOW_YEAR;
        return DateUtils.formatDateTime(c, millis, flags);
    }

    /**
     * @param stats null + failed=false = loading; null + failed=true = couldn't load
     *              (never shown as "0 reviews"); zero reviews = "No reviews yet".
     */
    static void bindStats(Context c, View root, ReviewStats stats, boolean failed) {
        TextView average = root.findViewById(R.id.stats_average);
        TextView starsView = root.findViewById(R.id.stats_stars);
        TextView total = root.findViewById(R.id.stats_total);
        LinearLayout bars = root.findViewById(R.id.stats_bars);
        if (bars.getChildCount() == 0) {
            for (int s = 5; s >= 1; s--) {
                View row = LayoutInflater.from(c).inflate(R.layout.item_rating_bar, bars, false);
                ((TextView) row.findViewById(R.id.bar_label)).setText(String.valueOf(s));
                row.setTag(s);
                bars.addView(row);
            }
        }
        boolean has = stats != null && stats.hasReviews();
        average.setText(has ? stats.averageText() : "–");
        starsView.setText(has ? stars((int) Math.round(stats.average)) : stars(0));
        starsView.setAlpha(has ? 1f : 0.4f);
        if (stats == null) total.setText(failed ? c.getString(R.string.stats_failed) : "");
        else if (!has) total.setText(R.string.stats_none);
        else total.setText(c.getResources().getQuantityString(R.plurals.stats_total,
                    (int) Math.min(stats.total, Integer.MAX_VALUE), number(stats.total)));
        for (int i = 0; i < bars.getChildCount(); i++) {
            View row = bars.getChildAt(i);
            final int s = (Integer) row.getTag();
            final float fraction = stats != null ? stats.fraction(s) : 0f;
            TextView count = row.findViewById(R.id.bar_count);
            count.setText(stats != null ? number(stats.count(s)) : "");
            final View track = row.findViewById(R.id.bar_track);
            final View fill = row.findViewById(R.id.bar_fill);
            track.post(() -> { // animate the bar to its real share of all reviews
                ViewGroup.LayoutParams lp = fill.getLayoutParams();
                int target = Math.round(track.getWidth() * fraction);
                if (fraction > 0 && target < 6) target = 6; // a single review is still visible
                lp.width = target;
                fill.setLayoutParams(lp);
                fill.setScaleX(0f);
                fill.setPivotX(0f);
                fill.animate().scaleX(1f).setDuration(MotionPrefs.enabled(c) ? 420 : 0).start();
            });
            row.setContentDescription(c.getString(R.string.bar_description, s, stats != null ? number(stats.count(s)) : "–"));
        }
        root.setContentDescription(has
                ? c.getString(R.string.stats_description, stats.averageText(), number(stats.total))
                : total.getText());
    }

    static View reviewCard(Context c, ViewGroup parent, Review r, boolean collapsible) {
        View card = LayoutInflater.from(c).inflate(R.layout.item_review, parent, false);
        bindReview(c, card, r, collapsible);
        return card;
    }

    static void bindReview(final Context c, View card, final Review r, boolean collapsible) {
        String name = r.authorName != null ? r.authorName : c.getString(R.string.review_anonymous);
        ((TextView) card.findViewById(R.id.review_name)).setText(name);
        ((TextView) card.findViewById(R.id.review_stars)).setText(stars(r.rating));
        String when = date(c, r.shownDate());
        if (r.updatedAt != null && r.createdAt != null && r.updatedAt - r.createdAt > 60_000L) {
            when = when + " · " + c.getString(R.string.review_edited);
        }
        ((TextView) card.findViewById(R.id.review_date)).setText(when);
        ImageView avatar = card.findViewById(R.id.review_avatar);
        // Missing / deleted profile picture -> initials of the public name.
        AvatarLoader.load(avatar, r.authorPhotoUrl, InitialsDrawable.avatarFallback(c, name));

        final TextView text = card.findViewById(R.id.review_text);
        final TextView more = card.findViewById(R.id.review_more);
        text.setText(r.text);
        text.setMaxLines(collapsible ? COLLAPSED_LINES : Integer.MAX_VALUE);
        more.setVisibility(View.GONE);
        more.setText(R.string.review_read_more); // recycled cards start collapsed
        if (collapsible) {
            text.post(() -> {
                android.text.Layout l = text.getLayout();
                boolean cut = l != null && l.getLineCount() > 0 && l.getEllipsisCount(l.getLineCount() - 1) > 0;
                more.setVisibility(cut ? View.VISIBLE : View.GONE);
            });
            more.setOnClickListener(v -> {
                boolean expand = text.getMaxLines() != Integer.MAX_VALUE;
                text.setMaxLines(expand ? Integer.MAX_VALUE : COLLAPSED_LINES);
                more.setText(expand ? R.string.review_read_less : R.string.review_read_more);
            });
        }
        card.findViewById(R.id.review_header).setContentDescription(c.getString(R.string.review_card_description,
                name, r.rating, when, ""));
        text.setContentDescription(null);

        View reply = card.findViewById(R.id.review_reply);
        if (r.hasReply()) {
            reply.setVisibility(View.VISIBLE);
            ((TextView) card.findViewById(R.id.reply_label)).setText(R.string.review_reply_label);
            ((TextView) card.findViewById(R.id.reply_date)).setText(date(c, r.ownerReplyAt));
            ((TextView) card.findViewById(R.id.reply_text)).setText(r.ownerReplyText);
            reply.setContentDescription(c.getString(R.string.reply_description, date(c, r.ownerReplyAt), r.ownerReplyText));
        } else {
            reply.setVisibility(View.GONE);
        }
    }
}