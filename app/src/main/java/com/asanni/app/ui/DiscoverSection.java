package com.asanni.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.profile.ProfileImages;
import com.asanni.app.restaurants.DiscoverFilters;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.LocationScopeLoader;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantImageLoader;
import com.asanni.app.restaurants.RestaurantQuery;
import com.asanni.app.restaurants.RestaurantRepository;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The Discover section of Home (layout/view_discover.xml).
 *
 * Location: app/src/main/java/com/asanni/app/ui/DiscoverSection.java  (NEW in phase 6)
 *
 * Data:
 *   profile ............ greeting first name, browsing city/area, profile picture (read only)
 *   catalogue .......... city/area records + aliases (LocationScopeLoader, existing catalogue)
 *   restaurants ........ RestaurantRepository: one bounded live listener, only while Discover is
 *                        visible (setActive); search + filters run on the phone (no query per key)
 *   GPS ................ UserPosition, only if permission was ALREADY granted; distances only
 * The selected browsing area and the phone's GPS position are kept separate on purpose.
 */
final class DiscoverSection implements RestaurantRepository.Observer {

    interface Host {
        HomeActivity activity();
        void openLocationPicker(String cityId, String areaId);
        void reloadProfile();
    }

    /** [CONFIG] Search waits this long after the last key press before updating results. */
    private static final long SEARCH_DEBOUNCE_MS = 250L;
    /** [CONFIG] Results shown on Discover itself while searching (the rest via "See all results"). */
    private static final int MAX_INLINE_RESULTS = 20;

    private final Host host;
    private final HomeActivity activity;
    private final View root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RestaurantRepository repository = RestaurantRepository.get();
    private final LocationScopeLoader scopeLoader = new LocationScopeLoader();
    private final DeviceLocationHelper deviceLocation;
    private final LocationCoachMark coach;
    private final boolean motion;

    private final ScrollView scroll;
    private final TextView greeting;
    private final TextView locationText;
    private final View locationButton;
    private final ImageView avatar;
    private final EditText searchInput;
    private final View searchClear;
    private final View filterButton;
    private final View filterDot;
    private final View report;
    private final TextView reportTitle;
    private final TextView reportSubtitle;
    private final View reportChip;
    private final TextView reportChipText;
    private final ImageView reportChipDot;
    private final View offlineNote;
    private final View featured;
    private final TextView eyebrow;
    private final View seeAll;
    private final View bigCard;
    private final View smallRow;
    private final View small1;
    private final View small2;
    private final View results;
    private final TextView resultsTitle;
    private final TextView resultsSort;
    private final View searchBar;
    private final ImageView searchIcon;
    private final View chips;
    private final View chipAvailable;
    private final View chipDistance;
    private final View chipCuisine;
    private final TextView chipCuisineText;
    private final TextView refreshed;
    private long lastServerAnswerAt; // time of the last LIVE answer from Firestore (footer)
    private final LinearLayout resultsList;
    private final TextView resultsSeeAll;
    private final View skeleton;
    private final View state;

