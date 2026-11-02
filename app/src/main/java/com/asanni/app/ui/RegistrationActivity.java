package com.asanni.app.ui;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.appearance.Appearance;
import com.asanni.app.appearance.AppearanceManager;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.Names;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfile;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.profile.AvatarPicker;
import com.asanni.app.profile.AvatarProcessor;
import com.asanni.app.profile.ProfileImageStore;
import com.asanni.app.profile.ProfileImages;
import com.asanni.app.profile.InitialsDrawable;
import com.asanni.app.profile.LocalProfileImageStore;
import com.asanni.app.profile.PakistanPhone;
import com.asanni.app.profile.PhoneVerificationSync;
import com.asanni.app.profile.SupportedLanguage;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-time registration form. Shown only while users/{uid}.registrationCompleted is not true.
 *
 * Location: app/src/main/java/com/asanni/app/ui/RegistrationActivity.java  (NEW in phase 4)
 *
 * Save order (registrationCompleted is set only after every step succeeded):
 *   1. validate names + require a Firebase-verified phone number (UserProfile.REQUIRE_VERIFIED_PHONE)
 *   2. upload the chosen photo, if any (Storage), and save its reference. Note: if step 3 then
 *      fails, the new photo is already on the profile (that's fine: it's optional and the user
 *      chose it); registration itself stays incomplete until step 3 succeeds.
 *   3. save names/language/appearance + registrationCompleted = true in ONE Firestore update
 *   4. apply the appearance, then notification explanation (once per device) -> Home
 * On any failure the typed values stay in place and the user can simply tap Save again.
 */
public class RegistrationActivity extends AsanniActivity {

    private static final int REQUEST_PICK_PHOTO = 6101;
    private static final String STATE_APPEARANCE = "appearance";
    private static final String STATE_PICKED_URI = "picked_uri";
    private static final String STATE_PHOTO_UPLOADED = "photo_uploaded";

    private final UserProfileRepository profiles = new UserProfileRepository();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EntranceAnimator animator;
    private AppearanceSelector appearanceSelector;

    private ProfileData profile;          // last loaded profile
    private String verifiedPhone;         // non-null only when Firebase Auth + profile agree
    private Uri pickedPhotoUri;           // chosen but not uploaded yet
    private byte[] pickedPhotoJpeg;
    private boolean photoUploaded;        // so a retry doesn't save the same picture twice
    private boolean navigating;           // stops double taps opening two verification screens
    private boolean saving;
    private boolean entrancePlayed;
    private boolean restoring;

