package com.asanni.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.UserProfile;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.location.CatalogueItem;
import com.asanni.app.location.CatalogueMatcher;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.location.LocationCatalogueRepository;
import com.asanni.app.location.LocationSelection;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

/**
 * Allow Location screen: a custom page on top of the real Android permission system.
 *
 * Location: app/src/main/java/com/asanni/app/ui/AllowLocationActivity.java  (NEW)
 *
 * "Allow while using app":
 *   1. Android's permission dialog (only now, never at app launch). Approximate is enough;
 *      on Android 12+ the user may also choose Precise.
 *   2. One current location -> address (Geocoder) -> matched to the Firestore catalogue.
 *   3. The match is shown as a SUGGESTION ("Use Clifton, Karachi"). Nothing is saved until the
 *      user taps it. Coordinates are never saved.
 * PHASE 9: manual city/area entry was REMOVED (location is locked to the phone's live location).
 * The "Enter area manually" button is hidden; when the area isn't recognised, the city-wide
 * "All of <city>" catalogue area is suggested instead (never an invented neighbourhood).
 */
public class AllowLocationActivity extends AsanniActivity {

    private static final int REQUEST_LOCATION_PERMISSION = 4101;
    /** [CONFIG] After this long, tell the user that saving is slow (it keeps trying). */
    private static final long SLOW_SAVE_NOTICE_MS = 12_000L;
    /** Local UI hint only (never used for onboarding decisions): has the user denied before? */
    private static final String PREFS_NAME = "asanni_location_ui";
    private static final String PREF_DENIED_BEFORE = "location_denied_before";
    private boolean rationaleBeforeRequest;

    private enum State {
        IDLE,               // nothing tried yet
        REQUESTING,         // Android permission dialog showing
        LOCATING,           // getting location + address + matching (busy)
        DENIED,             // denied this time; may ask again
        BLOCKED,            // "Don't ask again" / denied twice: only Settings can change it
        SERVICES_OFF,       // phone's Location switch is off
        FAILED,             // location/catalogue error; retry possible
        NO_MATCH,           // located, but no known city/area
        SUGGEST_AREA,       // city + area found: user can confirm
        SUGGEST_CITY,       // only the city found: pick the area manually
        SAVING              // saving the confirmed suggestion (busy)
    }

    private State state = State.IDLE;
    private String message;
    private CatalogueItem suggestedCity;
    private CatalogueItem suggestedArea;
    private boolean entrancePlayed;

    private DeviceLocationHelper deviceLocation;
    private LocationCatalogueRepository catalogue;
    private UserProfileRepository profiles;
    private EntranceAnimator animator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable slowSaveNotice = () -> {
        if (state == State.SAVING) {
            message = getString(R.string.location_saving_slow);
            render();
        }
    };