    private ProfileData profile;
    private FirebaseUser user;
    private LocationScope scope;
    private String scopeRequestKey;      // ignores late catalogue answers for an older location
    private List<Restaurant> restaurants; // null = not loaded yet
    private boolean fromCache;
    private RestaurantRepository.Failure failure;
    private DiscoverFilters filters = DiscoverFilters.defaults();
    private String query = "";
    private Double userLat;
    private Double userLng;
    private boolean active;
    private boolean navigating;          // prevents double-opening details / See all
    private boolean serverWaitExpired;   // the server didn't answer in time: stop showing the skeleton
    private boolean serverWaitScheduled;
    private boolean profileUnavailable; // profile couldn't be read (offline, nothing cached)
    private boolean cardsAnimated;
    private final Runnable applySearch = this::render;
    private final Runnable greetingTick = this::refreshGreeting;
    /** [CONFIG] Re-check freshness ("Updated 4 min ago", 30-minute limit) this often while visible. */
    private static final long REFRESH_MS = 60_000L;
    /** [CONFIG] Show offline/empty instead of the skeleton if the server hasn't answered by then. */
    private static final long SERVER_WAIT_MS = 10_000L;
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            render();
            if (active) main.postDelayed(this, REFRESH_MS);
        }
    };
    private final Runnable serverWaitTimeout = () -> {
        serverWaitScheduled = false;
        serverWaitExpired = true;
        render();
    };
    /** Connection back while live updates had stopped: re-attach by itself (a read, safe to retry). */
    private final ConnectivityMonitor.Listener connectivity = online -> {
        if (online && active && failure != null && scope != null) {
            failure = null;
            serverWaitExpired = false;
            repository.retry(scope.city.id);
        }
    };

    DiscoverSection(Host host, View discoverRoot) {
        this.host = host;
        this.activity = host.activity();
        this.root = discoverRoot;
        deviceLocation = new DeviceLocationHelper(activity);
        coach = new LocationCoachMark(discoverRoot);
        motion = Settings.Global.getFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;

        scroll = root.findViewById(R.id.discover_scroll);
        greeting = root.findViewById(R.id.discover_greeting);
        locationText = root.findViewById(R.id.discover_location_text);
        locationButton = root.findViewById(R.id.discover_location_button);
        avatar = root.findViewById(R.id.discover_avatar);
        searchInput = root.findViewById(R.id.discover_search_input);
        searchClear = root.findViewById(R.id.discover_search_clear);
        filterButton = root.findViewById(R.id.discover_filter_button);
        filterDot = root.findViewById(R.id.discover_filter_dot);
        report = root.findViewById(R.id.discover_report);
        reportTitle = root.findViewById(R.id.report_title);
        reportSubtitle = root.findViewById(R.id.report_subtitle);
        reportChip = root.findViewById(R.id.report_chip);
        reportChipText = root.findViewById(R.id.report_chip_text);
        reportChipDot = root.findViewById(R.id.report_chip_dot);
        offlineNote = root.findViewById(R.id.discover_offline_note);
        featured = root.findViewById(R.id.discover_featured);
        eyebrow = root.findViewById(R.id.discover_eyebrow);
        seeAll = root.findViewById(R.id.discover_see_all);
        bigCard = root.findViewById(R.id.featured_big);
        smallRow = root.findViewById(R.id.featured_small_row);
        small1 = root.findViewById(R.id.featured_small_1);
        small2 = root.findViewById(R.id.featured_small_2);
        results = root.findViewById(R.id.discover_results);
        resultsTitle = root.findViewById(R.id.results_title);
        resultsSort = root.findViewById(R.id.results_sort);
        searchBar = root.findViewById(R.id.discover_search_bar);
        searchIcon = root.findViewById(R.id.discover_search_icon);
        chips = root.findViewById(R.id.discover_chips);
        chipAvailable = root.findViewById(R.id.chip_available);
        chipDistance = root.findViewById(R.id.chip_distance);
        chipCuisine = root.findViewById(R.id.chip_cuisine);
        chipCuisineText = root.findViewById(R.id.chip_cuisine_text);
        refreshed = root.findViewById(R.id.discover_refreshed);
        resultsList = root.findViewById(R.id.results_list);
        resultsSeeAll = root.findViewById(R.id.results_see_all);
        skeleton = root.findViewById(R.id.discover_skeleton);
        state = root.findViewById(R.id.discover_state);

        // The avatar is shown only (phase 6): no click, no ripple, no invisible touch area.
        avatar.setOnClickListener(null);
        avatar.setClickable(false);

        locationButton.setOnClickListener(v -> changeLocation());
        filterButton.setOnClickListener(v -> openFilters());
        seeAll.setOnClickListener(v -> openSeeAll());
        resultsSeeAll.setOnClickListener(v -> openSeeAll());
        searchClear.setOnClickListener(v -> searchInput.setText(""));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                updateSearchBarLook();
                main.removeCallbacks(applySearch);
                main.postDelayed(applySearch, query.isEmpty() ? 0 : SEARCH_DEBOUNCE_MS);
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                main.removeCallbacks(applySearch);
                render();
                hideKeyboard();
                return true;
            }
            return false;
        });
        searchInput.setOnFocusChangeListener((v, hasFocus) -> updateSearchBarLook());
        // Quick filter chips (same DiscoverFilters as the filter sheet and the Map).
        chipAvailable.setOnClickListener(v -> {
            filters = filters.withAvailableNow(!filters.availableNow);
            render();
        });
        chipDistance.setOnClickListener(v -> {
            filters = filters.withMaxDistanceKm(filters.maxDistanceKm == null ? DiscoverFilters.NEAR_KM : null);
            render();
        });
        chipCuisine.setOnClickListener(v -> {
            hideKeyboard();
            FilterSheet.showCuisines(activity, filters, RestaurantQuery.cuisines(scoped()), f -> {
                filters = f;
                render();
            });
        });
        EntranceAnimator press = new EntranceAnimator(activity.getContentResolver());
        press.attachPressFeedback(bigCard);
        press.attachPressFeedback(small1);
        press.attachPressFeedback(small2);

        refreshGreeting();
        render();
    }

    // ------------------------------------------------------------------ lifecycle (from Home)

    /** Discover is visible and Home is started: listen to restaurants; otherwise stop listening. */
    void setActive(boolean nowActive) {
        if (nowActive == active) return;
        active = nowActive;
        if (active) {
            navigating = false;
            if (scope != null) repository.observe(this, scope.city.id);
            ConnectivityMonitor.get(activity).addListener(connectivity);
            requestPosition();
            refreshGreeting();
            main.removeCallbacks(minuteTick);
            main.postDelayed(minuteTick, REFRESH_MS);
            render();
        } else {
            repository.stopObserving(this);
            ConnectivityMonitor.get(activity).removeListener(connectivity);
            main.removeCallbacks(greetingTick);
            main.removeCallbacks(minuteTick);
            deviceLocation.cancel();
            coach.hide();
            hideKeyboard();
        }
    }

    void release() {
        repository.stopObserving(this);
        ConnectivityMonitor.get(activity).removeListener(connectivity);
        main.removeCallbacksAndMessages(null);
        deviceLocation.release();
    }

    /** Back key: close the tip first. Returns true when handled. */
    boolean handleBack() {
        if (coach.isShowing()) {
            coach.hide();
            return true;
        }
        if (!query.isEmpty()) {
            searchInput.setText("");
            return true;
        }
        return false;
    }

    void scrollToTop() {
        if (motion) scroll.smoothScrollTo(0, 0);
        else scroll.scrollTo(0, 0);
    }

    /** The profile couldn't be read at all (no network and no saved copy). */
    void onProfileUnavailable(FirebaseUser u) {
        user = u;
        if (profile == null) {
            profileUnavailable = true;
            ProfileImages.show(avatar, null, u, u != null ? u.getDisplayName() : null);
            render();
        }
    }

    /** New profile data (on every return to Home, and after the location was changed). */
    void onProfile(ProfileData p, FirebaseUser u) {
        profileUnavailable = false;
        profile = p;
        user = u;
        refreshGreeting();
        ProfileImages.show(avatar, p, u, p != null ? p.username() : (u != null ? u.getDisplayName() : null));
        if (p == null) return;

        String key = p.cityId + "/" + (p.areaId == null ? "" : p.areaId); // same format as LocationScope.key()
        if (p.cityId == null || p.cityId.isEmpty()) {
            scope = null;
            scopeRequestKey = null;
            locationText.setText(R.string.discover_location_missing);
            updateLocationDescription();
            render();
            return;
        }
        if (scope != null && key.equals(scope.key())) return; // unchanged
        // Show the saved names at once; aliases arrive with the catalogue.
        locationText.setText(p.addressLine() != null ? p.addressLine() : p.cityName);
        scopeRequestKey = key;
        scopeLoader.load(p.cityId, p.cityName, p.areaId, p.areaName, newScope -> {
            if (!key.equals(scopeRequestKey) || activity.isDestroyed()) return;
            boolean cityChanged = scope == null || newScope == null || !scope.city.id.equals(newScope.city.id);
            scope = newScope;
            if (scope != null) locationText.setText(scope.displayName());
            updateLocationDescription();
            if (cityChanged && active && scope != null) repository.observe(this, scope.city.id);
            render();
            if (active && scope != null && u != null) coach.showIfFirstTime(u.getUid());
        });
    }

    /** Called after Settings/the location form saved a new location: show its top results. */
    void onLocationChanged() {
        scrollToTop();
        cardsAnimated = false;
    }

    // ------------------------------------------------------------------ RestaurantRepository.Observer

    @Override
    public void onRestaurants(List<Restaurant> list, boolean cached) {
        boolean backOnline = !cached && fromCache;
        restaurants = list;
        fromCache = cached;
        failure = null;
        if (!cached) {
            lastServerAnswerAt = System.currentTimeMillis();
            serverWaitExpired = false;
            serverWaitScheduled = false;
            main.removeCallbacks(serverWaitTimeout);
        }
        if (backOnline) RestaurantImageLoader.clearFailures(); // retry broken images once, not on every update
        render();
    }

    @Override
    public void onRestaurantsFailed(RestaurantRepository.Failure f) {
        failure = f;
        if (restaurants != null) fromCache = true; // keep what's shown, but it's no longer live
        render();
    }

    // ------------------------------------------------------------------ actions

    private void changeLocation() {
        coach.hide();
        hideKeyboard();
        host.openLocationPicker(profile != null ? profile.cityId : null, profile != null ? profile.areaId : null);
    }

    private void openFilters() {
        hideKeyboard();
        List<Restaurant> scoped = scoped();
        FilterSheet.show(activity, filters, FilterSheet.Options.from(scoped, userLat, userLng,
                System.currentTimeMillis()), f -> {
            filters = f;
            render();
        });
    }

    /** Map tab: the full-screen map with Discover's browsing area, search text and filters. */
    void openMap() {
        hideKeyboard();
        coach.hide();
        AppNavigator.openMap(activity, scope, query, filters);
    }

    private void openSeeAll() {
        if (scope == null || navigating) return;
        navigating = true;
        hideKeyboard();
        AppNavigator.openRestaurantList(activity, scope, query, filters, userLat, userLng);
    }

    private void openDetail(Restaurant r) {
        if (navigating) return;
        navigating = true;
        hideKeyboard();
        AppNavigator.openRestaurantDetail(activity, r.id, scope, userLat, userLng);
    }

    private void retry() {
        if (!ConnectivityMonitor.get(activity).mayHaveInternet()) {
            OfflineOverlay.show(activity, this::retry, true);
            return;
        }
        failure = null;
        restaurants = null;
        serverWaitExpired = false;
        render();
        if (scope != null) repository.retry(scope.city.id);
    }

    // ------------------------------------------------------------------ greeting + position

    private void refreshGreeting() {
        Calendar now = Calendar.getInstance();
        greeting.setText(Greeting.text(activity, profile != null ? profile.firstName : null, now));
        main.removeCallbacks(greetingTick);
        if (active) main.postDelayed(greetingTick, Greeting.millisUntilNextChange(now));
    }

    private void requestPosition() {
        UserPosition.request(activity, deviceLocation, position -> {
            Double lat = position != null ? position.getLatitude() : null;
            Double lng = position != null ? position.getLongitude() : null;
            boolean changed = lat == null ? userLat != null : !lat.equals(userLat) || !lng.equals(userLng);
            userLat = lat;
            userLng = lng;
            if (changed) render();
        });
    }

    private void updateLocationDescription() {
        locationButton.setContentDescription(activity.getString(R.string.discover_location_description,
                locationText.getText()));
    }

    // ------------------------------------------------------------------ rendering

    private List<Restaurant> scoped() {
        return restaurants == null || scope == null ? new ArrayList<>() : RestaurantQuery.inScope(restaurants, scope);
    }

    private void render() {
        filterDot.setVisibility(filters.activeCount() > 0 ? View.VISIBLE : View.GONE);
        filterButton.setContentDescription(activity.getString(
                filters.activeCount() > 0 ? R.string.discover_filters_on : R.string.discover_filters));

        boolean online = ConnectivityMonitor.get(activity).isOnline();
        offlineNote.setVisibility(View.GONE);
        refreshed.setVisibility(View.GONE);
        chips.setVisibility(View.GONE); // shown again by bindChips() when there is data

        // 0. The profile itself couldn't be loaded (offline, nothing saved on this phone yet).
        if (profile == null && profileUnavailable) {
            showOnly(null);
            report.setVisibility(View.GONE);
            showState(online ? R.drawable.ic_storefront : R.drawable.ic_wifi_off,
                    activity.getString(online ? R.string.error_restaurants_title : R.string.error_restaurants_offline_title),
                    activity.getString(R.string.error_restaurants_text),
                    activity.getString(R.string.error_retry), () -> {
                        if (!ConnectivityMonitor.get(activity).mayHaveInternet()) {
                            OfflineOverlay.show(activity, host::reloadProfile, true);
                        } else {
                            host.reloadProfile();
                        }
                    });
            return;
        }
        // 1. No browsing location in the profile.
        if (profile != null && scope == null && scopeRequestKey == null) {
            showOnly(null);
            report.setVisibility(View.GONE);
            showState(R.drawable.ic_location_pin, activity.getString(R.string.no_location_title),
                    activity.getString(R.string.no_location_text),
                    activity.getString(R.string.empty_area_action), this::changeLocation);
            return;
        }
        // 2. Loading (profile, catalogue or restaurants). An EMPTY first answer from the phone's
        //    cache while online is not "no restaurants": the server answer is on its way.
        boolean waitingForServer = restaurants != null && restaurants.isEmpty() && fromCache && online
                && failure == null && !serverWaitExpired;
        if (waitingForServer && !serverWaitScheduled) {
            serverWaitScheduled = true;
            main.postDelayed(serverWaitTimeout, SERVER_WAIT_MS);
        }
        if (scope == null || (restaurants == null && failure == null) || waitingForServer) {
            showOnly(skeleton);
            report.setVisibility(View.VISIBLE);
            reportTitle.setText(R.string.report_loading);
            reportSubtitle.setText("");
            reportChip.setVisibility(View.INVISIBLE);
            return;
        }
        // 3. Could not load, or offline with nothing saved on the phone yet.
        boolean nothingOffline = restaurants != null && restaurants.isEmpty() && fromCache
                && (!online || failure != null || serverWaitExpired);
        if ((restaurants == null && failure != null) || nothingOffline) {
            showOnly(null);
            report.setVisibility(View.GONE);
            boolean offline = !online || failure == RestaurantRepository.Failure.NETWORK || nothingOffline;
            showState(offline ? R.drawable.ic_wifi_off : R.drawable.ic_storefront,
                    activity.getString(offline ? R.string.error_restaurants_offline_title : R.string.error_restaurants_title),
                    offline ? activity.getString(R.string.error_restaurants_offline_text, scope.shortLabel())
                            : activity.getString(R.string.error_restaurants_text),
                    activity.getString(R.string.error_retry), this::retry);
            return;
        }

        long now = System.currentTimeMillis();
        List<Restaurant> scoped = scoped();
        bindReport(RestaurantQuery.stats(scoped, now), now);
        offlineNote.setVisibility(fromCache && !scoped.isEmpty() ? View.VISIBLE : View.GONE);
        eyebrow.setText(activity.getString(R.string.discover_eyebrow, scope.shortLabel()));
        seeAll.setContentDescription(activity.getString(R.string.discover_see_all_description, scope.shortLabel()));

        // 4. Nothing in this area at all.
        if (scoped.isEmpty()) {
            showOnly(null);
            showState(R.drawable.ic_storefront, activity.getString(R.string.empty_area_title),
                    activity.getString(R.string.empty_area_text),
                    activity.getString(R.string.empty_area_action), this::changeLocation);
            return;
        }

        bindChips(scoped, now);
        bindRefreshed();
        // Shared search (same function as See All and Map): filters + deterministic order.
        List<Restaurant> filtered = RestaurantQuery.search(scoped, scope, filters, query, userLat, userLng, now);
        boolean searching = !query.trim().isEmpty();

        // 5. Searching: results replace Featured.
        if (searching) {
            if (filtered.isEmpty()) {
                showOnly(null);
                showState(R.drawable.ic_search, activity.getString(R.string.empty_search_title, query.trim()),
                        activity.getString(R.string.empty_search_text),
                        activity.getString(R.string.empty_search_action), () -> {
                            searchInput.setText("");
                            filters = DiscoverFilters.defaults();
                            render();
                        });
                return;
            }
            showOnly(results);
            bindResults(filtered, now);
            return;
        }
        // 6. A filter removed everything.
        if (filtered.isEmpty()) {
            showOnly(null);
            showState(R.drawable.ic_tune, activity.getString(R.string.empty_filter_title),
                    activity.getString(R.string.empty_filter_text),
                    activity.getString(R.string.empty_filter_action), () -> {
                        filters = DiscoverFilters.defaults();
                        render();
                    });
            return;
        }
        // 7. Featured: top-ranked restaurant + the next two.
        showOnly(featured);
        bindFeatured(filtered, now);
    }

    /** Shows exactly one of featured / results / skeleton (or none) and hides the state card. */
    private void showOnly(View which) {
        featured.setVisibility(which == featured ? View.VISIBLE : View.GONE);
        results.setVisibility(which == results ? View.VISIBLE : View.GONE);
        skeleton.setVisibility(which == skeleton ? View.VISIBLE : View.GONE);
        state.setVisibility(View.GONE);
        if (which == skeleton) pulse(skeleton);
        else skeleton.animate().cancel();
    }

    private void showState(int icon, String title, String text, String action, final Runnable onAction) {
        state.setVisibility(View.VISIBLE);
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(icon);
        ((TextView) state.findViewById(R.id.state_title)).setText(title);
        ((TextView) state.findViewById(R.id.state_text)).setText(text);
        TextView button = state.findViewById(R.id.state_action);
        button.setText(action);
        button.setOnClickListener(v -> onAction.run());
    }

    private void bindReport(RestaurantQuery.Stats s, long now) {
        report.setVisibility(View.VISIBLE);
        String where = scope.shortLabel();
        reportTitle.setText(s.total == 0
                ? activity.getString(R.string.report_title_zero, where)
                : activity.getResources().getQuantityString(R.plurals.report_title, s.total, s.total, where));
        String sub;
        if (fromCache) {
            sub = activity.getString(R.string.report_offline);
        } else {
            String live = s.withLiveAvailability > 0
                    ? activity.getResources().getQuantityString(R.plurals.report_live_tables,
                    s.withLiveAvailability, s.withLiveAvailability)
                    : activity.getString(R.string.report_no_live);
            String open = s.reportingOpenStatus > 0 ? activity.getString(R.string.report_open_now, s.openNow) : null;
            sub = RestaurantViews.join(activity, open, live);
        }
        reportSubtitle.setText(sub);

        reportChip.setVisibility(View.VISIBLE);
        reportChip.setBackgroundResource(fromCache ? R.drawable.bg_report_chip_offline : R.drawable.bg_report_chip_live);
        int chipColor = activity.getColor(fromCache ? R.color.asanni_report_offline_text : R.color.asanni_live_text);
        reportChipText.setText(fromCache ? R.string.report_chip_offline : R.string.report_chip_live);
        reportChipText.setTextColor(chipColor);
        reportChipDot.setColorFilter(chipColor);
        reportChip.setContentDescription(activity.getString(fromCache
                ? R.string.report_chip_offline_description : R.string.report_chip_live_description));
    }

    private void bindFeatured(List<Restaurant> ranked, long now) {
        bindBig(ranked.get(0), now);
        boolean has1 = ranked.size() > 1;
        boolean has2 = ranked.size() > 2;
        smallRow.setVisibility(has1 ? View.VISIBLE : View.GONE);
        if (has1) bindSmall(small1, ranked.get(1), now);
        small2.setVisibility(has2 ? View.VISIBLE : View.INVISIBLE);
        if (has2) bindSmall(small2, ranked.get(2), now);
        if (!cardsAnimated) {
            cardsAnimated = true;
            reveal(bigCard, 0);
            if (has1) reveal(small1, 60);
            if (has2) reveal(small2, 120);
        }
    }

    private void bindBig(final Restaurant r, long now) {
        Context c = activity;
        float d = c.getResources().getDisplayMetrics().density;
        ((TextView) bigCard.findViewById(R.id.featured_big_name)).setText(r.name);
        TextView meta = bigCard.findViewById(R.id.featured_big_meta);
        String metaText = RestaurantViews.cuisineAndArea(c, r, scope);
        meta.setText(metaText);
        meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);
        View badge = bigCard.findViewById(R.id.featured_big_badge);
        RestaurantViews.bindBadge(badge, r, now, false);

        // Info line: distance from the phone's GPS (only when known) + availability freshness.
        Double km = RestaurantViews.km(r, userLat, userLng);
        String distance = km != null ? c.getString(R.string.distance_from_you, RestaurantViews.distance(c, km)) : null;
        String updated = r.freeTables != null || r.openNow != null ? RestaurantViews.updated(c, r.updatedAtMillis, now) : null;
        String info = RestaurantViews.join(c, distance, updated);
        int icon = R.drawable.ic_navigation_outline;
        if (info.isEmpty() && r.address != null) {
            info = r.address;
            icon = R.drawable.ic_location_pin;
        } else if (distance == null) {
            icon = R.drawable.ic_schedule;
        }
        View infoRow = bigCard.findViewById(R.id.featured_big_info);
        infoRow.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) bigCard.findViewById(R.id.featured_big_info_text)).setText(info);
        ((ImageView) bigCard.findViewById(R.id.featured_big_info_icon)).setImageResource(icon);

        // Images: the real LOGO (shown whole); a restaurant photo only if the data has one.
        final ImageView logo = bigCard.findViewById(R.id.featured_big_logo);
        final ImageView cover = bigCard.findViewById(R.id.featured_big_cover);
        final ImageView logoBadge = bigCard.findViewById(R.id.featured_big_logo_badge);
        int widthPx = c.getResources().getDisplayMetrics().widthPixels;
        logo.setPadding(0, 0, 0, 0);
        cover.setVisibility(View.GONE);
        logoBadge.setVisibility(View.GONE);
        cover.setTag(null);     // a late photo of the previous top restaurant must not appear here
        logoBadge.setTag(null);
        // [UI ASSET] Fallback image: res/drawable/restaurant_placeholder.xml
        RestaurantImageLoader.load(logo, r.logo, (int) (134 * d), R.drawable.restaurant_placeholder, view -> {
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = (int) (18 * d);
            view.setPadding(pad, pad, pad, pad);
        });
        if (r.cover != null) {
            RestaurantImageLoader.load(cover, r.cover, widthPx, R.drawable.restaurant_placeholder, view -> {
                cover.setVisibility(View.VISIBLE);
                if (r.logo != null) {
                    logoBadge.setVisibility(View.VISIBLE);
                    RestaurantImageLoader.load(logoBadge, r.logo, (int) (60 * d), R.drawable.restaurant_placeholder, null);
                }
            });
        }
        logo.setContentDescription(null);
        bigCard.setContentDescription(c.getString(R.string.restaurant_card_description, r.name,
                RestaurantViews.join(c, RestaurantViews.join(c, metaText, RestaurantViews.badgeLong(c, r, now)), info)));
        bigCard.setOnClickListener(v -> openDetail(r));
    }

    private void bindSmall(View card, final Restaurant r, long now) {
        Context c = activity;
        ((TextView) card.findViewById(R.id.card_name)).setText(r.name);
        View badge = card.findViewById(R.id.card_badge);
        RestaurantViews.bindBadge(badge, r, now, false);
        String meta = RestaurantViews.cuisineAndDistance(c, r, scope, RestaurantViews.km(r, userLat, userLng));
        TextView metaView = card.findViewById(R.id.card_meta);
        metaView.setText(meta);
        metaView.setVisibility(meta.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        card.setContentDescription(c.getString(R.string.restaurant_card_description, r.name,
                RestaurantViews.join(c, meta, RestaurantViews.badgeLong(c, r, now))));
        card.setOnClickListener(v -> openDetail(r));
    }

    private void bindResults(List<Restaurant> ranked, long now) {
        resultsTitle.setText(activity.getResources().getQuantityString(R.plurals.results_count,
                ranked.size(), ranked.size()));
        // Honest sort label: how RestaurantQuery.search() actually ordered the list.
        resultsSort.setText(R.string.sort_best_match);
        int shown = Math.min(ranked.size(), MAX_INLINE_RESULTS);
        LayoutInflater inflater = LayoutInflater.from(activity);
        // Reuse existing rows; add/remove only the difference (cheap on every key press).
        while (resultsList.getChildCount() > shown) resultsList.removeViewAt(resultsList.getChildCount() - 1);
        while (resultsList.getChildCount() < shown) {
            resultsList.addView(inflater.inflate(R.layout.item_restaurant_row, resultsList, false));
        }
        for (int i = 0; i < shown; i++) {
            final Restaurant r = ranked.get(i);
            View row = resultsList.getChildAt(i);
            RestaurantViews.bindRow(row, r, scope, userLat, userLng, now);
            // Click on the row itself: the card's ripple follows the row's pressed/focused state.
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(row.findViewById(R.id.row_card).getContentDescription());
            row.setOnClickListener(v -> openDetail(r));
        }
        resultsSeeAll.setVisibility(ranked.size() > shown ? View.VISIBLE : View.GONE);
        resultsSeeAll.setText(activity.getString(R.string.search_see_all_results, ranked.size()));
    }

    // ------------------------------------------------------------------ phase 7: search bar + chips

    /** Reference look: red outline + red icon while searching, quiet outline otherwise. */
    private void updateSearchBarLook() {
        boolean activeSearch = searchInput.hasFocus() || !query.isEmpty();
        searchBar.setBackgroundResource(activeSearch ? R.drawable.bg_search_active : R.drawable.bg_search_idle);
        searchIcon.setColorFilter(activity.getColor(activeSearch ? R.color.asanni_welcome_accent : R.color.asanni_discover_muted));
    }

    /** Shows only the chips the data supports; drops a chip's filter if it stopped being possible. */
    private void bindChips(List<Restaurant> scoped, long now) {
        boolean live = RestaurantQuery.supportsAvailableNow(scoped, now) || filters.availableNow;
        boolean near = RestaurantQuery.supportsDistance(scoped, userLat, userLng);
        List<String> cuisines = RestaurantQuery.cuisines(scoped);
        if (!near && filters.maxDistanceKm != null) filters = filters.withMaxDistanceKm(null); // GPS gone
        chipAvailable.setVisibility(live ? View.VISIBLE : View.GONE);
        chipDistance.setVisibility(near ? View.VISIBLE : View.GONE);
        chipCuisine.setVisibility(!cuisines.isEmpty() ? View.VISIBLE : View.GONE);
        chips.setVisibility(live || near || !cuisines.isEmpty() ? View.VISIBLE : View.GONE);
        chipAvailable.setSelected(filters.availableNow);
        chipDistance.setSelected(filters.maxDistanceKm != null);
        chipCuisine.setSelected(filters.cuisine != null);
        chipCuisineText.setText(filters.cuisine != null ? filters.cuisine : activity.getString(R.string.chip_cuisine));
        chipCuisine.setContentDescription(filters.cuisine != null
                ? activity.getString(R.string.chip_cuisine_selected, filters.cuisine)
                : activity.getString(R.string.chip_cuisine_description));
        filterDot.setVisibility(filters.activeCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** Reference footer "Live availability refreshed at 9:41 PM" (only after a real server answer). */
    private void bindRefreshed() {
        if (lastServerAnswerAt == 0 || fromCache) {
            refreshed.setVisibility(View.GONE);
            return;
        }
        refreshed.setVisibility(View.VISIBLE);
        refreshed.setText(activity.getString(R.string.refreshed_at,
                android.text.format.DateFormat.getTimeFormat(activity).format(new java.util.Date(lastServerAnswerAt))));
    }

    // ------------------------------------------------------------------ small helpers

    private void reveal(View v, long delay) {
        if (!motion) return;
        v.setAlpha(0f);
        v.setTranslationY(14 * activity.getResources().getDisplayMetrics().density);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260L).start();
    }

    /** Gentle loading pulse (stops as soon as content appears; none with animations off). */
    private void pulse(final View v) {
        if (!motion) return;
        v.animate().cancel();
        v.setAlpha(1f);
        v.animate().alpha(0.55f).setDuration(700L).withEndAction(() -> {
            if (v.getVisibility() == View.VISIBLE) {
                v.animate().alpha(1f).setDuration(700L).withEndAction(() -> {
                    if (v.getVisibility() == View.VISIBLE) pulse(v);
                }).start();
            } else {
                v.setAlpha(1f);
            }
        }).start();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        searchInput.clearFocus();
    }
}