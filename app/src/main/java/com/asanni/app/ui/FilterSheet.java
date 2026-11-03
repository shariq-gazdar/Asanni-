package com.asanni.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.restaurants.DiscoverFilters;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Filter bottom sheet shared by Discover, See All and Map (same DiscoverFilters everywhere).
 *
 * Location: app/src/main/java/com/asanni/app/ui/FilterSheet.java
 *           (NEW in phase 6, MODIFIED in phase 7: Available now, Under 3 km, Cuisine)
 *
 * Only options backed by real data are offered:
 *   Active / Not active   greyed out (with a note) when no restaurant reports open/closed
 *   Available now         only when some restaurant reports a CURRENT free-table count
 *   Under 3 km            only when the phone's GPS position is known and restaurants have coordinates
 *   Cuisine               only the cuisines the restaurants actually list
 */
final class FilterSheet {

    interface Listener {
        void onFiltersChanged(DiscoverFilters filters);
    }

    /** Which options the current data supports. */
    static final class Options {
        final boolean status;
        final boolean availableNow;
        final boolean distance;
        final List<String> cuisines;

        Options(boolean status, boolean availableNow, boolean distance, List<String> cuisines) {
            this.status = status;
            this.availableNow = availableNow;
            this.distance = distance;
            this.cuisines = cuisines;
        }

        static Options from(List<Restaurant> restaurants, Double userLat, Double userLng, long now) {
            return new Options(RestaurantQuery.supportsStatusFilter(restaurants),
                    RestaurantQuery.supportsAvailableNow(restaurants, now),
                    RestaurantQuery.supportsDistance(restaurants, userLat, userLng),
                    RestaurantQuery.cuisines(restaurants));
        }
    }

    private FilterSheet() {}

    static void show(Activity activity, DiscoverFilters current, final Options options, final Listener listener) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final Dialog dialog = new Dialog(activity, R.style.AsanniPickerDialog);
        dialog.setContentView(R.layout.dialog_discover_filters);
        dialog.setCanceledOnTouchOutside(true);

        final View all = dialog.findViewById(R.id.filter_all);
        final View active = dialog.findViewById(R.id.filter_active);
        final View inactive = dialog.findViewById(R.id.filter_inactive);
        setup(all, activity.getString(R.string.filter_status_all), activity.getString(R.string.filter_status_all_detail), true);
        setup(active, activity.getString(R.string.filter_status_active), activity.getString(R.string.filter_status_active_detail), options.status);
        setup(inactive, activity.getString(R.string.filter_status_inactive), activity.getString(R.string.filter_status_inactive_detail), options.status);
        dialog.findViewById(R.id.filter_unavailable).setVisibility(options.status ? View.GONE : View.VISIBLE);

        // Keep only choices the data supports (a filter carried over from another screen may not be).
        DiscoverFilters start = current;
        if (!options.status) start = start.withStatus(DiscoverFilters.Status.ALL);
        if (!options.availableNow) start = start.withAvailableNow(false);
        if (!options.distance) start = start.withMaxDistanceKm(null);
        if (start.cuisine != null && !containsIgnoreCase(options.cuisines, start.cuisine)) start = start.withCuisine(null);
        final DiscoverFilters[] chosen = {start};

        // Phase 7 sections, added only when supported.
        LinearLayout more = dialog.findViewById(R.id.filter_more);
        LayoutInflater inflater = LayoutInflater.from(activity);
        final View availableRow = options.availableNow ? addSection(more, inflater, R.string.filter_availability_heading,
                activity.getString(R.string.chip_available_now), activity.getString(R.string.filter_available_detail)) : null;
        final View distanceRow = options.distance ? addSection(more, inflater, R.string.filter_distance_heading,
                activity.getString(R.string.chip_under_km), activity.getString(R.string.filter_distance_detail)) : null;
        final List<View> cuisineRows = new ArrayList<>();
        if (!options.cuisines.isEmpty()) {
            addHeading(more, inflater, R.string.filter_cuisine_heading);
            View any = inflater.inflate(R.layout.item_filter_option, more, false);
            setup(any, activity.getString(R.string.filter_cuisine_any), null, true);
            any.setTag(null);
            more.addView(any);
            cuisineRows.add(any);
            for (String c : options.cuisines) {
                View row = inflater.inflate(R.layout.item_filter_option, more, false);
                setup(row, c, null, true);
                row.setTag(c);
                more.addView(row);
                cuisineRows.add(row);
            }
        }

