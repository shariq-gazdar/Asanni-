package com.asanni.app.auth;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CredentialOption;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Real Google sign-in: Android Credential Manager (Google's own account picker)
 * gives a Google ID token, which is exchanged for a Firebase Authentication session.
 * The app never sees or stores the Google password.
 *
 * Location: app/src/main/java/com/asanni/app/auth/GoogleAuthClient.java  (NEW)
 *
 * Flow when the button is tapped:
 *   Step 1  Accounts that already used ASANNI (auto-select if only one) -> no needless picker.
 *   Step 2  If none, the full "Sign in with Google" sheet lets the user pick any account.
 */
public final class GoogleAuthClient {

    private static final String TAG = "AsanniGoogleAuth";

    public enum Failure {
        /** google-services.json missing, Google provider not enabled, or no web client ID. */
        MISSING_CONFIGURATION,
        /** Google rejected the app: wrong SHA-1/SHA-256 or package name in Firebase. */
        DEVELOPER_CONFIGURATION,
        /** There is no Google account on the device. */
        NO_GOOGLE_ACCOUNT,
        /** Google Play services missing/outdated on the device or emulator. */
        PLAY_SERVICES,
        /** The sign-in was interrupted; trying again usually works. */
        INTERRUPTED,
        NETWORK,
        /** Response could not be read as a Google ID token. */
        INVALID_RESPONSE,
        UNKNOWN
    }

    /** Result of the Google account step. */
    public interface SignInCallback {
        void onSignedIn(FirebaseUser user, boolean isNewFirebaseAccount);
        /** The user closed Google's sheet. Not an error and not a success. */
        void onCancelled();
        void onFailed(Failure failure, Exception error);
    }

    public interface SignOutCallback {
        void onSignedOut();
    }

    private final CredentialManager credentialManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = mainHandler::post;
    private CancellationSignal cancellation;

    public GoogleAuthClient(Context context) {
        credentialManager = CredentialManager.create(context.getApplicationContext());
    }

    /** The current Firebase session, or null. Use this to avoid signing in twice. */
    public static FirebaseUser currentUser() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    /**
     * [FIREBASE CONFIG] The OAuth "Web client" ID. The google-services Gradle plugin generates
     * R.string.default_web_client_id from google-services.json once Google sign-in is enabled.
     * Looked up by name so the project still compiles (and shows a clear error) before setup.
     */
    public static String findWebClientId(Context context) {
        int id = context.getResources()
                .getIdentifier("default_web_client_id", "string", context.getPackageName());
        if (id == 0) return null;
        String value = context.getString(id);
        return TextUtils.isEmpty(value) ? null : value;
    }

    /** Starts Google sign-in. Must be called from a visible Activity. */
    public void signIn(Activity activity, SignInCallback callback) {
        String webClientId = findWebClientId(activity);
        if (webClientId == null) {
            callback.onFailed(Failure.MISSING_CONFIGURATION, null);
            return;
        }

        // Step 1: only accounts already authorized for ASANNI; auto-select when there is exactly one.
        GetGoogleIdOption authorizedAccounts = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build();

        requestCredential(activity, authorizedAccounts, callback, () -> {
            // Step 2: nothing authorized yet -> Google's full account chooser.
            GetSignInWithGoogleOption anyAccount =
                    new GetSignInWithGoogleOption.Builder(webClientId).build();
            requestCredential(activity, anyAccount, callback, null);
        });
    }

    /** Cancels a running Google request (call from onDestroy). */
    public void cancel() {
        if (cancellation != null) {
            cancellation.cancel();
            cancellation = null;
        }
    }

    /**
     * [INTEGRATION POINT - SIGN OUT] Signs out of Firebase and clears Credential Manager's
     * remembered account, so the next sign-in doesn't silently reuse it.
     */
    public void signOut(SignOutCallback callback) {
        FirebaseAuth.getInstance().signOut();
        credentialManager.clearCredentialStateAsync(
                new ClearCredentialStateRequest(),
                new CancellationSignal(),
                mainExecutor,
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void result) {
                        callback.onSignedOut();
                    }

                    @Override
                    public void onError(ClearCredentialException e) {
                        Log.w(TAG, "clearCredentialState failed (Firebase sign-out still done)", e);
                        callback.onSignedOut();
                    }
                });
    }

    // ------------------------------------------------------------------------------------------

    private void requestCredential(Activity activity, CredentialOption option,
                                   SignInCallback callback, Runnable onNoCredential) {
        cancel();
        cancellation = new CancellationSignal();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        credentialManager.getCredentialAsync(activity, request, cancellation, mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        handleCredential(response.getCredential(), callback);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof NoCredentialException && onNoCredential != null) {
                            onNoCredential.run(); // move on to step 2
                            return;
                        }
                        handleCredentialError(e, callback);
                    }
                });
    }

    private void handleCredential(Credential credential, SignInCallback callback) {
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            Log.w(TAG, "Unexpected credential type: " + credential.getType());
            callback.onFailed(Failure.INVALID_RESPONSE, null);
            return;
        }
        String idToken;
        try {
            idToken = GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken();
        } catch (Exception e) { // GoogleIdTokenParsingException
            Log.w(TAG, "Could not parse Google ID token", e);
            callback.onFailed(Failure.INVALID_RESPONSE, e);
            return;
        }
        signInToFirebase(idToken, callback);
    }

    private void signInToFirebase(String idToken, SignInCallback callback) {
        AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(idToken, null);
        FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult() == null
                            || task.getResult().getUser() == null) {
                        Exception e = task.getException();
                        Log.w(TAG, "Firebase signInWithCredential failed", e);
                        Failure f;
                        if (e instanceof FirebaseNetworkException) {
                            f = Failure.NETWORK;
                        } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
                            f = Failure.DEVELOPER_CONFIGURATION; // usually wrong web client ID / project
                        } else {
                            f = Failure.UNKNOWN;
                        }
                        callback.onFailed(f, e);
                        return;
                    }
                    AuthResult result = task.getResult();
                    // isNewUser = new FIREBASE account. Only a hint: the Firestore profile decides
                    // whether this person still needs onboarding in ASANNI.
                    boolean isNew = result.getAdditionalUserInfo() != null
                            && result.getAdditionalUserInfo().isNewUser();
                    callback.onSignedIn(result.getUser(), isNew);
                });
    }

    private void handleCredentialError(GetCredentialException e, SignInCallback callback) {
        if (e instanceof GetCredentialCancellationException) {
            callback.onCancelled();
            return;
        }
        Log.w(TAG, "Credential Manager error: " + e.getType(), e);
        String message = String.valueOf(e.getMessage());
        Failure f;
        if (e instanceof NoCredentialException) {
            f = Failure.NO_GOOGLE_ACCOUNT;
        } else if (e instanceof GetCredentialInterruptedException) {
            f = Failure.INTERRUPTED;
        } else if (e instanceof GetCredentialProviderConfigurationException) {
            f = Failure.PLAY_SERVICES;
        } else if (message.contains("28444") || message.contains("Developer console")) {
            // Google's DEVELOPER_ERROR: SHA fingerprint / package / OAuth client mismatch.
            f = Failure.DEVELOPER_CONFIGURATION;
        } else if (message.toLowerCase(Locale.ROOT).contains("network")) {
            f = Failure.NETWORK;
        } else {
            f = Failure.UNKNOWN;
        }
        callback.onFailed(f, e);
    }
}