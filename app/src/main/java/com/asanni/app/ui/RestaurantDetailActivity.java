package com.asanni.app.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.restaurants.AreaNames;
import com.asanni.app.restaurants.Availability;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantImageLoader;
import com.asanni.app.restaurants.RestaurantQuery;
import com.asanni.app.restaurants.RestaurantRepository;
import com.asanni.app.routing.Route;
import com.asanni.app.routing.Routeconfig;
import com.asanni.app.routing.Routeservice;
import com.asanni.app.saved.RestaurantFeedback;
import com.asanni.app.saved.SavedRestaurants;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Restaurant Details (phase 8, built from the details reference image). ONE screen for every
 * restaurant, opened from Discover, See All, Map and Saved. Back (arrow or system) simply closes
 * it, so you return exactly where you came from (Discover with its bottom bar, Map, Saved...).
 *
 * Location: app/src/main/java/com/asanni/app/ui/RestaurantDetailActivity.java
 *           (REWRITTEN in phase 8)
 *
 * Data (all real, nothing invented):
 *   restaurant ... ONE live document listener (RestaurantRepository.watchOne), schema from
 *                  RestaurantSchema (name, coverUrl, cuisines, areaId, price, perHead,
 *                  availableTables, availabilityUpdatedAt, waitMinutes, address, location)
 *   area name .... existing areas catalogue (AreaNames, cached)
 *   reviews ...... read-only count + average (RestaurantFeedback) or "No reviews yet"
 *   saved ........ users/{uid}/savedRestaurants (SavedRestaurants)
 *   distance ..... straight line from the phone's GPS (never from the browsing area)
 *
 * ROUTING RULE: opening this page NEVER requests a road route. Only the "Get directions" button
 * calls the existing GraphHopper package (routing/Routeservice), then opens the Map to draw it.
 */
