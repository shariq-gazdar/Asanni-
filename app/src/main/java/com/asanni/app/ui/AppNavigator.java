package com.asanni.app.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

import com.asanni.app.R;

/**
 * Every screen-to-screen move in one place, so destinations are easy to change later.
 *
 * Location: app/src/main/java/com/asanni/app/ui/AppNavigator.java  (MODIFIED in phase 6)
 *
 *   Get Started --"Get started"--> Choose (diner)
 *   Get Started --"Log in"-------> Google Login --> OnboardingRouter decides:
 *        Choose -> Allow Location (-> Manual Location) -> Registration (-> Phone verification)
 *        -> Notification explanation -> Home
 *   Home (phase 6: bottom navigation Discover | Map | Saved | Profile)
 *     Profile tab ------------> Settings --profile row--> Info
 *                                        --address row--> Manual Location (edit mode)
 *     Discover location arrow -> Manual Location (edit mode) -> back to Discover
 *     Discover See all -------> Restaurant list --> Restaurant details
 *     Discover restaurant card -> Restaurant details
 *   Phase 8: Restaurant details (from Discover / See All / Map / Saved)
 *     Back -> wherever it was opened from · Get details -> Restaurant information
 *     Get directions -> Map with the route (Back -> Restaurant details)
 *     Saved tab card -> Restaurant details
 *   Info --Sign out--> Get Started
 */
final class AppNavigator {

    static final String EXTRA_CONTINUE_AS_DINER = "com.asanni.app.extra.CONTINUE_AS_DINER";
    static final String EXTRA_PREFILL_CITY_ID = "com.asanni.app.extra.PREFILL_CITY_ID";
    static final String EXTRA_PREFILL_AREA_ID = "com.asanni.app.extra.PREFILL_AREA_ID";
    /** Manual Location opened from Settings: after saving, just go back. */
    static final String EXTRA_EDIT_MODE = "com.asanni.app.extra.EDIT_MODE";

    // Phase 6: restaurant list / details
    static final String EXTRA_CITY_ID = "com.asanni.app.extra.CITY_ID";
    static final String EXTRA_CITY_NAME = "com.asanni.app.extra.CITY_NAME";
    static final String EXTRA_AREA_ID = "com.asanni.app.extra.AREA_ID";
    static final String EXTRA_AREA_NAME = "com.asanni.app.extra.AREA_NAME";
    static final String EXTRA_QUERY = "com.asanni.app.extra.QUERY";
    static final String EXTRA_FILTERS = "com.asanni.app.extra.FILTERS";
    static final String EXTRA_USER_LAT = "com.asanni.app.extra.USER_LAT";
    static final String EXTRA_USER_LNG = "com.asanni.app.extra.USER_LNG";
    static final String EXTRA_RESTAURANT_ID = "com.asanni.app.extra.RESTAURANT_ID";
    // Phase 8: Get directions -> Map
    static final String EXTRA_RESTAURANT_NAME = "com.asanni.app.extra.RESTAURANT_NAME"; // PHASE 9
    static final String EXTRA_ROUTE_TO = "com.asanni.app.extra.ROUTE_TO";
    static final String EXTRA_ROUTE_TOKEN = "com.asanni.app.extra.ROUTE_TOKEN";

    static final int REQUEST_EDIT_ADDRESS = 5101;
    static final int REQUEST_VERIFY_PHONE = 5102;

    private AppNavigator() {}

    static void openChooseRole(Activity from) {
        start(from, new Intent(from, ChooseRoleActivity.class));
    }

    static void openGoogleLogin(Activity from) {
        start(from, new Intent(from, GoogleLoginActivity.class));
    }

    static void openGoogleLoginThenContinueAsDiner(Activity from) {
        Intent intent = new Intent(from, GoogleLoginActivity.class);
        intent.putExtra(EXTRA_CONTINUE_AS_DINER, true);
        start(from, intent);
    }

    static void openChooseRoleAfterSignIn(Activity from) {
        startClearingBackStack(from, new Intent(from, ChooseRoleActivity.class));
    }

    static void openAllowLocation(Activity from) {
        start(from, new Intent(from, AllowLocationActivity.class));
    }

    static void openAllowLocationAfterSignIn(Activity from) {
        startClearingBackStack(from, new Intent(from, AllowLocationActivity.class));
    }

