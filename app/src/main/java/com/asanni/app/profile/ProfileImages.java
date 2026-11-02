package com.asanni.app.profile;

import android.content.Context;
import android.widget.ImageView;

import com.asanni.app.R;
import com.asanni.app.auth.ProfileData;
import com.asanni.app.auth.UserProfile;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One place that decides WHICH profile picture to show and which store saves new ones.
 * Used by Registration, Info, Settings and Home, so they always agree.
 *
 * Location: app/src/main/java/com/asanni/app/profile/ProfileImages.java  (NEW in phase 5)
 *
 * Order: custom picture on this phone -> old Firebase Storage picture (if it still loads)
 *        -> Google photo -> initials -> default ASANNI avatar.
 * A custom picture is never replaced by the Google photo at sign-in (sign-in doesn't touch it).
 */
public final class ProfileImages {

    private ProfileImages() {}

    /** [CONFIG] The picture store in use. Swap here to move to a cloud provider later. */
    public static ProfileImageStore store(Context c) {
        return new LocalProfileImageStore(c);
    }

    public static void show(ImageView view, ProfileData p, FirebaseUser user, String username) {
        Context c = view.getContext();
        File local = null;
        List<String> urls = new ArrayList<>();
        if (p != null && p.isLocalPhoto()) {
            local = LocalProfileImageStore.find(c, p.uid, p.photoLocalId);
        }
        if (p != null && UserProfile.PHOTO_SOURCE_CUSTOM.equals(p.photoSource) && p.photoUrl != null) {
            urls.add(p.photoUrl); // legacy Firebase Storage upload; skipped if it no longer loads
        }
        String google = googlePhoto(p, user);
        if (google != null && !urls.contains(google)) urls.add(google);
        AvatarLoader.load(view, local, urls, InitialsDrawable.avatarFallback(c, username));
    }

    /** The Google account photo (Auth is freshest; the profile copy covers older data). */
    static String googlePhoto(ProfileData p, FirebaseUser user) {
        if (user != null && user.getPhotoUrl() != null) return user.getPhotoUrl().toString();
        if (p != null && p.photoUrl != null && !UserProfile.PHOTO_SOURCE_CUSTOM.equals(p.photoSource)) {
            return p.photoUrl;
        }
        return null;
    }

    /** Short line under the picture explaining what is shown (honest about on-device storage). */
    public static int hint(Context c, ProfileData p, FirebaseUser user) {
        if (p != null && p.isLocalPhoto()) {
            return LocalProfileImageStore.find(c, p.uid, p.photoLocalId) != null
                    ? R.string.profile_picture_hint_local
                    : R.string.profile_picture_hint_local_missing;
        }
        if (p != null && p.hasCustomPhoto()) return R.string.profile_picture_hint_custom;
        return googlePhoto(p, user) != null ? R.string.profile_picture_hint_google
                : R.string.profile_picture_hint_none;
    }
}