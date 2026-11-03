package com.asanni.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.restaurants.DiscoverFilters;
import com.asanni.app.restaurants.LocationScope;
import com.asanni.app.restaurants.LocationScopeLoader;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantQuery;
import com.asanni.app.restaurants.RestaurantRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * See All: every restaurant in the browsing area matching the search + filters, in the same
 * order as Discover (same repository, location matching, status meaning and ranking).
 *
 * Location: app/src/main/java/com/asanni/app/ui/RestaurantListActivity.java  (NEW in phase 6)
 *
 * The ListView recycles rows, so long lists scroll smoothly. Data comes from the shared live
 * listener (no extra download when opened from Discover). Search/filter state starts from
 * Discover's and is kept across rotation.
 */
public class RestaurantListActivity extends AsanniActivity implements RestaurantRepository.Observer {

    private static final String STATE_QUERY = "list_query";
    private static final String STATE_FILTERS = "list_filters";
    private static final long SEARCH_DEBOUNCE_MS = 250L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RestaurantRepository repository = RestaurantRepository.get();
    private final List<Restaurant> shown = new ArrayList<>();
    private final Runnable applySearch = this::render;

    private LocationScope scope;
    private List<Restaurant> restaurants;
    private boolean fromCache;
    private RestaurantRepository.Failure failure;
    private DiscoverFilters filters = DiscoverFilters.defaults();
    private String query = "";
    private Double userLat;
    private Double userLng;
    private boolean started;
    private boolean navigating;
    private boolean serverWaitExpired;
    private boolean serverWaitScheduled;
    private final Runnable serverWaitTimeout = () -> {
        serverWaitScheduled = false;
        serverWaitExpired = true;
        render();
    };
    /** Connection back after live updates stopped: re-attach by itself (a read, safe to retry). */
    private final ConnectivityMonitor.Listener connectivity = online -> {
        if (online && started && failure != null && scope != null) {
            failure = null;
            serverWaitExpired = false;
            repository.retry(scope.city.id);
        }
    };

