package com.asanni.app.ui;

import android.os.Bundle;
import android.widget.ImageView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.profile.ProfileImages;
import com.google.firebase.auth.FirebaseUser;

/**
 * Home: blank for now, with the user's profile picture (top-right) as the way into Settings.
 *
 * Location: app/src/main/java/com/asanni/app/ui/HomeActivity.java  (MODIFIED in phase 5:
 *           menu button + drawer replaced by the profile button)
 *
 * The picture refreshes every time Home is shown (so a change made in Info appears at once) and
 * works offline: the on-device picture or the cached profile is used; the button always works,
 * even if no picture can be loaded (then initials / the default avatar are shown).
 */
public class HomeActivity extends AsanniActivity {

    private final UserProfileRepository profiles = new UserProfileRepository();
    private ImageView avatar;
    private boolean navigating; // prevents opening Settings twice from fast taps

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_home);
        SystemBarsHelper.apply(this, findViewById(R.id.home_root));
        avatar = findViewById(R.id.home_avatar);
        findViewById(R.id.button_profile).setOnClickListener(v -> openSettings());
        new EntranceAnimator(getContentResolver()).attachPressFeedback(findViewById(R.id.button_profile));
    }

    @Override
    protected void onResume() {
        super.onResume();
        navigating = false;
        if (avatar != null) refreshAvatar();
    }

    /** Last profile shown; avoids re-showing the placeholder on every resume. */
    private ProfileData lastProfile;

    private void refreshAvatar() {
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        // First time: show something right away (Google photo / initials). After that keep the
        // current picture until the profile arrives, so it doesn't flicker on every return.
        if (lastProfile == null) ProfileImages.show(avatar, null, user, user.getDisplayName());
        profiles.loadProfile(user.getUid(), new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(ProfileData p) {
                if (isFinishing() || isDestroyed()) return;
                lastProfile = p;
                ProfileImages.show(avatar, p, user, p.username());
                findViewById(R.id.button_profile).setContentDescription(
                        getString(R.string.home_profile_button_named, p.username()));
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure f, Exception e) {
                // Keep the Google photo / initials; Home itself doesn't need the network.
            }
        });
    }

    /** [NAVIGATION] Profile picture -> the existing Settings page. */
    private void openSettings() {
        if (navigating) return;
        navigating = true;
        AppNavigator.openSettings(this);
    }
}