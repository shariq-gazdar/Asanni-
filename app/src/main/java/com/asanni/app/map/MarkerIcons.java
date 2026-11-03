package com.asanni.app.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.asanni.app.R;
import com.asanni.app.restaurants.Availability;
import com.asanni.app.restaurants.Restaurant;

/**
 * Draws the round map markers from the reference ("5", "8", "15m"...) as bitmaps, so they don't
 * depend on fonts inside the map style.
 *
 * Location: app/src/main/java/com/asanni/app/map/MarkerIcons.java  (NEW in phase 7)
 *
 * [UI ASSET] Markers are drawn in code. Colors: res/values/colors_discover.xml
 * (asanni_marker_free / _wait / _full / _closed / _none). To use artwork from your ui folder
 * instead, add e.g. res/drawable-nodpi/marker_restaurant.png and return it from bitmapFor().
 *
 *   FREE     green circle with the number of free tables
 *   WAIT     orange circle with the waiting time ("15m")
 *   FULL     dark red circle (0 free tables reported)
 *   CLOSED   grey circle with a dash (reports closed)
 *   UNKNOWN  small warm-grey dot (registered, no live data yet)
 *   cluster  white circle with a red ring and the number of restaurants (several close together)
 * Selected markers are larger with a dark ring.
 */
public final class MarkerIcons {

    private MarkerIcons() {}

    /** Image name for a restaurant marker (also used as the style image id). */
    public static String keyFor(Restaurant r, long now, boolean selected) {
        String p = selected ? "s_" : "r_";
        switch (Availability.of(r, now)) {
            case FREE:
                return p + "free_" + Math.min(r.freeTables, 100);
            case WAIT:
                return p + "wait_" + Math.min(r.waitMinutes, 100);
            case FULL:
                return p + "full";
            case CLOSED:
                return p + "closed";
            default:
                return p + "none";
        }
    }

    /** Cluster image names: c_2 ... c_20, and c_21 shown as "20+". */
    public static String clusterKey(int count) {
        return "c_" + Math.max(2, Math.min(count, 21));
    }

    /** Draws the bitmap for an image name created by keyFor() / clusterKey(). */
    public static Bitmap bitmapFor(Context c, String key) {
        float d = c.getResources().getDisplayMetrics().density;
        if (key.startsWith("c_")) {
            int n = parse(key.substring(2));
            String label = n >= 21 ? "20+" : String.valueOf(n);
            return circle(d, 40, Color.WHITE, c.getColor(R.color.asanni_route_main), 3f,
                    label, c.getColor(R.color.asanni_route_main));
        }
        boolean selected = key.startsWith("s_");
        String kind = key.substring(2);
        int ring = selected ? c.getColor(R.color.asanni_marker_selected_ring) : c.getColor(R.color.asanni_marker_ring);
        float ringDp = selected ? 3.5f : 2.5f;
        float scale = selected ? 1.3f : 1f;
        if (kind.startsWith("free_")) {
            int n = parse(kind.substring(5));
            return circle(d, 36 * scale, c.getColor(R.color.asanni_marker_free), ring, ringDp,
                    n >= 100 ? "99+" : String.valueOf(n), Color.WHITE);
        }
        if (kind.startsWith("wait_")) {
            int n = parse(kind.substring(5));
            return circle(d, 36 * scale, c.getColor(R.color.asanni_marker_wait), ring, ringDp,
                    (n >= 100 ? "99+" : String.valueOf(n)) + "m", Color.WHITE);
        }
        if (kind.equals("full")) {
            return circle(d, 26 * scale, c.getColor(R.color.asanni_marker_full), ring, ringDp, null, Color.WHITE);
        }
        if (kind.equals("closed")) {
            return circle(d, 26 * scale, c.getColor(R.color.asanni_marker_closed), ring, ringDp, "–", Color.WHITE);
        }
        return circle(d, 20 * scale, c.getColor(R.color.asanni_marker_none), ring, ringDp, null, Color.WHITE);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Bitmap circle(float density, float diameterDp, int fill, int ring, float ringDp,
                                 String label, int textColor) {
        float shadow = 2f * density;
        int size = (int) Math.ceil(diameterDp * density + shadow * 2);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float cx = size / 2f;
        float cy = size / 2f;
        float r = diameterDp * density / 2f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x33000000); // soft shadow
        canvas.drawCircle(cx, cy + shadow * 0.6f, r, p);
        p.setColor(ring);
        canvas.drawCircle(cx, cy, r, p);
        p.setColor(fill);
        canvas.drawCircle(cx, cy, r - ringDp * density, p);

        if (label != null) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(textColor);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            t.setTextAlign(Paint.Align.CENTER);
            float textSize = (label.length() >= 3 ? 11.5f : 14f) * density * (diameterDp / 36f);
            t.setTextSize(Math.max(10f * density, textSize));
            Paint.FontMetrics fm = t.getFontMetrics();
            canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, t);
        }
        return bmp;
    }
}