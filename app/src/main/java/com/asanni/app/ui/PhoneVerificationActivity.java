package com.asanni.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.profile.PakistanPhone;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.auth.UserInfo;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Verifies a Pakistani mobile number with Firebase Phone Authentication and attaches it to the
 * user's EXISTING (Google) Firebase account. The UID and Google sign-in stay the same.
 *
 * Location: app/src/main/java/com/asanni/app/ui/PhoneVerificationActivity.java  (NEW in phase 4)
 *
 *   1. Number entered + validated (+923XXXXXXXXX)      4. Code checked by Firebase
 *   2. Firebase sends an SMS code                       5. Number linked/updated on the account
 *   3. User enters the code (or Android reads it)       6. ID token refreshed, profile saved
 * RESULT_OK is returned only after step 6. Nothing is "verified" before Firebase says so.
 * Codes are never stored or logged.
 */
public class PhoneVerificationActivity extends AsanniActivity {

    static final String EXTRA_INITIAL_NUMBER = "com.asanni.app.extra.INITIAL_NUMBER";
    static final String EXTRA_VERIFIED_NUMBER = "com.asanni.app.extra.VERIFIED_NUMBER";

    private static final String TAG = "AsanniPhoneVerify";
    // ---------------- [CONFIG] verification timing ----------------
    /** How long Firebase waits to auto-read the SMS; also the resend cooldown. */
    private static final long CODE_TIMEOUT_SECONDS = 60L;
    /** Extra codes a user may request on this screen (Firebase also rate-limits). */
    private static final int MAX_RESENDS = 3;
    private static final int CODE_LENGTH = 6;
    // ---------------------------------------------------------------

    private static final String STATE_STEP = "step";
    private static final String STATE_NUMBER = "number";
    private static final String STATE_VERIFICATION_ID = "verification_id";
    private static final String STATE_RESENDS_USED = "resends_used";
    private static final String STATE_RESEND_AT = "resend_at";

    private enum Step { ENTER_NUMBER, SENDING, ENTER_CODE, VERIFYING, SAVE_FAILED }

    private Step step = Step.ENTER_NUMBER;
    private String e164;
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private int resendsUsed;
    private long resendAvailableAtMs;
    private boolean entrancePlayed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable countdown = this::updateResendLabel;
    private EntranceAnimator animator;