    private View allowButton;
    private View allowIcon;
    private View allowProgress;
    private TextView allowLabel;
    private TextView manualButton;
    private TextView statusText;
    private TextView mapChip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Location is only ever stored for signed-in users.
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }

        setContentView(R.layout.activity_allow_location);
        entrancePlayed = savedInstanceState != null;
        SystemBarsHelper.apply(this, findViewById(R.id.allow_location_root));

        deviceLocation = new DeviceLocationHelper(this);
        catalogue = new LocationCatalogueRepository();
        profiles = new UserProfileRepository();
        animator = new EntranceAnimator(getContentResolver());

        allowButton = findViewById(R.id.button_allow);
        allowIcon = findViewById(R.id.allow_icon);
        allowProgress = findViewById(R.id.allow_progress);
        allowLabel = findViewById(R.id.allow_label);
        manualButton = findViewById(R.id.button_manual);
        statusText = findViewById(R.id.text_status);
        mapChip = findViewById(R.id.map_chip);

        findViewById(R.id.button_back).setOnClickListener(v -> onBackPressed());
        allowButton.setOnClickListener(v -> onPrimaryClicked());
        manualButton.setOnClickListener(v -> onManualClicked());
        animator.attachPressFeedback(allowButton);
        animator.attachPressFeedback(manualButton);
        manualButton.setVisibility(View.GONE); // PHASE 9: no manual city/area selection

        render();
    }

    // ------------------------------------------------------------------ buttons

    private void onPrimaryClicked() {
        switch (state) {
            case REQUESTING:
            case LOCATING:
            case SAVING:
                return; // busy: ignore repeated taps
            case BLOCKED:
                openAppSettings();
                return;
            case SERVICES_OFF:
                openLocationSettings();
                return;
            case SUGGEST_AREA:
                saveSuggestion();
                return;
            case SUGGEST_CITY: // PHASE 9: not reached any more (city-wide area is suggested instead)
                startAllowFlow();
                return;
            default:
                startAllowFlow();
        }
    }

    /** PHASE 9: manual entry removed; the (hidden) button does nothing. */
    private void onManualClicked() {
        // Location is locked to the phone's live location (LocationInfoActivity explains it).
    }

    // ------------------------------------------------------------------ permission

    private void startAllowFlow() {
        if (DeviceLocationHelper.hasLocationPermission(this)) {
            startLocating();
            return;
        }
        // Remembered so a first-time dismissal isn't mistaken for "Don't ask again".
        rationaleBeforeRequest =
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION);
        setState(State.REQUESTING, null);
        requestPermissions(DeviceLocationHelper.LOCATION_PERMISSIONS, REQUEST_LOCATION_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_LOCATION_PERMISSION) return;

        if (results.length == 0) { // dialog dismissed without an answer
            setState(State.IDLE, null);
            return;
        }
        for (int r : results) {
            if (r == PackageManager.PERMISSION_GRANTED) { // approximate OR precise is enough
                startLocating();
                return;
            }
        }
        // Denied. "Blocked" (only Settings can change it) = Android now hides the rationale AND
        // the user had already been asked before. A first-time dismissal stays "denied".
        boolean rationaleNow =
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean deniedBefore = prefs().getBoolean(PREF_DENIED_BEFORE, false);
        prefs().edit().putBoolean(PREF_DENIED_BEFORE, true).apply();
        if (!rationaleNow && (rationaleBeforeRequest || deniedBefore)) {
            setState(State.BLOCKED, getString(R.string.location_blocked));
        } else {
            setState(State.DENIED, getString(R.string.location_denied));
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ locating

    private void startLocating() {
        if (!DeviceLocationHelper.isLocationEnabled(this)) {
            setState(State.SERVICES_OFF, getString(R.string.location_services_off));
            return;
        }
        suggestedCity = null;
        suggestedArea = null;
        setState(State.LOCATING, null);
        deviceLocation.getCurrentLocation(new DeviceLocationHelper.LocationCallback() {
            @Override
            public void onLocation(Location location) {
                if (alive()) geocode(location);
            }

            @Override
            public void onLocationUnavailable() {
                if (alive()) setState(State.FAILED, getString(R.string.location_unavailable));
            }

            @Override
            public void onLocationFailed(Exception error) {
                if (alive()) setState(State.FAILED, getString(R.string.location_failed));
            }
        });
    }

    private void geocode(Location location) {
        deviceLocation.reverseGeocode(location, new DeviceLocationHelper.GeocodeCallback() {
            @Override
            public void onAddresses(List<Address> addresses) {
                if (!alive()) return;
                if (!CatalogueMatcher.isInSupportedCountry(addresses)) {
                    setState(State.NO_MATCH, getString(R.string.location_outside_country));
                    return;
                }
                matchCity(addresses);
            }

            @Override
            public void onGeocodeUnavailable() {
                if (alive()) setState(State.NO_MATCH, getString(R.string.location_geocode_unavailable));
            }
        });
    }

    private void matchCity(List<Address> addresses) {
        catalogue.loadCities(new LocationCatalogueRepository.Callback() {
            @Override
            public void onLoaded(List<CatalogueItem> cities) {
                if (!alive()) return;
                CatalogueItem city = CatalogueMatcher.matchCity(addresses, cities);
                if (city == null) {
                    setState(State.NO_MATCH, getString(R.string.location_no_city_match));
                    return;
                }
                matchArea(addresses, city);
            }

            @Override
            public void onFailed(LocationCatalogueRepository.Failure failure, Exception error) {
                if (!alive()) return;
                setState(State.FAILED, getString(LocationMessages.catalogue(failure)));
                if (failure == LocationCatalogueRepository.Failure.NETWORK) {
                    OfflineOverlay.show(AllowLocationActivity.this, null, false); // then tap Try again
                }
            }
        });
    }

    private void matchArea(List<Address> addresses, CatalogueItem city) {
        catalogue.loadAreas(city.id, new LocationCatalogueRepository.Callback() {
            @Override
            public void onLoaded(List<CatalogueItem> areas) {
                if (!alive()) return;
                suggestedCity = city;
                suggestedArea = CatalogueMatcher.matchArea(addresses, areas);
                String precision = DeviceLocationHelper.hasPreciseLocation(AllowLocationActivity.this)
                        ? "" : " " + getString(R.string.location_approximate_note);
                if (suggestedArea != null) {
                    setState(State.SUGGEST_AREA, getString(R.string.location_suggest_area,
                            suggestedArea.name, city.name) + precision);
                } else {
                    // PHASE 9: area not recognised -> the whole city ("All of Karachi").
                    for (CatalogueItem a : areas) if (a.isCityWide) suggestedArea = a;
                    if (suggestedArea == null) {
                        setState(State.NO_MATCH, getString(R.string.location_no_city_match));
                        return;
                    }
                    setState(State.SUGGEST_AREA,
                            getString(R.string.location_suggest_city, city.name) + precision);
                }
            }

            @Override
            public void onFailed(LocationCatalogueRepository.Failure failure, Exception error) {
                if (!alive()) return;
                setState(State.FAILED, getString(LocationMessages.catalogue(failure)));
                if (failure == LocationCatalogueRepository.Failure.NETWORK) {
                    OfflineOverlay.show(AllowLocationActivity.this, null, false); // then tap Try again
                }
            }
        });
    }

    // ------------------------------------------------------------------ saving

    /** Saves ONLY after the user tapped "Use <area>, <city>". */
    private void saveSuggestion() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        final LocationSelection selection =
                LocationSelection.of(suggestedCity, suggestedArea, UserProfile.SOURCE_GPS);
        if (OfflineOverlay.showIfOffline(this, this::saveSuggestion, false)) return; // phase 5
        setState(State.SAVING, null);
        handler.postDelayed(slowSaveNotice, SLOW_SAVE_NOTICE_MS);

        profiles.saveLocation(user.getUid(), selection, new UserProfileRepository.SaveCallback() {
            @Override
            public void onSaved() {
                handler.removeCallbacks(slowSaveNotice);
                if (alive()) AppNavigator.openRegistration(AllowLocationActivity.this); // next onboarding step
            }

            @Override
            public void onSaveFailed(UserProfileRepository.Failure failure, Exception error) {
                handler.removeCallbacks(slowSaveNotice);
                if (!alive()) return;
                // Keep the suggestion so the user can simply try again.
                setState(State.SUGGEST_AREA, getString(LocationMessages.save(failure)));
                if (failure == UserProfileRepository.Failure.NETWORK) {
                    OfflineOverlay.show(AllowLocationActivity.this, () -> saveSuggestion(), false);
                }
            }
        });
    }

    // ------------------------------------------------------------------ settings

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        safeStart(i);
    }

    private void openLocationSettings() {
        safeStart(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    private void safeStart(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            setState(state, getString(R.string.location_settings_unavailable));
        }
    }

    // ------------------------------------------------------------------ UI

    private void setState(State newState, String newMessage) {
        state = newState;
        message = newMessage;
        render();
    }

    private void render() {
        boolean busy = state == State.REQUESTING || state == State.LOCATING || state == State.SAVING;
        allowButton.setEnabled(!busy);
        allowButton.setAlpha(busy ? 0.75f : 1f);
        allowProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        allowIcon.setVisibility(busy ? View.GONE : View.VISIBLE);
        manualButton.setEnabled(state != State.SAVING);

        String label;
        switch (state) {
            case REQUESTING:
            case LOCATING:      label = getString(R.string.location_finding); break;
            case SAVING:        label = getString(R.string.location_saving); break;
            case BLOCKED:       label = getString(R.string.location_open_settings); break;
            case SERVICES_OFF:  label = getString(R.string.location_turn_on); break;
            case SUGGEST_AREA:  label = getString(R.string.location_use_suggestion,
                    suggestedArea.name, suggestedCity.name); break;
            case SUGGEST_CITY:  label = getString(R.string.location_choose_area_in, suggestedCity.name); break;
            case DENIED:
            case FAILED:
            case NO_MATCH:      label = getString(R.string.location_try_again); break;
            default:            label = getString(R.string.allow_location_button); break;
        }
        allowLabel.setText(label);
        allowButton.setContentDescription(label);

        manualButton.setText(state == State.SUGGEST_AREA
                ? R.string.location_choose_different_area : R.string.enter_location_manually);

        // The chip shows the sample label until a place has really been detected.
        if (suggestedCity != null && suggestedArea != null) {
            mapChip.setText(getString(R.string.location_chip_format, suggestedArea.name, suggestedCity.name));
        } else if (suggestedCity != null) {
            mapChip.setText(suggestedCity.name);
        } else {
            mapChip.setText(R.string.allow_location_map_chip_sample);
        }

        // text_status is an accessibility live region: TalkBack reads changes automatically.
        statusText.setText(message);
        statusText.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        if (animator == null) return; // redirected in onCreate
        if (!entrancePlayed) {
            animator.play(findViewById(R.id.top_bar), findViewById(R.id.text_title),
                    findViewById(R.id.text_description), findViewById(R.id.info_box),
                    findViewById(R.id.bottom_actions));
            animator.revealMap(findViewById(R.id.map_image));
            entrancePlayed = true;
        }
        animator.pulse(findViewById(R.id.map_pin_halo));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (deviceLocation == null) return; // redirected in onCreate
        // Back from Settings: continue automatically if the user fixed the problem there.
        // (Only reachable after the user already tapped Allow, so this never asks at launch.)
        if ((state == State.BLOCKED || state == State.DENIED)
                && DeviceLocationHelper.hasLocationPermission(this)) {
            startLocating();
        } else if (state == State.SERVICES_OFF && DeviceLocationHelper.isLocationEnabled(this)) {
            startLocating();
        }
    }

    /** Stay on screen while saving, so the result (success or error) is always shown. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (state == State.SAVING) return;
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (animator != null) animator.stopPulse(); // no motion while not visible
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(slowSaveNotice);
        if (deviceLocation != null) deviceLocation.release();
        if (animator != null) animator.cancel();
        super.onDestroy();
    }
}