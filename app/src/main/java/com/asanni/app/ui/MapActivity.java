package com.asanni.app.ui;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.graphics.RectF;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.map.MapConfig;
import com.asanni.app.map.MarkerIcons;
import com.asanni.app.map.OpenRouteServiceClient;
import com.asanni.app.map.RouteResult;
import com.asanni.app.map.RouteService;
import com.asanni.app.map.TravelMode;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.restaurants.DiscoverFilters;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.LocationScopeLoader;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantImageLoader;
import com.asanni.app.restaurants.RestaurantQuery;
import com.asanni.app.restaurants.RestaurantRepository;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.BackgroundLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Full-screen, real, interactive Map (phase 7). Opened from the Map tab; the bottom navigation is
 * NOT shown here. The back arrow and the system Back key both return to Discover (this screen
 * just closes, so Discover keeps its state and the intro never appears).
 *
 * Location: app/src/main/java/com/asanni/app/ui/MapActivity.java  (NEW in phase 7)
 *
 * Map ......... MapLibre + OpenFreeMap (OpenStreetMap) tiles, see map/MapConfig.java.
 * Markers ..... EVERY registered restaurant that has valid coordinates, from the same shared live
 *               listener as Discover (RestaurantRepository: one bounded listener, no extra reads).
 *               Close markers are clustered. User filters (shared DiscoverFilters) limit markers and
 *               a notice says so. Restaurants without coordinates are never placed at made-up
 *               positions; they stay in the list with "Location unavailable for this restaurant."
 * You ......... blue dot + accuracy circle from a real GPS fix (only with permission; asked here
 *               with an explanation). No "home" marker: the profile has no home coordinates.
 * Search ...... RestaurantQuery.search() (same as Discover): whole browsing city, your area first.
 * Routes ...... openrouteservice road routes (car / walk / bike, alternatives, estimated times
 *               WITHOUT live traffic) + "Open in maps app" for live-traffic navigation.
 */
public class MapActivity extends AsanniActivity implements RestaurantRepository.Observer {

    private static final String TAG = "AsanniMap";
    private static final int REQUEST_LOCATION = 7101;
    private static final String PREFS = "asanni_tips";
    private static final String KEY_LOCATION_ASKED = "map_location_asked";   // explanation card shown once
    private static final String KEY_LOCATION_DENIED = "map_location_denied"; // Android's dialog was answered "deny"
    private static final String STATE_QUERY = "map_query";
    private static final String STATE_FILTERS = "map_filters";
    private static final String STATE_SELECTED = "map_selected";
    private static final String STATE_CAMERA_MOVED = "map_camera_moved";
    /** [TIMING] Refresh the blue dot this often while the map is on screen (no background tracking). */
    private static final long LOCATION_REFRESH_MS = 20_000L;
    private static final long SEARCH_DEBOUNCE_MS = 250L;
    /** [ANIMATION] Camera moves and panel expansion. */
    private static final int CAMERA_MS = 650;
    private static final long PANEL_MS = 220L;

    // Map sources / layers (ids)
    private static final String SRC_RESTAURANTS = "asanni-restaurants";
    private static final String SRC_SELECTED = "asanni-selected";
    private static final String SRC_ROUTES = "asanni-routes";
    private static final String LYR_CLUSTERS = "asanni-clusters";
    private static final String LYR_MARKERS = "asanni-markers";
    private static final String LYR_SELECTED = "asanni-selected-marker";
    private static final String LYR_ROUTE_ALT = "asanni-route-alt";
    private static final String LYR_ROUTE_CASING = "asanni-route-casing";
    private static final String LYR_ROUTE_MAIN = "asanni-route-main";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RestaurantRepository repository = RestaurantRepository.get();
    private final Set<String> addedImages = new HashSet<>();
    private final List<Restaurant> listItems = new ArrayList<>();

    private MapView mapView;
    private MapLibreMap map;
    private Style style;
    private boolean triedFallbackStyle;
    private boolean cameraPlaced;      // first camera position done
    private boolean userMovedCamera;   // the user panned/zoomed/selected: don't jump around on our own
    private boolean placedOnUser;

    private LocationScope scope;
    private DiscoverFilters filters = DiscoverFilters.defaults();
    private String query = "";
    private String selectedId;
    private List<Restaurant> restaurants; // null = not loaded yet
    private boolean fromCache;
    private RestaurantRepository.Failure failure;
    private List<Restaurant> results = new ArrayList<>();

    private DeviceLocationHelper deviceLocation;
    private Location userLocation;     // real GPS fix only
    private boolean locationComponentReady;
    private boolean resumed;

    private RouteService routeService;
    private RouteResult route;
    private int routeIndex;
    private TravelMode routeMode = TravelMode.DRIVING;
    private String routeRestaurantId;
    private boolean routeVisible;

    private boolean panelExpanded;
    private ValueAnimator panelAnimator;
    private long lastFilterOpen;
    private boolean navigating;

    private EditText searchInput;
    private View searchClear;
    private View filterDot;
    private View filterNotice;
    private View panel;
    private View hero;
    private View actions;
    private View routeBox;
    private View legend;
    private TextView panelTitle;
    private TextView panelCount;
    private TextView panelState;
    private TextView missingNote;
    private ListView list;
    private RowAdapter adapter;
    private View statusBox;

