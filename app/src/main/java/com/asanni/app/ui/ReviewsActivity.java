package com.asanni.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.network.ConnectivityMonitor;
import com.asanni.app.reviews.Review;
import com.asanni.app.reviews.ReviewRepository;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Reviews page (NEW in phase 9): every published review of ONE restaurant, newest first, loaded
 * 20 at a time as you scroll (never all at once), with the rating statistics on top and the
 * restaurant owner's replies under their reviews.
 *
 * Location: app/src/main/java/com/asanni/app/ui/ReviewsActivity.java
 */
public class ReviewsActivity extends AsanniActivity {

    private String restaurantId;
    private ListView list;
    private View header;
    private View footer;
    private final List<Review> reviews = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private DocumentSnapshot cursor;
    private boolean loading;
    private boolean ended;
    private boolean pageFailed;
    private String restaurantName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_reviews);
        SystemBarsHelper.apply(this, findViewById(R.id.reviews_root));
        restaurantId = getIntent().getStringExtra(AppNavigator.EXTRA_RESTAURANT_ID);
        restaurantName = getIntent().getStringExtra(AppNavigator.EXTRA_RESTAURANT_NAME);
        if (restaurantId == null) {
            finish();
            return;
        }
        ((TextView) findViewById(R.id.reviews_restaurant)).setText(restaurantName != null ? restaurantName : "");
        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        list = findViewById(R.id.reviews_list);
        header = LayoutInflater.from(this).inflate(R.layout.item_reviews_header, list, false);
        footer = LayoutInflater.from(this).inflate(R.layout.item_reviews_footer, list, false);
        list.addHeaderView(header, null, false);
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) { }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
                // Near the end: next page. Never auto-retry after a failure (the footer offers it).
                if (!pageFailed && total > 0 && first + visible >= total - 3) loadNext();
            }
        });
        footer.findViewById(R.id.footer_text).setOnClickListener(v -> {
            if (pageFailed) loadNext();
        });
        ReviewViews.bindStats(this, header.findViewById(R.id.reviews_stats), null, false);
        loadStats();
        loadNext();
    }

    private void loadStats() {
        ReviewRepository.loadStats(restaurantId, false, s -> {
            if (!isDestroyed()) ReviewViews.bindStats(this, header.findViewById(R.id.reviews_stats), s, s == null);
        });
    }

    private void loadNext() {
        if (loading || ended) return;
        loading = true;
        pageFailed = false;
        renderFooter();
        final boolean first = cursor == null;
        ReviewRepository.loadPage(restaurantId, cursor, new ReviewRepository.ListCallback() {
            @Override
            public void onReviews(List<Review> page, DocumentSnapshot last) {
                if (isDestroyed()) return;
                loading = false;
                reviews.addAll(page);
                cursor = last;
                ended = last == null;
                adapter.notifyDataSetChanged();
                findViewById(R.id.reviews_progress).setVisibility(View.GONE);
                renderFooter();
                if (first && reviews.isEmpty()) showEmpty();
            }

            @Override
            public void onFailed(ReviewRepository.Failure f) {
                if (isDestroyed()) return;
                loading = false;
                findViewById(R.id.reviews_progress).setVisibility(View.GONE);
                if (first && reviews.isEmpty()) showError();
                else {
                    pageFailed = true;
                    renderFooter();
                }
            }
        });
    }

    private void renderFooter() {
        View progress = footer.findViewById(R.id.footer_progress);
        TextView text = footer.findViewById(R.id.footer_text);
        progress.setVisibility(loading && !reviews.isEmpty() ? View.VISIBLE : View.GONE);
        if (pageFailed) {
            text.setVisibility(View.VISIBLE);
            text.setText(R.string.reviews_load_more_failed);
        } else if (ended && !reviews.isEmpty()) {
            text.setVisibility(View.VISIBLE);
            text.setText(R.string.reviews_end);
        } else {
            text.setVisibility(View.GONE);
        }
    }

    /** No reviews: invite the first GENUINE review (the form is on the Description page). */
    private void showEmpty() {
        View state = findViewById(R.id.reviews_state);
        state.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(R.drawable.ic_star_outline);
        ((TextView) state.findViewById(R.id.state_title)).setText(R.string.reviews_empty_title);
        TextView t = state.findViewById(R.id.state_text);
        t.setVisibility(View.VISIBLE);
        t.setText(getString(R.string.reviews_empty_text, restaurantName != null ? restaurantName : ""));
        TextView a = state.findViewById(R.id.state_action);
        a.setVisibility(View.VISIBLE);
        a.setText(R.string.reviews_write);
        a.setOnClickListener(v -> finish());
    }

    private void showError() {
        View state = findViewById(R.id.reviews_state);
        state.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        boolean offline = !ConnectivityMonitor.get(this).isOnline();
        ((ImageView) state.findViewById(R.id.state_icon)).setImageResource(offline ? R.drawable.ic_wifi_off : R.drawable.ic_star_outline);
        ((TextView) state.findViewById(R.id.state_title)).setText(offline ? R.string.error_restaurants_offline_title : R.string.stats_failed);
        TextView t = state.findViewById(R.id.state_text);
        t.setVisibility(View.VISIBLE);
        t.setText(R.string.saved_error_text);
        TextView a = state.findViewById(R.id.state_action);
        a.setVisibility(View.VISIBLE);
        a.setText(R.string.error_retry);
        a.setOnClickListener(v -> {
            state.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            findViewById(R.id.reviews_progress).setVisibility(View.VISIBLE);
            loadStats();
            loadNext();
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return reviews.size(); }
        @Override public Review getItem(int position) { return reviews.get(position); }
        @Override public long getItemId(int position) { return reviews.get(position).authorUid.hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View card = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_review, parent, false);
            ReviewViews.bindReview(ReviewsActivity.this, card, reviews.get(position), true);
            if (convertView == null && MotionPrefs.enabled(ReviewsActivity.this)) {
                card.setAlpha(0f);
                card.setTranslationY(16f);
                card.animate().alpha(1f).translationY(0f).setDuration(220).start();
            }
            return card;
        }
    }
}