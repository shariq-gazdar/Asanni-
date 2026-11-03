package com.asanni.app.restaurants;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads restaurant logos/photos off the main thread, with a memory cache so the same image is
 * never downloaded twice in a session. No image library needed.
 *
 * Location: app/src/main/java/com/asanni/app/restaurants/RestaurantImageLoader.java  (NEW in phase 6)
 *
 * Accepts what the desktop app may store (see Restaurant.image()):
 *   https://...            downloaded (max 6 MB), decoded at the size it is shown
 *   data:image/...;base64  decoded locally
 *   plain base64 text      decoded locally
 * Anything else, or a broken/unreachable image, keeps the placeholder already shown. No crash.
 */
public final class RestaurantImageLoader {

    private static final String TAG = "AsanniRestaurantImg";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Sources that failed this session: not retried on every scroll. */
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<String, List<Waiting>> PENDING = new HashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        /** Called on the main thread when a real image replaced the placeholder. */
        void onLoaded(ImageView view);
    }

    private RestaurantImageLoader() {}

    /**
     * Shows the placeholder (centred, never blown up), then the real image (center-crop; the
     * listener may change that, e.g. to show a logo whole).
     * @param src         image reference from the restaurant document (may be null)
     * @param targetPx    the larger side the image is shown at (decoded no bigger than ~2x this)
     * @param placeholder drawable resource shown until (and unless) the real image loads
     */
    public static void load(final ImageView view, final String src, final int targetPx,
                            int placeholder, final Listener listener) {
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(placeholder);
        final String key = src == null ? null : cacheKey(src, targetPx);
        view.setTag(key);
        if (key == null) return;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            show(view, cached, listener);
            return;
        }
        synchronized (FAILED) {
            if (FAILED.contains(key)) return;
        }
        // Several views asking for the same image while it downloads share ONE download.
        synchronized (PENDING) {
            List<Waiting> waiting = PENDING.get(key);
            if (waiting != null) {
                waiting.add(new Waiting(view, listener));
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(new Waiting(view, listener));
            PENDING.put(key, waiting);
        }
        EXECUTOR.execute(() -> {
            Bitmap bmp = null;
            try {
                byte[] bytes = bytes(src);
                if (bytes != null) bmp = decode(bytes, Math.max(targetPx, 64));
            } catch (OutOfMemoryError | RuntimeException e) {
                bmp = null;
            }
            if (bmp != null) {
                CACHE.put(key, bmp);
            } else {
                synchronized (FAILED) {
                    FAILED.add(key);
                }
                Log.d(TAG, "Image not loaded (" + describe(src) + ")");
            }
            final Bitmap result = bmp;
            final List<Waiting> done;
            synchronized (PENDING) {
                done = PENDING.remove(key);
            }
            MAIN.post(() -> {
                if (result == null || done == null) return;
                for (Waiting w : done) {
                    ImageView v = w.view.get();
                    // Still showing this restaurant (views are reused for others while scrolling)?
                    if (v != null && key.equals(v.getTag())) show(v, result, w.listener);
                }
            });
        });
    }

    private static void show(ImageView view, Bitmap bmp, Listener listener) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(bmp);
        if (listener != null) listener.onLoaded(view);
    }

    private static final class Waiting {
        final WeakReference<ImageView> view;
        final Listener listener;

        Waiting(ImageView view, Listener listener) {
            this.view = new WeakReference<>(view);
            this.listener = listener;
        }
    }

    /** Forget failures (e.g. after the connection returns). */
    public static void clearFailures() {
        synchronized (FAILED) {
            FAILED.clear();
        }
    }

    private static String cacheKey(String src, int px) {
        // Base64 can be huge: key on its hash + length instead of the whole text.
        String id = src.startsWith("https://") ? src : "b64:" + src.length() + ":" + src.hashCode();
        return id + "@" + px;
    }

    private static String describe(String src) {
        return src.startsWith("https://") ? "url" : "base64, " + src.length() + " chars";
    }

    private static byte[] bytes(String src) {
        if (src.startsWith("https://")) return download(src);
        String b64 = src;
        if (src.startsWith("data:image/")) {
            int comma = src.indexOf(',');
            if (comma < 0 || !src.substring(0, comma).contains(";base64")) return null;
            b64 = src.substring(comma + 1);
        }
        try {
            return Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200 || c.getContentLength() > MAX_BYTES) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null; // offline, bad URL, server error: keep the placeholder
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Decodes at roughly the displayed size (saves memory on low-end phones). */
    private static Bitmap decode(byte[] data, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null; // not an image
        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / (sample * 2) >= targetPx * 2) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}