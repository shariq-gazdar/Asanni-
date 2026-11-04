package com.asanni.app.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.restaurants.AreaNames;
import com.asanni.app.restaurants.DevTestData;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantImageLoader;
import com.asanni.app.restaurants.RestaurantPhone;
import com.asanni.app.restaurants.RestaurantQuery;
import com.asanni.app.restaurants.RestaurantRepository;
import com.asanni.app.restaurants.UpdatedAgo;
import com.asanni.app.restaurants.WaitStatus;
import com.asanni.app.reviews.Review;
import com.asanni.app.reviews.ReviewRepository;
import com.asanni.app.reviews.ReviewStats;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

/**
 * Description page: "LIVE STATUS · Dine-in availability" (REWRITTEN in phase 9 from the
 * reference image; opened by "Get details" on Restaurant Details).
 *
 * Location: app/src/main/java/com/asanni/app/ui/RestaurantInfoActivity.java
 *           (NEW in phase 8, REWRITTEN in phase 9 - same class name, so navigation is unchanged)
 *
 * Data (all real, nothing invented):
 *   restaurant ... ONE live listener on restaurantOwner/{id} (RestaurantRepository.watchOne);
 *                  the refresh button reads that one document from the SERVER (refreshOne).
 *                  Fields: name, logoUrl (never the cover), areaId (-> areas catalogue name),
 *                  phone, waitMinutes, availableTables, availabilityUpdatedAt, isOpen,
 *                  acceptingDiners, location
 *   state ........ restaurants/WaitStatus (0 = Available now, 1-20 = Short wait, >20 = Currently
 *                  full; missing / stale / contradictory = honest unknown states)
 *   distance ..... straight line from the phone's GPS, like Restaurant Details
 *   by car ....... ONLY a real road route: from RouteMemory (a recent identical route) or after
 *                  "Open directions" (routing package, GraphHopper first). Opening this page
 *                  never requests a route.
 *   reviews ...... reviews/ReviewRepository (own review, statistics, top five)
 */