    private View numberGroup;
    private View fieldPhone;
    private EditText phoneInput;
    private TextView phoneMessage;
    private View codeGroup;
    private TextView codeSentTo;
    private EditText codeInput;
    private TextView codeMessage;
    private TextView resendButton;
    private TextView statusText;
    private View primaryButton;
    private View primaryProgress;
    private TextView primaryLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) { // session expired
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_phone_verification);
        SystemBarsHelper.apply(this, findViewById(R.id.phone_root));
        animator = new EntranceAnimator(getContentResolver());
        entrancePlayed = savedInstanceState != null;

        numberGroup = findViewById(R.id.number_group);
        fieldPhone = findViewById(R.id.field_phone);
        phoneInput = findViewById(R.id.input_phone);
        phoneMessage = findViewById(R.id.text_phone_message);
        codeGroup = findViewById(R.id.code_group);
        codeSentTo = findViewById(R.id.text_code_sent_to);
        codeInput = findViewById(R.id.input_code);
        codeMessage = findViewById(R.id.text_code_message);
        resendButton = findViewById(R.id.button_resend);
        statusText = findViewById(R.id.text_status);
        primaryButton = findViewById(R.id.button_primary);
        primaryProgress = findViewById(R.id.primary_progress);
        primaryLabel = findViewById(R.id.primary_label);

        findViewById(R.id.button_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.button_change_number).setOnClickListener(v -> backToNumber());
        resendButton.setOnClickListener(v -> resend());
        primaryButton.setOnClickListener(v -> onPrimary());
        animator.attachPressFeedback(primaryButton);

        if (savedInstanceState != null) {
            e164 = savedInstanceState.getString(STATE_NUMBER);
            verificationId = savedInstanceState.getString(STATE_VERIFICATION_ID);
            resendsUsed = savedInstanceState.getInt(STATE_RESENDS_USED);
            resendAvailableAtMs = savedInstanceState.getLong(STATE_RESEND_AT);
            String s = savedInstanceState.getString(STATE_STEP);
            step = verificationId != null && "ENTER_CODE".equals(s) ? Step.ENTER_CODE : Step.ENTER_NUMBER;
        } else {
            String initial = getIntent().getStringExtra(EXTRA_INITIAL_NUMBER);
            if (initial != null) phoneInput.setText(PakistanPhone.localPart(initial));
        }
        render(null);
    }

    // ------------------------------------------------------------------ step 1-2: send code

    private void onPrimary() {
        switch (step) {
            case ENTER_NUMBER: sendCode(false); break;
            case ENTER_CODE:   verifyCode(); break;
            case SAVE_FAILED:  saveToProfile(); break;
            default: break; // busy
        }
    }

    private void sendCode(boolean isResend) {
        String normalized = PakistanPhone.normalize(phoneInput.getText().toString());
        if (normalized == null) {
            showFieldError(fieldPhone, phoneMessage, getString(R.string.phone_error_invalid));
            return;
        }
        fieldPhone.setActivated(false);
        phoneMessage.setVisibility(View.GONE);
        e164 = normalized;
        // Phase 5: offline -> offline page. Its Try again only closes it: the user taps
        // "Send code" again themselves (SMS codes are never re-requested automatically).
        if (OfflineOverlay.showIfOffline(this, null, false)) return;
        step = Step.SENDING;
        render(null);

        PhoneAuthOptions.Builder b = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(e164)
                .setTimeout(CODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .setActivity(this) // needed for the reCAPTCHA fallback
                .setCallbacks(callbacks);
        if (isResend && resendToken != null) b.setForceResendingToken(resendToken);
        PhoneAuthProvider.verifyPhoneNumber(b.build());
    }

    private void resend() {
        if (System.currentTimeMillis() < resendAvailableAtMs) return;
        if (resendsUsed >= MAX_RESENDS) {
            render(getString(R.string.phone_error_resend_limit));
            return;
        }
        resendsUsed++;
        sendCode(true);
    }

    private final PhoneAuthProvider.OnVerificationStateChangedCallbacks callbacks =
            new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override
                public void onVerificationCompleted(PhoneAuthCredential credential) {
                    // Instant verification or Android read the SMS automatically.
                    if (!alive()) return;
                    // Already verifying (user tapped Verify) or done: don't link a second time.
                    if (step == Step.VERIFYING || step == Step.SAVE_FAILED) return;
                    if (credential.getSmsCode() != null) codeInput.setText(credential.getSmsCode());
                    attachToAccount(credential);
                }

                @Override
                public void onVerificationFailed(FirebaseException e) {
                    if (!alive()) return;
                    Log.w(TAG, "Verification failed: " + e.getClass().getSimpleName());
                    step = Step.ENTER_NUMBER;
                    render(getString(messageForSendFailure(e)));
                    if (e instanceof FirebaseNetworkException) {
                        OfflineOverlay.show(PhoneVerificationActivity.this, null, false);
                    }
                }

                @Override
                public void onCodeSent(String id, PhoneAuthProvider.ForceResendingToken token) {
                    if (!alive()) return;
                    verificationId = id;
                    resendToken = token;
                    resendAvailableAtMs = System.currentTimeMillis() + CODE_TIMEOUT_SECONDS * 1000;
                    step = Step.ENTER_CODE;
                    codeInput.setText("");
                    render(null);
                    codeInput.requestFocus();
                    updateResendLabel();
                }
            };

    // ------------------------------------------------------------------ step 4-5: verify + link

    private void verifyCode() {
        String code = codeInput.getText().toString().trim();
        if (!code.matches("\\d{" + CODE_LENGTH + "}")) {
            showFieldError(codeInput, codeMessage, getString(R.string.otp_error_format));
            return;
        }
        if (verificationId == null) {
            backToNumber();
            render(getString(R.string.otp_error_expired));
            return;
        }
        if (OfflineOverlay.showIfOffline(this, this::verifyCode, false)) return; // phase 5
        attachToAccount(PhoneAuthProvider.getCredential(verificationId, code));
    }

    /** Adds the verified number to the signed-in account (never signs in as someone else). */
    private void attachToAccount(PhoneAuthCredential credential) {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        step = Step.VERIFYING;
        render(null);

        boolean hasPhone = false;
        for (UserInfo info : user.getProviderData()) {
            if (PhoneAuthProvider.PROVIDER_ID.equals(info.getProviderId())) hasPhone = true;
        }
        // Already has a number -> replace it. No number yet -> link phone as a second sign-in method.
        if (hasPhone) {
            user.updatePhoneNumber(credential)
                    .addOnSuccessListener(r -> onAttached(user))
                    .addOnFailureListener(this::onAttachFailed);
        } else {
            user.linkWithCredential(credential)
                    .addOnSuccessListener(r -> onAttached(user))
                    .addOnFailureListener(this::onAttachFailed);
        }
    }

    private void onAttached(FirebaseUser user) {
        if (!alive()) return;
        // Refresh the ID token so Firestore rules can see the new phone_number claim.
        user.getIdToken(true)
                .addOnSuccessListener(t -> { if (alive()) saveToProfile(); })
                .addOnFailureListener(e -> {
                    if (!alive()) return;
                    step = Step.SAVE_FAILED;
                    render(getString(R.string.phone_error_save_failed));
                });
    }

    private void onAttachFailed(Exception e) {
        if (!alive()) return;
        Log.w(TAG, "Attach failed: " + e.getClass().getSimpleName());
        step = verificationId != null ? Step.ENTER_CODE : Step.ENTER_NUMBER; // instant flow: no code
        render(getString(messageForAttachFailure(e)));
    }

    // ------------------------------------------------------------------ step 6: profile

    private void saveToProfile() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        // Save the number Firebase actually has on the account (the verified one).
        final String verified = PakistanPhone.normalize(user.getPhoneNumber());
        if (verified == null) {
            step = Step.ENTER_NUMBER;
            render(getString(R.string.phone_error_generic));
            return;
        }
        step = Step.VERIFYING;
        render(null);
        new UserProfileRepository().savePhoneVerified(user.getUid(), verified,
                new UserProfileRepository.SaveCallback() {
                    @Override
                    public void onSaved() {
                        if (!alive()) return;
                        Intent data = new Intent().putExtra(EXTRA_VERIFIED_NUMBER, verified);
                        setResult(RESULT_OK, data);
                        finish();
                    }

                    @Override
                    public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                        if (!alive()) return;
                        // Verified on the account, but not saved in the profile yet: offer a retry.
                        // (PhoneVerificationSync also repairs this automatically later.)
                        step = Step.SAVE_FAILED;
                        render(getString(R.string.phone_error_save_failed));
                    }
                });
    }

    // ------------------------------------------------------------------ errors

    private static int messageForSendFailure(FirebaseException e) {
        String msg = String.valueOf(e.getMessage());
        if (e instanceof FirebaseAuthInvalidCredentialsException) return R.string.phone_error_invalid;
        if (e instanceof FirebaseTooManyRequestsException) return R.string.phone_error_too_many;
        if (e instanceof FirebaseNetworkException) return R.string.phone_error_network;
        if (e instanceof FirebaseAuthMissingActivityForRecaptchaException) return R.string.phone_error_generic;
        if (msg.contains("BILLING_NOT_ENABLED")) return R.string.phone_error_not_configured;
        if (msg.toLowerCase(Locale.ROOT).contains("region")) return R.string.phone_error_region;
        if (e instanceof FirebaseAuthException
                && "ERROR_OPERATION_NOT_ALLOWED".equals(((FirebaseAuthException) e).getErrorCode())) {
            return R.string.phone_error_not_configured;
        }
        return R.string.phone_error_generic;
    }

    private static int messageForAttachFailure(Exception e) {
        if (e instanceof FirebaseAuthUserCollisionException) return R.string.phone_error_in_use;
        if (e instanceof FirebaseAuthRecentLoginRequiredException) return R.string.phone_error_relogin;
        if (e instanceof FirebaseNetworkException) return R.string.phone_error_network;
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            String code = ((FirebaseAuthInvalidCredentialsException) e).getErrorCode();
            if ("ERROR_SESSION_EXPIRED".equals(code)) return R.string.otp_error_expired;
            return R.string.otp_error_wrong;
        }
        return R.string.phone_error_generic;
    }

    // ------------------------------------------------------------------ UI

    private void backToNumber() {
        if (step == Step.VERIFYING || step == Step.SENDING) return;
        step = Step.ENTER_NUMBER;
        verificationId = null;
        render(null);
    }

    private void render(String message) {
        boolean busy = step == Step.SENDING || step == Step.VERIFYING;
        boolean codeStep = step == Step.ENTER_CODE || step == Step.VERIFYING && verificationId != null;
        numberGroup.setVisibility(codeStep ? View.GONE : View.VISIBLE);
        codeGroup.setVisibility(codeStep ? View.VISIBLE : View.GONE);
        phoneInput.setEnabled(!busy);
        codeInput.setEnabled(!busy);

        if (codeStep && e164 != null) {
            codeSentTo.setText(getString(R.string.otp_sent_to, PakistanPhone.display(e164)));
        }
        if (step != Step.ENTER_CODE) codeMessage.setVisibility(View.GONE);

        int label;
        switch (step) {
            case SENDING:     label = R.string.phone_sending; break;
            case ENTER_CODE:  label = R.string.otp_verify_button; break;
            case VERIFYING:   label = R.string.otp_verifying; break;
            case SAVE_FAILED: label = R.string.phone_retry_save; break;
            default:          label = R.string.phone_send_code_button; break;
        }
        primaryLabel.setText(label);
        primaryButton.setEnabled(!busy);
        primaryButton.setAlpha(busy ? 0.75f : 1f);
        primaryProgress.setVisibility(busy ? View.VISIBLE : View.GONE);

        statusText.setText(message);
        statusText.setVisibility(message == null ? View.GONE : View.VISIBLE);
        updateResendLabel();
    }

    private void updateResendLabel() {
        handler.removeCallbacks(countdown);
        if (step != Step.ENTER_CODE) return;
        long left = (resendAvailableAtMs - System.currentTimeMillis() + 999) / 1000;
        if (resendsUsed >= MAX_RESENDS) {
            resendButton.setEnabled(false);
            resendButton.setText(R.string.otp_resend_limit);
        } else if (left > 0) {
            resendButton.setEnabled(false);
            resendButton.setText(getString(R.string.otp_resend_in, left / 60, left % 60));
            handler.postDelayed(countdown, 1000);
        } else {
            resendButton.setEnabled(true);
            resendButton.setText(R.string.otp_resend);
        }
    }

    private void showFieldError(View field, TextView message, String text) {
        field.setActivated(true);
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (step == Step.VERIFYING) return; // let linking finish so the result is shown
        super.onBackPressed();              // cancelled: previous number/profile unchanged
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_STEP, step.name());
        out.putString(STATE_NUMBER, e164);
        out.putString(STATE_VERIFICATION_ID, verificationId); // an ID, not the code
        out.putInt(STATE_RESENDS_USED, resendsUsed);
        out.putLong(STATE_RESEND_AT, resendAvailableAtMs);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator != null && !entrancePlayed) {
            animator.play(findViewById(R.id.top_bar), findViewById(R.id.text_title),
                    findViewById(R.id.text_description), findViewById(R.id.form),
                    findViewById(R.id.bottom_actions));
            entrancePlayed = true;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(countdown);
        if (animator != null) animator.cancel();
        super.onDestroy();
    }
}