public class RestaurantDetailActivity extends AsanniActivity implements RestaurantRepository.Observer,
        SavedRestaurants.Listener {

    private static final int REQUEST_LOCATION = 8101;
    /** [ANIMATION] Content entrance + cover fade. */
    private static final long ENTER_MS = 260L;

    private final RestaurantRepository repository = RestaurantRepository.get();
    private final SavedRestaurants saved = SavedRestaurants.get();
    private final AreaNames areaNames = new AreaNames();

    private String restaurantId;
    private LocationScope scope;
    private Restaurant restaurant;
    private RestaurantRepository.Watch watch;
    private String areaName;
    private String cityName;
    private String resolvedAreaFor;
    private RestaurantFeedback.Summary reviews;
    private boolean reviewsRequested;
    private Boolean isSaved;            // null = not known yet
    private boolean contentShown;
    private String coverShown;
    private boolean contentLoadedOnce;

    private DeviceLocationHelper deviceLocation;
    private Double userLat;
    private Double userLng;

    private Routeservice.Request routeRequest; // non-null while Get Directions is working

    private boolean directionsBusy;

    private int directionsGeneration;   // leaving the screen invalidates older callbacks
    private Route lastRoute;                   // only from an explicit Get Directions press

    // ============================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_restaurant_detail);
        applyInsets();
        deviceLocation = new DeviceLocationHelper(this);

        restaurantId = getIntent().getStringExtra(AppNavigator.EXTRA_RESTAURANT_ID);
        if (getIntent().hasExtra(AppNavigator.EXTRA_USER_LAT)) {
            userLat = getIntent().getDoubleExtra(AppNavigator.EXTRA_USER_LAT, 0);
            userLng = getIntent().getDoubleExtra(AppNavigator.EXTRA_USER_LNG, 0);
        }
        String cityId = getIntent().getStringExtra(AppNavigator.EXTRA_CITY_ID);
        if (cityId != null) {
            String cityNameExtra = getIntent().getStringExtra(AppNavigator.EXTRA_CITY_NAME);
            String areaId = getIntent().getStringExtra(AppNavigator.EXTRA_AREA_ID);
            String areaNameExtra = getIntent().getStringExtra(AppNavigator.EXTRA_AREA_NAME);
            scope = new LocationScope(new CatalogueItem(cityId, cityNameExtra != null ? cityNameExtra : cityId, null, null, null, false),
                    areaId == null ? null : new CatalogueItem(areaId, areaNameExtra != null ? areaNameExtra : areaId,
                            null, null, cityId, areaId.equals(cityId + "-all")));
        }

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_save).setOnClickListener(v -> toggleSaved());
        findViewById(R.id.detail_get_details).setOnClickListener(v -> openInformation());
        findViewById(R.id.detail_get_directions).setOnClickListener(v -> onGetDirections());
        findViewById(R.id.detail_address_row).setOnLongClickListener(v -> copyAddress());
        EntranceAnimator press = new EntranceAnimator(getContentResolver());
        press.attachPressFeedback(findViewById(R.id.detail_get_details));
        press.attachPressFeedback(findViewById(R.id.detail_get_directions));

        Restaurant loaded = restaurantId != null ? repository.findLoaded(restaurantId) : null;
        if (loaded != null && !loaded.isHiddenFromDiners()) bind(loaded); // instant from Discover/Map data
        else if (restaurantId == null) showMissing();
        else findViewById(R.id.detail_progress).setVisibility(View.VISIBLE);
        renderSaveButton();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (restaurantId == null) return;
        if (watch == null) watch = repository.watchOne(restaurantId, this);
        saved.observe(this);
        refreshPosition();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reviewsRequested = false;
        if (restaurant != null) loadReviews();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (watch != null) {
            watch.stop();
            watch = null;
        }
        saved.stopObserving(this);
        if (deviceLocation != null) deviceLocation.cancel();
        // Leaving the screen ends a running "Get directions" (a cancelled location fix never
        // calls back, and the Map must not pop up over another app). The user simply presses again.
        if (directionsBusy) {
            directionsGeneration++;
            if (routeRequest != null) routeRequest.cancel();
            routeRequest = null;
            setDirectionsBusy(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (routeRequest != null) routeRequest.cancel(); // no callback after the screen is gone
        routeRequest = null;
        if (deviceLocation != null) deviceLocation.release();
    }

    // ============================================================================ data

    @Override
    public void onRestaurants(List<Restaurant> list, boolean fromCache) {
        if (isDestroyed()) return;
        if (list.isEmpty()) {
            // Gone or disabled: only conclusive when the SERVER answered (a cache miss isn't).
            if (!fromCache) showMissing();
            else if (restaurant == null && !ConnectivityMonitor.get(this).isOnline()) {
                onRestaurantsFailed(RestaurantRepository.Failure.NETWORK);
            }
            return;
        }
        bind(list.get(0));
    }

    @Override
    public void onRestaurantsFailed(RestaurantRepository.Failure failure) {
        if (isDestroyed() || restaurant != null) return; // keep what is shown
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        View state = findViewById(R.id.detail_state);
        state.setVisibility(View.VISIBLE);
        boolean offline = !ConnectivityMonitor.get(this).isOnline() || failure == RestaurantRepository.Failure.NETWORK;
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(offline ? R.drawable.ic_wifi_off : R.drawable.ic_storefront);
        ((TextView) state.findViewById(R.id.state_title)).setText(offline ? R.string.error_restaurants_offline_title : R.string.error_restaurants_title);
        TextView text = state.findViewById(R.id.state_text);
        text.setVisibility(View.VISIBLE);
        text.setText(R.string.error_restaurants_text);
        TextView action = state.findViewById(R.id.state_action);
        action.setText(R.string.error_retry);
        action.setOnClickListener(v -> {
            state.setVisibility(View.GONE);
            findViewById(R.id.detail_progress).setVisibility(View.VISIBLE);
            if (watch != null) watch.stop();
            watch = repository.watchOne(restaurantId, this);
        });
    }

    @Override
    public void onSavedChanged(List<String> restaurantIds, boolean fromCache) {
        isSaved = restaurantId != null && restaurantIds.contains(restaurantId);
        renderSaveButton();
    }

    @Override
    public void onSavedFailed(SavedRestaurants.Failure failure) {
        isSaved = null; // unknown: the button still works, the next write decides
        renderSaveButton();
    }

    // ============================================================================ binding

    private void bind(Restaurant r) {
        long now = System.currentTimeMillis();
        restaurant = r;
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        findViewById(R.id.detail_state).setVisibility(View.GONE);
        View content = findViewById(R.id.detail_content);
        if (!contentShown) {
            contentShown = true;
            content.setVisibility(View.VISIBLE);
            if (motion()) {
                content.setAlpha(0f);
                content.setTranslationY(16 * getResources().getDisplayMetrics().density);
                content.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).start();
            }
        }

        findViewById(R.id.detail_test_banner).setVisibility(r.isTestData ? View.VISIBLE : View.GONE);
        String name = r.name != null && !r.name.trim().isEmpty() ? r.name : getString(R.string.detail_name_fallback);
        ((TextView) findViewById(R.id.detail_name)).setText(name);
        bindCover(r, name);
        resolveArea(r);
        bindMeta();
        bindAvailability(r, now);
        bindDistance();
        bindWait(r, now);
        bindAddress(r);
        if (!reviewsRequested) loadReviews();
    }

    /** coverUrl first; logo second; else the XML placeholder. Loaded off the main thread, cached. */
    private void bindCover(Restaurant r, String name) {
        String src = r.cover != null ? r.cover : r.logo;
        if (contentLoadedOnce && (src == null ? coverShown == null : src.equals(coverShown))) return; // no reload
        contentLoadedOnce = true;
        coverShown = src;
        final ImageView cover = findViewById(R.id.detail_cover);
        cover.setContentDescription(getString(R.string.detail_cover_description, name));
        int px = getResources().getDisplayMetrics().widthPixels;
        // [UI ASSET] The placeholder is the ImageView BACKGROUND (layout): res/drawable/
        // restaurant_cover_placeholder.xml (replace from ui/). The real photo is drawn over it.
        RestaurantImageLoader.load(cover, src, px, android.R.color.transparent, view -> {
            if (motion()) {
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(ENTER_MS).start();
            }
        });
    }

    private void resolveArea(final Restaurant r) {
        String key = r.cityRaw + "/" + r.areaRaw;
        if (key.equals(resolvedAreaFor)) return;
        resolvedAreaFor = key;
        areaNames.resolve(r.cityRaw, r.areaRaw, (area, city) -> {
            if (isDestroyed() || restaurant == null) return;
            areaName = area != null ? area : r.areaLabel(); // readable text stored by the desktop app
            cityName = city;
            bindMeta();
        });
    }

    /** "Contemporary · Clifton · PKR 2,500 per person" (each part only if known). */
    private void bindMeta() {
        Restaurant r = restaurant;
        if (r == null) return;
        String place = areaName != null ? areaName : cityName; // fallback: the city
        String text = RestaurantViews.join(this, RestaurantViews.join(this, r.cuisineText(), place), priceText(r));
        TextView meta = findViewById(R.id.detail_meta);
        meta.setText(text);
        meta.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** perHead true -> "PKR 2,500 per person"; false -> "Variable pricing"; unknown -> amount only / nothing. */
    String priceText(Restaurant r) {
        if (Boolean.FALSE.equals(r.perHead)) return getString(R.string.price_variable);
        if (r.price == null) return null; // never invent an amount
        String amount = formatAmount(r.price);
        return Boolean.TRUE.equals(r.perHead) ? getString(R.string.price_per_person, amount)
                : getString(R.string.price_from, amount);
    }

    static String formatAmount(double price) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US); // Western digits + "," (PKR style)
        f.setMaximumFractionDigits(price == Math.floor(price) ? 0 : 2);
        return f.format(price);
    }

    /** Green only when a CURRENT count exists; old counts are clearly marked; missing is neutral. */
    private void bindAvailability(Restaurant r, long now) {
        View box = findViewById(R.id.detail_avail);
        TextView title = findViewById(R.id.detail_avail_title);
        TextView sub = findViewById(R.id.detail_avail_sub);
        ImageView icon = findViewById(R.id.detail_avail_icon);
        String updated = updatedLower(r.updatedAtMillis, now);
        boolean current = r.hasCurrentAvailability(now);
        int fg;
        if (r.freeTables == null) {
            box.setBackgroundResource(R.drawable.bg_avail_neutral);
            fg = getColor(R.color.asanni_badge_neutral_text);
            title.setText(R.string.avail_unavailable);
            sub.setText(R.string.avail_unavailable_detail);
            icon.setVisibility(View.GONE);
        } else if (current) {
            boolean free = r.freeTables > 0;
            box.setBackgroundResource(free ? R.drawable.bg_avail_live : R.drawable.bg_avail_neutral);
            fg = getColor(free ? R.color.asanni_avail_live_text : R.color.asanni_badge_neutral_text);
            title.setText(free ? getResources().getQuantityString(R.plurals.avail_tables_free, r.freeTables, r.freeTables)
                    : getString(R.string.avail_no_tables));
            String when = updated != null ? updated : "";
            sub.setText(r.isTestData ? getString(R.string.avail_test, when)
                    : updated != null ? getString(R.string.avail_live, updated) : getString(R.string.avail_live_no_time));
            icon.setVisibility(View.VISIBLE);
        } else {
            // A count exists but it is old (or its time is invalid): not presented as live.
            box.setBackgroundResource(R.drawable.bg_avail_neutral);
            fg = getColor(R.color.asanni_badge_neutral_text);
            title.setText(r.freeTables > 0
                    ? getResources().getQuantityString(R.plurals.avail_tables_free, r.freeTables, r.freeTables)
                    : getString(R.string.avail_no_tables));
            sub.setText(getString(R.string.avail_outdated, updated != null ? updated : ""));
            icon.setVisibility(View.GONE);
        }
        title.setTextColor(fg);
        sub.setTextColor(fg);
        icon.setColorFilter(fg);
        box.setContentDescription(title.getText() + ". " + sub.getText());
    }

    /** Straight-line distance from the phone's GPS; driving time only after Get directions. */
    private void bindDistance() {
        Restaurant r = restaurant;
        if (r == null) return;
        TextView value = findViewById(R.id.detail_distance);
        TextView sub = findViewById(R.id.detail_distance_sub);
        if (!r.hasCoordinates()) {
            value.setText(R.string.distance_unavailable);
            sub.setText(R.string.distance_restaurant_unknown);
        } else if (userLat == null || userLng == null) {
            value.setText(R.string.distance_unavailable);
            sub.setText(R.string.distance_unavailable_detail);
        } else if (knownRoute(r) != null) {
            Route known = knownRoute(r);
            value.setText(known.distanceText());   // real road distance (GraphHopper)
            String t = known.durationText();
            sub.setText(t.isEmpty() ? getString(R.string.distance_drive_hint) : getString(R.string.distance_drive_time, t));
        } else {
            Double km = RestaurantQuery.distanceKm(r, userLat, userLng);
            value.setText(km != null ? RestaurantViews.distance(this, km) : getString(R.string.distance_unavailable));
            sub.setText(R.string.distance_straight_sub);
        }
        findViewById(R.id.detail_distance_box).setContentDescription(value.getText() + ". " + sub.getText());
    }

    /** PHASE 9: this page's route, or a recent identical one from another page (RouteMemory). */
    private Route knownRoute(Restaurant r) {
        if (lastRoute != null && !lastRoute.approximate) return lastRoute;
        RouteMemory.Entry e = RouteMemory.find(r.id, userLat, userLng, System.currentTimeMillis());
        return e != null ? e.route : null;
    }

    /** waitMinutes: 0 = "No wait" (a real value), missing = "not shared", old = "may be outdated". */
    private void bindWait(Restaurant r, long now) {
        TextView value = findViewById(R.id.detail_wait);
        TextView sub = findViewById(R.id.detail_wait_sub);
        if (r.waitMinutes == null) {
            value.setText(R.string.wait_unknown);
            sub.setText("");
            sub.setVisibility(View.GONE);
        } else {
            sub.setVisibility(View.VISIBLE);
            value.setText(r.waitMinutes == 0 ? getString(R.string.wait_none) : getString(R.string.wait_minutes, r.waitMinutes));
            boolean fresh = Availability.isFresh(r, now);
            if (!fresh) sub.setText(R.string.wait_outdated);
            else if (r.waitMinutes == 0 && Boolean.TRUE.equals(r.openNow) && !Boolean.FALSE.equals(r.acceptingDiners)) {
                sub.setText(R.string.wait_dine_in_now);
            } else {
                sub.setText(R.string.wait_reported);
            }
        }
        findViewById(R.id.detail_wait_box).setContentDescription(value.getText() + ". " + sub.getText());
    }

    private void bindAddress(Restaurant r) {
        TextView address = findViewById(R.id.detail_address);
        String raw = rawAddress(r);
        address.setText(raw != null ? raw : getString(R.string.address_missing));
        address.setTypeface(null, raw != null ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        findViewById(R.id.detail_address_row).setContentDescription(getString(R.string.address_heading) + ". "
                + address.getText() + (raw != null ? ". " + getString(R.string.address_copy_hint) : ""));
    }

    /** The address exactly as stored (line breaks kept), or null. */
    private static String rawAddress(Restaurant r) {
        return r.address == null || r.address.trim().isEmpty() ? null : r.address;
    }

    private boolean copyAddress() {
        String raw = restaurant != null ? rawAddress(restaurant) : null;
        if (raw == null) return false;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.address_heading), raw));
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show();
        return true;
    }

    // ============================================================================ reviews (read-only)

    private void loadReviews() {
        if (restaurantId == null) return;
        reviewsRequested = true;
        RestaurantFeedback.load(restaurantId, summary -> {
            if (isDestroyed()) return;
            reviews = summary;
            bindReviews();
        });
    }

    private void bindReviews() {
        View box = findViewById(R.id.detail_rating);
        TextView text = findViewById(R.id.detail_rating_text);
        ImageView star = findViewById(R.id.detail_rating_star);
        if (reviews != null && reviews.hasReviews()) {
            String avg = String.format(Locale.US, "%.1f", reviews.average);
            text.setText(avg);
            text.setTextColor(getColor(R.color.asanni_welcome_ink));
            star.setVisibility(View.VISIBLE);
            box.setContentDescription(getResources().getQuantityString(R.plurals.reviews_summary_description,
                    (int) Math.min(reviews.count, Integer.MAX_VALUE), avg, (int) Math.min(reviews.count, Integer.MAX_VALUE)));
        } else {
            text.setText(reviews != null && reviews.failed ? R.string.stats_failed
                    : R.string.reviews_none); // never an invented rating
            text.setTextColor(getColor(R.color.asanni_discover_muted));
            star.setVisibility(View.GONE);
            box.setContentDescription(getString(reviews != null && reviews.failed ? R.string.stats_failed
                    : R.string.reviews_none));
        }
        if (box.getAlpha() < 1f) box.animate().alpha(1f).setDuration(motion() ? 200 : 0).start();
    }

    // ============================================================================ save

    private void toggleSaved() {
        if (restaurantId == null || saved.isBusy(restaurantId)) return; // repeated taps ignored
        final boolean target = !Boolean.TRUE.equals(isSaved);
        boolean started = saved.setSaved(restaurantId, target, new SavedRestaurants.WriteCallback() {
            @Override
            public void onConfirmed() {
                if (isDestroyed()) return;
                renderSaveButton(); // the listener already shows the real state
            }

            @Override
            public void onPending() {
                if (isDestroyed()) return;
                renderSaveButton();
                Toast.makeText(RestaurantDetailActivity.this, R.string.save_pending, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailed(SavedRestaurants.Failure failure) {
                if (isDestroyed()) return;
                // Firestore already rolled back its local change; the listener restores the icon.
                renderSaveButton();
                Toast.makeText(RestaurantDetailActivity.this, failure == SavedRestaurants.Failure.SIGNED_OUT
                        ? R.string.save_signed_out : R.string.save_failed, Toast.LENGTH_LONG).show();
            }
        });
        if (started) {
            isSaved = target; // instant feedback; the listener (incl. pending writes) confirms it
            renderSaveButton();
            View icon = findViewById(R.id.button_save_icon);
            if (motion()) {
                icon.setScaleX(0.7f);
                icon.setScaleY(0.7f);
                icon.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            }
        }
    }

    private void renderSaveButton() {
        boolean on = Boolean.TRUE.equals(isSaved);
        boolean busy = restaurantId != null && saved.isBusy(restaurantId);
        ImageView icon = findViewById(R.id.button_save_icon);
        // [UI ASSET] Saved: res/drawable/ic_bookmark_filled.xml; not saved: res/drawable/ic_nav_saved.xml
        icon.setImageResource(on ? R.drawable.ic_bookmark_filled : R.drawable.ic_nav_saved);
        icon.setColorFilter(getColor(on ? R.color.asanni_welcome_accent : R.color.asanni_welcome_ink));
        findViewById(R.id.button_save_progress).setVisibility(busy ? View.VISIBLE : View.GONE);
        View button = findViewById(R.id.button_save);
        // Not only color: the icon shape (filled vs outline) and the spoken label change too.
        button.setContentDescription(getString(on ? R.string.detail_unsave : R.string.detail_save));
        button.setSelected(on);
    }

    // ============================================================================ Get details

    private void openInformation() {
        if (restaurantId == null) return;
        AppNavigator.openRestaurantInformation(this, restaurantId, scope, userLat, userLng); // PHASE 9
    }

    // ============================================================================ Get directions (explicit only)

    private void onGetDirections() {
        Restaurant r = restaurant;
        if (r == null || directionsBusy) return; // duplicate taps / already working
        hideDirectionsStatus();
        if (!r.hasCoordinates()) {
            showDirectionsStatus(getString(R.string.directions_no_restaurant_location), null, null);
            return; // GraphHopper is NOT called with missing coordinates
        }
        if (!DeviceLocationHelper.hasLocationPermission(this)) {
            showDirectionsStatus(getString(R.string.directions_no_user_location), getString(R.string.map_location_allow),
                    () -> requestPermissions(DeviceLocationHelper.LOCATION_PERMISSIONS, REQUEST_LOCATION));
            return;
        }
        if (!DeviceLocationHelper.isLocationEnabled(this)) {
            showDirectionsStatus(getString(R.string.directions_gps_off), getString(R.string.map_gps_turn_on), () -> {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (ActivityNotFoundException ignored) {
                    // no settings screen
                }
            });
            return;
        }
        if (!ConnectivityMonitor.get(this).mayHaveInternet()) {
            showDirectionsStatus(getString(R.string.directions_offline), getString(R.string.directions_retry), this::onGetDirections);
            return;
        }
        setDirectionsBusy(true);
        final int gen = ++directionsGeneration;
        // A FRESH position for the start of the route (never the browsing area).
        UserPosition.request(this, deviceLocation, position -> {
            if (isDestroyed() || gen != directionsGeneration) return;
            if (position == null) {
                setDirectionsBusy(false);
                showDirectionsStatus(getString(R.string.directions_no_user_location), getString(R.string.directions_retry),
                        this::onGetDirections);
                return;
            }
            userLat = position.getLatitude();
            userLng = position.getLongitude();
            requestRoute(r, position, gen);
        });
    }

    /** The existing GraphHopper package (Routeservice) - the ONLY route request on this page. */
    private void requestRoute(final Restaurant r, final Location from, final int gen) {
        if (routeRequest != null) return;
        routeRequest = Routeservice.get().request(from.getLatitude(), from.getLongitude(), r.latitude, r.longitude,
                Routeconfig.Profile.DRIVING, route -> {
                    if (isDestroyed() || gen != directionsGeneration) return; // cancelled (screen left)
                    routeRequest = null;
                    setDirectionsBusy(false);
                    if (route.approximate) {
                        // Every routing service failed: Routeservice returned a straight line.
                        // That is NOT a road route, so nothing is drawn and no directions are claimed.
                        showDirectionsStatus(getString(R.string.directions_failed), getString(R.string.directions_retry),
                                this::onGetDirections);
                        return;
                    }
                    lastRoute = route;
                    RouteMemory.remember(r.id, route, from.getLatitude(), from.getLongitude()); // PHASE 9
                    bindDistance(); // real road distance + drive time now known
                    // Map draws the same route (Routeservice answers it from its cache: no 2nd credit).
                    AppNavigator.openMapWithRoute(this, r.id, scope, from.getLatitude(), from.getLongitude());
                });
    }

    private void setDirectionsBusy(boolean busy) {
        directionsBusy = busy;
        findViewById(R.id.detail_directions_progress).setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.detail_directions_icon).setVisibility(busy ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.detail_directions_label)).setText(
                busy ? R.string.detail_finding_route : R.string.detail_get_directions);
        View button = findViewById(R.id.detail_get_directions);
        button.setEnabled(!busy);
        button.setAlpha(busy ? 0.85f : 1f);
        button.setContentDescription(getString(busy ? R.string.detail_finding_route : R.string.detail_get_directions));
    }

    private void showDirectionsStatus(String message, String action, final Runnable onAction) {
        View box = findViewById(R.id.detail_directions_status);
        box.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.detail_directions_message)).setText(message);
        TextView a = findViewById(R.id.detail_directions_action);
        a.setVisibility(action != null ? View.VISIBLE : View.GONE);
        a.setText(action);
        a.setOnClickListener(onAction == null ? null : v -> onAction.run());
    }

    private void hideDirectionsStatus() {
        findViewById(R.id.detail_directions_status).setVisibility(View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) return;
        for (int g : grantResults) {
            if (g == PackageManager.PERMISSION_GRANTED) {
                hideDirectionsStatus();
                refreshPosition(); // distance only; the route still waits for a new tap
                return;
            }
        }
    }

    // ============================================================================ helpers

    /** Straight-line distance source: the phone's GPS (only with permission; never asked here). */
    private void refreshPosition() {
        UserPosition.request(this, deviceLocation, position -> {
            if (isDestroyed() || position == null) return;
            userLat = position.getLatitude();
            userLng = position.getLongitude();
            bindDistance();
        });
    }

    /** "updated 1 min ago", "last updated 3 hours ago", or null when unknown / in the future. */
    private String updatedLower(Long millis, long now) {
        if (millis == null) return null;
        long age = now - millis;
        if (age < -5 * 60_000L) return null; // clock problem: don't show a strange time
        if (age < 60_000L) return getString(R.string.updated_lower_just_now);
        if (age < 60 * 60_000L) {
            int m = (int) (age / 60_000L);
            return getResources().getQuantityString(R.plurals.updated_lower_minutes, m, m);
        }
        if (age < 24 * 60 * 60_000L) {
            int h = (int) (age / (60 * 60_000L));
            return getResources().getQuantityString(R.plurals.updated_lower_hours, h, h);
        }
        return getString(R.string.updated_lower_date, DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
    }

    private void showMissing() {
        restaurant = null;
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        findViewById(R.id.detail_content).setVisibility(View.GONE);
        contentShown = false;
        View state = findViewById(R.id.detail_state);
        state.setVisibility(View.VISIBLE);
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(R.drawable.ic_storefront);
        ((TextView) state.findViewById(R.id.state_title)).setText(R.string.detail_not_found);
        state.findViewById(R.id.state_text).setVisibility(View.GONE);
        TextView action = state.findViewById(R.id.state_action);
        action.setText(R.string.detail_back);
        action.setOnClickListener(v -> finish());
    }

    private boolean motion() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    /** Cover goes under the status bar (white icons over the photo); buttons stay below it. */
    @SuppressWarnings("deprecation")
    private void applyInsets() {
        final View root = findViewById(R.id.detail_root);
        final View top = findViewById(R.id.detail_top_bar);
        final View scroll = findViewById(R.id.detail_scroll);
        final int topPad = top.getPaddingTop();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                boolean night = com.asanni.app.appearance.AppearanceManager.isNight(this);
                c.setSystemBarsAppearance(night ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int t;
            int b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                t = bars.top;
                b = bars.bottom;
            } else {
                t = insets.getSystemWindowInsetTop();
                b = insets.getSystemWindowInsetBottom();
            }
            top.setPadding(top.getPaddingLeft(), topPad + t, top.getPaddingRight(), top.getPaddingBottom());
            scroll.setPadding(0, 0, 0, b);
            return insets;
        });
        root.requestApplyInsets();
    }
}