    /** Onboarding: pick a location (optionally pre-selecting a GPS-detected city). */
    static void openManualLocation(Activity from, String prefillCityId) {
        Intent intent = new Intent(from, ManualLocationActivity.class);
        if (prefillCityId != null) intent.putExtra(EXTRA_PREFILL_CITY_ID, prefillCityId);
        start(from, intent);
    }

    /** Settings: change the saved address with the SAME manual form; returns to Settings. */
    static void openEditAddress(Activity from, String cityId, String areaId) {
        Intent intent = new Intent(from, ManualLocationActivity.class);
        intent.putExtra(EXTRA_EDIT_MODE, true);
        if (cityId != null) intent.putExtra(EXTRA_PREFILL_CITY_ID, cityId);
        if (areaId != null) intent.putExtra(EXTRA_PREFILL_AREA_ID, areaId);
        from.startActivityForResult(intent, REQUEST_EDIT_ADDRESS, animation(from));
    }

    /** After the location step of onboarding: Registration decides what's left. */
    static void openRegistration(Activity from) {
        startClearingBackStack(from, new Intent(from, RegistrationActivity.class));
    }

    /** Phone verification for a number (E.164 or null). Result: RESULT_OK when verified + saved. */
    static void openPhoneVerification(Activity from, String initialE164) {
        Intent intent = new Intent(from, PhoneVerificationActivity.class);
        if (initialE164 != null) intent.putExtra(PhoneVerificationActivity.EXTRA_INITIAL_NUMBER, initialE164);
        from.startActivityForResult(intent, REQUEST_VERIFY_PHONE, animation(from));
    }

    static void openNotificationPrompt(Activity from) {
        startClearingBackStack(from, new Intent(from, NotificationPermissionActivity.class));
    }

    /** [NAVIGATION] The diner home screen (phase 6: Discover / Map / Saved / Profile). */
    static void openHome(Activity from) {
        startClearingBackStack(from, new Intent(from, HomeActivity.class));
    }

    static void openSettings(Activity from) {
        start(from, new Intent(from, SettingsActivity.class));
    }

    static void openInfo(Activity from) {
        start(from, new Intent(from, InfoActivity.class));
    }

    /** See All: every matching restaurant in the browsing area, same search + filters as Discover. */
    static void openRestaurantList(Activity from, com.asanni.app.restaurants.LocationScope scope, String query,
                                   com.asanni.app.restaurants.DiscoverFilters filters, Double userLat, Double userLng) {
        Intent intent = new Intent(from, RestaurantListActivity.class);
        putScope(intent, scope);
        intent.putExtra(EXTRA_QUERY, query);
        intent.putExtra(EXTRA_FILTERS, filters.encode());
        putPosition(intent, userLat, userLng);
        start(from, intent);
    }

