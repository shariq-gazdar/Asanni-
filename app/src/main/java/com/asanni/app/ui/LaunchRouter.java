package com.asanni.app.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfileRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;

/**
 * Decides where the app goes when it is opened (phase 7). Runs WHILE the intro plays, so a
 * signed-in user goes  Intro -> Discover  with no Get Started, login or "Setting up your profile"
 * page in between.
 *
 * Location: app/src/main/java/com/asanni/app/ui/LaunchRouter.java  (NEW in phase 7)
 *
 *   No Firebase user ......................... Get Started (existing sign-in + onboarding)
 *   Signed in, profile read (server or cache)  OnboardingRouter: incomplete -> the missing step,
 *                                               complete -> (notification prompt once) -> Discover
 *   Signed in, no profile document yet ....... creates it with the existing ensureProfile()
 *                                               (never overwrites) -> first onboarding step
 *   Signed in, network slow / offline ........ the copy saved on the phone; if there is none,
 *                                               Discover anyway (it shows "offline" + Try again
 *                                               and re-checks onboarding when the profile loads).
 *                                               NEVER signs the user out for a network problem.
 *   Session no longer valid (account deleted/
 *   disabled, checked with Firebase) ......... signed out -> Get Started
 *
 * Only the profile is waited for; restaurants, pictures etc. load on Discover itself.
 */
final class LaunchRouter {

    private static final String TAG = "AsanniLaunch";
    /** [TIMING] After this long without a server answer, use the phone's saved copy instead. */
    static final long SERVER_WAIT_MS = 4_000L;
    /** [TIMING] Absolute limit: a decision is always made by then (no endless loading). */
    static final long HARD_LIMIT_MS = 7_000L;

    enum Kind { SIGNED_OUT, PROFILE, PROFILE_UNKNOWN, NEEDS_LOGIN_SCREEN }

    static final class Decision {
        final Kind kind;
        final ProfileData profile;

        Decision(Kind kind, ProfileData profile) {
            this.kind = kind;
            this.profile = profile;
        }
    }

    interface Callback {
        void onDecision(Decision decision);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final UserProfileRepository profiles = new UserProfileRepository();
    private Callback callback;
    private boolean decided;
    private String uid;

    private final Runnable serverTimeout = this::useCachedCopy;
    private final Runnable hardLimit = () -> decide(new Decision(Kind.PROFILE_UNKNOWN, null));

    /** Starts the decision. The callback runs once, on the main thread. */
    void start(Callback cb) {
        callback = cb;
        decided = false;
        final FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            decide(new Decision(Kind.SIGNED_OUT, null));
            return;
        }
        uid = user.getUid();
        main.postDelayed(serverTimeout, SERVER_WAIT_MS);
        main.postDelayed(hardLimit, HARD_LIMIT_MS);
        profiles.loadProfile(uid, new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(ProfileData profile) {
                decide(new Decision(Kind.PROFILE, profile));
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure failure, Exception error) {
                if (decided) return;
                if (failure == UserProfileRepository.Failure.UNKNOWN && error == null) {
                    createMissingProfile(user); // signed in, but no users/{uid} document yet
                } else if (failure == UserProfileRepository.Failure.PERMISSION_DENIED) {
                    checkSessionStillValid(user);
                } else {
                    useCachedCopy();
                }
            }
        });
    }

    /** Stops timers; no callback after this (screen closed). */
    void cancel() {
        decided = true;
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------------------------------

    private void createMissingProfile(FirebaseUser user) {
        profiles.ensureProfile(user, new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(ProfileData profile) {
                decide(new Decision(Kind.PROFILE, profile));
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure failure, Exception error) {
                // New account and offline: the existing login screen shows the retry / offline page.
                decide(new Decision(Kind.NEEDS_LOGIN_SCREEN, null));
            }
        });
    }

    /** PERMISSION_DENIED can mean an expired/revoked session. Ask Firebase Auth before acting. */
    private void checkSessionStillValid(FirebaseUser user) {
        user.reload()
                .addOnSuccessListener(unused -> useCachedCopy()) // session fine: likely rules; don't sign out
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseAuthInvalidUserException) {
                        Log.i(TAG, "Session no longer valid; signing out");
                        FirebaseAuth.getInstance().signOut();
                        decide(new Decision(Kind.SIGNED_OUT, null));
                    } else {
                        useCachedCopy(); // network problem: never sign out for that
                    }
                });
    }

    private void useCachedCopy() {
        if (decided || uid == null) return;
        profiles.loadCachedProfile(uid, new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(ProfileData profile) {
                decide(new Decision(Kind.PROFILE, profile));
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure failure, Exception error) {
                decide(new Decision(Kind.PROFILE_UNKNOWN, null));
            }
        });
    }

    private void decide(Decision d) {
        if (decided) return;
        decided = true;
        main.removeCallbacksAndMessages(null);
        Log.i(TAG, "Launch decision: " + d.kind);
        final Callback cb = callback;
        main.post(() -> {
            if (cb != null) cb.onDecision(d);
        });
    }

    /** Opens the destination for a decision and clears the back stack. */
    static void apply(Activity from, Decision d) {
        switch (d.kind) {
            case SIGNED_OUT:
                AppNavigator.openGetStartedFromLaunch(from);
                break;
            case NEEDS_LOGIN_SCREEN:
                AppNavigator.openGoogleLoginFromLaunch(from);
                break;
            case PROFILE:
                OnboardingRouter.route(from, d.profile); // incomplete -> missing step; complete -> Discover
                break;
            case PROFILE_UNKNOWN:
            default:
                AppNavigator.openHome(from); // Discover handles "offline" and re-checks onboarding
                break;
        }
    }
}