package com.asanni.app.ui;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.Names;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.profile.AvatarPicker;
import com.asanni.app.profile.AvatarProcessor;
import com.asanni.app.profile.ProfileImageStore;
import com.asanni.app.profile.ProfileImages;
import com.asanni.app.profile.InitialsDrawable;
import com.asanni.app.profile.PakistanPhone;
import com.asanni.app.profile.PhoneVerificationSync;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Info page: profile picture, names, read-only email, verified contact number, sign out.
 *
 * Location: app/src/main/java/com/asanni/app/ui/InfoActivity.java  (NEW in phase 4)
 *
 * - Photo: choose -> preview -> "Save photo" uploads to Storage; the profile changes only after
 *   the upload AND the Firestore update succeed. "Cancel" keeps the current photo.
 * - Names: saved as first/middle/last + displayName = first + " " + last (one source of truth).
 * - Email: shown, never editable (it belongs to the Google account).
 * - Phone: a new number must pass Firebase verification; until then the old verified number
 *   stays as it is. Appearance is NOT here: it lives in Settings only (one preference).
 */
public class InfoActivity extends AsanniActivity {

    private static final int REQUEST_PICK_PHOTO = 8101;

    private final UserProfileRepository profiles = new UserProfileRepository();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EntranceAnimator animator;
    private boolean entrancePlayed;

    private ProfileData profile;
    private byte[] pendingPhoto;     // chosen, previewed, not uploaded yet
    private boolean uploadingPhoto;
    private boolean savingNames;
    private boolean namesEdited;     // don't overwrite what the user is typing on reload
    private boolean filling;         // ignore text changes made by the app itself
    private boolean navigating;      // stops double taps opening two verification screens

