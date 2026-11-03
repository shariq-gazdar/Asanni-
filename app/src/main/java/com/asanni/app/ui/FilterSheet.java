package com.asanni.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.restaurants.DiscoverFilters;

/**
 * Filter bottom sheet (Status: All / Active / Not active). Shared by Discover and See All.
 *
 * Location: app/src/main/java/com/asanni/app/ui/FilterSheet.java  (NEW in phase 6)
 *
 * Active / Not active are disabled (with an explanation) when no restaurant in the area reports
 * whether it is open, so the sheet never offers an option that can't work.
 */
final class FilterSheet {

    interface Listener {
        void onFiltersChanged(DiscoverFilters filters);
    }

    private FilterSheet() {}

    static void show(Activity activity, DiscoverFilters current, boolean statusSupported, final Listener listener) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final Dialog dialog = new Dialog(activity, R.style.AsanniPickerDialog);
        dialog.setContentView(R.layout.dialog_discover_filters);
        dialog.setCanceledOnTouchOutside(true);

        final View all = dialog.findViewById(R.id.filter_all);
        final View active = dialog.findViewById(R.id.filter_active);
        final View inactive = dialog.findViewById(R.id.filter_inactive);
        setup(all, R.string.filter_status_all, R.string.filter_status_all_detail, true);
        setup(active, R.string.filter_status_active, R.string.filter_status_active_detail, statusSupported);
        setup(inactive, R.string.filter_status_inactive, R.string.filter_status_inactive_detail, statusSupported);
        dialog.findViewById(R.id.filter_unavailable).setVisibility(statusSupported ? View.GONE : View.VISIBLE);

        final DiscoverFilters[] chosen = {statusSupported ? current : current.withStatus(DiscoverFilters.Status.ALL)};
        final Runnable refresh = () -> {
            check(all, chosen[0].status == DiscoverFilters.Status.ALL);
            check(active, chosen[0].status == DiscoverFilters.Status.ACTIVE);
            check(inactive, chosen[0].status == DiscoverFilters.Status.NOT_ACTIVE);
        };
        refresh.run();
        all.setOnClickListener(v -> { chosen[0] = chosen[0].withStatus(DiscoverFilters.Status.ALL); refresh.run(); });
        active.setOnClickListener(v -> { chosen[0] = chosen[0].withStatus(DiscoverFilters.Status.ACTIVE); refresh.run(); });
        inactive.setOnClickListener(v -> { chosen[0] = chosen[0].withStatus(DiscoverFilters.Status.NOT_ACTIVE); refresh.run(); });
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
        dialog.show();
    }

    private static void setup(View row, int title, int detail, boolean enabled) {
        ((TextView) row.findViewById(R.id.filter_option_title)).setText(title);
        ((TextView) row.findViewById(R.id.filter_option_detail)).setText(detail);
        row.setEnabled(enabled);
        row.setClickable(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
    }

    private static void check(View row, boolean on) {
        row.findViewById(R.id.filter_option_check).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        row.setSelected(on);
        TextView title = row.findViewById(R.id.filter_option_title);
        // Screen readers announce "selected" from setSelected(); the text needs no extra words.
        row.setContentDescription(title.getText() + ". "
                + ((TextView) row.findViewById(R.id.filter_option_detail)).getText());
    }
}