    private View loading;
    private View form;
    private ImageView avatar;
    private TextView photoHint;
    private View removePhoto;
    private EditText firstName;
    private EditText middleName;
    private EditText lastName;
    private TextView firstNameError;
    private TextView middleNameError;
    private TextView lastNameError;
    private TextView usernamePreview;
    private View phoneInputGroup;
    private View phoneVerifiedGroup;
    private View fieldPhone;
    private EditText phoneInput;
    private TextView phoneMessage;
    private TextView phoneVerifiedText;
    private TextView statusText;
    private View saveButton;
    private View saveProgress;
    private TextView saveLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) { // sign-in expired
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_registration);
        SystemBarsHelper.apply(this, findViewById(R.id.registration_root));
        animator = new EntranceAnimator(getContentResolver());
        bindViews();

        ((TextView) findViewById(R.id.text_email)).setText(user.getEmail()); // from Auth, read-only
        ((TextView) findViewById(R.id.text_language)).setText(SupportedLanguage.DEFAULT.nativeName);
        appearanceSelector = new AppearanceSelector(this, a -> { /* applied after saving */ });
        appearanceSelector.setSelected(AppearanceManager.current(this));

        findViewById(R.id.button_change_photo).setOnClickListener(v -> pickPhoto());
        findViewById(R.id.button_pick_photo).setOnClickListener(v -> pickPhoto());
        removePhoto.setOnClickListener(v -> discardPickedPhoto());
        findViewById(R.id.button_verify_phone).setOnClickListener(v -> startPhoneVerification());
        findViewById(R.id.button_change_phone).setOnClickListener(v -> startPhoneVerification());
        saveButton.setOnClickListener(v -> onSave());
        animator.attachPressFeedback(saveButton);

        TextWatcher preview = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateUsernamePreview(); }
        };
        firstName.addTextChangedListener(preview);
        lastName.addTextChangedListener(preview);

        entrancePlayed = savedInstanceState != null;
        restoring = savedInstanceState != null;
        if (savedInstanceState != null) {
            appearanceSelector.setSelected(Appearance.fromId(savedInstanceState.getString(STATE_APPEARANCE)));
            photoUploaded = savedInstanceState.getBoolean(STATE_PHOTO_UPLOADED);
            String uri = savedInstanceState.getString(STATE_PICKED_URI);
            if (uri != null && !photoUploaded) processPickedPhoto(Uri.parse(uri));
        }
        loadProfile();
    }

    private void bindViews() {
        loading = findViewById(R.id.loading);
        form = findViewById(R.id.form);
        avatar = findViewById(R.id.avatar_image);
        photoHint = findViewById(R.id.text_photo_hint);
        removePhoto = findViewById(R.id.button_remove_photo);
        firstName = findViewById(R.id.input_first_name);
        middleName = findViewById(R.id.input_middle_name);
        lastName = findViewById(R.id.input_last_name);
        firstNameError = findViewById(R.id.text_first_name_error);
        middleNameError = findViewById(R.id.text_middle_name_error);
        lastNameError = findViewById(R.id.text_last_name_error);
        usernamePreview = findViewById(R.id.text_username_preview);
        phoneInputGroup = findViewById(R.id.phone_input_group);
        phoneVerifiedGroup = findViewById(R.id.phone_verified_group);
        fieldPhone = findViewById(R.id.field_phone);
        phoneInput = findViewById(R.id.input_phone);
        phoneMessage = findViewById(R.id.text_phone_message);
        phoneVerifiedText = findViewById(R.id.text_phone_verified);
        statusText = findViewById(R.id.text_status);
        saveButton = findViewById(R.id.button_save);
        saveProgress = findViewById(R.id.save_progress);
        saveLabel = findViewById(R.id.save_label);
    }

    // ------------------------------------------------------------------ load + prefill

    private void loadProfile() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) return;
        showLoading(true);
        profiles.loadProfile(user.getUid(), new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(ProfileData p) {
                if (!alive()) return;
                profile = p;
                if (p.registrationCompleted) { // returning user: never show the form again
                    OnboardingRouter.openHomeOrNotificationPrompt(RegistrationActivity.this, p.uid);
                    return;
                }
                showLoading(false);
                prefill(user, p, !restoring);
                PhoneVerificationSync.reconcile(user, p, () -> { if (alive()) loadProfileQuietly(); });
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure f, Exception e) {
                if (!alive()) return;
                showLoading(false);
                showStatus(getString(R.string.registration_error_load));
                if (f == UserProfileRepository.Failure.NETWORK) {
                    OfflineOverlay.show(RegistrationActivity.this, () -> loadProfile(), true);
                }
                saveLabel.setText(R.string.registration_retry_load);
            }
        });
    }

    /** Re-reads the profile without touching what the user typed (after phone repair/verify). */
    private void loadProfileQuietly() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) return;
        profiles.loadProfile(user.getUid(), new UserProfileRepository.ProfileCallback() {
            @Override public void onProfileReady(ProfileData p) {
                if (!alive()) return;
                profile = p;
                renderPhone(user);
            }
            @Override public void onProfileFailed(UserProfileRepository.Failure f, Exception e) { }
        });
    }

    /** Fills the form with saved data or, for a new profile, a best guess from Google. */
    private void prefill(FirebaseUser user, ProfileData p, boolean overwriteText) {
        if (overwriteText) {
            String first = p.firstName;
            String last = p.lastName;
            if (first == null && last == null) {
                // Google's display name can't always be split correctly: this is only a suggestion.
                String[] guess = Names.splitForPrefill(user.getDisplayName());
                first = guess[0];
                last = guess[1];
            }
            firstName.setText(first != null ? first : "");
            middleName.setText(p.middleName != null ? p.middleName : "");
            lastName.setText(last != null ? last : "");
            if (p.hasAppearance()) appearanceSelector.setSelected(p.appearance());
            if (p.phoneNumber != null) phoneInput.setText(PakistanPhone.localPart(p.phoneNumber));
        }
        updateUsernamePreview();
        renderAvatar(user);
        renderPhone(user);
    }

    private void renderAvatar(FirebaseUser user) {
        if (pickedPhotoJpeg != null) return; // the preview of the chosen photo stays
        // Phase 5: on-device picture -> Google photo -> initials (see ProfileImages).
        ProfileImages.show(avatar, profile, user, currentUsername());
        photoHint.setText(ProfileImages.hint(this, profile, user));
    }

    private void renderPhone(FirebaseUser user) {
        verifiedPhone = PhoneVerificationSync.isVerified(user, profile) ? profile.phoneNumber : null;
        phoneInputGroup.setVisibility(verifiedPhone == null ? View.VISIBLE : View.GONE);
        phoneVerifiedGroup.setVisibility(verifiedPhone == null ? View.GONE : View.VISIBLE);
        if (verifiedPhone != null) {
            phoneVerifiedText.setText(PakistanPhone.display(verifiedPhone));
            phoneVerifiedGroup.setContentDescription(getString(R.string.phone_verified_description,
                    PakistanPhone.display(verifiedPhone)));
        }
    }

    private void updateUsernamePreview() {
        String name = currentUsername();
        usernamePreview.setText(name.isEmpty() ? getString(R.string.username_preview_empty)
                : getString(R.string.username_preview, name));
        // Keep the initials in step with the typed name when no picture is showing yet.
        FirebaseUser user = GoogleAuthClient.currentUser();
        // Based on what can actually be shown on THIS phone (an on-device picture saved on another
        // phone isn't here, so the initials are what's showing).
        boolean pictureHere = profile != null && profile.isLocalPhoto()
                && LocalProfileImageStore.find(this, profile.uid, profile.photoLocalId) != null;
        if (pickedPhotoJpeg == null && profile != null && !pictureHere
                && (user == null || user.getPhotoUrl() == null)) {
            avatar.setTag(null); // stop any pending image load from replacing the initials
            avatar.setImageDrawable(InitialsDrawable.avatarFallback(this, name));
        }
    }

    private String currentUsername() {
        return Names.username(firstName.getText().toString(), lastName.getText().toString());
    }

    // ------------------------------------------------------------------ photo

    private void pickPhoto() {
        if (saving) return;
        startActivityForResult(AvatarPicker.createIntent(), REQUEST_PICK_PHOTO);
    }

    private void processPickedPhoto(Uri uri) {
        photoHint.setText(R.string.profile_picture_processing);
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
                    photoHint.setText(R.string.profile_picture_error_read);
                    return;
                }
                pickedPhotoUri = uri;
                pickedPhotoJpeg = result.jpeg;
                photoUploaded = false; // a newer photo: save it (the older file is then removed)
                avatar.setTag(null);
                avatar.setImageDrawable(new BitmapDrawable(getResources(), result.preview));
                EntranceAnimator.popIn(avatar);
                photoHint.setText(R.string.profile_picture_hint_chosen);
                removePhoto.setVisibility(View.VISIBLE);
            });
        });
    }

    private void discardPickedPhoto() {
        if (saving) return;
        pickedPhotoUri = null;
        pickedPhotoJpeg = null;
        removePhoto.setVisibility(View.GONE);
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user != null) renderAvatar(user);
    }

    // ------------------------------------------------------------------ phone

    private void startPhoneVerification() {
        if (saving || navigating) return;
        String typed = PakistanPhone.normalize(phoneInput.getText().toString());
        if (phoneInputGroup.getVisibility() == View.VISIBLE && typed == null
                && !phoneInput.getText().toString().trim().isEmpty()) {
            showPhoneError(getString(R.string.phone_error_invalid));
            return;
        }
        navigating = true;
        AppNavigator.openPhoneVerification(this, typed != null ? typed : verifiedPhone);
    }

    private void showPhoneError(String text) {
        fieldPhone.setActivated(true);
        phoneMessage.setTextColor(getColor(R.color.asanni_welcome_accent));
        phoneMessage.setText(text);
    }

    // ------------------------------------------------------------------ save

    private void onSave() {
        if (saving) return;
        if (profile == null) { // the profile never loaded: retry that first
            statusText.setVisibility(View.GONE);
            saveLabel.setText(R.string.registration_save_button);
            loadProfile();
            return;
        }
        statusText.setVisibility(View.GONE);
        if (!validate()) return;

        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        // Phase 5: registration must reach the server; no internet -> offline page (no queued save).
        if (OfflineOverlay.showIfOffline(this, this::onSave, false)) return;
        setSaving(true);
        if (pickedPhotoJpeg != null && !photoUploaded) {
            // Phase 5: saved privately on this phone (no Firebase Storage / no billing needed).
            ProfileImages.store(this).saveCustomPhoto(user.getUid(), pickedPhotoJpeg, new ProfileImageStore.Callback() {
                @Override
                public void onSaved(boolean confirmedByServer) {
                    if (!alive()) return;
                    photoUploaded = true;
                    saveForm(user);
                }

                @Override
                public void onFailed(ProfileImageStore.Failure failure, Exception error) {
                    if (!alive()) return;
                    setSaving(false);
                    showStatus(getString(failure == ProfileImageStore.Failure.WRITE_FAILED
                            ? R.string.photo_error_write : R.string.photo_error_profile));
                }
            });
        } else {
            saveForm(user);
        }
    }

    private void saveForm(FirebaseUser user) {
        final Appearance appearance = appearanceSelector.getSelected();
        final String first = Names.clean(firstName.getText().toString());
        final String middle = Names.clean(middleName.getText().toString());
        final String last = Names.clean(lastName.getText().toString());

        profiles.saveRegistration(user.getUid(), first, middle, last, SupportedLanguage.DEFAULT,
                appearance, new UserProfileRepository.SaveCallback() {
                    @Override
                    public void onSaved() {
                        if (!alive()) return;
                        // Keep Firebase Auth's display name in step (best effort; Firestore is the source).
                        user.updateProfile(new UserProfileChangeRequest.Builder()
                                .setDisplayName(Names.username(first, last)).build());
                        AppearanceManager.setLocal(RegistrationActivity.this, appearance);
                        AppearanceManager.setPendingSync(RegistrationActivity.this, false); // just saved
                        OnboardingRouter.openHomeOrNotificationPrompt(RegistrationActivity.this, user.getUid());
                    }

                    @Override
                    public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                        if (!alive()) return;
                        setSaving(false);
                        if (f == UserProfileRepository.Failure.NETWORK) {
                            OfflineOverlay.show(RegistrationActivity.this, () -> onSave(), false);
                        }
                        showStatus(getString(f == UserProfileRepository.Failure.NETWORK
                                ? R.string.registration_error_network : R.string.registration_error_save));
                    }
                });
    }

    private boolean validate() {
        boolean ok = true;
        ok &= check(firstName, firstNameError, Names.isValidRequired(firstName.getText().toString()),
                R.string.first_name_error);
        ok &= check(middleName, middleNameError, Names.isValidOptional(middleName.getText().toString()),
                R.string.middle_name_error);
        ok &= check(lastName, lastNameError, Names.isValidRequired(lastName.getText().toString()),
                R.string.last_name_error);
        if (UserProfile.REQUIRE_VERIFIED_PHONE && verifiedPhone == null) {
            String typed = phoneInput.getText().toString().trim();
            showPhoneError(getString(typed.isEmpty() ? R.string.phone_error_required
                    : PakistanPhone.isValid(typed) ? R.string.phone_error_verify_first
                    : R.string.phone_error_invalid));
            ok = false;
        }
        if (!ok) showStatus(getString(R.string.registration_error_fix_fields));
        return ok;
    }

    private boolean check(EditText field, TextView error, boolean valid, int message) {
        field.setActivated(!valid);
        error.setText(valid ? null : getString(message));
        error.setVisibility(valid ? View.GONE : View.VISIBLE);
        return valid;
    }

    private void setSaving(boolean value) {
        saving = value;
        saveButton.setEnabled(!value);
        saveButton.setAlpha(value ? 0.75f : 1f);
        saveProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        saveLabel.setText(value ? R.string.registration_saving : R.string.registration_save_button);
        firstName.setEnabled(!value);
        middleName.setEnabled(!value);
        lastName.setEnabled(!value);
        appearanceSelector.setEnabled(!value);
    }

    private void showLoading(boolean on) {
        loading.setVisibility(on ? View.VISIBLE : View.GONE);
        form.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        saveButton.setEnabled(!on);
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    // ------------------------------------------------------------------ results + lifecycle

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PHOTO && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            processPickedPhoto(data.getData());
        } else if (requestCode == AppNavigator.REQUEST_VERIFY_PHONE && resultCode == RESULT_OK) {
            // Verified by Firebase and saved: re-read the profile to show it.
            fieldPhone.setActivated(false);
            phoneMessage.setTextColor(getColor(R.color.asanni_welcome_muted));
            phoneMessage.setText(R.string.phone_verification_required);
            loadProfileQuietly();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        navigating = false;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_APPEARANCE, appearanceSelector.getSelected().id);
        out.putBoolean(STATE_PHOTO_UPLOADED, photoUploaded);
        if (pickedPhotoUri != null) out.putString(STATE_PICKED_URI, pickedPhotoUri.toString());
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (saving) return; // finish saving first so the result is shown
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator != null && !entrancePlayed) {
            animator.play(findViewById(R.id.text_step), findViewById(R.id.text_title),
                    findViewById(R.id.text_description), form, findViewById(R.id.bottom_actions));
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