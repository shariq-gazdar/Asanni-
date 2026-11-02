package com.asanni.app.profile;

import android.content.Intent;
import android.os.Build;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;

/**
 * Opens Android's photo picker to choose a profile picture. NO storage permission is needed:
 * the system picker only gives the app the one image the user chose.
 *
 * Location: app/src/main/java/com/asanni/app/profile/AvatarPicker.java  (NEW in phase 4)
 *
 *   Android 13+ (and Android 11/12 with the picker update): the system Photo Picker
 *   Older devices: the system file chooser (Storage Access Framework), images only
 */
public final class AvatarPicker {

    private AvatarPicker() {}

    public static Intent createIntent() {
        if (isPhotoPickerAvailable()) {
            Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("image/*");
            return i;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        return i;
    }

    private static boolean isPhotoPickerAvailable() {
        if (Build.VERSION.SDK_INT >= 33) return true;
        return Build.VERSION.SDK_INT >= 30
                && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
    }
}