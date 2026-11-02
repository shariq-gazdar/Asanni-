package com.asanni.app.profile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small image loader for profile pictures, so no extra image library is needed.
 *
 * Location: app/src/main/java/com/asanni/app/profile/AvatarLoader.java  (MODIFIED in phase 5)
 *
 * Shows the fallback (initials / default avatar) at once, then tries, in order:
 *   1. an on-device file (custom picture; works offline)
 *   2. each URL (e.g. an old Firebase Storage picture, then the Google photo)
 * and shows the first one that loads. Missing, corrupt or unreachable images simply fall
 * through to the next source, and finally keep the fallback. Never crashes on a bad image.
 */
public final class AvatarLoader {

    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AvatarLoader() {}

    /** Convenience: URLs only. */
    public static void load(ImageView view, String url, Drawable fallback) {
        List<String> urls = new ArrayList<>();
        if (url != null) urls.add(url);
        load(view, null, urls, fallback);
    }

    /**
     * @param file  on-device picture to try first (may be null)
     * @param urls  remote pictures to try next, in order (may be empty)
     */
    public static void load(ImageView view, File file, List<String> urls, Drawable fallback) {
        view.setImageDrawable(fallback);
        final String key = (file != null ? file.getAbsolutePath() + "@" + file.lastModified() : "")
                + "|" + urls;
        view.setTag(key);

        String cachedKey = firstCached(file, urls);
        if (cachedKey != null) {
            view.setImageBitmap(CACHE.get(cachedKey));
            return;
        }
        if (file == null && urls.isEmpty()) return;

        EXECUTOR.execute(() -> {
            Bitmap bmp = null;
            if (file != null) {
                bmp = decodeFile(file);
                if (bmp != null) CACHE.put(fileKey(file), bmp);
            }
            for (int i = 0; bmp == null && i < urls.size(); i++) {
                String u = sharperGooglePhoto(urls.get(i));
                if (!u.startsWith("https://")) continue;
                bmp = download(u);
                if (bmp != null) CACHE.put(u, bmp);
            }
            final Bitmap result = bmp;
            MAIN.post(() -> {
                if (result != null && key.equals(view.getTag())) view.setImageBitmap(result);
            });
        });
    }

    /** Forget a cached on-device picture (after it was replaced). */
    public static void clearMemoryCache() {
        CACHE.evictAll();
    }

    private static String firstCached(File file, List<String> urls) {
        if (file != null && CACHE.get(fileKey(file)) != null) return fileKey(file);
        if (file != null) return null; // file not decoded yet: it has priority over URLs
        for (String u : urls) {
            String k = sharperGooglePhoto(u);
            if (CACHE.get(k) != null) return k;
        }
        return null;
    }

    private static String fileKey(File f) {
        return "file:" + f.getAbsolutePath() + "@" + f.lastModified();
    }

    /** Google profile photos come as 96 px by default; ask for 256 px for a sharp avatar. */
    static String sharperGooglePhoto(String url) {
        if (url != null && url.contains("googleusercontent.com") && url.matches(".*=s\\d+-c$")) {
            return url.replaceAll("=s\\d+-c$", "=s256-c");
        }
        return url == null ? "" : url;
    }

    private static Bitmap decodeFile(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o); // null if corrupt
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200 || c.getContentLength() > MAX_DOWNLOAD_BYTES) return null;
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null; // e.g. offline, or an old Firebase Storage URL that no longer works
        } finally {
            if (c != null) c.disconnect();
        }
    }
}