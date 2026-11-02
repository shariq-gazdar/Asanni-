package com.asanni.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.UserProfile;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.LocationCatalogueRepository;
import com.asanni.app.location.LocationSelection;
import com.google.firebase.auth.FirebaseUser;

import java.util.Collections;
import java.util.List;

/**
 * Manual Location form: Country (fixed: Pakistan), City and Area chosen from the Firestore catalogue.
 *
 * Location: app/src/main/java/com/asanni/app/ui/ManualLocationActivity.java
 *           (MODIFIED in phase 4: edit mode for Settings, then Registration as the next step)
 *
 * - Never asks for location permission.
 * - City and Area must be picked from the lists; typed text is only a search filter.
 * - Changing the city clears the area and loads that city's areas.
 * - Saves only when both are valid and belong together; success only after Firestore confirms.
 */
public class ManualLocationActivity extends AsanniActivity {

    private static final String STATE_CITY_ID = "city_id";
    private static final String STATE_CITY_NAME = "city_name";
    private static final String STATE_AREA_ID = "area_id";
    private static final String STATE_AREA_NAME = "area_name";
    private static final String STATE_AREA_CITY_ID = "area_city_id";
    /** [CONFIG] After this long, tell the user that saving is slow (it keeps trying). */
    private static final long SLOW_SAVE_NOTICE_MS = 12_000L;

    private enum AreaLoad { NONE, LOADING, READY, EMPTY, ERROR }

    private LocationCatalogueRepository catalogue;
    private UserProfileRepository profiles;
    private EntranceAnimator animator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CatalogueItem selectedCity;
    private CatalogueItem selectedArea;
    private List<CatalogueItem> cityAreas = Collections.emptyList();
    private AreaLoad areaLoad = AreaLoad.NONE;
    private boolean cityLoading;
    private boolean saving;
    private boolean entrancePlayed;
    private SearchablePicker openPicker;
    /** true when opened from Settings to change the saved address. */
    private boolean editMode;
    /** Area to pre-select once the city's areas load (edit mode); used once. */
    private String prefillAreaId;

    private View fieldCity;
    private TextView cityValue;
    private View cityProgress;
    private View cityTrailing;
    private TextView cityMessage;
    private View fieldArea;
    private TextView areaValue;
    private View areaProgress;
    private View areaTrailing;
    private TextView areaMessage;
    private TextView statusText;
    private View saveButton;
    private View saveProgress;
    private TextView saveLabel;

    private final Runnable slowSaveNotice = () -> {
        if (saving) showStatus(getString(R.string.location_saving_slow));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Location is only ever stored for signed-in users.
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }

        setContentView(R.layout.activity_manual_location);
        entrancePlayed = savedInstanceState != null;
        SystemBarsHelper.apply(this, findViewById(R.id.manual_location_root));

        catalogue = new LocationCatalogueRepository();
        profiles = new UserProfileRepository();
        animator = new EntranceAnimator(getContentResolver());
        bindViews();
        editMode = getIntent().getBooleanExtra(AppNavigator.EXTRA_EDIT_MODE, false);
        if (savedInstanceState == null) {
            prefillAreaId = getIntent().getStringExtra(AppNavigator.EXTRA_PREFILL_AREA_ID);
        }
        if (editMode) {
            // [EDIT TEXT] Edit-mode titles: manual_location_edit_top_title / manual_location_edit_title
            ((TextView) findViewById(R.id.text_top_title)).setText(R.string.manual_location_edit_top_title);
            ((TextView) findViewById(R.id.text_title)).setText(R.string.manual_location_edit_title);
        }

        findViewById(R.id.button_back).setOnClickListener(v -> onBackPressed());
        fieldCity.setOnClickListener(v -> openCityPicker());
        fieldArea.setOnClickListener(v -> onAreaFieldClicked());
        saveButton.setOnClickListener(v -> onSaveClicked());
        animator.attachPressFeedback(saveButton);