public class RestaurantInfoActivity extends AsanniActivity implements RestaurantRepository.Observer,
        DirectionsFlow.Ui {

    private static final int REQUEST_LOCATION = 9101;
    /** [CONFIG] How often "Updated 3 mins ago" is re-worded (no Firestore read). */
    private static final long TICK_MS = 30_000L;

    private final RestaurantRepository repository = RestaurantRepository.get();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String restaurantId;
    private LocationScope scope;
    private RestaurantRepository.Watch watch;
    private Restaurant restaurant;
    private String areaName;
    private boolean areaRequested;
    private String logoShown;

    private DeviceLocationHelper deviceLocation;
    private DeviceLocationHelper directionsLocation;
    private DirectionsFlow directions;
    private Double userLat;
    private Double userLng;
    private boolean locating;

    private boolean refreshing;
    private int refreshPending;
    private boolean refreshFailed;
    private ObjectAnimator refreshSpin;

    // reviews
    private ProfileData profile;
    private Review myReview;
    private boolean mineLoaded;
    private int selectedRating;
    private boolean submitting;
    private ReviewStats stats;
    private boolean statsFailed;
    private List<Review> topReviews;
    private boolean reviewsRequested;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (restaurant != null) bindStatus(restaurant, System.currentTimeMillis());
            main.postDelayed(this, TICK_MS);
        }
    };

    // ============================================================================ lifecycle

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
        deviceLocation = new DeviceLocationHelper(this);
        // Its OWN location helper: one helper cancels its previous request, and a cancelled
        // request never calls back - so distance refresh and directions must not share one.
        directionsLocation = new DeviceLocationHelper(this);
        directions = new DirectionsFlow(this, directionsLocation, REQUEST_LOCATION, this);

        Intent in = getIntent();
        restaurantId = in.getStringExtra(AppNavigator.EXTRA_RESTAURANT_ID);
        if (in.hasExtra(AppNavigator.EXTRA_USER_LAT) && in.hasExtra(AppNavigator.EXTRA_USER_LNG)) {
            userLat = in.getDoubleExtra(AppNavigator.EXTRA_USER_LAT, 0);
            userLng = in.getDoubleExtra(AppNavigator.EXTRA_USER_LNG, 0);
        }
        String cityId = in.getStringExtra(AppNavigator.EXTRA_CITY_ID);
        if (cityId != null) {
            String cityName = in.getStringExtra(AppNavigator.EXTRA_CITY_NAME);
            String areaId = in.getStringExtra(AppNavigator.EXTRA_AREA_ID);
            String areaNameExtra = in.getStringExtra(AppNavigator.EXTRA_AREA_NAME);
            scope = new LocationScope(new CatalogueItem(cityId, cityName != null ? cityName : cityId, null, null, null, false),
                    areaId == null ? null : new CatalogueItem(areaId, areaNameExtra != null ? areaNameExtra : areaId,
                            null, null, cityId, areaId.equals(cityId + "-all")));
        }

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.desc_open_directions).setOnClickListener(v -> directions.start(restaurant, scope));
        findViewById(R.id.desc_review_submit).setOnClickListener(v -> submitReview());
        findViewById(R.id.desc_see_more).setOnClickListener(v -> {
            if (restaurantId != null) AppNavigator.openReviews(this, restaurantId, restaurant != null ? restaurant.name : null);
        });
        findViewById(R.id.desc_reviews_state).setOnClickListener(v -> {
            if (statsFailed || topReviews == null) loadReviews(true);
        });
        EntranceAnimator press = new EntranceAnimator(getContentResolver());
        press.attachPressFeedback(findViewById(R.id.desc_open_directions));
        press.attachPressFeedback(findViewById(R.id.desc_review_submit));
        buildStars();
        bindCounter();
        bindLanguageTitles();
        ReviewViews.bindStats(this, findViewById(R.id.desc_stats), null, false);

        Restaurant loaded = restaurantId != null ? repository.findLoaded(restaurantId) : null;
        if (loaded != null && !loaded.isHiddenFromDiners()) bind(loaded);
        else if (restaurantId == null) finish();
        else findViewById(R.id.rinfo_progress).setVisibility(View.VISIBLE);
        loadProfile();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (restaurantId == null) return;
        if (watch == null) watch = repository.watchOne(restaurantId, this);
        refreshPosition();
        if (!reviewsRequested) loadReviews(false);
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (watch != null) watch.stop();
        watch = null;
        main.removeCallbacks(tick);
        if (deviceLocation != null) deviceLocation.cancel();
        if (directions != null) directions.cancel(); // never open the Map over another app
        locating = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (refreshSpin != null) refreshSpin.cancel();
        if (deviceLocation != null) deviceLocation.release();
        if (directionsLocation != null) directionsLocation.release();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_LOCATION) return;
        boolean granted = false;
        for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) granted = true;
        if (granted) refreshPosition();
        directions.onPermissionResult(granted, results.length > 0);
    }

    // ============================================================================ data

    @Override
    public void onRestaurants(List<Restaurant> list, boolean fromCache) {
        if (isDestroyed()) return;
        Restaurant r = list.isEmpty() ? null : list.get(0);
        if (r != null && !r.isHiddenFromDiners()) {
            findViewById(R.id.rinfo_state).setVisibility(View.GONE);
            bind(r);
        } else if (!fromCache) {
            finish(); // removed / disabled while open: Restaurant Details shows "no longer available"
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
        restaurant = r;
        if (!areaRequested) {
            areaRequested = true;
            new AreaNames().resolve(r.cityRaw, r.areaRaw, (area, city) -> {
                if (isDestroyed()) return;
                areaName = area != null ? area : r.areaLabel();
                if (restaurant != null) bindHeader(restaurant);
            });
        }
        render(r);
    }

    private void render(Restaurant r) {
        long now = System.currentTimeMillis();
        findViewById(R.id.rinfo_progress).setVisibility(View.GONE);
        findViewById(R.id.rinfo_test).setVisibility(r.isTestData ? View.VISIBLE : View.GONE);
        bindHeader(r);
        bindStatus(r, now);
        bindDrive();
        bindReviewForm();
    }

    // ============================================================================ header

    private void bindHeader(Restaurant r) {
        ((TextView) findViewById(R.id.rinfo_name)).setText(r.name != null ? r.name : getString(R.string.detail_name_fallback));
        ImageView logo = findViewById(R.id.desc_logo);
        if (logoShown == null || !logoShown.equals(String.valueOf(r.logo))) {
            logoShown = String.valueOf(r.logo);
            int px = (int) (88 * getResources().getDisplayMetrics().density);
            // [UI ASSET] Logo placeholder: res/drawable/restaurant_placeholder.xml (no / broken logoUrl).
            // Real logo = logoUrl only (never the cover photo); center-crop keeps its proportions.
            RestaurantImageLoader.load(logo, r.logo, px, R.drawable.restaurant_placeholder, v -> {
                v.setAlpha(0f);
                v.animate().alpha(1f).setDuration(MotionPrefs.enabled(this) ? 220 : 0).start();
            });
        }
        String area = areaName != null ? areaName : r.areaLabel();
        if (area == null) area = getString(R.string.desc_area_missing);
        Double km = RestaurantQuery.distanceKm(r, userLat, userLng); // GPS only, like Details
        ((TextView) findViewById(R.id.desc_area)).setText(km != null
                ? getString(R.string.desc_area_distance, area, RestaurantViews.distance(this, km)) : area);

        final String dial = RestaurantPhone.dialable(r.phone);
        View phoneRow = findViewById(R.id.desc_phone_row);
        if (dial == null) {
            phoneRow.setVisibility(View.GONE); // missing / invalid number: no row
        } else {
            String shown = RestaurantPhone.display(dial);
            phoneRow.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.desc_phone_text)).setText(shown);
            phoneRow.setContentDescription(getString(R.string.desc_phone_description, shown));
            phoneRow.setOnClickListener(v -> {
                try {
                    // ACTION_DIAL only opens the dialer with the number; nothing is called
                    // automatically and no phone permission is needed.
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + dial)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, shown, Toast.LENGTH_LONG).show(); // tablet without a dialer
                }
            });
        }
    }

    // ============================================================================ status card

    private void bindStatus(Restaurant r, long now) {
        WaitStatus.State s = WaitStatus.of(r, now);
        int bg;
        int fg;
        String label;
        String value;
        String text;
        switch (s) {
            case AVAILABLE_NOW:
                bg = R.color.desc_green_bg;
                fg = R.color.desc_green_text;
                label = getString(R.string.status_available_now);
                value = r.freeTables != null && r.freeTables > 0
                        ? getResources().getQuantityString(R.plurals.status_value_tables, r.freeTables, r.freeTables)
                        : getString(R.string.status_value_no_wait);
                text = getString(R.string.desc_walk_in);
                break;
            case SHORT_WAIT:
                bg = R.color.desc_peach_bg;
                fg = R.color.desc_peach_text;
                label = getString(R.string.status_short_wait);
                value = getString(R.string.status_value_minutes, r.waitMinutes);
                text = getString(R.string.desc_walk_in);
                break;
            case CURRENTLY_FULL:
                bg = R.color.desc_full_bg;
                fg = R.color.desc_full_text;
                label = getString(R.string.status_currently_full);
                value = getString(R.string.status_value_minutes, r.waitMinutes);
                text = getString(R.string.desc_walk_in);
                break;
            case NOT_ACCEPTING:
                bg = R.color.desc_neutral_bg;
                fg = R.color.desc_neutral_text;
                label = getString(R.string.status_not_accepting);
                boolean closed = Boolean.FALSE.equals(r.openNow);
                value = getString(closed ? R.string.status_value_closed : R.string.status_value_not_accepting);
                text = getString(closed ? R.string.desc_text_closed : R.string.desc_text_not_accepting);
                break;
            case STALE:
                bg = R.color.desc_neutral_bg;
                fg = R.color.desc_neutral_text;
                label = getString(R.string.status_stale);
                value = r.waitMinutes == null ? getString(R.string.status_value_unknown)
                        : r.waitMinutes == 0 ? getString(R.string.status_value_no_wait)
                        : getString(R.string.status_value_minutes, r.waitMinutes);
                text = getString(R.string.desc_text_stale);
                break;
            default:
                bg = R.color.desc_neutral_bg;
                fg = R.color.desc_neutral_text;
                label = getString(R.string.status_unknown);
                value = getString(R.string.status_value_unknown);
                text = getString(R.string.desc_text_unknown);
                break;
        }
        int fgColor = getColor(fg);
        View card = findViewById(R.id.desc_status_card);
        card.setBackgroundTintList(ColorStateList.valueOf(getColor(bg)));
        findViewById(R.id.desc_status_dot).setBackgroundTintList(ColorStateList.valueOf(fgColor));
        TextView labelView = findViewById(R.id.desc_status_label);
        TextView updatedView = findViewById(R.id.desc_status_updated);
        TextView valueView = findViewById(R.id.desc_status_value);
        TextView textView = findViewById(R.id.desc_status_text);
        labelView.setText(label);
        String updated = updatedText(r.updatedAtMillis, now);
        updatedView.setText(updated);
        valueView.setText(value);
        textView.setText(text);
        for (TextView t : new TextView[]{labelView, updatedView, valueView, textView}) t.setTextColor(fgColor);
        card.setContentDescription(getString(R.string.status_card_description, label, value, updated, text));
        bindLanguage(r, s);
    }

    /** availabilityUpdatedAt -> "Updated just now / 5 mins ago / 3 hours ago / 4 Nov, 2:30 pm". */
    private String updatedText(Long at, long now) {
        UpdatedAgo u = UpdatedAgo.of(at, now);
        switch (u.unit) {
            case JUST_NOW:
                return getString(R.string.upd_just_now);
            case MINUTES:
                return getResources().getQuantityString(R.plurals.upd_minutes, (int) u.value, (int) u.value);
            case HOURS:
                return getResources().getQuantityString(R.plurals.upd_hours, (int) u.value, (int) u.value);
            case DATE: // the phone's own time zone
                return getString(R.string.upd_date, DateUtils.formatDateTime(this, u.value,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME));
            case INVALID:
                return getString(R.string.upd_invalid);
            default:
                return getString(R.string.upd_missing);
        }
    }

    // ============================================================================ availability language

    private void bindLanguageTitles() {
        styleLanguage(R.id.desc_lang_free, R.color.desc_green_bg, R.color.desc_green_text,
                getString(R.string.lang_free_title), getString(R.string.lang_free_sub), getString(R.string.lang_free_label));
        styleLanguage(R.id.desc_lang_wait, R.color.desc_peach_bg, R.color.desc_peach_text,
                getString(R.string.lang_wait_title), getString(R.string.lang_wait_sub, WaitStatus.SHORT_WAIT_MAX_MINUTES),
                getString(R.string.lang_wait_label));
        styleLanguage(R.id.desc_lang_full, R.color.desc_full_bg, R.color.desc_full_text,
                getString(R.string.lang_full_title), getString(R.string.lang_full_sub, WaitStatus.SHORT_WAIT_MAX_MINUTES),
                getString(R.string.lang_full_label));
    }

    private void styleLanguage(int id, int bg, int fg, String title, String sub, String label) {
        View card = findViewById(id);
        int c = getColor(fg);
        card.setBackgroundTintList(ColorStateList.valueOf(getColor(bg)));
        ((TextView) card.findViewById(R.id.lang_title)).setText(title);
        ((TextView) card.findViewById(R.id.lang_title)).setTextColor(getColor(R.color.desc_ink));
        ((TextView) card.findViewById(R.id.lang_sub)).setText(sub);
        ((TextView) card.findViewById(R.id.lang_sub)).setTextColor(getColor(R.color.desc_muted));
        ((TextView) card.findViewById(R.id.lang_label)).setText(label);
        ((TextView) card.findViewById(R.id.lang_label)).setTextColor(c);
        card.findViewById(R.id.lang_dot).setBackgroundTintList(ColorStateList.valueOf(c));
    }

    /**
     * The three cards explain the colors (as in the reference). The card matching the CURRENT,
     * real state is highlighted and shows the restaurant's own number; nothing else is claimed.
     */
    private void bindLanguage(Restaurant r, WaitStatus.State s) {
        TextView freeSub = findViewById(R.id.desc_lang_free).findViewById(R.id.lang_sub);
        TextView waitSub = findViewById(R.id.desc_lang_wait).findViewById(R.id.lang_sub);
        TextView fullSub = findViewById(R.id.desc_lang_full).findViewById(R.id.lang_sub);
        freeSub.setText(s == WaitStatus.State.AVAILABLE_NOW && r.freeTables != null && r.freeTables > 0
                ? getResources().getQuantityString(R.plurals.lang_free_sub_tables, r.freeTables, r.freeTables)
                : getString(R.string.lang_free_sub));
        waitSub.setText(s == WaitStatus.State.SHORT_WAIT ? getString(R.string.lang_wait_sub_now, r.waitMinutes)
                : getString(R.string.lang_wait_sub, WaitStatus.SHORT_WAIT_MAX_MINUTES));
        fullSub.setText(s == WaitStatus.State.CURRENTLY_FULL ? getString(R.string.lang_full_sub_now, r.waitMinutes)
                : getString(R.string.lang_full_sub, WaitStatus.SHORT_WAIT_MAX_MINUTES));
        highlight(R.id.desc_lang_free, s == WaitStatus.State.AVAILABLE_NOW, WaitStatus.isReported(s));
        highlight(R.id.desc_lang_wait, s == WaitStatus.State.SHORT_WAIT, WaitStatus.isReported(s));
        highlight(R.id.desc_lang_full, s == WaitStatus.State.CURRENTLY_FULL, WaitStatus.isReported(s));
    }

    private void highlight(int id, boolean current, boolean anyCurrent) {
        View card = findViewById(id);
        float target = !anyCurrent || current ? 1f : 0.55f;
        card.animate().alpha(target).setDuration(MotionPrefs.enabled(this) ? 200 : 0).start();
        String title = ((TextView) card.findViewById(R.id.lang_title)).getText().toString();
        String sub = ((TextView) card.findViewById(R.id.lang_sub)).getText().toString();
        card.setContentDescription(title + ". " + sub + (current ? ". " + getString(R.string.lang_current) : ""));
    }

    // ============================================================================ distance by car

    private void refreshPosition() {
        if (locating) return;
        if (!DeviceLocationHelper.hasLocationPermission(this) || !DeviceLocationHelper.isLocationEnabled(this)) {
            bindDrive();
            return;
        }
        locating = true;
        bindDrive();
        UserPosition.request(this, deviceLocation, position -> {
            locating = false;
            if (isDestroyed()) return;
            if (position != null) {
                userLat = position.getLatitude();
                userLng = position.getLongitude();
            }
            if (restaurant != null) bindHeader(restaurant);
            bindDrive();
        });
    }

    /** Road distance + time ONLY from a real route; otherwise an honest straight-line value. */
    private void bindDrive() {
        TextView value = findViewById(R.id.desc_drive_value);
        Restaurant r = restaurant;
        if (r == null) {
            value.setText("");
            return;
        }
        RouteMemory.Entry e = RouteMemory.find(r.id, userLat, userLng, System.currentTimeMillis());
        if (!r.hasCoordinates()) {
            value.setText(R.string.drive_no_coords);
        } else if (e != null) {
            String t = e.route.durationText();
            value.setText(t.isEmpty() ? getString(R.string.drive_distance_only, e.route.distanceText())
                    : getString(R.string.drive_value, e.route.distanceText(), t));
        } else if (userLat != null && userLng != null) {
            Double km = RestaurantQuery.distanceKm(r, userLat, userLng);
            value.setText(km != null ? getString(R.string.drive_straight, RestaurantViews.distance(this, km))
                    : getString(R.string.drive_need_location));
        } else {
            value.setText(locating ? R.string.drive_locating : R.string.drive_need_location);
        }
        findViewById(R.id.desc_drive_row).setContentDescription(getString(R.string.desc_from_location) + ". " + value.getText());
    }

    // DirectionsFlow.Ui ----------------------------------------------------------------------

    @Override
    public void onDirectionsBusy(boolean busy) {
        findViewById(R.id.desc_open_directions_progress).setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.desc_open_directions_icon).setVisibility(busy ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.desc_open_directions_label)).setText(
                busy ? R.string.desc_finding_route : R.string.desc_open_directions);
        View b = findViewById(R.id.desc_open_directions);
        b.setEnabled(!busy);
        b.setAlpha(busy ? 0.85f : 1f);
        b.setContentDescription(getString(busy ? R.string.desc_finding_route : R.string.desc_open_directions));
    }

    @Override
    public void onDirectionsProblem(String message, String action, final Runnable onAction) {
        View box = findViewById(R.id.desc_directions_status);
        box.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.desc_directions_message)).setText(message);
        TextView a = findViewById(R.id.desc_directions_action);
        a.setVisibility(action != null ? View.VISIBLE : View.GONE);
        a.setText(action);
        a.setOnClickListener(onAction == null ? null : v -> onAction.run());
        box.announceForAccessibility(message);
    }

    @Override
    public void onDirectionsClear() {
        findViewById(R.id.desc_directions_status).setVisibility(View.GONE);
    }

    @Override
    public void onRouteKnown(RouteMemory.Entry entry) {
        if (entry != null) {
            userLat = entry.fromLat;
            userLng = entry.fromLng;
        }
        bindDrive();
    }

    // ============================================================================ refresh

    /** Real refresh: the restaurant document from the server + review statistics + top reviews. */
    private void refresh() {
        if (refreshing || restaurantId == null) return; // no overlapping refreshes
        if (!ConnectivityMonitor.get(this).mayHaveInternet()) {
            Toast.makeText(this, R.string.desc_refresh_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        refreshing = true;
        refreshFailed = false;
        refreshPending = 1;
        findViewById(R.id.button_refresh).setContentDescription(getString(R.string.desc_refreshing));
        startSpin();
        repository.refreshOne(restaurantId, new RestaurantRepository.Observer() {
            @Override
            public void onRestaurants(List<Restaurant> list, boolean fromCache) {
                if (isDestroyed()) return;
                RestaurantInfoActivity.this.onRestaurants(list, fromCache);
                refreshDone(false);
            }

            @Override
            public void onRestaurantsFailed(RestaurantRepository.Failure failure) {
                if (isDestroyed()) return;
                refreshDone(true); // the content already shown stays
            }
        });
        refreshPosition();
        if (restaurant == null || !restaurant.isTestData) {
            refreshPending += 2;
            ReviewRepository.loadStats(restaurantId, true, s -> {
                onStats(s);
                refreshDone(s == null);
            });
            ReviewRepository.loadTop(restaurantId, true, new ReviewRepository.ListCallback() {
                @Override public void onReviews(List<Review> reviews, com.google.firebase.firestore.DocumentSnapshot last) {
                    onTop(reviews);
                    refreshDone(false);
                }

                @Override public void onFailed(ReviewRepository.Failure f) {
                    refreshDone(true);
                }
            });
        }
    }

    private void refreshDone(boolean failed) {
        if (failed) refreshFailed = true;
        if (--refreshPending > 0 || isDestroyed()) return;
        refreshing = false;
        stopSpin();
        findViewById(R.id.button_refresh).setContentDescription(getString(R.string.desc_refresh));
        Toast.makeText(this, refreshFailed ? R.string.desc_refresh_failed : R.string.desc_refreshed, Toast.LENGTH_SHORT).show();
    }

    private void startSpin() {
        View icon = findViewById(R.id.refresh_icon);
        if (refreshSpin == null) {
            refreshSpin = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f);
            refreshSpin.setDuration(800);
            refreshSpin.setInterpolator(new LinearInterpolator());
            refreshSpin.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (MotionPrefs.enabled(this)) refreshSpin.start();
        else icon.setAlpha(0.5f);
    }

    private void stopSpin() {
        View icon = findViewById(R.id.refresh_icon);
        if (refreshSpin != null) refreshSpin.cancel();
        icon.setAlpha(1f);
        icon.animate().rotation(0f).setDuration(MotionPrefs.enabled(this) ? 200 : 0).start();
    }

    // ============================================================================ reviews: read

    private void loadReviews(boolean fromServer) {
        if (restaurantId == null) return;
        reviewsRequested = true;
        if (DevTestData.isTestId(restaurantId)) { // the local dummy has no Firestore document
            onStats(new ReviewStats(new long[6]));
            onTop(new java.util.ArrayList<>());
            return;
        }
        statsFailed = false;
        TextView state = findViewById(R.id.desc_reviews_state);
        state.setVisibility(View.GONE);
        ReviewRepository.loadStats(restaurantId, fromServer, this::onStats);
        ReviewRepository.loadTop(restaurantId, fromServer, new ReviewRepository.ListCallback() {
            @Override public void onReviews(List<Review> reviews, com.google.firebase.firestore.DocumentSnapshot last) {
                onTop(reviews);
            }

            @Override public void onFailed(ReviewRepository.Failure f) {
                if (isDestroyed()) return;
                topReviews = null;
                renderTop();
            }
        });
    }

    private void onStats(ReviewStats s) {
        if (isDestroyed()) return;
        stats = s;
        statsFailed = s == null;
        ReviewViews.bindStats(this, findViewById(R.id.desc_stats), s, statsFailed);
        renderTop();
    }

    private void onTop(List<Review> reviews) {
        if (isDestroyed()) return;
        topReviews = reviews;
        renderTop();
    }

    private void renderTop() {
        LinearLayout list = findViewById(R.id.desc_top_reviews);
        TextView state = findViewById(R.id.desc_reviews_state);
        View seeMore = findViewById(R.id.desc_see_more);
        list.removeAllViews();
        if (topReviews == null) {
            state.setVisibility(View.VISIBLE);
            state.setText(R.string.reviews_failed);
            seeMore.setVisibility(View.GONE);
            return;
        }
        for (Review r : topReviews) list.addView(ReviewViews.reviewCard(this, list, r, true));
        if (topReviews.isEmpty()) {
            state.setVisibility(View.VISIBLE);
            state.setText(R.string.reviews_none_text);
        } else {
            state.setVisibility(View.GONE);
        }
        // "See more" whenever there is at least one review (the Reviews page shows them all).
        seeMore.setVisibility(!topReviews.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ============================================================================ reviews: write

    private void loadProfile() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) return;
        new UserProfileRepository().loadProfile(user.getUid(), new UserProfileRepository.ProfileCallback() {
            @Override public void onProfileReady(ProfileData p) {
                if (!isDestroyed()) profile = p;
            }

            @Override public void onProfileFailed(UserProfileRepository.Failure f, Exception e) {
                // The review form asks the user to try again if the profile is still missing.
            }
        });
        if (restaurantId == null || DevTestData.isTestId(restaurantId)) return;
        ReviewRepository.loadMine(restaurantId, user.getUid(), new ReviewRepository.MineCallback() {
            @Override public void onMine(Review mine) {
                if (isDestroyed()) return;
                mineLoaded = true;
                myReview = mine;
                EditText input = findViewById(R.id.desc_review_input);
                if (mine != null && input.getText().toString().trim().isEmpty() && selectedRating == 0) {
                    input.setText(mine.text); // edit the existing review instead of writing a duplicate
                    setRating(mine.rating, false);
                }
                bindReviewForm();
            }

            @Override public void onFailed(ReviewRepository.Failure f) {
                // Not fatal: submitting still creates-or-edits in one transaction.
            }
        });
    }

    private void buildStars() {
        LinearLayout row = findViewById(R.id.desc_stars);
        row.removeAllViews();
        int size = (int) (48 * getResources().getDisplayMetrics().density);
        int pad = (int) (7 * getResources().getDisplayMetrics().density);
        for (int i = 1; i <= 5; i++) {
            final int stars = i;
            ImageView star = new ImageView(this);
            star.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            star.setPadding(pad, pad, pad, pad);
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
            star.setBackgroundResource(ripple.resourceId);
            star.setClickable(true);
            star.setFocusable(true);
            star.setOnClickListener(v -> setRating(stars, true));
            row.addView(star);
        }
        setRating(0, false);
    }

    private void setRating(int rating, boolean animate) {
        selectedRating = rating;
        LinearLayout row = findViewById(R.id.desc_stars);
        String[] words = getResources().getStringArray(R.array.review_rating_words);
        for (int i = 0; i < row.getChildCount(); i++) {
            ImageView star = (ImageView) row.getChildAt(i);
            boolean on = i < rating;
            // [UI ASSET] Stars: res/drawable/ic_star_filled.xml / ic_star_outline.xml
            star.setImageResource(on ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
            star.setColorFilter(getColor(on ? R.color.desc_star : R.color.desc_muted));
            star.setSelected(i + 1 == rating);
            star.setContentDescription(getResources().getQuantityString(R.plurals.review_star_description, i + 1, i + 1)
                    + ", " + words[i]);
            if (animate && on && MotionPrefs.enabled(this)) {
                star.setScaleX(0.7f);
                star.setScaleY(0.7f);
                star.animate().scaleX(1f).scaleY(1f).setStartDelay(i * 30L).setDuration(160).start();
            }
        }
        TextView hint = findViewById(R.id.desc_stars_hint);
        hint.setText(rating == 0 ? getString(R.string.review_tap_star)
                : getResources().getQuantityString(R.plurals.review_star_description, rating, rating) + " · " + words[rating - 1]);
        if (rating > 0) clearReviewError();
    }

    private void bindCounter() {
        final EditText input = findViewById(R.id.desc_review_input);
        final TextView counter = findViewById(R.id.desc_review_counter);
        counter.setText(getString(R.string.review_counter, "0", ReviewViews.number(ReviewRepository.MAX_TEXT)));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                counter.setText(getString(R.string.review_counter, ReviewViews.number(s.length()),
                        ReviewViews.number(ReviewRepository.MAX_TEXT)));
                counter.setTextColor(getColor(s.length() >= ReviewRepository.MAX_TEXT ? R.color.desc_red : R.color.desc_muted));
                if (s.toString().trim().length() > 0) clearReviewError();
            }
        });
    }

    private void bindReviewForm() {
        boolean test = restaurant != null && restaurant.isTestData;
        boolean editing = myReview != null;
        ((TextView) findViewById(R.id.desc_rate_title)).setText(editing ? R.string.desc_rate_title_edit : R.string.desc_rate_title);
        ((TextView) findViewById(R.id.desc_review_submit_label)).setText(submitting ? R.string.review_submitting
                : editing ? R.string.review_update : R.string.review_submit);
        View submit = findViewById(R.id.desc_review_submit);
        submit.setEnabled(!submitting && !test);
        submit.setAlpha(test ? 0.5f : 1f);
        findViewById(R.id.desc_review_input).setEnabled(!submitting && !test);
        TextView message = findViewById(R.id.desc_review_message);
        if (test) {
            message.setText(R.string.review_test_restaurant);
        } else if (editing && message.getText().length() == 0) {
            message.setText(R.string.review_editing_note);
            message.setTextColor(getColor(R.color.desc_muted));
        }
    }

    private void submitReview() {
        if (submitting || restaurantId == null || restaurant == null || restaurant.isTestData) return; // no double submits
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            reviewError(getString(R.string.review_signed_out));
            return;
        }
        EditText input = findViewById(R.id.desc_review_input);
        String text = input.getText().toString();
        if (selectedRating < 1 || selectedRating > 5) {
            reviewError(getString(R.string.review_need_rating));
            return;
        }
        if (text.trim().isEmpty()) {
            reviewError(getString(R.string.review_need_text));
            input.requestFocus();
            return;
        }
        if (text.trim().length() > ReviewRepository.MAX_TEXT) {
            reviewError(getString(R.string.review_too_long));
            return;
        }
        String name = ReviewRepository.publicName(profile, user.getDisplayName());
        if (name == null) {
            reviewError(getString(R.string.review_profile_needed));
            loadProfile();
            return;
        }
        if (!ConnectivityMonitor.get(this).mayHaveInternet()) {
            reviewError(getString(R.string.review_failed_offline));
            return;
        }
        submitting = true;
        findViewById(R.id.desc_review_submit_progress).setVisibility(View.VISIBLE);
        bindReviewForm();
        final int rating = selectedRating;
        ReviewRepository.submit(restaurantId, user.getUid(), rating, text, name, ReviewRepository.publicPhoto(profile),
                new ReviewRepository.SubmitCallback() {
                    @Override public void onSubmitted(boolean edited) {
                        if (isDestroyed()) return;
                        submitting = false;
                        findViewById(R.id.desc_review_submit_progress).setVisibility(View.GONE);
                        bindReviewForm();
                        TextView message = findViewById(R.id.desc_review_message);
                        message.setText(edited ? R.string.review_updated : R.string.review_success);
                        message.setTextColor(getColor(R.color.desc_green_text));
                        mineLoaded = false;
                        loadMineQuietly(user.getUid());
                        loadReviews(true); // statistics + top five recalculated from the stored reviews
                    }

                    @Override public void onFailed(ReviewRepository.Failure f) {
                        if (isDestroyed()) return;
                        submitting = false;
                        findViewById(R.id.desc_review_submit_progress).setVisibility(View.GONE);
                        bindReviewForm();
                        int msg;
                        switch (f) {
                            case OFFLINE: msg = R.string.review_failed_offline; break;
                            case NOT_ALLOWED: msg = R.string.review_failed_not_allowed; break;
                            case SIGNED_OUT: msg = R.string.review_signed_out; break;
                            default: msg = R.string.review_failed; break;
                        }
                        reviewError(getString(msg)); // the typed text stays in the box
                    }
                });
    }

    private void loadMineQuietly(String uid) {
        ReviewRepository.loadMine(restaurantId, uid, new ReviewRepository.MineCallback() {
            @Override public void onMine(Review mine) {
                if (isDestroyed()) return;
                myReview = mine;
                mineLoaded = true;
                bindReviewForm();
            }

            @Override public void onFailed(ReviewRepository.Failure f) { }
        });
    }

    private void reviewError(String text) {
        TextView message = findViewById(R.id.desc_review_message);
        message.setText(text);
        message.setTextColor(getColor(R.color.desc_red));
        message.announceForAccessibility(text);
    }

    private void clearReviewError() {
        TextView message = findViewById(R.id.desc_review_message);
        if (message.getCurrentTextColor() == getColor(R.color.desc_red)) {
            message.setText(myReview != null ? getString(R.string.review_editing_note) : "");
            message.setTextColor(getColor(R.color.desc_muted));
        }
    }
}