    private final Runnable applySearch = this::renderAll;
    private final Runnable locationTick = new Runnable() {
        @Override public void run() {
            refreshLocation(false);
            if (resumed) main.postDelayed(this, LOCATION_REFRESH_MS);
        }
    };
    private final ConnectivityMonitor.Listener connectivity = online -> {
        if (!online) return;
        if (failure != null && scope != null) {
            failure = null;
            repository.retry(scope.city.id);
        }
        if (map != null && style == null && statusBox.getVisibility() == View.VISIBLE) {
            triedFallbackStyle = false;
            loadStyle(MapConfig.STYLE_URL);
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
        MapLibre.getInstance(getApplicationContext()); // must run before the MapView is created
        setContentView(R.layout.activity_map);
        SystemBarsHelper.apply(this, findViewById(R.id.map_root));
        applyInsets();

        deviceLocation = new DeviceLocationHelper(this);
        routeService = new OpenRouteServiceClient(MapConfig.routingKey(this));
        bindViews();
        readState(savedInstanceState);

        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        mapView.addOnDidFailLoadingMapListener(this::onStyleFailed);
        mapView.getMapAsync(m -> {
            map = m;
            setUpMap();
            loadStyle(MapConfig.STYLE_URL);
        });
        renderAll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapView == null) return;
        mapView.onStart();
        if (scope != null) repository.observe(this, scope.city.id);
        else repository.observe(this, null);
        ConnectivityMonitor.get(this).addListener(connectivity);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView == null) return;
        mapView.onResume();
        resumed = true;
        navigating = false;
        main.removeCallbacks(locationTick);
        main.post(locationTick);
        updateLocationBanner(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView == null) return;
        resumed = false;
        main.removeCallbacks(locationTick);
        deviceLocation.cancel();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mapView == null) return;
        repository.stopObserving(this);
        ConnectivityMonitor.get(this).removeListener(connectivity);
        mapView.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (routeService != null) routeService.cancel();
        if (deviceLocation != null) deviceLocation.release();
        if (mapView != null) mapView.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (mapView != null) mapView.onSaveInstanceState(out);
        out.putString(STATE_QUERY, query);
        out.putString(STATE_FILTERS, filters.encode());
        out.putString(STATE_SELECTED, selectedId);
        out.putBoolean(STATE_CAMERA_MOVED, userMovedCamera || cameraPlaced); // MapView restores the view itself
    }

    /** System Back = the back arrow: return to Discover (keyboard / offline page close first). */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (OfflineOverlay.isShowing(this)) {
            OfflineOverlay.hide(this);
            return;
        }
        backToDiscover();
    }

    private void backToDiscover() {
        hideKeyboard();
        finish();
        overridePendingTransition(R.anim.screen_enter, R.anim.screen_exit);
    }

    // ============================================================================ setup

    private void bindViews() {
        searchInput = findViewById(R.id.map_search_input);
        searchClear = findViewById(R.id.map_search_clear);
        filterDot = findViewById(R.id.map_filter_dot);
        filterNotice = findViewById(R.id.map_filter_notice);
        panel = findViewById(R.id.map_panel);
        hero = findViewById(R.id.map_hero);
        actions = findViewById(R.id.map_actions);
        routeBox = findViewById(R.id.map_route);
        legend = findViewById(R.id.map_legend);
        panelTitle = findViewById(R.id.map_panel_title);
        panelCount = findViewById(R.id.map_panel_count);
        panelState = findViewById(R.id.map_panel_state);
        missingNote = findViewById(R.id.map_missing_note);
        list = findViewById(R.id.map_list);
        statusBox = findViewById(R.id.map_status);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < listItems.size()) select(listItems.get(position).id, true);
        });

        findViewById(R.id.map_back).setOnClickListener(v -> backToDiscover());
        findViewById(R.id.map_filter_button).setOnClickListener(v -> openFilters());
        findViewById(R.id.map_filter_clear).setOnClickListener(v -> {
            filters = DiscoverFilters.defaults();
            renderAll();
        });
        findViewById(R.id.map_my_location).setOnClickListener(v -> onMyLocationClicked());
        findViewById(R.id.map_status_retry).setOnClickListener(v -> {
            triedFallbackStyle = false;
            loadStyle(MapConfig.STYLE_URL);
        });
        findViewById(R.id.map_action_directions).setOnClickListener(v -> requestRoute());
        findViewById(R.id.map_action_details).setOnClickListener(v -> openDetails());
        findViewById(R.id.map_action_external).setOnClickListener(v -> openInMapsApp());
        findViewById(R.id.route_close).setOnClickListener(v -> closeRoute());
        findViewById(R.id.route_retry).setOnClickListener(v -> requestRoute());
        panelState.setOnClickListener(v -> {
            if (failure != null && scope != null) {
                failure = null;
                restaurants = null;
                renderAll();
                repository.retry(scope.city.id);
            }
        });
        hero.setOnClickListener(v -> {
            Restaurant r = heroRestaurant();
            if (r != null) select(r.id, true);
        });

        // Legend chips (non-interactive): same colors as the markers and badges.
        bindLegend(findViewById(R.id.legend_free), R.string.legend_free, R.drawable.bg_badge_free, R.color.asanni_badge_free_text);
        bindLegend(findViewById(R.id.legend_wait), R.string.legend_wait, R.drawable.bg_badge_wait, R.color.asanni_badge_wait_text);
        bindLegend(findViewById(R.id.legend_full), R.string.legend_full, R.drawable.bg_badge_closed, R.color.asanni_badge_closed_text);

        // Swipe the panel handle/header up for more results, down for fewer; tap toggles.
        View.OnTouchListener drag = new PanelDrag();
        findViewById(R.id.map_panel_handle).setOnTouchListener(drag);
        findViewById(R.id.map_panel_header).setOnTouchListener(drag);
        findViewById(R.id.map_panel_handle).setOnClickListener(v -> setPanelExpanded(!panelExpanded));
        findViewById(R.id.map_panel_header).setOnClickListener(v -> setPanelExpanded(!panelExpanded));

        buildModeChips();

        searchClear.setOnClickListener(v -> searchInput.setText(""));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                main.removeCallbacks(applySearch);
                main.postDelayed(applySearch, query.isEmpty() ? 0 : SEARCH_DEBOUNCE_MS);
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            main.removeCallbacks(applySearch);
            renderAll();
            hideKeyboard();
            if (!results.isEmpty()) select(results.get(0).id, true); // best match first, shown on the map
            return true;
        });
    }

    private void readState(Bundle saved) {
        Intent in = getIntent();
        String cityId = in.getStringExtra(AppNavigator.EXTRA_CITY_ID);
        if (saved != null) {
            query = nonNull(saved.getString(STATE_QUERY));
            filters = DiscoverFilters.decode(saved.getString(STATE_FILTERS));
            selectedId = saved.getString(STATE_SELECTED);
            userMovedCamera = saved.getBoolean(STATE_CAMERA_MOVED, false);
        } else {
            query = nonNull(in.getStringExtra(AppNavigator.EXTRA_QUERY));
            filters = DiscoverFilters.decode(in.getStringExtra(AppNavigator.EXTRA_FILTERS));
        }
        searchInput.setText(query);
        searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        String cityName = in.getStringExtra(AppNavigator.EXTRA_CITY_NAME);
        searchInput.setHint(cityName != null ? getString(R.string.map_search_hint, cityName)
                : getString(R.string.map_search_hint_plain));
        if (cityId != null) {
            new LocationScopeLoader().load(cityId, cityName, in.getStringExtra(AppNavigator.EXTRA_AREA_ID),
                    in.getStringExtra(AppNavigator.EXTRA_AREA_NAME), s -> {
                        if (isDestroyed()) return;
                        scope = s;
                        renderAll();
                        maybePlaceCamera();
                    });
        }
    }

    private void setUpMap() {
        map.setMinZoomPreference(MapConfig.MIN_ZOOM);
        map.setMaxZoomPreference(MapConfig.MAX_ZOOM);
        map.getUiSettings().setTiltGesturesEnabled(false);
        map.getUiSettings().setCompassEnabled(true);
        map.addOnMapClickListener(this::onMapClick);
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMovedCamera = true;
        });
        // Keep the OpenStreetMap attribution + compass visible above the panel / below the top bar.
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeMapOrnaments());
        findViewById(R.id.map_top).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeMapOrnaments());
    }

    private void placeMapOrnaments() {
        if (map == null) return;
        float d = getResources().getDisplayMetrics().density;
        View bottom = findViewById(R.id.map_bottom);
        int bottomSpace = bottom.getHeight() + (int) (6 * d);
        int topSpace = findViewById(R.id.map_top).getBottom() + (int) (8 * d);
        map.getUiSettings().setAttributionMargins((int) (92 * d), 0, 0, bottomSpace);
        map.getUiSettings().setLogoMargins((int) (12 * d), 0, 0, bottomSpace);
        map.getUiSettings().setCompassMargins(0, topSpace, (int) (16 * d), 0);
    }

    // ============================================================================ style + layers

    private void loadStyle(String url) {
        if (map == null) return;
        style = null;
        showMapStatus(true, false);
        map.setStyle(new Style.Builder().fromUri(url), s -> {
            style = s;
            addedImages.clear();
            locationComponentReady = false;
            tintStyle(s);
            addRestaurantLayers(s);
            showMapStatus(false, false);
            enableLocationComponent();
            renderMarkers();
            drawRoute();
            maybePlaceCamera();
        });
    }

    private void onStyleFailed(String message) {
        if (style != null) return; // a tile failed after the map loaded: MapLibre retries by itself
        Log.w(TAG, "Map style failed to load");
        if (!triedFallbackStyle) {
            triedFallbackStyle = true;
            loadStyle(MapConfig.FALLBACK_STYLE_URL);
        } else {
            showMapStatus(false, true);
        }
    }

    private void showMapStatus(boolean loading, boolean error) {
        statusBox.setVisibility(loading || error ? View.VISIBLE : View.GONE);
        findViewById(R.id.map_status_progress).setVisibility(loading ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.map_status_title)).setText(error ? R.string.map_error_title : R.string.map_loading);
        findViewById(R.id.map_status_text).setVisibility(error ? View.VISIBLE : View.GONE);
        findViewById(R.id.map_status_retry).setVisibility(error ? View.VISIBLE : View.GONE);
    }

    /** Warm beige land + soft blue water like the reference (positron is grey by default). */
    private void tintStyle(Style s) {
        int land = getColor(R.color.asanni_map_land);
        int water = getColor(R.color.asanni_map_water);
        for (Layer layer : s.getLayers()) {
            try {
                if (layer instanceof BackgroundLayer) {
                    layer.setProperties(PropertyFactory.backgroundColor(land));
                } else if (layer instanceof FillLayer && layer.getId().toLowerCase(Locale.ROOT).contains("water")) {
                    layer.setProperties(PropertyFactory.fillColor(water));
                }
            } catch (RuntimeException e) {
                // A style change on one layer must never break the map.
            }
        }
    }

    private void addRestaurantLayers(Style s) {
        // Routes (under the markers): alternatives grey, the chosen route red with a white edge.
        s.addSource(new GeoJsonSource(SRC_ROUTES, FeatureCollection.fromFeatures(new ArrayList<Feature>())));
        s.addLayer(new LineLayer(LYR_ROUTE_ALT, SRC_ROUTES)
                .withFilter(Expression.eq(Expression.get("sel"), false))
                .withProperties(PropertyFactory.lineColor(getColor(R.color.asanni_route_alt)),
                        PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.85f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));
        s.addLayer(new LineLayer(LYR_ROUTE_CASING, SRC_ROUTES)
                .withFilter(Expression.eq(Expression.get("sel"), true))
                .withProperties(PropertyFactory.lineColor(getColor(R.color.asanni_marker_ring)),
                        PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));
        s.addLayer(new LineLayer(LYR_ROUTE_MAIN, SRC_ROUTES)
                .withFilter(Expression.eq(Expression.get("sel"), true))
                .withProperties(PropertyFactory.lineColor(getColor(R.color.asanni_route_main)),
                        PropertyFactory.lineWidth(5.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));

        // Restaurants, clustered when close together.
        s.addSource(new GeoJsonSource(SRC_RESTAURANTS, FeatureCollection.fromFeatures(new ArrayList<Feature>()),
                new GeoJsonOptions().withCluster(true).withClusterRadius(46).withClusterMaxZoom(14)));
        for (int n = 2; n <= 21; n++) ensureImage(MarkerIcons.clusterKey(n));
        s.addLayer(new SymbolLayer(LYR_CLUSTERS, SRC_RESTAURANTS)
                .withFilter(Expression.has("point_count"))
                .withProperties(
                        PropertyFactory.iconImage(Expression.concat(Expression.literal("c_"),
                                Expression.toString(Expression.min(Expression.get("point_count"), Expression.literal(21))))),
                        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)));
        s.addLayer(new SymbolLayer(LYR_MARKERS, SRC_RESTAURANTS)
                .withFilter(Expression.not(Expression.has("point_count")))
                .withProperties(PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.symbolSortKey(Expression.get("sort")),
                        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)));

        // The selected restaurant: never clustered, always on top.
        s.addSource(new GeoJsonSource(SRC_SELECTED, FeatureCollection.fromFeatures(new ArrayList<Feature>())));
        s.addLayer(new SymbolLayer(LYR_SELECTED, SRC_SELECTED)
                .withProperties(PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)));
    }

    private void ensureImage(String key) {
        if (style == null || addedImages.contains(key)) return;
        style.addImage(key, MarkerIcons.bitmapFor(this, key)); // [UI ASSET] see MarkerIcons
        addedImages.add(key);
    }

    // ============================================================================ data

    @Override
    public void onRestaurants(List<Restaurant> list, boolean cached) {
        restaurants = list;
        fromCache = cached;
        failure = null;
        renderAll();
        maybePlaceCamera();
    }

    @Override
    public void onRestaurantsFailed(RestaurantRepository.Failure f) {
        failure = f;
        if (restaurants != null) fromCache = true;
        renderAll();
    }

    /** Registered restaurants that pass the user's filters (the map's marker set). */
    private List<Restaurant> markerSet(long now) {
        List<Restaurant> registered = RestaurantQuery.registered(restaurants);
        return RestaurantQuery.apply(registered, effectiveFilters(), "", lat(), lng(), now);
    }

    /** "Under 3 km" carried over from Discover waits for this screen's first GPS fix (not "nothing"). */
    private DiscoverFilters effectiveFilters() {
        return lat() == null && filters.maxDistanceKm != null ? filters.withMaxDistanceKm(null) : filters;
    }

    private void renderAll() {
        if (searchInput == null) return;
        long now = System.currentTimeMillis();
        filterDot.setVisibility(filters.activeCount() > 0 ? View.VISIBLE : View.GONE);
        filterNotice.setVisibility(filters.activeCount() > 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.map_filter_button).setContentDescription(getString(
                filters.activeCount() > 0 ? R.string.discover_filters_on : R.string.map_filters));

        if (restaurants != null) {
            // Search: the whole browsing city (your area ranked first), same function as Discover.
            List<Restaurant> candidates = scope != null ? RestaurantQuery.inCity(restaurants, scope)
                    : RestaurantQuery.registered(restaurants);
            results = RestaurantQuery.search(candidates, scope, effectiveFilters(), query, lat(), lng(), now);
            if (query.trim().isEmpty() && lat() != null) RestaurantQuery.nearestLiveFirst(results, lat(), lng(), now);
            // The selected restaurant must still match the filters; otherwise un-select it.
            if (selectedId != null && findIn(markerSet(now), selectedId) == null) {
                selectedId = null;
                closeRoute();
            }
        } else {
            results = new ArrayList<>();
        }
        renderMarkers();
        renderPanel(now);
    }

    private void renderMarkers() {
        if (style == null || restaurants == null) return;
        long now = System.currentTimeMillis();
        List<Feature> features = new ArrayList<>();
        List<Feature> selected = new ArrayList<>();
        for (Restaurant r : markerSet(now)) {
            if (!r.hasCoordinates()) continue; // never placed at a made-up position
            boolean isSel = r.id.equals(selectedId);
            String key = MarkerIcons.keyFor(r, now, isSel);
            ensureImage(key);
            Feature f = Feature.fromGeometry(Point.fromLngLat(r.longitude, r.latitude));
            f.addStringProperty("id", r.id);
            f.addStringProperty("icon", key);
            f.addNumberProperty("sort", sortKey(key));
            if (isSel) selected.add(f);
            else features.add(f);
        }
        GeoJsonSource src = (GeoJsonSource) style.getSource(SRC_RESTAURANTS);
        if (src != null) src.setGeoJson(FeatureCollection.fromFeatures(features));
        GeoJsonSource sel = (GeoJsonSource) style.getSource(SRC_SELECTED);
        if (sel != null) sel.setGeoJson(FeatureCollection.fromFeatures(selected));
    }

    /** Live markers drawn above "no data" markers when they overlap. */
    private static int sortKey(String key) {
        if (key.contains("free_")) return 4;
        if (key.contains("wait_")) return 3;
        if (key.endsWith("full")) return 2;
        if (key.endsWith("closed")) return 1;
        return 0;
    }

    // ============================================================================ panel

    private Restaurant heroRestaurant() {
        if (selectedId != null && restaurants != null) {
            Restaurant r = findIn(RestaurantQuery.registered(restaurants), selectedId);
            if (r != null) return r;
        }
        return results.isEmpty() ? null : results.get(0);
    }

    private void renderPanel(long now) {
        String where = scope != null ? scope.shortLabel() : null;
        String trimmed = query.trim();
        Restaurant top = heroRestaurant();
        boolean nearestLive = trimmed.isEmpty() && lat() != null && top != null
                && top.hasCurrentAvailability(now) && top.freeTables > 0;
        panelTitle.setText(!trimmed.isEmpty() ? getString(R.string.map_panel_results, trimmed)
                : nearestLive ? getString(R.string.map_panel_nearest)
                : where != null ? getString(R.string.map_panel_area, where) : getString(R.string.map_search_hint_plain));
        panelCount.setText(restaurants == null ? "" : getResources().getQuantityString(R.plurals.map_panel_count,
                results.size(), results.size()));

        // States without results.
        String state = null;
        boolean online = ConnectivityMonitor.get(this).isOnline();
        if (restaurants == null && failure == null) state = getString(R.string.map_loading_restaurants);
        else if (restaurants == null || (restaurants.isEmpty() && fromCache && !online)) {
            state = getString(!online || failure == RestaurantRepository.Failure.NETWORK
                    ? R.string.map_restaurants_offline : R.string.map_restaurants_error);
        } else if (results.isEmpty() && selectedId == null) {
            state = getString(trimmed.isEmpty() && filters.isDefault() ? R.string.map_no_restaurants : R.string.map_no_results);
        }
        panelState.setVisibility(state != null ? View.VISIBLE : View.GONE);
        panelState.setText(state);

        hero.setVisibility(top != null ? View.VISIBLE : View.GONE);
        if (top != null) bindHero(top, now);
        boolean selectedShown = top != null && top.id.equals(selectedId);
        actions.setVisibility(selectedShown && !routeVisible ? View.VISIBLE : View.GONE);
        routeBox.setVisibility(routeVisible ? View.VISIBLE : View.GONE);
        legend.setVisibility(routeVisible ? View.GONE : View.VISIBLE);

        int missing = 0;
        for (Restaurant r : results) if (!r.hasCoordinates()) missing++;
        missingNote.setVisibility(missing > 0 && !routeVisible ? View.VISIBLE : View.GONE);
        if (missing > 0) missingNote.setText(getResources().getQuantityString(R.plurals.map_missing_coordinates, missing, missing));

        listItems.clear();
        for (Restaurant r : results) if (top == null || !r.id.equals(top.id)) listItems.add(r);
        adapter.notifyDataSetChanged();
        if (listItems.isEmpty() && panelExpanded) setPanelExpanded(false);
        findViewById(R.id.map_panel_handle).setVisibility(listItems.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    private void bindHero(Restaurant r, long now) {
        ((TextView) hero.findViewById(R.id.hero_name)).setText(r.name);
        TextView meta = hero.findViewById(R.id.hero_meta);
        String metaText = RestaurantViews.cuisineAndArea(this, r, scope);
        meta.setText(metaText);
        meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);
        RestaurantViews.bindBadge(hero.findViewById(R.id.hero_badge), r, now, false);

        String info;
        int icon;
        if (!r.hasCoordinates()) {
            info = getString(R.string.map_location_unavailable);
            icon = R.drawable.ic_location_pin;
        } else {
            Double km = RestaurantViews.km(r, lat(), lng());
            String dist = km != null ? RestaurantViews.distance(this, km) : null;
            String routeTime = routeTimeFor(r);
            String updated = RestaurantViews.updated(this, r.updatedAtMillis, now);
            info = RestaurantViews.join(this, RestaurantViews.join(this, dist, routeTime), updated);
            icon = R.drawable.ic_navigation_outline;
            if (info.isEmpty()) {
                info = r.address != null ? r.address : nonNull(RestaurantViews.area(r, scope));
                icon = R.drawable.ic_location_pin;
            }
        }
        ((TextView) hero.findViewById(R.id.hero_info)).setText(info);
        ((ImageView) hero.findViewById(R.id.hero_info_icon)).setImageResource(icon);
        int px = (int) (124 * getResources().getDisplayMetrics().density);
        // [UI ASSET] Fallback image: res/drawable/restaurant_placeholder.xml
        RestaurantImageLoader.load(hero.findViewById(R.id.hero_image), r.cover != null ? r.cover : r.logo, px,
                R.drawable.restaurant_placeholder, null);
        hero.setContentDescription(getString(R.string.restaurant_card_description, r.name,
                RestaurantViews.join(this, RestaurantViews.join(this, metaText, RestaurantViews.badgeLong(this, r, now)), info)));
    }

    /** Travel time of the current route to this restaurant (from the routing provider only). */
    private String routeTimeFor(Restaurant r) {
        if (route == null || !r.id.equals(routeRestaurantId) || route.routes.isEmpty()) return null;
        Double s = route.routes.get(Math.min(routeIndex, route.routes.size() - 1)).durationSeconds;
        return s == null ? null : formatDuration(s);
    }

    private void setPanelExpanded(boolean expand) {
        if (expand && listItems.isEmpty()) return;
        panelExpanded = expand;
        final int target = expand ? (int) (getResources().getDisplayMetrics().heightPixels * 0.42f) : 0;
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        int startH = list.getVisibility() == View.VISIBLE ? list.getHeight() : 0;
        list.setVisibility(View.VISIBLE);
        findViewById(R.id.map_panel_handle).setContentDescription(getString(
                expand ? R.string.map_panel_collapse : R.string.map_panel_expand));
        boolean motion = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        if (!motion) {
            lp.height = target;
            list.setLayoutParams(lp);
            if (!expand) list.setVisibility(View.GONE);
            return;
        }
        if (panelAnimator != null) panelAnimator.cancel();
        ValueAnimator a = ValueAnimator.ofInt(startH, target);
        panelAnimator = a;
        a.setDuration(PANEL_MS);
        a.addUpdateListener(an -> {
            lp.height = (int) an.getAnimatedValue();
            list.setLayoutParams(lp);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (!panelExpanded) list.setVisibility(View.GONE);
            }
        });
        a.start();
    }

    /** Vertical swipe on the handle / header. */
    private final class PanelDrag implements View.OnTouchListener {
        private float downY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            float d = getResources().getDisplayMetrics().density;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    v.setPressed(true);
                    return true; // we handle the whole gesture (swipe or tap)
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    float dy = e.getRawY() - downY;
                    if (dy < -28 * d) setPanelExpanded(true);
                    else if (dy > 28 * d) setPanelExpanded(false);
                    else v.performClick(); // a tap toggles (same as screen readers' click)
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        }
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return listItems.size(); }
        @Override public Restaurant getItem(int position) { return listItems.get(position); }
        @Override public long getItemId(int position) { return listItems.get(position).id.hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_restaurant_row, parent, false);
            Restaurant r = listItems.get(position);
            RestaurantViews.bindRow(row, r, scope, lat(), lng(), System.currentTimeMillis());
            if (!r.hasCoordinates()) {
                ((TextView) row.findViewById(R.id.row_meta)).setText(R.string.map_location_unavailable);
                row.findViewById(R.id.row_meta).setVisibility(View.VISIBLE);
            }
            return row;
        }
    }

    private void bindLegend(View badge, int text, int bg, int color) {
        badge.setBackgroundResource(bg);
        TextView t = badge.findViewById(R.id.badge_text);
        t.setText(text);
        t.setTextColor(getColor(color));
        ((ImageView) badge.findViewById(R.id.badge_dot)).setColorFilter(getColor(color));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        badge.setContentDescription(getString(text));
    }

    // ============================================================================ selection

    private boolean onMapClick(LatLng point) {
        if (map == null || style == null) return false;
        hideKeyboard();
        PointF p = map.getProjection().toScreenLocation(point);
        float tol = 14 * getResources().getDisplayMetrics().density;
        RectF box = new RectF(p.x - tol, p.y - tol, p.x + tol, p.y + tol);
        List<Feature> hits = map.queryRenderedFeatures(box, LYR_SELECTED, LYR_MARKERS, LYR_CLUSTERS);
        if (!hits.isEmpty()) {
            Feature f = hits.get(0);
            if (f.hasProperty("point_count")) {
                zoomIntoCluster(f);
            } else if (f.hasProperty("id")) {
                select(f.getStringProperty("id"), true);
            }
            return true;
        }
        if (routeVisible) {
            List<Feature> lines = map.queryRenderedFeatures(box, LYR_ROUTE_ALT);
            if (!lines.isEmpty() && lines.get(0).hasProperty("idx")) {
                routeIndex = lines.get(0).getNumberProperty("idx").intValue();
                drawRoute();
                bindRouteOptions();
                return true;
            }
        }
        if (panelExpanded) setPanelExpanded(false);
        return false;
    }

    private void zoomIntoCluster(Feature cluster) {
        GeoJsonSource src = (GeoJsonSource) style.getSource(SRC_RESTAURANTS);
        if (src == null || !(cluster.geometry() instanceof Point)) return;
        Point pt = (Point) cluster.geometry();
        double zoom;
        try {
            zoom = Math.min(src.getClusterExpansionZoom(cluster) + 0.5, MapConfig.MAX_ZOOM);
        } catch (RuntimeException e) {
            zoom = map.getCameraPosition().zoom + 2;
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(pt.latitude(), pt.longitude()), zoom), CAMERA_MS);
    }

    /** Highlights a restaurant (marker + first card) and moves the camera to its real position. */
    private void select(String id, boolean moveCamera) {
        if (id == null || restaurants == null) return;
        if (routeVisible && !id.equals(routeRestaurantId)) closeRoute();
        selectedId = id;
        if (panelExpanded) setPanelExpanded(false);
        renderAll();
        Restaurant r = findIn(RestaurantQuery.registered(restaurants), id);
        if (r == null || !moveCamera || map == null) return;
        if (!r.hasCoordinates()) return; // the card says "Location unavailable for this restaurant."
        userMovedCamera = true; // a chosen restaurant wins over the automatic first position
        final double zoom = Math.max(map.getCameraPosition().zoom, MapConfig.RESTAURANT_ZOOM);
        final double la = r.latitude;
        final double lo = r.longitude;
        panel.post(() -> moveCameraTo(la, lo, zoom)); // after the panel grew (card + buttons)
    }

    private void moveCameraTo(double latitude, double longitude, double zoom) {
        if (map == null) return;
        int[] pad = cameraPadding();
        CameraPosition pos = new CameraPosition.Builder()
                .target(new LatLng(latitude, longitude)).zoom(zoom)
                .padding(pad[0], pad[1], pad[2], pad[3]).build();
        map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), CAMERA_MS);
    }

    /** Space taken by the top bar and the bottom panel, so targets land in the visible map area. */
    private int[] cameraPadding() {
        float d = getResources().getDisplayMetrics().density;
        int top = findViewById(R.id.map_top).getBottom() + (int) (12 * d);
        int bottom = findViewById(R.id.map_bottom).getHeight() + (int) (12 * d);
        int side = (int) (24 * d);
        return new int[]{side, top, side, bottom};
    }

    /**
     * First camera position: your GPS position, else the restaurants of your area / city / all.
     * Never moves the camera after the user panned, zoomed or picked a restaurant.
     */
    private void maybePlaceCamera() {
        if (map == null || style == null || userMovedCamera) return;
        if (userLocation != null) {
            if (!placedOnUser) {
                placedOnUser = true;
                cameraPlaced = true;
                moveCameraTo(userLocation.getLatitude(), userLocation.getLongitude(), MapConfig.USER_ZOOM);
            }
            return;
        }
        if (cameraPlaced || restaurants == null) return;
        List<LatLng> points = new ArrayList<>();
        if (scope != null) {
            for (Restaurant r : RestaurantQuery.inScope(restaurants, scope)) {
                if (r.hasCoordinates()) points.add(new LatLng(r.latitude, r.longitude));
            }
            if (points.isEmpty()) {
                for (Restaurant r : RestaurantQuery.inCity(restaurants, scope)) {
                    if (r.hasCoordinates()) points.add(new LatLng(r.latitude, r.longitude));
                }
            }
        }
        if (points.isEmpty()) {
            for (Restaurant r : RestaurantQuery.registered(restaurants)) {
                if (r.hasCoordinates()) points.add(new LatLng(r.latitude, r.longitude));
            }
        }
        int[] pad = cameraPadding();
        if (points.size() >= 2) {
            cameraPlaced = true;
            LatLngBounds bounds = new LatLngBounds.Builder().includes(points).build();
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad[0], pad[1], pad[2], pad[3]), CAMERA_MS);
        } else if (points.size() == 1) {
            cameraPlaced = true;
            moveCameraTo(points.get(0).getLatitude(), points.get(0).getLongitude(), MapConfig.RESTAURANT_ZOOM - 1);
        } else {
            // Nothing located yet: a neutral view of the country (never labelled as "you");
            // tried again when restaurants or a GPS fix arrive.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(MapConfig.COUNTRY_LAT, MapConfig.COUNTRY_LNG), MapConfig.COUNTRY_ZOOM));
        }
    }

    // ============================================================================ filters / details / external

    private void openFilters() {
        long now0 = System.currentTimeMillis();
        if (now0 - lastFilterOpen < 800) return; // double tap: one sheet only
        lastFilterOpen = now0;
        hideKeyboard();
        List<Restaurant> base = restaurants == null ? new ArrayList<>()
                : scope != null ? RestaurantQuery.inCity(restaurants, scope) : RestaurantQuery.registered(restaurants);
        FilterSheet.show(this, filters, FilterSheet.Options.from(base, lat(), lng(), System.currentTimeMillis()), f -> {
            filters = f;
            renderAll();
        });
    }

    private void openDetails() {
        if (navigating || selectedId == null) return;
        navigating = true;
        AppNavigator.openRestaurantDetail(this, selectedId, scope, lat(), lng());
    }

    /** Hands the destination to the phone's maps app (e.g. Google Maps, which has live traffic). */
    private void openInMapsApp() {
        Restaurant r = selectedId != null && restaurants != null ? findIn(RestaurantQuery.registered(restaurants), selectedId) : null;
        if (r == null || navigating) return;
        Uri uri;
        if (r.hasCoordinates()) {
            uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", r.latitude, r.longitude,
                    r.latitude, r.longitude, Uri.encode(r.name)));
        } else if (r.address != null) {
            uri = Uri.parse("geo:0,0?q=" + Uri.encode(r.address));
        } else {
            setRouteStatus(getString(R.string.route_need_coordinates), false);
            return;
        }
        try {
            navigating = true;
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            navigating = false;
            android.widget.Toast.makeText(this, R.string.no_maps_app, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================================ routing

    private void buildModeChips() {
        LinearLayout modes = findViewById(R.id.route_modes);
        modes.removeAllViews();
        for (final TravelMode m : TravelMode.values()) {
            TextView chip = (TextView) LayoutInflater.from(this).inflate(R.layout.item_route_mode, modes, false);
            chip.setText(m == TravelMode.DRIVING ? R.string.route_mode_driving
                    : m == TravelMode.WALKING ? R.string.route_mode_walking : R.string.route_mode_cycling);
            chip.setTag(m);
            chip.setOnClickListener(v -> {
                if (routeMode == m) return;
                routeMode = m;
                requestRoute();
            });
            modes.addView(chip);
        }
        updateModeChips();
    }

    private void updateModeChips() {
        LinearLayout modes = findViewById(R.id.route_modes);
        for (int i = 0; i < modes.getChildCount(); i++) {
            View chip = modes.getChildAt(i);
            chip.setSelected(chip.getTag() == routeMode);
        }
    }

    private void requestRoute() {
        Restaurant r = selectedId != null && restaurants != null ? findIn(RestaurantQuery.registered(restaurants), selectedId) : null;
        if (r == null) return;
        routeService.cancel(); // an older request (other mode) must not overwrite what we show now
        routeVisible = true;
        routeRestaurantId = r.id;
        route = null;
        routeIndex = 0;
        updateModeChips();
        drawRoute();
        renderAll();
        if (!r.hasCoordinates()) {
            setRouteStatus(getString(R.string.route_need_coordinates), false);
            return;
        }
        if (userLocation == null) {
            setRouteStatus(getString(R.string.route_need_location), false);
            onMyLocationClicked(); // asks for permission / GPS with an explanation
            return;
        }
        if (MapConfig.routingKey(this) == null) {
            setRouteStatus(getString(R.string.route_not_configured), false);
            return;
        }
        if (!ConnectivityMonitor.get(this).mayHaveInternet()) {
            setRouteStatus(getString(R.string.route_failed_offline), true);
            return;
        }
        setRouteStatus(getString(R.string.route_loading), false);
        final String forId = r.id;
        routeService.route(userLocation.getLatitude(), userLocation.getLongitude(), r.latitude, r.longitude, routeMode,
                new RouteService.Callback() {
                    @Override
                    public void onRoutes(RouteResult result) {
                        if (isDestroyed() || !routeVisible || !forId.equals(routeRestaurantId)) return;
                        route = result;
                        routeIndex = 0; // index 0 = fastest (sorted by the provider's duration)
                        setRouteStatus(null, false);
                        drawRoute();
                        bindRouteOptions();
                        renderAll();
                        fitRoute();
                    }

                    @Override
                    public void onRouteFailed(RouteService.Failure f) {
                        if (isDestroyed() || !routeVisible || !forId.equals(routeRestaurantId)) return;
                        int msg;
                        switch (f) {
                            case NOT_CONFIGURED: msg = R.string.route_not_configured; break;
                            case OFFLINE: msg = R.string.route_failed_offline; break;
                            case NO_ROUTE: msg = R.string.route_failed_unroutable; break;
                            case LIMIT_REACHED: msg = R.string.route_failed_limit; break;
                            default: msg = R.string.route_failed; break;
                        }
                        setRouteStatus(getString(msg), f != RouteService.Failure.NOT_CONFIGURED);
                    }
                });
    }

    private void setRouteStatus(String text, boolean retry) {
        TextView status = findViewById(R.id.route_status);
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
        status.setText(text);
        findViewById(R.id.route_retry).setVisibility(retry ? View.VISIBLE : View.GONE);
        if (text != null) {
            ((LinearLayout) findViewById(R.id.route_options)).removeAllViews();
            findViewById(R.id.route_note).setVisibility(View.GONE);
        }
    }

    private void bindRouteOptions() {
        LinearLayout options = findViewById(R.id.route_options);
        options.removeAllViews();
        if (route == null) return;
        for (int i = 0; i < route.routes.size(); i++) {
            final int index = i;
            RouteResult.Route rt = route.routes.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_route_option, options, false);
            ((TextView) row.findViewById(R.id.route_label)).setText(i == 0
                    ? getString(R.string.route_recommended) : getString(R.string.route_alternative, i));
            String time = rt.durationSeconds != null ? formatDuration(rt.durationSeconds) : null;
            String dist = rt.distanceMeters != null ? RestaurantViews.distance(this, rt.distanceMeters / 1000.0) : null;
            ((TextView) row.findViewById(R.id.route_summary)).setText(time != null && dist != null
                    ? getString(R.string.route_summary, time, dist) : RestaurantViews.join(this, time, dist));
            row.findViewById(R.id.route_swatch).setBackgroundColor(getColor(i == routeIndex
                    ? R.color.asanni_route_main : R.color.asanni_route_alt));
            row.setSelected(i == routeIndex);
            row.setOnClickListener(v -> {
                routeIndex = index;
                drawRoute();
                bindRouteOptions();
                renderAll();
            });
            options.addView(row);
        }
        // openrouteservice never includes live traffic: always say the time is an estimate.
        findViewById(R.id.route_note).setVisibility(route.trafficAware ? View.GONE : View.VISIBLE);
    }

    private void drawRoute() {
        if (style == null) return;
        GeoJsonSource src = (GeoJsonSource) style.getSource(SRC_ROUTES);
        if (src == null) return;
        List<Feature> lines = new ArrayList<>();
        if (routeVisible && route != null) {
            for (int i = route.routes.size() - 1; i >= 0; i--) { // chosen route drawn last (on top)
                List<Point> pts = new ArrayList<>();
                for (double[] c : route.routes.get(i).coordinates) pts.add(Point.fromLngLat(c[0], c[1]));
                Feature f = Feature.fromGeometry(LineString.fromLngLats(pts));
                f.addBooleanProperty("sel", i == routeIndex);
                f.addNumberProperty("idx", i);
                lines.add(f);
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(lines));
    }

    private void fitRoute() {
        if (map == null || route == null || route.routes.isEmpty()) return;
        List<LatLng> pts = new ArrayList<>();
        for (double[] c : route.routes.get(routeIndex).coordinates) pts.add(new LatLng(c[1], c[0]));
        if (userLocation != null) pts.add(new LatLng(userLocation.getLatitude(), userLocation.getLongitude()));
        if (pts.size() < 2) return;
        int[] pad = cameraPadding();
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds.Builder().includes(pts).build(),
                pad[0], pad[1], pad[2], pad[3]), CAMERA_MS);
    }

    private void closeRoute() {
        if (routeService != null) routeService.cancel();
        boolean wasVisible = routeVisible;
        routeVisible = false;
        route = null;
        routeRestaurantId = null;
        drawRoute();
        if (wasVisible && searchInput != null) renderPanel(System.currentTimeMillis());
    }

    private String formatDuration(double seconds) {
        int minutes = (int) Math.max(1, Math.round(seconds / 60.0));
        if (minutes < 60) return getString(R.string.route_minutes, minutes);
        return getString(R.string.route_hours_minutes, minutes / 60, minutes % 60);
    }

    // ============================================================================ your location

    private Double lat() {
        return userLocation != null ? userLocation.getLatitude() : null;
    }

    private Double lng() {
        return userLocation != null ? userLocation.getLongitude() : null;
    }

    private boolean hasPermission() {
        return DeviceLocationHelper.hasLocationPermission(this);
    }

    /** One fresh GPS fix (foreground only; nothing is stored or sent anywhere except routing). */
    private void refreshLocation(final boolean moveCamera) {
        if (!hasPermission() || !DeviceLocationHelper.isLocationEnabled(this)) return;
        deviceLocation.getCurrentLocation(new DeviceLocationHelper.LocationCallback() {
            @Override
            public void onLocation(Location location) {
                if (isDestroyed()) return;
                boolean first = userLocation == null;
                userLocation = location;
                pushLocationToMap();
                if (moveCamera) moveCameraTo(location.getLatitude(), location.getLongitude(), MapConfig.USER_ZOOM);
                else if (first) maybePlaceCamera();
                if (first) renderAll(); // distances + "Nearest live tables" become possible
                if (first && routeVisible && route == null) requestRoute(); // was waiting for a fix
                updateLocationBanner(false);
            }

            @Override
            public void onLocationUnavailable() {
                // Keep the last fix (if any); never invent one.
            }

            @Override
            public void onLocationFailed(Exception error) {
                Log.w(TAG, "Location failed: " + error.getClass().getSimpleName());
            }
        });
    }

    /** Blue dot + accuracy circle (MapLibre LocationComponent fed with OUR real fixes only). */
    @SuppressWarnings("MissingPermission")
    private void enableLocationComponent() {
        if (map == null || style == null || locationComponentReady || !hasPermission()) return;
        try {
            LocationComponent lc = map.getLocationComponent();
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(this, style)
                    .useDefaultLocationEngine(false) // no continuous background engine; we push fixes
                    .build());
            lc.setLocationComponentEnabled(true);
            lc.setCameraMode(CameraMode.NONE);
            lc.setRenderMode(RenderMode.NORMAL);
            locationComponentReady = true;
            pushLocationToMap();
        } catch (RuntimeException e) {
            Log.w(TAG, "Location dot unavailable: " + e.getClass().getSimpleName());
        }
    }

    private void pushLocationToMap() {
        if (userLocation == null) return;
        enableLocationComponent();
        if (!locationComponentReady) return;
        try {
            map.getLocationComponent().forceLocationUpdate(userLocation);
        } catch (RuntimeException e) {
            Log.w(TAG, "Location dot update failed: " + e.getClass().getSimpleName());
        }
    }

    private void onMyLocationClicked() {
        if (!hasPermission()) {
            boolean permanentlyDenied = prefs().getBoolean(KEY_LOCATION_DENIED, false)
                    && Build.VERSION.SDK_INT >= 23
                    && !shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    && !shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            if (permanentlyDenied) showDeniedBanner();
            else showAskBanner();
            return;
        }
        if (!DeviceLocationHelper.isLocationEnabled(this)) {
            showGpsOffBanner();
            return;
        }
        if (userLocation != null) moveCameraTo(userLocation.getLatitude(), userLocation.getLongitude(), MapConfig.USER_ZOOM);
        else android.widget.Toast.makeText(this, R.string.map_location_waiting, android.widget.Toast.LENGTH_SHORT).show();
        refreshLocation(true);
    }

    /** First visit: explain, once, why location helps. Otherwise only when the user asks. */
    private void updateLocationBanner(boolean force) {
        if (hasPermission()) {
            if (!DeviceLocationHelper.isLocationEnabled(this) && !prefs().getBoolean("map_gps_hint_shown", false)) {
                prefs().edit().putBoolean("map_gps_hint_shown", true).apply();
                showGpsOffBanner();
            } else if (DeviceLocationHelper.isLocationEnabled(this)) {
                hideBanner();
            }
            return;
        }
        if (!prefs().getBoolean(KEY_LOCATION_ASKED, false) || force) showAskBanner();
    }

    private void showAskBanner() {
        showBanner(R.string.map_location_title, R.string.map_location_text, R.string.map_location_allow,
                v -> {
                    hideBanner();
                    prefs().edit().putBoolean(KEY_LOCATION_ASKED, true).apply();
                    requestPermissions(DeviceLocationHelper.LOCATION_PERMISSIONS, REQUEST_LOCATION);
                },
                R.string.map_location_not_now, v -> {
                    prefs().edit().putBoolean(KEY_LOCATION_ASKED, true).apply();
                    hideBanner();
                });
    }

    private void showDeniedBanner() {
        showBanner(R.string.map_location_title, R.string.map_location_denied, R.string.map_location_open_settings,
                v -> {
                    hideBanner();
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null)));
                    } catch (ActivityNotFoundException ignored) {
                        // no settings screen: nothing to do
                    }
                },
                R.string.map_location_not_now, v -> hideBanner());
    }

    private void showGpsOffBanner() {
        showBanner(R.string.map_gps_off_title, R.string.map_gps_off_text, R.string.map_gps_turn_on,
                v -> {
                    hideBanner();
                    try {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    } catch (ActivityNotFoundException ignored) {
                        // no location settings screen on this device
                    }
                },
                R.string.map_location_not_now, v -> hideBanner());
    }

    private void showBanner(int title, int text, int primary, View.OnClickListener onPrimary,
                            int secondary, View.OnClickListener onSecondary) {
        View b = findViewById(R.id.map_location_banner);
        ((TextView) findViewById(R.id.map_location_title)).setText(title);
        ((TextView) findViewById(R.id.map_location_text)).setText(text);
        TextView p = findViewById(R.id.map_location_primary);
        TextView s = findViewById(R.id.map_location_secondary);
        p.setText(primary);
        s.setText(secondary);
        p.setOnClickListener(onPrimary);
        s.setOnClickListener(onSecondary);
        if (b.getVisibility() != View.VISIBLE) {
            b.setVisibility(View.VISIBLE);
            b.setAlpha(0f);
            b.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void hideBanner() {
        findViewById(R.id.map_location_banner).setVisibility(View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) return;
        boolean granted = false;
        for (int g : grantResults) if (g == PackageManager.PERMISSION_GRANTED) granted = true;
        prefs().edit().putBoolean(KEY_LOCATION_DENIED, !granted && grantResults.length > 0).apply();
        if (granted) {
            enableLocationComponent();
            if (!DeviceLocationHelper.isLocationEnabled(this)) showGpsOffBanner();
            else refreshLocation(true);
            if (routeVisible) requestRoute();
        }
        // Denied: browsing continues; the my-location button explains how to turn it on later.
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ============================================================================ small helpers

    private static Restaurant findIn(List<Restaurant> list, String id) {
        if (list == null || id == null) return null;
        for (Restaurant r : list) if (r.id.equals(id)) return r;
        return null;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchInput != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        if (searchInput != null) searchInput.clearFocus();
    }

    /** Edge-to-edge: the map fills the screen; controls stay clear of the status/navigation bars. */
    @SuppressWarnings("deprecation")
    private void applyInsets() {
        final View root = findViewById(R.id.map_root);
        final View top = findViewById(R.id.map_top);
        final View bottom = findViewById(R.id.map_bottom);
        final int topPad = top.getPaddingTop();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int t;
            int b;
            int l;
            int r;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                t = bars.top;
                b = Math.max(bars.bottom, ime);
                l = bars.left;
                r = bars.right;
            } else {
                t = insets.getSystemWindowInsetTop();
                b = insets.getSystemWindowInsetBottom();
                l = insets.getSystemWindowInsetLeft();
                r = insets.getSystemWindowInsetRight();
            }
            v.setPadding(0, 0, 0, 0); // the map itself goes under the bars
            top.setPadding(top.getPaddingLeft(), topPad + t, top.getPaddingRight(), top.getPaddingBottom());
            bottom.setPadding(l, 0, r, b);
            return insets;
        });
        root.requestApplyInsets();
    }
}