    /**
     * Phase 7: full-screen Map (opened from the Map tab). REORDER_TO_FRONT + singleTop in the
     * manifest = never two Map screens in the stack, even with fast double taps.
     */
    static void openMap(Activity from, com.asanni.app.restaurants.LocationScope scope, String query,
                        com.asanni.app.restaurants.DiscoverFilters filters) {
        Intent intent = new Intent(from, MapActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        putScope(intent, scope);
        intent.putExtra(EXTRA_QUERY, query);
        intent.putExtra(EXTRA_FILTERS, filters.encode());
        start(from, intent);
    }

    /** Restaurant details (read-only information from the desktop app). */
    static void openRestaurantDetail(Activity from, String restaurantId, com.asanni.app.restaurants.LocationScope scope,
                                     Double userLat, Double userLng) {
        Intent intent = new Intent(from, RestaurantDetailActivity.class);
        intent.putExtra(EXTRA_RESTAURANT_ID, restaurantId);
        putScope(intent, scope);
        putPosition(intent, userLat, userLng);
        start(from, intent);
    }

    /**
     * Restaurant Details "Get details" -> Description page (phase 9 design). The browsing scope
     * and the phone's last position travel along, so "Open directions" behaves like Details.
     */
    static void openRestaurantInformation(Activity from, String restaurantId,
                                          com.asanni.app.restaurants.LocationScope scope, Double userLat, Double userLng) {
        Intent intent = new Intent(from, RestaurantInfoActivity.class);
        intent.putExtra(EXTRA_RESTAURANT_ID, restaurantId);
        putScope(intent, scope);
        putPosition(intent, userLat, userLng);
        start(from, intent);
    }

    /** PHASE 9: Description page "See more" -> all reviews of the restaurant. */
    static void openReviews(Activity from, String restaurantId, String restaurantName) {
        Intent intent = new Intent(from, ReviewsActivity.class);
        intent.putExtra(EXTRA_RESTAURANT_ID, restaurantId);
        intent.putExtra(EXTRA_RESTAURANT_NAME, restaurantName);
        start(from, intent);
    }

    /** PHASE 9: read-only "Your location" panel (Discover location chip, Settings address row). */
    static void openLocationInfo(Activity from) {
        from.startActivityForResult(new Intent(from, LocationInfoActivity.class), REQUEST_EDIT_ADDRESS, animation(from));
    }

    /** PHASE 9: Premium placeholder (no plans, prices or payments yet). */
    static void openPremium(Activity from) {
        start(from, new Intent(from, PremiumActivity.class));
    }

    /**
     * Phase 8: "Get directions" pressed on Restaurant Details -> the Map with that restaurant
     * selected and its route drawn. The Map draws a route ONLY when it receives EXTRA_ROUTE_TO
     * (never just because it was opened or a restaurant was selected).
     * CLEAR_TOP + SINGLE_TOP: when Details was opened FROM the Map, Details closes and that same
     * Map (with its search + filters) receives the route in onNewIntent - Back then goes to
     * Discover as usual. Opened from Discover / Saved, a new Map opens above Details, and Back
     * returns to Details.
     */
    static void openMapWithRoute(Activity from, String restaurantId, com.asanni.app.restaurants.LocationScope scope,
                                 double fromLat, double fromLng) {
        Intent intent = new Intent(from, MapActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        putScope(intent, scope);
        intent.putExtra(EXTRA_ROUTE_TO, restaurantId);
        intent.putExtra(EXTRA_USER_LAT, fromLat);
        intent.putExtra(EXTRA_USER_LNG, fromLng);
        intent.putExtra(EXTRA_ROUTE_TOKEN, nextRouteToken()); // each press handled exactly once
        start(from, intent);
    }

    private static long lastRouteToken;

    /** Increasing token: the Map ignores any token not newer than the last one it handled. */
    private static synchronized long nextRouteToken() {
        long t = Math.max(System.currentTimeMillis(), lastRouteToken + 1);
        lastRouteToken = t;
        return t;
    }

    private static void putScope(Intent intent, com.asanni.app.restaurants.LocationScope scope) {
        if (scope == null) return;
        intent.putExtra(EXTRA_CITY_ID, scope.city.id);
        intent.putExtra(EXTRA_CITY_NAME, scope.city.name);
        if (scope.area != null) {
            intent.putExtra(EXTRA_AREA_ID, scope.area.id);
            intent.putExtra(EXTRA_AREA_NAME, scope.area.name);
        }
    }

    private static void putPosition(Intent intent, Double lat, Double lng) {
        if (lat != null && lng != null) {
            intent.putExtra(EXTRA_USER_LAT, lat.doubleValue());
            intent.putExtra(EXTRA_USER_LNG, lng.doubleValue());
        }
    }

    /** Phase 7: launch routing (LaunchRouter) for a signed-out user. */
    static void openGetStartedFromLaunch(Activity from) {
        Intent intent = new Intent(from, GetStartedActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        from.startActivity(intent, ActivityOptions.makeCustomAnimation(from, R.anim.intro_enter, R.anim.intro_exit).toBundle());
    }

    /** Phase 7: brand-new account whose profile couldn't be created offline: the existing login
     *  screen shows its retry / offline page (the only case that still shows it at launch). */
    static void openGoogleLoginFromLaunch(Activity from) {
        startClearingBackStack(from, new Intent(from, GoogleLoginActivity.class));
    }

    static void openGetStartedAfterSignOut(Activity from) {
        startClearingBackStack(from, new Intent(from, GetStartedActivity.class));
    }

    private static void startClearingBackStack(Activity from, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        start(from, intent);
    }

    /** [ANIMATION] Uses res/anim/screen_enter.xml + screen_exit.xml for every screen change. */
    private static void start(Activity from, Intent intent) {
        from.startActivity(intent, animation(from));
    }

    private static android.os.Bundle animation(Activity from) {
        return ActivityOptions.makeCustomAnimation(from, R.anim.screen_enter, R.anim.screen_exit).toBundle();
    }
}