package com.asanni.app.ui;

import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.restaurants.Availability;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantImageLoader;
import com.asanni.app.restaurants.RestaurantQuery;
import com.asanni.app.restaurants.RestaurantSchema;

import java.util.Locale;

/**
 * Turns a Restaurant into card/row/badge content. Shared by Discover, See All and details so the
 * same data always reads the same way. Only real values are shown; unknown values are omitted or
 * shown as a neutral "not shared" text, never as 0 or a guess.
 *
 * Location: app/src/main/java/com/asanni/app/ui/RestaurantViews.java  (NEW in phase 6)
 */
final class RestaurantViews {

    private RestaurantViews() {}

    // ------------------------------------------------------------------ badge

    // Phase 7: the state itself lives in restaurants/Availability (shared with map markers).
    private static Availability.Kind kind(Restaurant r, long now) {
        return Availability.of(r, now);
    }

    /** Long text, always used for screen readers ("8 tables available"). */
    static String badgeLong(Context c, Restaurant r, long now) {
        Resources res = c.getResources();
        switch (kind(r, now)) {
            case FREE:
                return res.getQuantityString(R.plurals.badge_tables_available, r.freeTables, r.freeTables);
            case WAIT:
                return c.getString(R.string.badge_wait, r.waitMinutes);
            case FULL:
                return c.getString(R.string.badge_full);
            case CLOSED:
                return c.getString(R.string.badge_closed);
            default:
                return c.getString(R.string.badge_unavailable);
        }
    }

    private static String badgeShort(Context c, Restaurant r, long now) {
        Resources res = c.getResources();
        switch (kind(r, now)) {
            case FREE:
                return res.getQuantityString(R.plurals.badge_free_short, r.freeTables, r.freeTables);
            case WAIT:
                return c.getString(R.string.badge_wait_short, r.waitMinutes);
            case FULL:
                return c.getString(R.string.badge_full);
            case CLOSED:
                return c.getString(R.string.badge_closed);
            default:
                return c.getString(R.string.badge_unavailable_short);
        }
    }

    /** @param badge an included layout/view_badge.xml */
    static void bindBadge(View badge, Restaurant r, long now, boolean longForm) {
        Context c = badge.getContext();
        Availability.Kind k = kind(r, now);
        int bg;
        int fg;
        switch (k) {
            case FREE:
                bg = R.drawable.bg_badge_free;
                fg = R.color.asanni_badge_free_text;
                break;
            case WAIT:
                bg = R.drawable.bg_badge_wait;
                fg = R.color.asanni_badge_wait_text;
                break;
            case CLOSED:
                bg = R.drawable.bg_badge_closed;
                fg = R.color.asanni_badge_closed_text;
                break;
            default:
                bg = R.drawable.bg_badge_neutral;
                fg = R.color.asanni_badge_neutral_text;
                break;
        }
        int color = c.getColor(fg);
        badge.setBackgroundResource(bg);
        TextView text = badge.findViewById(R.id.badge_text);
        ImageView dot = badge.findViewById(R.id.badge_dot);
        text.setText(longForm ? badgeLong(c, r, now) : badgeShort(c, r, now));
        text.setTextColor(color);
        dot.setColorFilter(color);
        // Dot only for live states; the text itself always carries the meaning (not color alone).
        dot.setVisibility(k == Availability.Kind.FREE || k == Availability.Kind.WAIT ? View.VISIBLE : View.GONE);
        badge.setContentDescription(badgeLong(c, r, now));
    }

    // ------------------------------------------------------------------ text pieces

    /** Area shown for a restaurant: its own readable area, else the selected area's catalogue name. */
    static String area(Restaurant r, LocationScope scope) {
        String a = r.areaLabel();
        if (a != null) return a;
        // Only fall back to the selected area's name when the restaurant really is in it (the Map
        // lists the whole city / all registered restaurants).
        return scope != null && RestaurantQuery.isInScope(r, scope) ? scope.shortLabel() : null;
    }

    /** "Contemporary · Clifton" (missing parts are left out). */
    static String cuisineAndArea(Context c, Restaurant r, LocationScope scope) {
        return join(c, r.cuisineText(), area(r, scope));
    }

    /** Small cards: "French · 1.6 km" when GPS distance is known, else "French · Clifton". */
    static String cuisineAndDistance(Context c, Restaurant r, LocationScope scope, Double km) {
        String first = r.cuisines.isEmpty() ? null : r.cuisines.get(0);
        String second = km != null ? distance(c, km) : area(r, scope);
        return join(c, first, second);
    }

    static String join(Context c, String a, String b) {
        boolean ha = a != null && !a.isEmpty();
        boolean hb = b != null && !b.isEmpty();
        if (ha && hb) return a + c.getString(R.string.meta_separator) + b;
        if (ha) return a;
        return hb ? b : "";
    }

    static String distance(Context c, double km) {
        if (km < 1) {
            int m = (int) (Math.round(km * 100) * 10); // nearest 10 m
            return c.getString(R.string.distance_m, Math.max(10, m));
        }
        String v = km < 10 ? String.format(Locale.US, "%.1f", km) : String.valueOf(Math.round(km));
        return c.getString(R.string.distance_km, v);
    }

    /** "Updated 4 min ago", or null when the time is unknown or in the future. */
    static String updated(Context c, Long millis, long now) {
        if (millis == null) return null;
        long age = now - millis;
        if (age < -5 * 60_000L) return null;
        Resources res = c.getResources();
        if (age < 60_000L) return c.getString(R.string.updated_just_now);
        if (age < 60 * 60_000L) {
            int m = (int) (age / 60_000L);
            return res.getQuantityString(R.plurals.updated_minutes, m, m);
        }
        if (age < 24 * 60 * 60_000L) {
            int h = (int) (age / (60 * 60_000L));
            return res.getQuantityString(R.plurals.updated_hours, h, h);
        }
        return c.getString(R.string.updated_days, DateUtils.formatDateTime(c, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
    }

    static Double km(Restaurant r, Double lat, Double lng) {
        return RestaurantQuery.distanceKm(r, lat, lng);
    }

    // ------------------------------------------------------------------ list row

    /** Binds layout/item_restaurant_row.xml. */
    static void bindRow(View row, Restaurant r, LocationScope scope, Double lat, Double lng, long now) {
        Context c = row.getContext();
        ImageView logo = row.findViewById(R.id.row_logo);
        int px = (int) (56 * c.getResources().getDisplayMetrics().density);
        // [UI ASSET] Fallback image: res/drawable/restaurant_placeholder.xml
        RestaurantImageLoader.load(logo, r.logo, px, R.drawable.restaurant_placeholder, null);
        ((TextView) row.findViewById(R.id.row_name)).setText(r.name);
        Double km = km(r, lat, lng);
        String meta = join(c, r.cuisineText(), km != null ? distance(c, km) : area(r, scope));
        TextView metaView = row.findViewById(R.id.row_meta);
        metaView.setText(meta);
        metaView.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        View badge = row.findViewById(R.id.row_badge);
        bindBadge(badge, r, now, false);
        String description = c.getString(R.string.restaurant_card_description, r.name,
                join(c, meta, badgeLong(c, r, now)));
        row.findViewById(R.id.row_card).setContentDescription(description);
    }
}