    private ListView list;
    private TextView count;
    private View progress;
    private View state;
    private EditText search;
    private View filterDot;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_restaurant_list);
        SystemBarsHelper.apply(this, findViewById(R.id.list_root));

        String cityId = getIntent().getStringExtra(AppNavigator.EXTRA_CITY_ID);
        if (getIntent().hasExtra(AppNavigator.EXTRA_USER_LAT)) {
            userLat = getIntent().getDoubleExtra(AppNavigator.EXTRA_USER_LAT, 0);
            userLng = getIntent().getDoubleExtra(AppNavigator.EXTRA_USER_LNG, 0);
        }
        query = savedInstanceState != null ? savedInstanceState.getString(STATE_QUERY, "")
                : nonNull(getIntent().getStringExtra(AppNavigator.EXTRA_QUERY));
        filters = DiscoverFilters.decode(savedInstanceState != null ? savedInstanceState.getString(STATE_FILTERS)
                : getIntent().getStringExtra(AppNavigator.EXTRA_FILTERS));

        list = findViewById(R.id.list_view);
        count = findViewById(R.id.list_count);
        progress = findViewById(R.id.list_progress);
        state = findViewById(R.id.list_state);
        search = findViewById(R.id.list_search_input);
        filterDot = findViewById(R.id.list_filter_dot);
        final View clear = findViewById(R.id.list_search_clear);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent); // the card draws its own rounded ripple
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (navigating || position >= shown.size()) return;
            navigating = true;
            hideKeyboard();
            AppNavigator.openRestaurantDetail(this, shown.get(position).id, scope, userLat, userLng);
        });

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.list_filter_button).setOnClickListener(v -> {
            hideKeyboard();
            List<Restaurant> scoped = scoped();
            FilterSheet.show(this, filters, FilterSheet.Options.from(scoped, userLat, userLng,
                    System.currentTimeMillis()), f -> {
                filters = f;
                render();
            });
        });
        search.setText(query);
        clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(v -> search.setText(""));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                main.removeCallbacks(applySearch);
                main.postDelayed(applySearch, query.isEmpty() ? 0 : SEARCH_DEBOUNCE_MS);
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            main.removeCallbacks(applySearch);
            render();
            hideKeyboard();
            return true;
        });

        String cityName = getIntent().getStringExtra(AppNavigator.EXTRA_CITY_NAME);
        ((TextView) findViewById(R.id.list_title)).setText(getString(R.string.list_title,
                nonNull(getIntent().getStringExtra(AppNavigator.EXTRA_AREA_NAME)).isEmpty()
                        ? nonNull(cityName) : getIntent().getStringExtra(AppNavigator.EXTRA_AREA_NAME)));
        render();
        new LocationScopeLoader().load(cityId, cityName,
                getIntent().getStringExtra(AppNavigator.EXTRA_AREA_ID),
                getIntent().getStringExtra(AppNavigator.EXTRA_AREA_NAME), s -> {
                    if (isDestroyed()) return;
                    scope = s;
                    if (scope == null) {
                        finish(); // opened without a location (shouldn't happen): nothing to list
                        return;
                    }
                    ((TextView) findViewById(R.id.list_title)).setText(getString(R.string.list_title, scope.shortLabel()));
                    if (started) repository.observe(this, scope.city.id);
                    render();
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (scope != null) repository.observe(this, scope.city.id);
        ConnectivityMonitor.get(this).addListener(connectivity);
    }

    @Override
    protected void onResume() {
        super.onResume();
        navigating = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        repository.stopObserving(this);
        ConnectivityMonitor.get(this).removeListener(connectivity);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_QUERY, query);
        out.putString(STATE_FILTERS, filters.encode());
    }

    // ------------------------------------------------------------------ data

    @Override
    public void onRestaurants(List<Restaurant> list, boolean cached) {
        restaurants = list;
        fromCache = cached;
        failure = null;
        if (!cached) {
            serverWaitExpired = false;
            serverWaitScheduled = false;
            main.removeCallbacks(serverWaitTimeout);
        }
        render();
    }

    @Override
    public void onRestaurantsFailed(RestaurantRepository.Failure f) {
        failure = f;
        if (restaurants != null) fromCache = true;
        render();
    }

    private List<Restaurant> scoped() {
        return restaurants == null || scope == null ? new ArrayList<>() : RestaurantQuery.inScope(restaurants, scope);
    }

    private void render() {
        filterDot.setVisibility(filters.activeCount() > 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.list_filter_button).setContentDescription(getString(
                filters.activeCount() > 0 ? R.string.discover_filters_on : R.string.discover_filters));
        boolean online = ConnectivityMonitor.get(this).isOnline();
        boolean waitingForServer = restaurants != null && restaurants.isEmpty() && fromCache && online
                && failure == null && !serverWaitExpired;
        if (waitingForServer && !serverWaitScheduled) {
            serverWaitScheduled = true;
            main.postDelayed(serverWaitTimeout, 10_000L);
        }
        state.setVisibility(View.GONE);

        if (scope == null || (restaurants == null && failure == null) || waitingForServer) {
            setRows(new ArrayList<>());
            progress.setVisibility(View.VISIBLE);
            count.setText("");
            return;
        }
        progress.setVisibility(View.GONE);
        boolean nothingOffline = restaurants != null && restaurants.isEmpty() && fromCache
                && (!online || failure != null || serverWaitExpired);
        if ((restaurants == null && failure != null) || nothingOffline) {
            setRows(new ArrayList<>());
            count.setText("");
            boolean offline = !online || failure == RestaurantRepository.Failure.NETWORK || nothingOffline;
            showState(offline ? R.drawable.ic_wifi_off : R.drawable.ic_storefront,
                    getString(offline ? R.string.error_restaurants_offline_title : R.string.error_restaurants_title),
                    offline ? getString(R.string.error_restaurants_offline_text, scope.shortLabel())
                            : getString(R.string.error_restaurants_text),
                    getString(R.string.error_retry), this::retry);
            return;
        }

        List<Restaurant> scoped = scoped();
        // Shared search (same function as Discover and Map).
        List<Restaurant> filtered = RestaurantQuery.search(scoped, scope, filters, query, userLat, userLng,
                System.currentTimeMillis());
        setRows(filtered);
        String countText = getResources().getQuantityString(R.plurals.list_count, filtered.size(), filtered.size());
        count.setText(fromCache ? RestaurantViews.join(this, countText, getString(R.string.offline_saved_note)) : countText);

        if (scoped.isEmpty()) {
            showState(R.drawable.ic_storefront, getString(R.string.empty_area_title),
                    getString(R.string.empty_area_text), getString(R.string.list_back_to_discover), () -> finish());
        } else if (filtered.isEmpty() && !query.trim().isEmpty()) {
            showState(R.drawable.ic_search, getString(R.string.empty_search_title, query.trim()),
                    getString(R.string.empty_search_text), getString(R.string.empty_search_action), () -> {
                        filters = DiscoverFilters.defaults();
                        search.setText("");
                        render();
                    });
        } else if (filtered.isEmpty()) {
            showState(R.drawable.ic_tune, getString(R.string.empty_filter_title),
                    getString(R.string.empty_filter_text), getString(R.string.empty_filter_action), () -> {
                        filters = DiscoverFilters.defaults();
                        render();
                    });
        }
    }

    private void retry() {
        if (!ConnectivityMonitor.get(this).mayHaveInternet()) {
            OfflineOverlay.show(this, this::retry, true);
            return;
        }
        failure = null;
        restaurants = null;
        serverWaitExpired = false;
        render();
        if (scope != null) repository.retry(scope.city.id);
    }

    private void setRows(List<Restaurant> rows) {
        shown.clear();
        shown.addAll(rows);
        adapter.notifyDataSetChanged();
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

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Restaurant getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shown.get(position).id.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_restaurant_row, parent, false);
            RestaurantViews.bindRow(row, shown.get(position), scope, userLat, userLng, System.currentTimeMillis());
            return row; // taps: ListView.setOnItemClickListener (the card's ripple follows the row press)
        }
    }
}