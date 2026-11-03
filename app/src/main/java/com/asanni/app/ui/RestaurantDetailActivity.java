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
import com.asanni.app.location.CatalogueItem;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantImageLoader;
import com.asanni.app.restaurants.RestaurantRepository;

import java.util.List;

/**
 * Restaurant details: a minimal, read-only page made only from what the desktop app provides
 * (logo, name, cuisine, open/closed, tables, wait, updated time, area/address, distance, phone).
 * Missing information is left out; nothing is invented.
 *
 * Location: app/src/main/java/com/asanni/app/ui/RestaurantDetailActivity.java  (NEW in phase 6)
 *
 * Live: it observes the shared listener, so table counts update while the page is open.
 * "Call" opens the phone's dialer (no call permission needed; nothing is dialled automatically).
 */
public class RestaurantDetailActivity extends AsanniActivity implements RestaurantRepository.Observer {

    private final RestaurantRepository repository = RestaurantRepository.get();
    private String restaurantId;
    private LocationScope scope;
    private Double userLat;
    private Double userLng;
    private boolean fetchedOnce;
    /** True once the shared listener's data contained this restaurant (only then can it "disappear"). */
    private boolean seenInListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_restaurant_detail);
        SystemBarsHelper.apply(this, findViewById(R.id.detail_root));
        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        restaurantId = getIntent().getStringExtra(AppNavigator.EXTRA_RESTAURANT_ID);
        if (getIntent().hasExtra(AppNavigator.EXTRA_USER_LAT)) {
            userLat = getIntent().getDoubleExtra(AppNavigator.EXTRA_USER_LAT, 0);
            userLng = getIntent().getDoubleExtra(AppNavigator.EXTRA_USER_LNG, 0);
        }
        String cityId = getIntent().getStringExtra(AppNavigator.EXTRA_CITY_ID);
        if (cityId != null) {
            String cityName = getIntent().getStringExtra(AppNavigator.EXTRA_CITY_NAME);
            String areaId = getIntent().getStringExtra(AppNavigator.EXTRA_AREA_ID);
            String areaName = getIntent().getStringExtra(AppNavigator.EXTRA_AREA_NAME);
            scope = new LocationScope(new CatalogueItem(cityId, cityName != null ? cityName : cityId, null, null, null, false),
                    areaId == null ? null : new CatalogueItem(areaId, areaName != null ? areaName : areaId, null, null,
                            cityId, areaId.equals(cityId + "-all")));
        }

        Restaurant loaded = repository.findLoaded(restaurantId);
        if (loaded != null) bind(loaded);
        else if (restaurantId == null) showMissing();
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    private void load() {
        if (restaurantId == null) return;
        if (repository.findLoaded(restaurantId) != null || scope != null) {
            repository.observe(this, scope != null ? scope.city.id : null);
        }
        if (repository.findLoaded(restaurantId) == null && !fetchedOnce) {
            fetchedOnce = true; // e.g. the app was restarted on this page: read just this document
            repository.fetchOne(restaurantId, new RestaurantRepository.Observer() {
                @Override
                public void onRestaurants(List<Restaurant> list, boolean fromCache) {
                    if (isDestroyed()) return;
                    if (!list.isEmpty()) bind(list.get(0));
                    else showMissing();
                }

                @Override
                public void onRestaurantsFailed(RestaurantRepository.Failure failure) {
                    if (isDestroyed() || findViewById(R.id.detail_content).getVisibility() == View.VISIBLE) return;
                    showError();
                }
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        repository.stopObserving(this);
    }

    @Override
    public void onRestaurants(List<Restaurant> list, boolean fromCache) {
        for (Restaurant r : list) {
            if (r.id.equals(restaurantId)) {
                seenInListener = true;
                if (r.isHiddenFromDiners()) showMissing();
                else bind(r);
                return;
            }
        }
        // Not in the latest data (deleted or disabled by the restaurant) once the server answered.
        // (Not when it was only fetched on its own: it may be outside the listener's 500-document window.)
        if (!fromCache && seenInListener && list.size() < com.asanni.app.restaurants.RestaurantSchema.MAX_DOCUMENTS
                && findViewById(R.id.detail_content).getVisibility() == View.VISIBLE) showMissing();
    }

    @Override
    public void onRestaurantsFailed(RestaurantRepository.Failure failure) {
        // Keep what's shown; the page simply stops updating until the connection returns.
    }

    // ------------------------------------------------------------------------------------------

    private void bind(final Restaurant r) {
        long now = System.currentTimeMillis();
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        findViewById(R.id.detail_state).setVisibility(View.GONE);
        findViewById(R.id.detail_content).setVisibility(View.VISIBLE);

        float d = getResources().getDisplayMetrics().density;
        ImageView logo = findViewById(R.id.detail_logo);
        String image = r.logo != null ? r.logo : r.cover;
        final boolean isLogo = r.logo != null;
        logo.setContentDescription(getString(R.string.detail_logo_description, r.name));
        // [UI ASSET] Fallback image: res/drawable/restaurant_placeholder.xml
        RestaurantImageLoader.load(logo, image, (int) (190 * d), R.drawable.restaurant_placeholder, view -> {
            if (isLogo) { // show a logo whole, never cropped
                view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = (int) (20 * d);
                view.setPadding(pad, pad, pad, pad);
            }
        });

        ((TextView) findViewById(R.id.detail_name)).setText(r.name);
        TextView cuisine = findViewById(R.id.detail_cuisine);
        String cuisineText = r.cuisineText();
        cuisine.setText(cuisineText);
        cuisine.setVisibility(cuisineText == null ? View.GONE : View.VISIBLE);
        RestaurantViews.bindBadge(findViewById(R.id.detail_badge), r, now, true);

        // Right now
        LinearLayout status = findViewById(R.id.detail_status_lines);
        status.removeAllViews();
        addLine(status, R.drawable.ic_storefront, getString(r.openNow == null ? R.string.detail_open_unknown
                : r.openNow ? R.string.detail_open : R.string.detail_closed));
        if (r.acceptingDiners != null) {
            addLine(status, R.drawable.ic_role_diner,
                    getString(r.acceptingDiners ? R.string.detail_accepting : R.string.detail_not_accepting));
        }
        if (r.hasCurrentAvailability(now)) {
            String tables = r.totalTables != null
                    ? getResources().getQuantityString(R.plurals.detail_tables_of_total, r.totalTables, r.freeTables, r.totalTables)
                    : getResources().getQuantityString(R.plurals.badge_tables_available, r.freeTables, r.freeTables);
            addLine(status, R.drawable.ic_table_restaurant, tables);
        } else {
            addLine(status, R.drawable.ic_table_restaurant, getString(r.freeTables != null
                    ? R.string.detail_tables_stale : R.string.badge_unavailable));
        }
        if (r.waitMinutes != null && r.waitMinutes > 0) {
            addLine(status, R.drawable.ic_schedule, getString(R.string.badge_wait, r.waitMinutes));
        }
        String updated = RestaurantViews.updated(this, r.updatedAtMillis, now);
        if (updated != null) addLine(status, R.drawable.ic_schedule, updated);

        // Location
        LinearLayout location = findViewById(R.id.detail_location_lines);
        location.removeAllViews();
        String area = RestaurantViews.area(r, scope);
        if (r.address != null) addLine(location, R.drawable.ic_location_pin, r.address);
        else if (area != null) addLine(location, R.drawable.ic_location_pin, area);
        Double km = RestaurantViews.km(r, userLat, userLng);
        if (km != null) {
            addLine(location, R.drawable.ic_navigation_outline,
                    getString(R.string.distance_from_you, RestaurantViews.distance(this, km)));
        }
        boolean hasLocation = location.getChildCount() > 0;
        location.setVisibility(hasLocation ? View.VISIBLE : View.GONE);
        findViewById(R.id.detail_location_heading).setVisibility(hasLocation ? View.VISIBLE : View.GONE);

        View call = findViewById(R.id.detail_call);
        call.setVisibility(r.phone != null ? View.VISIBLE : View.GONE);
        call.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r.phone)));
            } catch (ActivityNotFoundException e) {
                // No dialer (e.g. a tablet): nothing to do.
            }
        });
    }

    private void addLine(LinearLayout parent, int icon, String text) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_detail_line, parent, false);
        ((ImageView) line.findViewById(R.id.line_icon)).setImageResource(icon);
        ((TextView) line.findViewById(R.id.line_text)).setText(text);
        parent.addView(line);
    }

    private void showMissing() {
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        findViewById(R.id.detail_content).setVisibility(View.GONE);
        View state = findViewById(R.id.detail_state);
        state.setVisibility(View.VISIBLE);
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(R.drawable.ic_storefront);
        ((TextView) state.findViewById(R.id.state_title)).setText(R.string.detail_not_found);
        state.findViewById(R.id.state_text).setVisibility(View.GONE);
        TextView action = state.findViewById(R.id.state_action);
        action.setText(R.string.list_back_to_discover);
        action.setOnClickListener(v -> finish());
    }

    private void showError() {
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        View state = findViewById(R.id.detail_state);
        state.setVisibility(View.VISIBLE);
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(R.drawable.ic_wifi_off);
        ((TextView) state.findViewById(R.id.state_title)).setText(R.string.error_restaurants_title);
        ((TextView) state.findViewById(R.id.state_text)).setText(R.string.error_restaurants_text);
        state.findViewById(R.id.state_text).setVisibility(View.VISIBLE);
        TextView action = state.findViewById(R.id.state_action);
        action.setText(R.string.error_retry);
        action.setOnClickListener(v -> {
            fetchedOnce = false;
            state.setVisibility(View.GONE);
            findViewById(R.id.detail_progress).setVisibility(View.VISIBLE);
            repository.stopObserving(this);
            load();
        });
    }
}