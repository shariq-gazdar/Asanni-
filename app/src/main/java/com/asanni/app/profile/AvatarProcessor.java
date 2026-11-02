package com.asanni.app.profile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Turns any picked photo (even a 20 MB camera image) into a small square avatar:
 * downsampled while decoding (low memory), rotated upright, center-cropped to a square,
 * scaled to AVATAR_SIZE_PX and compressed as JPEG. Run it OFF the main thread.
 * It's shown as a circle in the app (the square keeps the image usable elsewhere later).
 *
 * Location: app/src/main/java/com/asanni/app/profile/AvatarProcessor.java  (NEW in phase 4)
 */
public final class AvatarProcessor {

    // ---------------- [CONFIG] avatar output ----------------
    public static final int AVATAR_SIZE_PX = 512;
    private static final int JPEG_QUALITY = 85;
    /** Refuse anything still larger than this after compression (matches storage.rules). */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    // --------------------------------------------------------

    /** The processed avatar: JPEG bytes for upload + a bitmap for the preview. */
    public static final class Result {
        public final byte[] jpeg;
        public final Bitmap preview;

        Result(byte[] jpeg, Bitmap preview) {
            this.jpeg = jpeg;
            this.preview = preview;
        }
    }

    private AvatarProcessor() {}

    public static Result process(ContentResolver resolver, Uri uri) throws IOException {
        // 1) Read only the size.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(resolver, uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not an image");

        // 2) Decode at a reduced size (power of two) that is still >= the avatar size.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int shortSide = Math.min(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (shortSide / (sample * 2) >= AVATAR_SIZE_PX) sample *= 2;
        opts.inSampleSize = sample;
        Bitmap decoded;
        try (InputStream in = open(resolver, uri)) {
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) throw new IOException("Could not decode image");

        // 3) Rotate upright using the photo's EXIF orientation.
        int rotation = 0;
        try (InputStream in = open(resolver, uri)) {
            int o = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (IOException | RuntimeException ignored) {
            // No EXIF (e.g. PNG): keep as is.
        }

        // 4) Center-crop to a square and scale to the avatar size in one step.
        int side = Math.min(decoded.getWidth(), decoded.getHeight());
        int x = (decoded.getWidth() - side) / 2;
        int y = (decoded.getHeight() - side) / 2;
        Matrix m = new Matrix();
        float scale = (float) AVATAR_SIZE_PX / side;
        m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap square = Bitmap.createBitmap(decoded, x, y, side, side, m, true);
        if (square != decoded) decoded.recycle();

        // 5) JPEG.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        byte[] bytes = out.toByteArray();
        if (bytes.length > MAX_BYTES) throw new IOException("Image too large after compression");
        return new Result(bytes, square);
    }

    private static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IOException("Cannot open image");
        return in;
    }
}