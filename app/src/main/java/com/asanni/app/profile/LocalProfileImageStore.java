package com.asanni.app.profile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.asanni.app.auth.UserProfileRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps custom profile pictures in the app's PRIVATE storage on this phone. No paid service,
 * no network, and no storage permission. (Other apps can't read these files.)
 *
 * Location: app/src/main/java/com/asanni/app/profile/LocalProfileImageStore.java  (NEW in phase 5)
 *
 *   File:       <app files>/profile_pictures/<uid>/avatar_<time>.jpg
 *   Firestore:  users/{uid}.photoSource = "local", photoLocalId = "avatar_<time>.jpg"
 *               (only the file NAME; photoUrl keeps the Google photo for other phones)
 *
 * LIMITS (also explained to the user): the picture does NOT sync to other phones and is removed
 * if the app is uninstalled or its data is cleared. Other phones show the Google photo instead.
 *
 * Safe order: write the new file -> save the reference in Firestore -> delete older files.
 * If Firestore rejects the reference, the new file is deleted and the old picture stays.
 */
public final class LocalProfileImageStore implements ProfileImageStore {

    private static final String TAG = "AsanniLocalImages";
    static final String FOLDER = "profile_pictures";
    /** [CONFIG] Wait this long for Firestore to confirm before reporting "saved on this phone". */
    private static final long SERVER_CONFIRM_TIMEOUT_MS = 8_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    public LocalProfileImageStore(Context context) {
        appContext = context.getApplicationContext();
    }

    /** The file for a stored picture, or null if it isn't on this phone (other phone / reinstall). */
    public static File find(Context c, String uid, String localId) {
        if (uid == null || localId == null || !isValidId(localId)) return null;
        File f = new File(userDir(c, uid), localId);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    static boolean isValidId(String id) {
        return id.matches("avatar_\\d+\\.jpg"); // never a path: no "/" or ".."
    }

    private static File userDir(Context c, String uid) {
        return new File(new File(c.getApplicationContext().getFilesDir(), FOLDER), uid);
    }

    @Override
    public void saveCustomPhoto(String uid, byte[] jpeg, Callback callback) {
        final String id = "avatar_" + System.currentTimeMillis() + ".jpg";
        IO.execute(() -> {
            File dir = userDir(appContext, uid);
            File tmp = new File(dir, id + ".tmp");
            File target = new File(dir, id);
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdirs failed");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(jpeg);
                    out.getFD().sync();
                }
                if (!tmp.renameTo(target)) throw new IOException("rename failed");
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                Log.w(TAG, "Could not write avatar", e);
                main.post(() -> callback.onFailed(Failure.WRITE_FAILED, e));
                return;
            }
            main.post(() -> saveReference(uid, id, target, callback));
        });
    }

    private void saveReference(String uid, String id, File newFile, Callback callback) {
        final boolean[] reported = {false};
        // Offline, Firestore keeps the update queued and only confirms later: don't wait forever,
        // and say honestly that the profile update is still pending.
        Runnable timeout = () -> {
            if (reported[0]) return;
            reported[0] = true;
            callback.onSaved(false);
        };
        main.postDelayed(timeout, SERVER_CONFIRM_TIMEOUT_MS);

        new UserProfileRepository().saveLocalPhoto(uid, id, new UserProfileRepository.SaveCallback() {
            @Override
            public void onSaved() {
                main.removeCallbacks(timeout);
                deleteOthers(uid, id);
                if (!reported[0]) {
                    reported[0] = true;
                    callback.onSaved(true);
                }
            }

            @Override
            public void onSaveFailed(UserProfileRepository.Failure f, Exception e) {
                main.removeCallbacks(timeout);
                // Rejected: remove the new file so the previous picture stays in use.
                IO.execute(() -> {
                    //noinspection ResultOfMethodCallIgnored
                    newFile.delete();
                });
                if (!reported[0]) {
                    reported[0] = true;
                    callback.onFailed(Failure.PROFILE_REJECTED, e);
                }
            }
        });
    }

    /** After the new picture is confirmed: remove this user's older pictures (and stray temp files). */
    private void deleteOthers(String uid, String keepId) {
        IO.execute(() -> {
            File[] files = userDir(appContext, uid).listFiles();
            if (files == null) return;
            long keep = stamp(keepId);
            for (File f : files) {
                String n = f.getName();
                // Only OLDER pictures and leftover temp files: a newer save that is still waiting
                // for Firestore must not lose its file.
                if (n.endsWith(".tmp") || (!n.equals(keepId) && stamp(n) < keep)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        });
    }

    /** avatar_1730000000000.jpg -> 1730000000000; anything else -> -1 (treated as old). */
    private static long stamp(String name) {
        try {
            return Long.parseLong(name.substring("avatar_".length(), name.length() - ".jpg".length()));
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}