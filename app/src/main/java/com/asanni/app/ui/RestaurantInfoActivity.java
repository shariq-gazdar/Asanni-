package com.asanni.app.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.restaurants.AreaNames;
import com.asanni.app.restaurants.Availability;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantRepository;

import java.util.List;

/**
 * "Get details" destination (phase 8): everything the desktop app has shared about a
 * restaurant, grouped and readable, plus a Call button when a phone number exists. Nothing that
 * isn't in the data is shown (no menus, hours or facilities until restaurants provide them).
 *
 * Location: app/src/main/java/com/asanni/app/ui/RestaurantInfoActivity.java  (NEW in phase 8)
 */
public class RestaurantInfoActivity extends AsanniActivity implements RestaurantRepository.Observer {

    private final RestaurantRepository repository = RestaurantRepository.get();
    private String restaurantId;
    private RestaurantRepository.Watch watch;
    private Restaurant restaurant;
    private String areaName;
    private String cityName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_restaurant_info);
        SystemBarsHelper.apply(this, findViewById(R.id.rinfo_root));
        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        restaurantId = getIntent().getStringExtra(AppNavigator.EXTRA_RESTAURANT_ID);
        Restaurant loaded = restaurantId != null ? repository.findLoaded(restaurantId) : null;
        if (loaded != null && !loaded.isHiddenFromDiners()) bind(loaded);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (restaurantId != null && watch == null) watch = repository.watchOne(restaurantId, this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (watch != null) watch.stop();
        watch = null;
    }

    @Override
    public void onRestaurants(List<Restaurant> list, boolean fromCache) {
        if (isDestroyed()) return;
        Restaurant r = list.isEmpty() ? null : list.get(0);
        if (r != null && !r.isHiddenFromDiners()) {
            findViewById(R.id.rinfo_state).setVisibility(View.GONE);
            bind(r);
        } else if (!fromCache) {
            finish(); // removed / disabled while open: Details shows "no longer available"
        } else if (restaurant == null && !ConnectivityMonitor.get(this).isOnline()) {
            onRestaurantsFailed(RestaurantRepository.Failure.NETWORK); // offline, nothing cached
        }
    }

    @Override
    public void onRestaurantsFailed(RestaurantRepository.Failure failure) {
        if (isDestroyed() || restaurant != null) return; // keep what is shown
        findViewById(R.id.rinfo_progress).setVisibility(View.GONE);
        View state = findViewById(R.id.rinfo_state);
        state.setVisibility(View.VISIBLE);
        boolean offline = !ConnectivityMonitor.get(this).isOnline() || failure == RestaurantRepository.Failure.NETWORK;
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(offline ? R.drawable.ic_wifi_off : R.drawable.ic_storefront);
        ((TextView) state.findViewById(R.id.state_title)).setText(
                offline ? R.string.error_restaurants_offline_title : R.string.error_restaurants_title);
        TextView text = state.findViewById(R.id.state_text);
        text.setVisibility(View.VISIBLE);
        text.setText(offline ? R.string.saved_error_text : R.string.error_restaurants_text);
        TextView action = state.findViewById(R.id.state_action);
        action.setVisibility(View.VISIBLE);
        action.setText(R.string.error_retry);
        action.setOnClickListener(v -> {
            state.setVisibility(View.GONE);
            findViewById(R.id.rinfo_progress).setVisibility(View.VISIBLE);
            if (watch != null) watch.stop();
            watch = repository.watchOne(restaurantId, this);
        });
    }

    private void bind(final Restaurant r) {
        boolean first = restaurant == null;
        restaurant = r;
        if (first) {
            new AreaNames().resolve(r.cityRaw, r.areaRaw, (area, city) -> {
                if (isDestroyed()) return;
                areaName = area != null ? area : r.areaLabel();
                cityName = city;
                if (restaurant != null) render(restaurant);
            });
        }
        render(r);
    }

    private void render(Restaurant r) {
        long now = System.currentTimeMillis();
        findViewById(R.id.rinfo_progress).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.rinfo_name)).setText(r.name != null ? r.name : getString(R.string.detail_name_fallback));
        findViewById(R.id.rinfo_test).setVisibility(r.isTestData ? View.VISIBLE : View.GONE);
        LinearLayout sections = findViewById(R.id.rinfo_sections);
        sections.removeAllViews();

        // Right now
        heading(sections, R.string.rinfo_heading_status);
        row(sections, R.string.rinfo_open, yesNo(r.openNow), null, null);
        row(sections, R.string.rinfo_accepting, yesNo(r.acceptingDiners), null, null);
        String tables;
        if (r.freeTables == null) tables = getString(R.string.rinfo_not_shared);
        else if (r.totalTables != null) tables = getString(R.string.rinfo_tables_value, r.freeTables, r.totalTables);
        else tables = String.valueOf(r.freeTables);
        if (r.freeTables != null && !r.hasCurrentAvailability(now)) tables = tables + " · " + getString(R.string.wait_outdated);
        row(sections, R.string.rinfo_tables, tables, null, null);
        String wait = r.waitMinutes == null ? getString(R.string.rinfo_not_shared)
                : r.waitMinutes == 0 ? getString(R.string.wait_none) : getString(R.string.wait_minutes, r.waitMinutes);
        if (r.waitMinutes != null && !Availability.isFresh(r, now)) wait = wait + " · " + getString(R.string.wait_outdated);
        row(sections, R.string.rinfo_wait, wait, null, null);
        String updated = RestaurantViews.updated(this, r.updatedAtMillis, now);
        row(sections, R.string.rinfo_updated, updated != null ? updated : getString(R.string.rinfo_not_shared), null, null);

        // Food & price
        heading(sections, R.string.rinfo_heading_food);
        row(sections, R.string.rinfo_cuisines, r.cuisines.isEmpty() ? getString(R.string.rinfo_not_shared)
                : android.text.TextUtils.join(", ", r.cuisines), null, null);
        String price = priceText(r);
        row(sections, R.string.rinfo_price, price != null ? price : getString(R.string.rinfo_not_shared), null, null);

        // Place
        heading(sections, R.string.rinfo_heading_place);
        row(sections, R.string.rinfo_area, areaName != null ? areaName : getString(R.string.rinfo_not_shared), null, null);
        row(sections, R.string.rinfo_city, cityName != null ? cityName : getString(R.string.rinfo_not_shared), null, null);
        row(sections, R.string.rinfo_address, r.address != null ? r.address : getString(R.string.rinfo_not_shared), null, null);

        // Contact
        heading(sections, R.string.rinfo_heading_contact);
        final String dial = dialable(r.phone);
        row(sections, R.string.rinfo_phone, dial != null ? dial : getString(R.string.rinfo_not_shared),
                dial != null ? getString(R.string.rinfo_call) : null, dial == null ? null : v -> {
                    try {
                        // ACTION_DIAL only opens the dialer; nothing is called automatically.
                        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + dial)));
                    } catch (ActivityNotFoundException ignored) {
                        // no dialer (tablet)
                    }
                });
    }

    /** Same rules as Restaurant Details. */
    private String priceText(Restaurant r) {
        if (Boolean.FALSE.equals(r.perHead)) return getString(R.string.price_variable);
        if (r.price == null) return null;
        String amount = RestaurantDetailActivity.formatAmount(r.price);
        return Boolean.TRUE.equals(r.perHead) ? getString(R.string.price_per_person, amount) : getString(R.string.price_from, amount);
    }

    /** The desktop app stores numbers without '+' (e.g. 922135000000): add it for dialing. */
    static String dialable(String phone) {
        if (phone == null) return null;
        String p = phone.trim();
        if (p.startsWith("+")) return p;
        if (p.startsWith("92") && p.length() >= 11) return "+" + p;
        return p;
    }

    private String yesNo(Boolean b) {
        return b == null ? getString(R.string.rinfo_not_shared) : getString(b ? R.string.rinfo_yes : R.string.rinfo_no);
    }

    private void heading(LinearLayout parent, int text) {
        TextView h = (TextView) LayoutInflater.from(this).inflate(R.layout.item_filter_heading, parent, false);
        h.setText(text);
        h.setTextColor(getColor(R.color.asanni_welcome_accent));
        parent.addView(h);
    }

    private void row(LinearLayout parent, int label, String value, String action, View.OnClickListener onAction) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_info_row, parent, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(label);
        ((TextView) row.findViewById(R.id.row_value)).setText(value);
        TextView a = row.findViewById(R.id.row_action);
        a.setVisibility(action != null ? View.VISIBLE : View.GONE);
        a.setText(action);
        a.setOnClickListener(onAction);
        row.setContentDescription(getString(label) + ": " + value);
        parent.addView(row);
    }
}