        final Runnable refresh = () -> {
            DiscoverFilters f = chosen[0];
            check(all, f.status == DiscoverFilters.Status.ALL);
            check(active, f.status == DiscoverFilters.Status.ACTIVE);
            check(inactive, f.status == DiscoverFilters.Status.NOT_ACTIVE);
            if (availableRow != null) check(availableRow, f.availableNow);
            if (distanceRow != null) check(distanceRow, f.maxDistanceKm != null);
            for (View row : cuisineRows) {
                Object tag = row.getTag();
                check(row, tag == null ? f.cuisine == null : ((String) tag).equalsIgnoreCase(f.cuisine == null ? "" : f.cuisine));
            }
        };
        refresh.run();
        all.setOnClickListener(v -> { chosen[0] = chosen[0].withStatus(DiscoverFilters.Status.ALL); refresh.run(); });
        active.setOnClickListener(v -> { chosen[0] = chosen[0].withStatus(DiscoverFilters.Status.ACTIVE); refresh.run(); });
        inactive.setOnClickListener(v -> { chosen[0] = chosen[0].withStatus(DiscoverFilters.Status.NOT_ACTIVE); refresh.run(); });
        if (availableRow != null) {
            availableRow.setOnClickListener(v -> { chosen[0] = chosen[0].withAvailableNow(!chosen[0].availableNow); refresh.run(); });
        }
        if (distanceRow != null) {
            distanceRow.setOnClickListener(v -> {
                chosen[0] = chosen[0].withMaxDistanceKm(chosen[0].maxDistanceKm == null ? DiscoverFilters.NEAR_KM : null);
                refresh.run();
            });
        }
        for (final View row : cuisineRows) {
            row.setOnClickListener(v -> { chosen[0] = chosen[0].withCuisine((String) row.getTag()); refresh.run(); });
        }
        dialog.findViewById(R.id.filter_reset).setOnClickListener(v -> {
            chosen[0] = DiscoverFilters.defaults();
            refresh.run();
        });
        dialog.findViewById(R.id.filter_apply).setOnClickListener(v -> {
            dialog.dismiss();
            listener.onFiltersChanged(chosen[0]);
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        // Long cuisine lists scroll inside the sheet (max ~70% of the screen).
        final View scroll = dialog.findViewById(R.id.filter_scroll);
        final int maxScroll = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v.getHeight() > maxScroll) {
                v.getLayoutParams().height = maxScroll;
                v.requestLayout();
            }
        });
        dialog.show();
    }

    /** Cuisine picker on its own (Discover's Cuisine chip): "Any cuisine" + the real cuisines. */
    static void showCuisines(Activity activity, final DiscoverFilters current, List<String> cuisines,
                             final Listener listener) {
        if (activity.isFinishing() || activity.isDestroyed() || cuisines.isEmpty()) return;
        final Dialog dialog = new Dialog(activity, R.style.AsanniPickerDialog);
        dialog.setContentView(R.layout.dialog_cuisine_picker);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout list = dialog.findViewById(R.id.cuisine_list);
        LayoutInflater inflater = LayoutInflater.from(activity);
        List<String> options = new ArrayList<>();
        options.add(null);
        options.addAll(cuisines);
        for (final String c : options) {
            View row = inflater.inflate(R.layout.item_filter_option, list, false);
            setup(row, c == null ? activity.getString(R.string.filter_cuisine_any) : c, null, true);
            check(row, c == null ? current.cuisine == null : c.equalsIgnoreCase(current.cuisine == null ? "" : current.cuisine));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onFiltersChanged(current.withCuisine(c));
            });
            list.addView(row);
        }
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        final View scroll = dialog.findViewById(R.id.cuisine_scroll);
        final int maxScroll = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.6f);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v.getHeight() > maxScroll) {
                v.getLayoutParams().height = maxScroll;
                v.requestLayout();
            }
        });
        dialog.show();
    }

    private static View addSection(LinearLayout parent, LayoutInflater inflater, int heading, String title, String detail) {
        addHeading(parent, inflater, heading);
        View row = inflater.inflate(R.layout.item_filter_option, parent, false);
        setup(row, title, detail, true);
        parent.addView(row);
        return row;
    }

    private static void addHeading(LinearLayout parent, LayoutInflater inflater, int heading) {
        TextView h = (TextView) inflater.inflate(R.layout.item_filter_heading, parent, false);
        h.setText(heading);
        parent.addView(h);
    }

    private static void setup(View row, String title, String detail, boolean enabled) {
        ((TextView) row.findViewById(R.id.filter_option_title)).setText(title);
        TextView d = row.findViewById(R.id.filter_option_detail);
        d.setText(detail);
        d.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        row.setEnabled(enabled);
        row.setClickable(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
    }

    private static void check(View row, boolean on) {
        row.findViewById(R.id.filter_option_check).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        row.setSelected(on);
        TextView title = row.findViewById(R.id.filter_option_title);
        TextView detail = row.findViewById(R.id.filter_option_detail);
        // Screen readers announce "selected" from setSelected(); the text needs no extra words.
        row.setContentDescription(detail.getVisibility() == View.VISIBLE
                ? title.getText() + ". " + detail.getText() : title.getText());
    }

    private static boolean containsIgnoreCase(List<String> list, String v) {
        for (String s : list) if (s.equalsIgnoreCase(v)) return true;
        return false;
    }
}