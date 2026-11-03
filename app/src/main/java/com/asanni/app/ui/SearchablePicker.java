package com.asanni.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.LocationCatalogueRepository;
import com.asanni.app.location.TextNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * A searchable bottom-sheet list (used for City and Area). Native Dialog + ListView, no libraries.
 *
 * Location: app/src/main/java/com/asanni/app/ui/SearchablePicker.java  (NEW)
 *
 * Only items from the loaded catalogue can be picked: typing in the search box FILTERS the list,
 * it never becomes a value. States: loading, list, "no matches", empty catalogue, error + Retry.
 */
final class SearchablePicker {

    /** [CONFIG] Sheet height as a share of the screen. */
    private static final float SHEET_HEIGHT_FRACTION = 0.85f;

    interface Loader {
        void load(LocationCatalogueRepository.Callback callback);
    }

    interface OnPicked {
        void onPicked(CatalogueItem item);
    }

    private final Activity activity;
    private final Dialog dialog;
    private final Loader loader;
    private final OnPicked onPicked;
    private final String emptyCatalogueText;
    private final String selectedId;

    private final ListView list;
    private final View progress;
    private final View messageBox;
    private final TextView message;
    private final View retry;
    private final EditText search;

    private final List<CatalogueItem> all = new ArrayList<>();
    private final List<CatalogueItem> shown = new ArrayList<>();
    private ArrayAdapter<CatalogueItem> adapter;
    private boolean loaded;

    SearchablePicker(Activity activity, String title, String searchHint, String emptyCatalogueText,
                     String selectedId, Loader loader, OnPicked onPicked) {
        this.activity = activity;
        this.loader = loader;
        this.onPicked = onPicked;
        this.emptyCatalogueText = emptyCatalogueText;
        this.selectedId = selectedId;

        dialog = new Dialog(activity, R.style.AsanniPickerDialog);
        dialog.setContentView(R.layout.dialog_searchable_picker);
        dialog.setCanceledOnTouchOutside(true);

        ((TextView) dialog.findViewById(R.id.picker_title)).setText(title);
        search = dialog.findViewById(R.id.picker_search);
        search.setHint(searchHint);
        search.setContentDescription(searchHint);
        list = dialog.findViewById(R.id.picker_list);
        progress = dialog.findViewById(R.id.picker_progress);
        messageBox = dialog.findViewById(R.id.picker_message_box);
        message = dialog.findViewById(R.id.picker_message);
        retry = dialog.findViewById(R.id.picker_retry);

        dialog.findViewById(R.id.picker_close).setOnClickListener(v -> dialog.dismiss());
        retry.setOnClickListener(v -> load());

        adapter = new ArrayAdapter<CatalogueItem>(activity, R.layout.item_picker_option,
                R.id.option_name, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = convertView != null ? convertView
                        : LayoutInflater.from(getContext()).inflate(R.layout.item_picker_option, parent, false);
                CatalogueItem item = getItem(position);
                boolean isSelected = item != null && item.id.equals(SearchablePicker.this.selectedId);
                TextView name = row.findViewById(R.id.option_name);
                name.setText(item != null ? item.name : "");
                row.findViewById(R.id.option_check).setVisibility(isSelected ? View.VISIBLE : View.GONE);
                row.setContentDescription(isSelected && item != null
                        ? activity.getString(R.string.picker_selected_item, item.name) : null);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            CatalogueItem item = shown.get(position);
            dialog.dismiss();
            this.onPicked.onPicked(item);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
    }

    void show() {
        Window w = dialog.getWindow();
        if (w != null) {
            int height = (int) (activity.getResources().getDisplayMetrics().heightPixels
                    * SHEET_HEIGHT_FRACTION);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        load();
    }

    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void load() {
        showLoading();
        loader.load(new LocationCatalogueRepository.Callback() {
            @Override
            public void onLoaded(List<CatalogueItem> items) {
                if (!dialog.isShowing()) return;
                all.clear();
                all.addAll(items);
                loaded = true;
                applyFilter();
            }

            @Override
            public void onFailed(LocationCatalogueRepository.Failure failure, Exception error) {
                if (!dialog.isShowing()) return;
                showMessage(activity.getString(LocationMessages.catalogue(failure)), true);
                if (failure == LocationCatalogueRepository.Failure.NETWORK) {
                    // Phase 5: close the list and show the offline page; reopens itself when online.
                    dialog.dismiss();
                    OfflineOverlay.show(activity, SearchablePicker.this::show, true);
                }
            }
        });
    }

    private void applyFilter() {
        if (!loaded) return;
        String q = TextNormalizer.normalize(search.getText().toString());
        shown.clear();
        for (CatalogueItem item : all) {
            if (item.matchesQuery(q)) shown.add(item);
        }
        adapter.notifyDataSetChanged();

        if (all.isEmpty()) {
            showMessage(emptyCatalogueText, false);
        } else if (shown.isEmpty()) {
            showMessage(activity.getString(R.string.picker_no_matches, search.getText().toString().trim()), false);
        } else {
            progress.setVisibility(View.GONE);
            messageBox.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading() {
        list.setVisibility(View.GONE);
        messageBox.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
    }

    private void showMessage(String text, boolean canRetry) {
        progress.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        messageBox.setVisibility(View.VISIBLE);
        message.setText(text);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    }
}