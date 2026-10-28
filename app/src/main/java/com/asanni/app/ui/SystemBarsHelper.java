package com.asanni.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * Edge-to-edge window setup shared by the new screens: transparent bars with dark icons,
 * and the root view padded so content never hides under the status/navigation bars.
 *
 * Location: app/src/main/java/com/asanni/app/ui/SystemBarsHelper.java  (NEW)
 */
final class SystemBarsHelper {

    private SystemBarsHelper() {}

    @SuppressWarnings("deprecation")
    static void apply(Activity activity, View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = activity.getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light); // light background -> dark icons
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            root.setSystemUiVisibility(flags);
        }
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}