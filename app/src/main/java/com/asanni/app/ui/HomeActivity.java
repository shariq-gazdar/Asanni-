package com.asanni.app.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfileRepository;
import com.google.firebase.auth.FirebaseUser;

/**
 * Home: four sections with a bottom navigation bar (Discover, Map, Saved, Profile).
 *
 * Location: app/src/main/java/com/asanni/app/ui/HomeActivity.java  (REWRITTEN in phase 6)
 *
 *   Discover  restaurant discovery (DiscoverSection); opens by default
 *   Map       phase 7: opens the full-screen MapActivity (no bottom navigation there);
 *             its back arrow / Back returns here with Discover exactly as it was
 *   Saved     intentionally blank in phase 6 (future saved list goes into @id/section_saved)
 *   Profile   opens the EXISTING Settings page; coming back re-selects the previous section
 *
 * Sections are views inside this one screen (no new libraries, no fragments), so switching tabs
 * never recreates the screen or reloads Discover. The selected section survives rotation /
 * recreation (saved state). Back: from Map/Saved returns to Discover; from Discover leaves.
 * The intro animation is never involved here (it only runs from the launcher).
 */
public class HomeActivity extends AsanniActivity implements DiscoverSection.Host {

    static final int TAB_DISCOVER = 0;
    static final int TAB_MAP = 1;
    static final int TAB_SAVED = 2;
    static final int TAB_PROFILE = 3;
    private static final String STATE_TAB = "home_tab";
    /** [ANIMATION] Section cross-fade. */
    private static final long SECTION_FADE_MS = 160L;

    private final UserProfileRepository profiles = new UserProfileRepository();
    private View[] sections;
    private BottomNavBar nav;
    private DiscoverSection discover;
    private int current = TAB_DISCOVER;
    private boolean started;
    private ProfileData pendingOnboarding; // incomplete profile found while in the background
    private boolean navigating; // prevents opening Settings / the location form twice

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
        applyInsets();

        sections = new View[]{findViewById(R.id.section_discover), findViewById(R.id.section_map),
                findViewById(R.id.section_saved)};
        nav = new BottomNavBar(new View[]{findViewById(R.id.nav_discover), findViewById(R.id.nav_map),
                findViewById(R.id.nav_saved), findViewById(R.id.nav_profile)}, this::onTabSelected);
        discover = new DiscoverSection(this, findViewById(R.id.section_discover));

