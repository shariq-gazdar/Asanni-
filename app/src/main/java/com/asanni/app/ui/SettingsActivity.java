package com.asanni.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.asanni.app.R;
import com.asanni.app.appearance.Appearance;
import com.asanni.app.appearance.AppearanceManager;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.profile.ProfileImages;
import com.asanni.app.profile.InitialsDrawable;
import com.asanni.app.profile.NotificationPrompt;
import com.asanni.app.profile.SupportedLanguage;
import com.google.firebase.auth.FirebaseUser;

/**
 * Settings page (matches the Settings reference image).
 *
 * Location: app/src/main/java/com/asanni/app/ui/SettingsActivity.java  (NEW in phase 4)
 *
 *   profile card  -> Info page
 *   appearance    -> applied immediately on this phone + saved to the profile
 *   Home area     -> the SAME manual city/area form from phase 3 (edit mode)
 *   alerts        -> Android's notification settings for ASANNI (shows the real On/Off state)
 *   language      -> English (only option for now)
 * Data is reloaded every time the page is shown, so edits on Info appear at once.
 */
public class SettingsActivity extends AsanniActivity {

    private final UserProfileRepository profiles = new UserProfileRepository();
    private ProfileData profile;
    private AppearanceSelector appearanceSelector;
    private EntranceAnimator animator;
    private boolean entrancePlayed;
    private boolean navigating;

    private ImageView avatar;
    private TextView name;
    private TextView subtitle;
    private TextView addressValue;
    private TextView alertsValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_settings);
        SystemBarsHelper.apply(this, findViewById(R.id.settings_root));
        animator = new EntranceAnimator(getContentResolver());
        entrancePlayed = savedInstanceState != null;

        avatar = findViewById(R.id.settings_avatar);
        name = findViewById(R.id.settings_name);
        subtitle = findViewById(R.id.settings_subtitle);
        addressValue = findViewById(R.id.value_address);
        alertsValue = findViewById(R.id.value_alerts);
        ((TextView) findViewById(R.id.value_language)).setText(SupportedLanguage.DEFAULT.nativeName);
        ((TextView) findViewById(R.id.text_version)).setText(versionLine());

        appearanceSelector = new AppearanceSelector(this, this::onAppearanceChosen);
        appearanceSelector.setSelected(AppearanceManager.current(this));

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.row_profile).setOnClickListener(v -> {
            if (navigating) return;
            navigating = true;
            AppNavigator.openInfo(this); // [NAVIGATION] profile row -> Info
        });
        findViewById(R.id.row_address).setOnClickListener(v -> editAddress());
        findViewById(R.id.row_alerts).setOnClickListener(v -> SystemSettingsLinks.openNotificationSettings(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        navigating = false;
        if (name == null) return; // redirected in onCreate
        alertsValue.setText(NotificationPrompt.notificationsAllowed(this)
                ? R.string.settings_alerts_on : R.string.settings_alerts_off);
        loadProfile();
    }

    private void loadProfile() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        profiles.loadProfile(user.getUid(), new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(ProfileData p) {
                if (isFinishing() || isDestroyed()) return;
                profile = p;
                render(user);
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure f, Exception e) {
                if (isFinishing() || isDestroyed()) return;
                if (profile == null) {
                    name.setText(user.getDisplayName());
                    subtitle.setText(R.string.settings_profile_load_error);
                }
            }
        });
    }

    private void render(FirebaseUser user) {
        // Phase 5: say so when these details come from the phone's cache (offline), not the server.
        findViewById(R.id.text_offline_note).setVisibility(profile.fromCache ? android.view.View.VISIBLE
                : android.view.View.GONE);
        String username = profile.username();
        name.setText(username);
        subtitle.setText(profile.cityName != null
                ? getString(R.string.settings_profile_subtitle, profile.cityName)
                : getString(R.string.settings_profile_subtitle_no_city));
        ProfileImages.show(avatar, profile, user, username); // phase 5: on-device -> Google -> initials
        findViewById(R.id.row_profile).setContentDescription(
                getString(R.string.settings_profile_description, username));

        String address = profile.addressLine();
        addressValue.setText(address != null ? address : getString(R.string.settings_address_none));
        findViewById(R.id.row_address).setContentDescription(getString(R.string.settings_address_description,
                address != null ? address : getString(R.string.settings_address_none)));

        // A choice made here while offline: keep it and try saving it again.
        if (AppearanceManager.isPendingSync(this)) {
            saveAppearanceToProfile(AppearanceManager.current(this), false);
            appearanceSelector.setSelected(AppearanceManager.current(this));
            return;
        }
        // Keep the pills in step with the profile (e.g. changed on another phone).
        if (profile.hasAppearance() && profile.appearance() != AppearanceManager.current(this)) {
            AppearanceManager.setLocal(this, profile.appearance());
            applyAppearanceNow();
            return;
        }
        appearanceSelector.setSelected(AppearanceManager.current(this));
    }

    /** Applied at once on this phone; saved to the profile so other phones follow. */
    private void onAppearanceChosen(Appearance appearance) {
        AppearanceManager.setLocal(this, appearance);
        AppearanceManager.setPendingSync(this, true); // cleared once Firestore confirms
        saveAppearanceToProfile(appearance, true);
        applyAppearanceNow(); // rebuilds this screen in the new theme
    }

    private void saveAppearanceToProfile(Appearance appearance, boolean tellUserOnFailure) {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) return;
        final Context app = getApplicationContext();
        profiles.saveAppearance(user.getUid(), appearance, new UserProfileRepository.SaveCallback() {
            @Override public void onSaved() {
                AppearanceManager.setPendingSync(app, false);
            }
            @Override public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                // Honest: applied on this phone, not yet on the account; retried next time.
                if (tellUserOnFailure) {
                    Toast.makeText(app, R.string.settings_appearance_not_saved, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** [NAVIGATION] Home area -> manual city/area form (country stays Pakistan). */
    private void editAddress() {
        if (navigating) return;
        navigating = true;
        AppNavigator.openEditAddress(this,
                profile != null ? profile.cityId : null, profile != null ? profile.areaId : null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AppNavigator.REQUEST_EDIT_ADDRESS && resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.settings_address_saved, Toast.LENGTH_SHORT).show();
            // onResume reloads the profile and shows the new address.
        }
    }

    private String versionLine() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return getString(R.string.settings_version, v != null ? v : "");
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.brand_name);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator != null && !entrancePlayed) {
            animator.play(findViewById(R.id.top_bar), findViewById(R.id.row_profile),
                    findViewById(R.id.appearance_selector));
            entrancePlayed = true;
        }
    }

    @Override
    protected void onDestroy() {
        if (animator != null) animator.cancel();
        super.onDestroy();
    }
}