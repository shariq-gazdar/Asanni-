package com.asanni.app.routing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small HTTP + JSON helper shared by the providers. No extra Gradle dependency needed
 * (HttpURLConnection and org.json are part of Android).
 *
 * Location: app/src/main/java/com/asanni/app/routing/RoutingHttp.java  (NEW)
 */
final class RoutingHttp {

    private RoutingHttp() {}

    static JSONObject getJson(String url, Map<String, String> extraHeaders) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(Routeconfig.CONNECT_TIMEOUT_MS);
            c.setReadTimeout(Routeconfig.READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", Routeconfig.USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = in == null ? "" : read(in);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + shorten(body));
            }
            return new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }

    /** Converts GeoJSON-style [[lng, lat], ...] into {lat, lng} pairs. */
    static List<double[]> lngLatToPoints(JSONArray coordinates) throws JSONException {
        List<double[]> points = new ArrayList<>(coordinates.length());
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray p = coordinates.getJSONArray(i);
            points.add(new double[]{p.getDouble(1), p.getDouble(0)});
        }
        if (points.size() < 2) throw new JSONException("route has fewer than 2 points");
        return points;
    }

    /**
     * Always formats with Locale.US: on a phone set to Urdu or Arabic the default locale can
     * produce non-ASCII digits or a comma decimal separator, which breaks the URL.
     */
    static String num(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String read(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}