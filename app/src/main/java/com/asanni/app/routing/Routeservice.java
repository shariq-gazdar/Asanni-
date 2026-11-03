package com.asanni.app.routing;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ONLY class screens should call for routes.
 *
 * Location: app/src/main/java/com/asanni/app/routing/RouteService.java  (NEW)
 *
 * - Tries providers in RouteConfig.PROVIDER_ORDER; first success wins.
 * - Never leaves the screen empty: if all fail, returns a straight-line Route (approximate = true).
 * - Caches results (~100 m / 10 min) so re-opening the same restaurant costs no API quota.
 * - Network runs on a background thread; the callback always runs on the MAIN thread.
 *
 * Usage (e.g. when the diner taps a restaurant marker - NOT on every GPS update):
 *
 *   routeRequest = RouteService.get().request(userLat, userLng, r.lat, r.lng,
 *           RouteConfig.Profile.DRIVING, route -> showRoute(route));
 *
 *   // in onDestroyView()/onDestroy():
 *   if (routeRequest != null) routeRequest.cancel();
 */
public final class Routeservice {

    public interface Callback {
        /** Main thread. route is never null. */
        void onRoute(Route route);
    }

    /** Returned by request(); cancel() stops the callback from firing (e.g. screen closed). */
    public static final class Request {
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    private static volatile Routeservice instance;

    public static Routeservice get() {
        if (instance == null) {
            synchronized (Routeservice.class) {
                if (instance == null) instance = new Routeservice();
            }
        }
        return instance;
    }

    private final List<RouteProvider> providers = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, CacheEntry> cache =
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) {
                    return size() > Routeconfig.CACHE_SIZE;
                }
            };

    private Routeservice() {
        for (Routeconfig.ProviderId id : Routeconfig.PROVIDER_ORDER) {
            switch (id) {
                case GRAPHHOPPER:      providers.add(new GraphHopperRouteProvider()); break;
                case OSRM:             providers.add(new OsrmRouteProvider()); break;
                case OPENROUTESERVICE: providers.add(new OrsRouteProvider()); break;
            }
        }
    }

    public Request request(double fromLat, double fromLng, double toLat, double toLng,
                           Routeconfig.Profile profile, Callback callback) {
        Request req = new Request();
        executor.execute(() -> {
            if (req.isCancelled()) return;
            Route route = compute(fromLat, fromLng, toLat, toLng, profile);
            main.post(() -> {
                if (!req.isCancelled()) callback.onRoute(route);
            });
        });
        return req;
    }

    // ------------------------------------------------------------------ internals (background thread)

    private Route compute(double fromLat, double fromLng, double toLat, double toLng,
                          Routeconfig.Profile profile) {
        String key = cacheKey(fromLat, fromLng, toLat, toLng, profile);
        Route cached = fromCache(key);
        if (cached != null) return cached;

        if (valid(fromLat, fromLng) && valid(toLat, toLng)) {
            for (RouteProvider p : providers) {
                if (!p.isConfigured() || !p.supports(profile)) continue;
                try {
                    Route r = p.route(fromLat, fromLng, toLat, toLng, profile);
                    Log.i(Routeconfig.LOG_TAG, "Route from " + p.name() + ": " + r.distanceText());
                    putCache(key, r);
                    return r;
                } catch (Exception e) {
                    Log.w(Routeconfig.LOG_TAG, p.name() + " failed, trying next: " + e.getMessage());
                }
            }
        }
        Log.w(Routeconfig.LOG_TAG, "No routing service answered; showing straight line");
        return straightLine(fromLat, fromLng, toLat, toLng); // not cached, so the next tap retries
    }

    private static Route straightLine(double fromLat, double fromLng, double toLat, double toLng) {
        List<double[]> pts = new ArrayList<>(2);
        pts.add(new double[]{fromLat, fromLng});
        pts.add(new double[]{toLat, toLng});
        return new Route(haversineMeters(fromLat, fromLng, toLat, toLng), -1, pts,
                "straight line", "Map data © OpenStreetMap contributors", true);
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static boolean valid(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(lat == 0 && lng == 0);
    }

    /** 3 decimals ≈ 110 m, so small GPS jitter still hits the cache. */
    private static String cacheKey(double a, double b, double c, double d, Routeconfig.Profile p) {
        return String.format(Locale.US, "%.3f,%.3f>%.3f,%.3f:%s", a, b, c, d, p.name());
    }

    private synchronized Route fromCache(String key) {
        CacheEntry e = cache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.time > Routeconfig.CACHE_TTL_MS) {
            cache.remove(key);
            return null;
        }
        return e.route;
    }

    private synchronized void putCache(String key, Route route) {
        cache.put(key, new CacheEntry(route, System.currentTimeMillis()));
    }

    private static final class CacheEntry {
        final Route route;
        final long time;
        CacheEntry(Route route, long time) { this.route = route; this.time = time; }
    }
}