        if (savedInstanceState != null) {
            int saved = savedInstanceState.getInt(STATE_TAB, TAB_DISCOVER);
            current = saved == TAB_SAVED ? TAB_SAVED : TAB_DISCOVER; // Map/Profile are their own screens
        }
        showSection(current, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        updateDiscoverActive();
        if (pendingOnboarding != null) {
            ProfileData p = pendingOnboarding;
            pendingOnboarding = null;
            OnboardingRouter.route(this, p);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        navigating = false;
        if (nav == null) return;
        // Back from Settings / Map: the previous tab is selected again.
        nav.select(current, nav.selected() == TAB_PROFILE || nav.selected() == TAB_MAP);
        loadProfile();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        updateDiscoverActive();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discover != null) discover.release();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_TAB, current);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (OfflineOverlay.isShowing(this)) {
            OfflineOverlay.hide(this);
            return;
        }
        if (current == TAB_DISCOVER && discover != null && discover.handleBack()) return;
        if (current != TAB_DISCOVER) {
            showSection(TAB_DISCOVER, true);
            return;
        }
        super.onBackPressed();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AppNavigator.REQUEST_EDIT_ADDRESS && resultCode == RESULT_OK && discover != null) {
            discover.onLocationChanged(); // the profile itself is re-read in onResume
        }
    }

    // ------------------------------------------------------------------ DiscoverSection.Host

    @Override
    public HomeActivity activity() {
        return this;
    }

    /** [NAVIGATION] Location arrow -> the existing city/area form (edit mode), back to Discover. */
    @Override
    public void openLocationPicker(String cityId, String areaId) {
        if (navigating) return;
        navigating = true;
        AppNavigator.openEditAddress(this, cityId, areaId);
    }

    @Override
    public void reloadProfile() {
        loadProfile();
    }

    // ------------------------------------------------------------------ tabs

    private void onTabSelected(int tab) {
        if (tab == TAB_MAP) {
            // [NAVIGATION] Map tab -> full-screen map (its own screen; Back returns to Discover).
            if (navigating) return;
            navigating = true;
            nav.select(TAB_MAP, true);
            discover.openMap();
            return;
        }
        if (tab == TAB_PROFILE) {
            // [NAVIGATION] Profile tab -> the existing Settings page (not a duplicate).
            if (navigating) return;
            navigating = true;
            nav.select(TAB_PROFILE, true);
            AppNavigator.openSettings(this);
            return;
        }
        if (tab == current) {
            if (tab == TAB_DISCOVER) discover.scrollToTop(); // re-tap: back to the top
            return;
        }
        showSection(tab, true);
    }

    private void showSection(int tab, boolean animate) {
        int previous = current;
        current = tab;
        nav.select(tab, animate);
        boolean motion = animate && Settings.Global.getFloat(getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        for (int i = 0; i < sections.length; i++) {
            final View s = sections[i];
            s.animate().cancel();
            if (i == tab) {
                s.setVisibility(View.VISIBLE);
                if (motion) {
                    s.setAlpha(0f);
                    s.animate().alpha(1f).setDuration(SECTION_FADE_MS).start();
                } else {
                    s.setAlpha(1f);
                }
            } else if (i == previous && motion && s.getVisibility() == View.VISIBLE) {
                s.animate().alpha(0f).setDuration(SECTION_FADE_MS / 2)
                        .withEndAction(() -> s.setVisibility(View.GONE)).start();
            } else {
                s.setVisibility(View.GONE);
            }
        }
        updateDiscoverActive();
    }

    /** Discover listens to Firestore only while it is visible and Home is on screen. */
    private void updateDiscoverActive() {
        if (discover != null) discover.setActive(started && current == TAB_DISCOVER);
    }

    // ------------------------------------------------------------------ profile

    /** Profile known: continue unfinished onboarding (only when visible), else show Discover. */
    private void onProfileLoaded(ProfileData p, FirebaseUser user) {
        if (p.status() != com.asanni.app.auth.UserProfile.Status.COMPLETE) {
            if (started) OnboardingRouter.route(this, p);
            else pendingOnboarding = p; // Android blocks starting screens from the background
            return;
        }
        discover.onProfile(p, user);
    }

    private void loadProfile() {
        final FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            return;
        }
        profiles.loadProfile(user.getUid(), new UserProfileRepository.ProfileCallback() {
            @Override
            public void onProfileReady(ProfileData p) {
                if (isFinishing() || isDestroyed()) return;
                // Phase 7: launch may open Home before the profile was known (offline). If it turns
                // out onboarding isn't finished, continue it now (never loops: only when incomplete).
                onProfileLoaded(p, user);
            }

            @Override
            public void onProfileFailed(UserProfileRepository.Failure f, Exception e) {
                if (isFinishing() || isDestroyed()) return;
                if (f == UserProfileRepository.Failure.UNKNOWN && e == null) {
                    // Signed in but no profile document yet (new account, created while offline):
                    // create it with the existing ensureProfile() (never overwrites) and continue onboarding.
                    profiles.ensureProfile(user, new UserProfileRepository.ProfileCallback() {
                        @Override public void onProfileReady(ProfileData p) {
                            if (!isFinishing() && !isDestroyed()) onProfileLoaded(p, user);
                        }

                        @Override public void onProfileFailed(UserProfileRepository.Failure f2, Exception e2) {
                            if (!isFinishing() && !isDestroyed()) discover.onProfileUnavailable(user);
                        }
                    });
                    return;
                }
                // No profile copy at all (e.g. offline on a fresh install): keep Home usable with the
                // Google photo; Discover shows an offline/error message with Try again.
                discover.onProfileUnavailable(user);
            }
        });
    }

    // ------------------------------------------------------------------ system bars

    /**
     * Content starts below the status bar / display cutout; the bottom navigation background
     * reaches the bottom edge with its items above the gesture/navigation bar.
     */
    @SuppressWarnings("deprecation")
    private void applyInsets() {
        final View root = findViewById(R.id.home_root);
        final View sectionsView = findViewById(R.id.home_sections);
        final View navView = findViewById(R.id.home_nav);
        final int navPadBottom = navView.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int navBottom;   // system navigation / gesture bar (never the keyboard)
            int imeBottom;   // keyboard height measured from the screen bottom (0 when closed)
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                navBottom = bars.bottom;
                left = bars.left;
                right = bars.right;
                imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                navBottom = insets.getStableInsetBottom(); // stable = without the keyboard
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                int all = insets.getSystemWindowInsetBottom();
                imeBottom = all > navBottom ? all : 0;
            }
            v.setPadding(left, 0, right, 0);
            navView.setPadding(navView.getPaddingLeft(), navView.getPaddingTop(),
                    navView.getPaddingRight(), navPadBottom + navBottom);
            // Keyboard open: let Discover scroll its last results above the keyboard
            // (the bottom navigation, including its inset padding, already covers navHeight).
            int navHeight = navView.getHeight() > 0 ? navView.getHeight() : 0;
            sectionsView.setPadding(0, top, 0, Math.max(0, imeBottom - navHeight));
            return insets;
        });
        root.requestApplyInsets();
    }
}