        if (savedInstanceState != null) {
            restore(savedInstanceState);
            if (selectedCity != null) loadAreasForSelectedCity(); // re-checks the restored area
        } else {
            String prefill = getIntent().getStringExtra(AppNavigator.EXTRA_PREFILL_CITY_ID);
            if (prefill != null) prefillCity(prefill); // selectCity() loads the areas itself
        }
        render();
    }

    private void bindViews() {
        fieldCity = findViewById(R.id.field_city);
        cityValue = findViewById(R.id.field_city_value);
        cityProgress = findViewById(R.id.field_city_progress);
        cityTrailing = findViewById(R.id.field_city_trailing);
        cityMessage = findViewById(R.id.text_city_message);
        fieldArea = findViewById(R.id.field_area);
        areaValue = findViewById(R.id.field_area_value);
        areaProgress = findViewById(R.id.field_area_progress);
        areaTrailing = findViewById(R.id.field_area_trailing);
        areaMessage = findViewById(R.id.text_area_message);
        statusText = findViewById(R.id.text_status);
        saveButton = findViewById(R.id.button_save);
        saveProgress = findViewById(R.id.save_progress);
        saveLabel = findViewById(R.id.save_label);
    }

    // ------------------------------------------------------------------ city

    /** City detected on the Allow Location page: pre-select it (the user can still change it). */
    private void prefillCity(String cityId) {
        cityLoading = true;
        catalogue.loadCities(new LocationCatalogueRepository.Callback() {
            @Override
            public void onLoaded(List<CatalogueItem> cities) {
                if (!alive()) return;
                cityLoading = false;
                if (selectedCity == null) {
                    for (CatalogueItem c : cities) {
                        if (c.id.equals(cityId)) {
                            selectCity(c);
                            return;
                        }
                    }
                }
                render();
            }

            @Override
            public void onFailed(LocationCatalogueRepository.Failure failure, Exception error) {
                if (!alive()) return;
                cityLoading = false; // the user can still open the list and retry there
                render();
            }
        });
        render();
    }

    private void openCityPicker() {
        if (saving) return;
        dismissPicker();
        openPicker = new SearchablePicker(this,
                getString(R.string.city_picker_title),
                getString(R.string.city_search_hint),
                getString(R.string.catalogue_error_not_seeded),
                selectedCity != null ? selectedCity.id : null,
                catalogue::loadCities,
                this::selectCity);
        openPicker.show();
    }

    private void selectCity(CatalogueItem city) {
        boolean changed = selectedCity == null || !selectedCity.id.equals(city.id);
        selectedCity = city;
        cityMessage.setVisibility(View.GONE);
        fieldCity.setActivated(false);
        if (changed) {
            // New city: the old area no longer applies.
            selectedArea = null;
            cityAreas = Collections.emptyList();
            loadAreasForSelectedCity();
        }
        render();
    }

    // ------------------------------------------------------------------ area

    private void loadAreasForSelectedCity() {
        final String cityId = selectedCity.id;
        areaLoad = AreaLoad.LOADING;
        catalogue.loadAreas(cityId, new LocationCatalogueRepository.Callback() {
            @Override
            public void onLoaded(List<CatalogueItem> areas) {
                if (!alive() || selectedCity == null || !selectedCity.id.equals(cityId)) return;
                cityAreas = areas;
                areaLoad = areas.isEmpty() ? AreaLoad.EMPTY : AreaLoad.READY;
                // Edit mode: pre-select the currently saved area (only if it's still in the list).
                if (selectedArea == null && prefillAreaId != null) {
                    for (CatalogueItem a : areas) {
                        if (a.id.equals(prefillAreaId)) selectedArea = a;
                    }
                }
                prefillAreaId = null;
                // A restored area must still exist in this city's list.
                if (selectedArea != null && !contains(areas, selectedArea.id)) selectedArea = null;
                render();
            }

            @Override
            public void onFailed(LocationCatalogueRepository.Failure failure, Exception error) {
                if (!alive() || selectedCity == null || !selectedCity.id.equals(cityId)) return;
                areaLoad = AreaLoad.ERROR;
                if (failure == LocationCatalogueRepository.Failure.NETWORK) {
                    // Reading the area list is safe to repeat: retry by itself when back online.
                    OfflineOverlay.show(ManualLocationActivity.this, () -> {
                        if (selectedCity != null) { loadAreasForSelectedCity(); render(); }
                    }, true);
                }
                render();
            }
        });
    }

    private void onAreaFieldClicked() {
        if (saving) return;
        if (selectedCity == null) {
            showFieldError(fieldCity, cityMessage, getString(R.string.error_choose_city_first));
            return;
        }
        if (areaLoad == AreaLoad.ERROR) { // tap to retry
            loadAreasForSelectedCity();
            render();
            return;
        }
        dismissPicker();
        final CatalogueItem city = selectedCity;
        openPicker = new SearchablePicker(this,
                getString(R.string.area_picker_title, city.name),
                getString(R.string.area_search_hint),
                getString(R.string.area_empty, city.name),
                selectedArea != null ? selectedArea.id : null,
                callback -> catalogue.loadAreas(city.id, callback),
                area -> {
                    if (selectedCity == null || !selectedCity.id.equals(area.cityId)) return;
                    selectedArea = area;
                    fieldArea.setActivated(false);
                    render();
                });
        openPicker.show();
    }

    // ------------------------------------------------------------------ save

    private void onSaveClicked() {
        if (saving) return; // no duplicate submissions
        statusText.setVisibility(View.GONE);

        boolean valid = true;
        if (selectedCity == null) {
            showFieldError(fieldCity, cityMessage, getString(R.string.error_city_required));
            valid = false;
        }
        if (selectedArea == null) {
            showFieldError(fieldArea, areaMessage, getString(R.string.error_area_required));
            valid = false;
        } else if (selectedCity != null && !selectedCity.id.equals(selectedArea.cityId)) {
            selectedArea = null;
            showFieldError(fieldArea, areaMessage, getString(R.string.error_area_mismatch));
            valid = false;
        }
        if (!valid) return;

        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        LocationSelection selection =
                LocationSelection.of(selectedCity, selectedArea, UserProfile.SOURCE_MANUAL);
        if (OfflineOverlay.showIfOffline(this, this::onSaveClicked, false)) return; // phase 5
        setSaving(true);
        handler.postDelayed(slowSaveNotice, SLOW_SAVE_NOTICE_MS);

        profiles.saveLocation(user.getUid(), selection, new UserProfileRepository.SaveCallback() {
            @Override
            public void onSaved() {
                handler.removeCallbacks(slowSaveNotice);
                if (!alive()) return;
                if (editMode) {
                    // Opened from Settings: go back; Settings reloads the new address.
                    setResult(RESULT_OK);
                    finish();
                } else {
                    AppNavigator.openRegistration(ManualLocationActivity.this); // next onboarding step
                }
            }

            @Override
            public void onSaveFailed(UserProfileRepository.Failure failure, Exception error) {
                handler.removeCallbacks(slowSaveNotice);
                if (!alive()) return;
                setSaving(false); // selections are kept, so the user can just try again
                showStatus(getString(LocationMessages.save(failure)));
                if (failure == UserProfileRepository.Failure.NETWORK) {
                    OfflineOverlay.show(ManualLocationActivity.this, () -> onSaveClicked(), false);
                }
            }
        });
    }

    private void setSaving(boolean value) {
        saving = value;
        saveButton.setEnabled(!value);
        saveButton.setAlpha(value ? 0.75f : 1f);
        saveProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        saveLabel.setText(value ? R.string.location_saving : R.string.save_location_button);
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        // City field
        cityValue.setText(selectedCity != null ? selectedCity.name : null);
        cityProgress.setVisibility(cityLoading ? View.VISIBLE : View.GONE);
        cityTrailing.setVisibility(cityLoading ? View.GONE : View.VISIBLE);
        fieldCity.setContentDescription(getString(R.string.city_field_description,
                selectedCity != null ? selectedCity.name : getString(R.string.not_selected)));

        // Area field
        boolean hasCity = selectedCity != null;
        fieldArea.setAlpha(hasCity ? 1f : 0.6f);
        areaValue.setHint(hasCity ? R.string.area_placeholder : R.string.area_placeholder_no_city);
        areaValue.setText(selectedArea != null ? selectedArea.name : null);
        boolean areaBusy = areaLoad == AreaLoad.LOADING;
        areaProgress.setVisibility(areaBusy ? View.VISIBLE : View.GONE);
        areaTrailing.setVisibility(areaBusy ? View.GONE : View.VISIBLE);
        fieldArea.setContentDescription(getString(R.string.area_field_description,
                selectedArea != null ? selectedArea.name : getString(R.string.not_selected)));

        if (!fieldArea.isActivated()) { // don't hide a validation error
            String hint = null;
            if (hasCity) {
                switch (areaLoad) {
                    case LOADING: hint = getString(R.string.area_loading); break;
                    case EMPTY:   hint = getString(R.string.area_empty, selectedCity.name); break;
                    case ERROR:   hint = getString(R.string.area_load_failed); break;
                    default:      break;
                }
            }
            areaMessage.setTextColor(getColor(areaLoad == AreaLoad.ERROR
                    ? R.color.asanni_welcome_accent : R.color.asanni_welcome_muted));
            areaMessage.setText(hint);
            areaMessage.setVisibility(hint == null ? View.GONE : View.VISIBLE);
        }
    }

    private void showFieldError(View field, TextView messageView, String text) {
        field.setActivated(true); // red border (bg_form_field)
        messageView.setTextColor(getColor(R.color.asanni_welcome_accent));
        messageView.setText(text);
        messageView.setVisibility(View.VISIBLE); // live region: TalkBack reads it automatically
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE); // live region
    }

    private static boolean contains(List<CatalogueItem> items, String id) {
        for (CatalogueItem i : items) {
            if (i.id.equals(id)) return true;
        }
        return false;
    }

    private void dismissPicker() {
        if (openPicker != null) {
            openPicker.dismiss();
            openPicker = null;
        }
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (selectedCity != null) {
            out.putString(STATE_CITY_ID, selectedCity.id);
            out.putString(STATE_CITY_NAME, selectedCity.name);
        }
        if (selectedArea != null) {
            out.putString(STATE_AREA_ID, selectedArea.id);
            out.putString(STATE_AREA_NAME, selectedArea.name);
            out.putString(STATE_AREA_CITY_ID, selectedArea.cityId);
        }
    }

    private void restore(Bundle in) {
        String cityId = in.getString(STATE_CITY_ID);
        String cityName = in.getString(STATE_CITY_NAME);
        if (cityId != null && cityName != null) {
            selectedCity = new CatalogueItem(cityId, cityName, null, null, null, false);
            String areaId = in.getString(STATE_AREA_ID);
            String areaName = in.getString(STATE_AREA_NAME);
            String areaCity = in.getString(STATE_AREA_CITY_ID);
            if (areaId != null && areaName != null && cityId.equals(areaCity)) {
                // Re-checked against the freshly loaded list in loadAreasForSelectedCity().
                selectedArea = new CatalogueItem(areaId, areaName, null, null, areaCity, false);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator == null) return; // redirected in onCreate
        if (!entrancePlayed) {
            animator.play(findViewById(R.id.top_bar), findViewById(R.id.text_title),
                    findViewById(R.id.text_description), findViewById(R.id.form),
                    findViewById(R.id.bottom_actions));
            entrancePlayed = true;
        }
    }

    /** Stay on screen while saving, so the result (success or error) is always shown. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (saving) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(slowSaveNotice);
        dismissPicker();
        if (animator != null) animator.cancel();
        super.onDestroy();
    }
}