package com.asanni.app.map;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Real road routes from openrouteservice (https://openrouteservice.org), phase 7.
 *
 * Location: app/src/main/java/com/asanni/app/map/OpenRouteServiceClient.java  (NEW in phase 7)
 *
 * What it can and can't do (stated honestly in the UI too):
 *   + road routes for car, walking and cycling, with distance and estimated travel time
 *   + up to 3 alternative routes for trips up to 100 km; the fastest is recommended
 *   - NO live traffic: times are estimates from road types and speed limits
 * Nothing is drawn unless the provider returned a route (no straight-line "fake" route).
 */
public final class OpenRouteServiceClient implements RouteService {

    private static final String TAG = "AsanniRouting";
    private static final int TIMEOUT_MS = 15_000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final String apiKey;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Future<?> running;
    private int generation; // ignores answers to cancelled / older requests

    public OpenRouteServiceClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void route(final double fromLat, final double fromLng, final double toLat, final double toLng,
                      final TravelMode mode, final Callback callback) {
        cancel();
        final int myGeneration = ++generation;
        if (apiKey == null) {
            callback.onRouteFailed(Failure.NOT_CONFIGURED);
            return;
        }
        running = EXECUTOR.submit(() -> {
            RouteResult result = null;
            Failure failure = null;
            try {
                boolean alternatives = straightKm(fromLat, fromLng, toLat, toLng) < MapConfig.ORS_ALTERNATIVES_MAX_KM * 0.8;
                String body = requestBody(fromLat, fromLng, toLat, toLng, alternatives);
                Response r = post(String.format(Locale.US, MapConfig.ORS_DIRECTIONS_URL, mode.orsProfile), body);
                if (r.code == 200) {
                    result = parse(r.body, mode);
                    if (result.routes.isEmpty()) failure = Failure.NO_ROUTE;
                } else {
                    failure = classify(r.code, r.body);
                    Log.w(TAG, "Routing HTTP " + r.code + " -> " + failure);
                }
            } catch (IOException e) {
                failure = Failure.OFFLINE;
            } catch (JSONException | RuntimeException e) {
                Log.w(TAG, "Routing response not understood: " + e.getClass().getSimpleName());
                failure = Failure.UNKNOWN;
            }
            final RouteResult finalResult = result;
            final Failure finalFailure = failure;
            main.post(() -> {
                if (myGeneration != generation) return; // cancelled or replaced
                if (finalFailure != null) callback.onRouteFailed(finalFailure);
                else callback.onRoutes(finalResult);
            });
        });
    }

    @Override
    public void cancel() {
        generation++;
        if (running != null) running.cancel(true);
        running = null;
    }

    // ------------------------------------------------------------------------------------------

    static String requestBody(double fromLat, double fromLng, double toLat, double toLng, boolean alternatives)
            throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray coords = new JSONArray();
        coords.put(new JSONArray().put(fromLng).put(fromLat)); // openrouteservice wants [lng, lat]
        coords.put(new JSONArray().put(toLng).put(toLat));
        o.put("coordinates", coords);
        o.put("instructions", false);
        o.put("preference", "fastest");
        if (alternatives) {
            JSONObject alt = new JSONObject();
            alt.put("target_count", MapConfig.ORS_MAX_ALTERNATIVES);
            alt.put("weight_factor", 1.6);
            alt.put("share_factor", 0.6);
            o.put("alternative_routes", alt);
        }
        return o.toString();
    }

    /** Parses the GeoJSON answer; routes sorted fastest first. Malformed routes are skipped. */
    static RouteResult parse(String json, TravelMode mode) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray features = root.optJSONArray("features");
        List<RouteResult.Route> routes = new ArrayList<>();
        if (features != null) {
            for (int i = 0; i < features.length(); i++) {
                JSONObject f = features.optJSONObject(i);
                if (f == null) continue;
                JSONObject geometry = f.optJSONObject("geometry");
                JSONArray line = geometry != null ? geometry.optJSONArray("coordinates") : null;
                if (line == null || line.length() < 2) continue;
                List<double[]> points = new ArrayList<>();
                for (int p = 0; p < line.length(); p++) {
                    JSONArray xy = line.optJSONArray(p);
                    if (xy == null || xy.length() < 2) continue;
                    double lng = xy.optDouble(0, Double.NaN);
                    double lat = xy.optDouble(1, Double.NaN);
                    if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                    points.add(new double[]{lng, lat});
                }
                if (points.size() < 2) continue;
                JSONObject props = f.optJSONObject("properties");
                JSONObject summary = props != null ? props.optJSONObject("summary") : null;
                Double distance = summary != null && summary.has("distance") ? summary.optDouble("distance") : null;
                Double duration = summary != null && summary.has("duration") ? summary.optDouble("duration") : null;
                if (distance != null && (distance.isNaN() || distance < 0)) distance = null;
                if (duration != null && (duration.isNaN() || duration < 0)) duration = null;
                routes.add(new RouteResult.Route(points, distance, duration));
            }
        }
        Collections.sort(routes, new Comparator<RouteResult.Route>() {
            @Override
            public int compare(RouteResult.Route a, RouteResult.Route b) {
                if (a.durationSeconds == null && b.durationSeconds == null) return 0;
                if (a.durationSeconds == null) return 1;
                if (b.durationSeconds == null) return -1;
                return Double.compare(a.durationSeconds, b.durationSeconds);
            }
        });
        return new RouteResult(mode, routes, false);
    }

    static Failure classify(int httpCode, String body) {
        if (httpCode == 401 || httpCode == 403) return Failure.NOT_CONFIGURED; // missing / invalid key
        if (httpCode == 429) return Failure.LIMIT_REACHED;
        int code = 0;
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) code = err.optInt("code", 0);
        } catch (JSONException | RuntimeException ignored) {
            code = 0;
        }
        // 2009 route not found, 2010 point not found, 2004 limits exceeded (e.g. too far)
        if (code == 2009 || code == 2010 || code == 2004 || httpCode == 404) return Failure.NO_ROUTE;
        return Failure.UNKNOWN;
    }

    private static double straightKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Response post(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/geo+json, application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, in == null ? "" : read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = input.read(buf)) != -1) {
                total += n;
                if (total > 5 * 1024 * 1024) throw new IOException("Response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}