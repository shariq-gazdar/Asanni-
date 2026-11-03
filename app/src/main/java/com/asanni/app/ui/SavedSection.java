package com.asanni.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.asanni.app.R;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.restaurants.AreaNames;
import com.asanni.app.restaurants.DevTestData;
import com.asanni.app.restaurants.Restaurant;
import com.asanni.app.restaurants.RestaurantImageLoader;
import com.asanni.app.restaurants.RestaurantRepository;
import com.asanni.app.saved.SavedRestaurants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Saved tab of Home (phase 8; it was an empty placeholder in phase 6).
 *
 * Location: app/src/main/java/com/asanni/app/ui/SavedSection.java  (NEW in phase 8)
 *
 * IDs come from users/{uid}/savedRestaurants (live, only while this tab is visible). Restaurant
 * information always comes from the authoritative restaurant documents: already-loaded data first,
 * then the missing ones in batches of 30 (RestaurantRepository.fetchMany) - never copies, never one
 * read per card. A saved restaurant that no longer exists shows "No longer available" + remove.
 */
final class SavedSection implements SavedRestaurants.Listener {

    private final HomeActivity activity;
    private final SavedRestaurants saved = SavedRestaurants.get();
    private final RestaurantRepository repository = RestaurantRepository.get();
    private final ListView list;
    private final View progress;
    private final View state;
    private final TextView count;
    private final Adapter adapter = new Adapter();
    private final Map<String, String> areaNames = new HashMap<>();

    private List<String> ids;                       // null = not loaded yet
    private final Map<String, Restaurant> byId = new HashMap<>();
    /** IDs a completed fetch has answered for (so "missing" really means not found). */
    private final java.util.Set<String> attempted = new java.util.HashSet<>();
    private boolean fetchIncomplete;
    private boolean fetchInFlight;
    private SavedRestaurants.Failure failure;
    private boolean active;
    private boolean navigating;
    private int fetchGeneration;

    SavedSection(HomeActivity activity, View root) {
        this.activity = activity;
        list = root.findViewById(R.id.saved_list);
        progress = root.findViewById(R.id.saved_progress);
        state = root.findViewById(R.id.saved_state);
        count = root.findViewById(R.id.saved_count);
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
        list.setItemsCanFocus(true); // the card and its remove button are separate focus targets
    }

    /** Listens only while the Saved tab is visible and Home is on screen. */
    void setActive(boolean nowActive) {
        if (nowActive == active) return;
        active = nowActive;
        if (active) {
            navigating = false;
            saved.observe(this);
            if (ids != null && fetchIncomplete && !fetchInFlight) loadRestaurants(); // retry failed reads
            render();
        } else {
            saved.stopObserving(this);
        }
    }

    void release() {
        saved.stopObserving(this);
    }

    // ------------------------------------------------------------------ data

    @Override
    public void onSavedChanged(List<String> restaurantIds, boolean fromCache) {
        failure = null;
        ids = new ArrayList<>();
        for (String id : restaurantIds) {
            // [DEV TEST DATA] A saved dummy restaurant is hidden outside debug builds.
            if (DevTestData.isTestId(id) && !DevTestData.active()) continue;
            ids.add(id);
        }
        loadRestaurants();
        render();
    }

    @Override
    public void onSavedFailed(SavedRestaurants.Failure f) {
        failure = f;
        render();
    }

    private void loadRestaurants() {
        final int gen = ++fetchGeneration;
        List<String> need = new ArrayList<>();
        for (String id : ids) if (!byId.containsKey(id) && !attempted.contains(id)) need.add(id);
        if (need.isEmpty()) return;
        fetchIncomplete = true;
        fetchInFlight = true;
        repository.fetchMany(need, (found, complete) -> {
            if (gen != fetchGeneration || activity.isDestroyed()) return;
            fetchInFlight = false;
            byId.putAll(found);
            fetchIncomplete = !complete;
            if (complete) attempted.addAll(need);
            for (Restaurant r : found.values()) resolveArea(r);
            render();
        });
    }

    private void resolveArea(final Restaurant r) {
        if (areaNames.containsKey(r.id)) return;
        areaNames.put(r.id, r.areaLabel());
        new AreaNames().resolve(r.cityRaw, r.areaRaw, (area, city) -> {
            areaNames.put(r.id, area != null ? area : (r.areaLabel() != null ? r.areaLabel() : city));
            adapter.notifyDataSetChanged();
        });
    }

    // ------------------------------------------------------------------ UI