    private ImageView avatar;
    private TextView username;
    private TextView photoStatus;
    private View photoActions;
    private EditText firstName;
    private EditText middleName;
    private EditText lastName;
    private TextView firstNameError;
    private TextView middleNameError;
    private TextView lastNameError;
    private View saveNamesButton;
    private View namesProgress;
    private TextView namesLabel;
    private TextView phoneValue;
    private View badgeVerified;
    private View badgePending;
    private TextView phoneAction;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_info);
        SystemBarsHelper.apply(this, findViewById(R.id.info_root));
        animator = new EntranceAnimator(getContentResolver());
        entrancePlayed = savedInstanceState != null;
        namesEdited = savedInstanceState != null; // EditTexts restore their own text
        bindViews();

        ((TextView) findViewById(R.id.value_email)).setText(user.getEmail()); // read-only
        findViewById(R.id.row_email).setContentDescription(
                getString(R.string.info_email_description, String.valueOf(user.getEmail())));

        findViewById(R.id.button_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.button_change_photo).setOnClickListener(v -> pickPhoto());
        findViewById(R.id.button_cancel_photo).setOnClickListener(v -> cancelPhoto());
        findViewById(R.id.button_save_photo).setOnClickListener(v -> savePhoto());
        saveNamesButton.setOnClickListener(v -> saveNames());
        findViewById(R.id.row_phone).setOnClickListener(v -> changePhone());
        findViewById(R.id.button_sign_out).setOnClickListener(v -> signOut());
        animator.attachPressFeedback(saveNamesButton);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!filling) namesEdited = true;
            }
        };
        firstName.addTextChangedListener(watcher);
        middleName.addTextChangedListener(watcher);
        lastName.addTextChangedListener(watcher);
    }

    private void bindViews() {
        avatar = findViewById(R.id.info_avatar);
        username = findViewById(R.id.info_username);
        photoStatus = findViewById(R.id.text_photo_status);
        photoActions = findViewById(R.id.photo_actions);
        firstName = findViewById(R.id.input_first_name);
        middleName = findViewById(R.id.input_middle_name);
        lastName = findViewById(R.id.input_last_name);
        firstNameError = findViewById(R.id.text_first_name_error);
        middleNameError = findViewById(R.id.text_middle_name_error);
        lastNameError = findViewById(R.id.text_last_name_error);
        saveNamesButton = findViewById(R.id.button_save_names);
        namesProgress = findViewById(R.id.names_progress);
        namesLabel = findViewById(R.id.names_label);
        phoneValue = findViewById(R.id.value_phone);
        badgeVerified = findViewById(R.id.badge_phone_verified);
        badgePending = findViewById(R.id.badge_phone_pending);
        phoneAction = findViewById(R.id.action_phone);
        statusText = findViewById(R.id.text_status);
    }

    // ------------------------------------------------------------------ load

    @Override
    protected void onResume() {
        super.onResume();
        navigating = false;
        if (avatar != null) loadProfile();
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
                if (!alive()) return;
                profile = p;
                render(user);
                PhoneVerificationSync.reconcile(user, p, () -> { if (alive()) loadProfile(); });
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure f, Exception e) {
                if (!alive()) return;
                if (profile == null && f == UserProfileRepository.Failure.NETWORK) {
                    // Nothing saved on the phone to show yet: offline page, retries by itself.
                    OfflineOverlay.show(InfoActivity.this, () -> loadProfile(), true);
                } else {
                    showStatus(getString(R.string.info_error_load));
                }
            }
        });
    }

    private void render(FirebaseUser user) {
        // Phase 5: say so when these details come from the phone's cache (offline), not the server.
        findViewById(R.id.text_offline_note).setVisibility(profile.fromCache ? View.VISIBLE : View.GONE);
        String name = profile.username();
        username.setText(name);
        if (!namesEdited) {
            filling = true;
            firstName.setText(profile.firstName != null ? profile.firstName : "");
            middleName.setText(profile.middleName != null ? profile.middleName : "");
            lastName.setText(profile.lastName != null ? profile.lastName : "");
            filling = false;
        }
        if (pendingPhoto == null) renderSavedPhoto(user, name);

        boolean verified = PhoneVerificationSync.isVerified(user, profile);
        if (profile.phoneNumber != null) {
            phoneValue.setText(PakistanPhone.display(profile.phoneNumber));
            badgeVerified.setVisibility(verified ? View.VISIBLE : View.GONE);
            badgePending.setVisibility(verified ? View.GONE : View.VISIBLE);
            phoneAction.setText(verified ? R.string.phone_change : R.string.phone_verify_button);
        } else {
            phoneValue.setText(R.string.info_phone_none);
            badgeVerified.setVisibility(View.GONE);
            badgePending.setVisibility(View.GONE);
            phoneAction.setText(R.string.phone_add);
        }
        findViewById(R.id.row_phone).setContentDescription(getString(
                verified ? R.string.info_phone_description_verified : R.string.info_phone_description_unverified,
                profile.phoneNumber != null ? PakistanPhone.display(profile.phoneNumber)
                        : getString(R.string.info_phone_none)));
    }

    private void renderSavedPhoto(FirebaseUser user, String name) {
        ProfileImages.show(avatar, profile, user, name); // phase 5: on-device -> Google -> initials
        photoStatus.setText(ProfileImages.hint(this, profile, user));
        photoActions.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ photo

    private void pickPhoto() {
        if (uploadingPhoto) return;
        startActivityForResult(AvatarPicker.createIntent(), REQUEST_PICK_PHOTO);
    }

    private void processPicked(android.net.Uri uri) {
        photoStatus.setText(R.string.profile_picture_processing);
        background.execute(() -> {
            AvatarProcessor.Result r;
            try {
                r = AvatarProcessor.process(getContentResolver(), uri);
            } catch (Exception e) {
                r = null;
            }
            final AvatarProcessor.Result result = r;
            main.post(() -> {
                if (!alive()) return;
                if (result == null) {
                    photoStatus.setText(R.string.profile_picture_error_read);
                    return;
                }
                pendingPhoto = result.jpeg;
                avatar.setTag(null);
                avatar.setImageDrawable(new BitmapDrawable(getResources(), result.preview));
                EntranceAnimator.popIn(avatar);
                photoStatus.setText(R.string.info_photo_preview);
                photoActions.setVisibility(View.VISIBLE);
            });
        });
    }

    private void cancelPhoto() {
        if (uploadingPhoto) return;
        pendingPhoto = null;
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user != null && profile != null) renderSavedPhoto(user, profile.username());
    }

    private void savePhoto() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (uploadingPhoto || pendingPhoto == null || user == null) return;
        uploadingPhoto = true;
        photoActions.setVisibility(View.GONE);
        photoStatus.setText(R.string.info_photo_saving);
        // Phase 5: saved privately on this phone (no Firebase Storage / no billing needed).
        ProfileImages.store(this).saveCustomPhoto(user.getUid(), pendingPhoto,
                new ProfileImageStore.Callback() {
                    @Override
                    public void onSaved(boolean confirmedByServer) {
                        uploadingPhoto = false;
                        if (!alive()) return;
                        pendingPhoto = null;
                        // Honest: "saved" vs "saved here, profile update waiting for internet".
                        Toast.makeText(InfoActivity.this, confirmedByServer
                                        ? R.string.info_photo_saved : R.string.info_photo_saved_pending,
                                Toast.LENGTH_LONG).show();
                        loadProfile();
                    }

                    @Override
                    public void onFailed(ProfileImageStore.Failure failure, Exception error) {
                        uploadingPhoto = false;
                        if (!alive()) return;
                        // Profile unchanged: the preview stays so the user can retry or cancel.
                        photoStatus.setText(failure == ProfileImageStore.Failure.WRITE_FAILED
                                ? R.string.photo_error_write : R.string.photo_error_profile);
                        photoActions.setVisibility(View.VISIBLE);
                    }
                });
    }

    // ------------------------------------------------------------------ names

    private void saveNames() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (savingNames || user == null) return;
        boolean ok = check(firstName, firstNameError, Names.isValidRequired(firstName.getText().toString()), R.string.first_name_error);
        ok &= check(middleName, middleNameError, Names.isValidOptional(middleName.getText().toString()), R.string.middle_name_error);
        ok &= check(lastName, lastNameError, Names.isValidRequired(lastName.getText().toString()), R.string.last_name_error);
        if (!ok) return;

        final String first = Names.clean(firstName.getText().toString());
        final String middle = Names.clean(middleName.getText().toString());
        final String last = Names.clean(lastName.getText().toString());
        if (OfflineOverlay.showIfOffline(this, this::saveNames, false)) return; // phase 5
        setSavingNames(true);
        profiles.saveNames(user.getUid(), first, middle, last, new UserProfileRepository.SaveCallback() {
            @Override
            public void onSaved() {
                if (!alive()) return;
                setSavingNames(false);
                namesEdited = false;
                user.updateProfile(new UserProfileChangeRequest.Builder()
                        .setDisplayName(Names.username(first, last)).build()); // best effort
                Toast.makeText(InfoActivity.this, R.string.info_names_saved, Toast.LENGTH_SHORT).show();
                loadProfile(); // Settings shows the new name too (it reloads on resume)
            }

            @Override
            public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                if (!alive()) return;
                setSavingNames(false); // typed values stay
                showStatus(getString(f == UserProfileRepository.Failure.NETWORK
                        ? R.string.registration_error_network : R.string.info_error_save_names));
                if (f == UserProfileRepository.Failure.NETWORK) {
                    OfflineOverlay.show(InfoActivity.this, () -> saveNames(), false);
                }
            }
        });
    }

    private boolean check(EditText field, TextView error, boolean valid, int message) {
        field.setActivated(!valid);
        error.setText(valid ? null : getString(message));
        error.setVisibility(valid ? View.GONE : View.VISIBLE);
        return valid;
    }

    private void setSavingNames(boolean value) {
        savingNames = value;
        saveNamesButton.setEnabled(!value);
        saveNamesButton.setAlpha(value ? 0.75f : 1f);
        namesProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        namesLabel.setText(value ? R.string.registration_saving : R.string.info_save_names);
        if (value) statusText.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ phone + sign out

    /** [NAVIGATION] New number -> Firebase verification screen. The old number stays until then. */
    private void changePhone() {
        if (navigating) return;
        navigating = true;
        AppNavigator.openPhoneVerification(this, profile != null ? profile.phoneNumber : null);
    }

    private void signOut() {
        findViewById(R.id.button_sign_out).setEnabled(false);
        new GoogleAuthClient(this).signOut(() -> {
            if (alive()) AppNavigator.openGetStartedAfterSignOut(this);
        });
    }

    // ------------------------------------------------------------------ helpers + lifecycle

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PHOTO && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            processPicked(data.getData());
        } else if (requestCode == AppNavigator.REQUEST_VERIFY_PHONE && resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.info_phone_saved, Toast.LENGTH_SHORT).show();
            // onResume reloads and shows the new verified number.
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (uploadingPhoto || savingNames) return; // let the save finish so its result is shown
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator != null && !entrancePlayed) {
            animator.play(findViewById(R.id.top_bar), findViewById(R.id.photo_section),
                    findViewById(R.id.name_card), findViewById(R.id.contact_card));
            entrancePlayed = true;
        }
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        if (animator != null) animator.cancel();
        super.onDestroy();
    }
}