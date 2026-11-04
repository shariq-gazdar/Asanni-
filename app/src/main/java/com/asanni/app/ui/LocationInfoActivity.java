package com.asanni.app.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfile;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.location.DeviceLocationHelper;
import com.asanni.app.location.LiveLocationResolver;
import com.asanni.app.location.LocationSelection;
import com.asanni.app.network.ConnectivityMonitor;
import com.google.firebase.auth.FirebaseUser;

/**
 * "Your location" (NEW in phase 9) - READ-ONLY. Opened from the Discover location chip and the
 * Settings location row. Shows the city + area and HOW it was detected (fresh GPS detection with
 * its time, an older detection, or a legacy hand-picked location). The only action is
 * "Update from my current location": GPS -> reverse geocoding -> catalogue (LiveLocationResolver),
 * then the result is saved with source "gps" and the server time. No city/area selection exists.
 *
 * Location: app/src/main/java/com/asanni/app/ui/LocationInfoActivity.java
 */
public class LocationInfoActivity extends AsanniActivity {

    private static final int REQUEST_LOCATION = 9201;

    private final UserProfileRepository profiles = new UserProfileRepository();
    private DeviceLocationHelper device;
    private LiveLocationResolver resolver;
    private ProfileData profile;
    private boolean busy;
    private boolean changed;
    private boolean rationaleBefore;
    private Runnable pendingAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_location_info);
        SystemBarsHelper.apply(this, findViewById(R.id.locinfo_root));
        device = new DeviceLocationHelper(this);
        resolver = new LiveLocationResolver(this, device);
        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.locinfo_update).setOnClickListener(v -> update());
        findViewById(R.id.locinfo_message).setOnClickListener(v -> {
            if (pendingAction != null) pendingAction.run();
        });
        new EntranceAnimator(getContentResolver()).attachPressFeedback(findViewById(R.id.locinfo_update));
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (device != null) loadProfile();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (resolver != null && busy) {
            resolver.cancel();
            setBusy(false, 0);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (device != null) device.release();
    }

    @Override
    public void finish() {
        if (changed) setResult(RESULT_OK); // Discover / Settings show the new location
        super.finish();
    }

    private void loadProfile() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) return;
        profiles.loadProfile(user.getUid(), new UserProfileRepository.ProfileCallback() {
            @Override public void onProfileReady(ProfileData p) {
                if (isDestroyed()) return;
                profile = p;
                render();
            }

            @Override public void onProfileFailed(UserProfileRepository.Failure f, Exception e) {
                // Keep showing what we have.
            }
        });
    }

    private void render() {
        TextView city = findViewById(R.id.locinfo_city);
        TextView area = findViewById(R.id.locinfo_area);
        TextView source = findViewById(R.id.locinfo_source);
        if (profile == null || profile.cityId == null) {
            city.setText(profile == null ? "" : getString(R.string.locinfo_missing));
            area.setText("");
            source.setText("");
            return;
        }
        city.setText(profile.cityName);
        area.setText(profile.areaName);
        String how;
        if (UserProfile.SOURCE_GPS.equals(profile.locationSource)) {
            how = profile.locationUpdatedAt != null
                    ? getString(R.string.locinfo_detected_at, DateUtils.formatDateTime(this, profile.locationUpdatedAt,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME))
                    : getString(R.string.locinfo_detected_earlier); // saved before phase 9: no time known
        } else {
            how = getString(R.string.locinfo_manual_legacy);
        }
        if (profile.areaId != null && profile.areaId.equals(profile.cityId + "-all")) {
            how = how + "\n" + getString(R.string.locinfo_city_wide);
        }
        source.setText(how);
        findViewById(R.id.locinfo_card).setContentDescription(profile.cityName + ", " + profile.areaName + ". " + how);
    }

    // ------------------------------------------------------------------ update

    private void update() {
        if (busy) return;
        if (!DeviceLocationHelper.hasLocationPermission(this)) {
            rationaleBefore = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (!rationaleBefore && deniedBefore()) { // "Don't ask again": Android shows no dialog
                showBlocked();
                return;
            }
            requestPermissions(DeviceLocationHelper.LOCATION_PERMISSIONS, REQUEST_LOCATION);
            return;
        }
        if (!ConnectivityMonitor.get(this).mayHaveInternet()) {
            message(getString(R.string.locinfo_offline), null);
            return;
        }
        setBusy(true, R.string.locinfo_updating);
        message(null, null);
        resolver.resolve(new LiveLocationResolver.Callback() {
            @Override public void onResolved(LiveLocationResolver.Result result) {
                if (isDestroyed()) return;
                save(result);
            }

            @Override public void onFailed(LiveLocationResolver.Failure f) {
                if (isDestroyed()) return;
                setBusy(false, 0);
                showFailure(f);
            }
        });
    }

    private void save(LiveLocationResolver.Result result) {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        final LocationSelection selection = result.selection(UserProfile.SOURCE_GPS);
        final boolean same = profile != null && selection.cityId.equals(profile.cityId)
                && selection.areaId.equals(profile.areaId);
        setBusy(true, R.string.locinfo_saving);
        profiles.saveLocation(user.getUid(), selection, new UserProfileRepository.SaveCallback() {
            @Override public void onSaved() {
                if (isDestroyed()) return;
                setBusy(false, 0);
                changed = changed || !same;
                message(getString(same ? R.string.locinfo_same : R.string.locinfo_updated, selection.displayName()), null);
                loadProfile();
            }

            @Override public void onSaveFailed(UserProfileRepository.Failure failure, Exception error) {
                if (isDestroyed()) return;
                setBusy(false, 0);
                message(getString(LocationMessages.save(failure)), null);
            }
        });
    }

    private void showFailure(LiveLocationResolver.Failure f) {
        switch (f) {
            case NO_PERMISSION:
                message(getString(R.string.locinfo_need_permission), this::update);
                break;
            case SERVICES_OFF:
                message(getString(R.string.locinfo_services_off) + " " + getString(R.string.locinfo_turn_on),
                        () -> safeStart(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
                break;
            case NO_FIX:
                message(getString(R.string.locinfo_no_fix), null);
                break;
            case GEOCODE_UNAVAILABLE:
                message(getString(R.string.locinfo_geocode), null);
                break;
            case OUTSIDE_COUNTRY:
                message(getString(R.string.locinfo_outside), null);
                break;
            case CITY_NOT_SUPPORTED:
                message(getString(R.string.locinfo_not_supported), null);
                break;
            case CATALOGUE_OFFLINE:
                message(getString(R.string.locinfo_offline), null);
                break;
            default:
                message(getString(R.string.locinfo_failed), null);
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_LOCATION) return;
        for (int r : results) {
            if (r == PackageManager.PERMISSION_GRANTED) {
                update();
                return;
            }
        }
        if (results.length == 0) return; // dismissed
        boolean rationaleNow = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean before = deniedBefore();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DENIED, true).apply();
        if (!rationaleNow && (rationaleBefore || before)) {
            showBlocked(); // "Don't ask again": only Android settings can change it
        } else {
            message(getString(R.string.locinfo_need_permission) + " " + getString(R.string.locinfo_allow), this::update);
        }
    }

    private static final String PREFS = "asanni_location_ui"; // same file AllowLocationActivity uses
    private static final String KEY_DENIED = "location_denied_before";

    private boolean deniedBefore() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DENIED, false);
    }

    private void showBlocked() {
        message(getString(R.string.locinfo_blocked) + " " + getString(R.string.locinfo_open_settings), () ->
                safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            message(getString(R.string.location_settings_unavailable), null);
        }
    }

    /** @param action tapping the message runs it (null = informational only) */
    private void message(String text, Runnable action) {
        TextView m = findViewById(R.id.locinfo_message);
        pendingAction = action;
        m.setVisibility(text == null ? View.GONE : View.VISIBLE);
        m.setText(text);
        m.setClickable(action != null);
        m.setFocusable(action != null);
        m.setTextColor(getColor(action != null ? R.color.desc_red : R.color.desc_ink));
        if (text != null) m.announceForAccessibility(text);
    }

    private void setBusy(boolean b, int label) {
        busy = b;
        findViewById(R.id.locinfo_update_progress).setVisibility(b ? View.VISIBLE : View.GONE);
        findViewById(R.id.locinfo_update_icon).setVisibility(b ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.locinfo_update_label)).setText(b ? label : R.string.locinfo_update);
        findViewById(R.id.locinfo_update).setEnabled(!b);
        findViewById(R.id.locinfo_update).setAlpha(b ? 0.85f : 1f);
    }
}