    private void render() {
        state.setVisibility(View.GONE);
        if (ids == null && failure == null) {
            progress.setVisibility(View.VISIBLE);
            count.setText("");
            adapter.notifyDataSetChanged();
            return;
        }
        progress.setVisibility(View.GONE);
        if (ids == null) { // failed before anything loaded
            count.setText("");
            showState(R.drawable.ic_wifi_off, R.string.saved_error_title, R.string.saved_error_text,
                    R.string.error_retry, () -> {
                        if (!ConnectivityMonitor.get(activity).mayHaveInternet()) {
                            OfflineOverlay.show(activity, this::retry, true);
                        } else {
                            retry();
                        }
                    });
            adapter.notifyDataSetChanged();
            return;
        }
        count.setText(ids.isEmpty() ? "" : activity.getResources().getQuantityString(R.plurals.saved_count, ids.size(), ids.size()));
        count.setOnClickListener(null);
        count.setClickable(false);
        if (failure != null) {
            // Live updates stopped after the list loaded: keep the list, say so, offer a retry.
            count.setText(R.string.saved_refresh_failed);
            count.setOnClickListener(v -> retry());
        }
        if (ids.isEmpty()) {
            showState(R.drawable.ic_nav_saved, R.string.saved_empty_title, R.string.saved_empty_text, 0, null);
        }
        adapter.notifyDataSetChanged();
    }

    private void retry() {
        ids = null;
        attempted.clear();
        failure = null;
        render();
        saved.retry();
    }

    private void showState(int icon, int title, int text, int action, final Runnable onAction) {
        state.setVisibility(View.VISIBLE);
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(icon);
        ((TextView) state.findViewById(R.id.state_title)).setText(title);
        TextView t = state.findViewById(R.id.state_text);
        t.setVisibility(View.VISIBLE);
        t.setText(text);
        TextView a = state.findViewById(R.id.state_action);
        a.setVisibility(action != 0 ? View.VISIBLE : View.GONE);
        if (action != 0) {
            a.setText(action);
            a.setOnClickListener(v -> onAction.run());
        }
    }

    private void open(String id) {
        if (id == null || navigating) return;
        Restaurant r = byId.get(id);
        if (r == null && !fetchIncomplete) return; // "No longer available": nothing to open
        navigating = true;
        AppNavigator.openRestaurantDetail(activity, id, null, null, null);
    }

    private void remove(final String id, String name) {
        saved.setSaved(id, false, new SavedRestaurants.WriteCallback() {
            @Override public void onConfirmed() { }

            @Override public void onPending() {
                Toast.makeText(activity, R.string.save_pending, Toast.LENGTH_LONG).show();
            }

            @Override public void onFailed(SavedRestaurants.Failure f) {
                Toast.makeText(activity, R.string.save_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return ids == null ? 0 : ids.size(); }
        @Override public String getItem(int position) { return ids.get(position); }
        @Override public long getItemId(int position) { return ids.get(position).hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_saved_row, parent, false);
            final String id = ids.get(position);
            final Restaurant r = byId.get(id);
            TextView name = row.findViewById(R.id.saved_name);
            TextView meta = row.findViewById(R.id.saved_meta);
            View badge = row.findViewById(R.id.saved_badge);
            ImageView image = row.findViewById(R.id.saved_image);
            long now = System.currentTimeMillis();
            if (r == null) {
                boolean unknown = fetchIncomplete || !attempted.contains(id);
                boolean failed = unknown && !fetchInFlight && fetchIncomplete;
                name.setText(unknown ? activity.getString(failed ? R.string.saved_unloaded_name : R.string.saved_loading_name)
                        : activity.getString(R.string.saved_missing_name));
                meta.setText(failed ? activity.getString(R.string.saved_unloaded_text)
                        : unknown ? "" : activity.getString(R.string.saved_missing_text));
                badge.setVisibility(View.GONE);
                RestaurantImageLoader.load(image, null, 0, android.R.color.transparent, null);
            } else {
                name.setText(r.name);
                String text = RestaurantViews.join(activity, r.cuisineText(), areaNames.get(r.id));
                if (r.isTestData) text = RestaurantViews.join(activity, "TEST DATA", text);
                meta.setText(text);
                badge.setVisibility(View.VISIBLE);
                RestaurantViews.bindBadge(badge, r, now, false);
                int px = (int) (108 * activity.getResources().getDisplayMetrics().density);
                // [UI ASSET] Fallback = the ImageView background (restaurant_cover_placeholder.xml)
                RestaurantImageLoader.load(image, r.cover != null ? r.cover : r.logo, px, android.R.color.transparent, null);
            }
            String label = r != null ? r.name : activity.getString(R.string.saved_missing_name);
            View removeButton = row.findViewById(R.id.saved_remove);
            removeButton.setContentDescription(activity.getString(R.string.saved_remove, label));
            removeButton.setOnClickListener(v -> remove(id, label));
            View card = row.findViewById(R.id.saved_card);
            card.setContentDescription(label + ". " + meta.getText());
            card.setOnClickListener(v -> open(id));
            return row;
        }
    }

}