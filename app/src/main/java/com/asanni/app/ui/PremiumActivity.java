package com.asanni.app.ui;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.premium.Entitlement;
import com.asanni.app.premium.Entitlements;

/**
 * ASANNI Premium placeholder (NEW in phase 9). No prices, no payment, no "upgrade" button that
 * pretends to work. Shows the user's VERIFIED plan from entitlements/{uid} (Free by default).
 * The next phase replaces the body with the real plans (Google Play Billing + server checks).
 *
 * Location: app/src/main/java/com/asanni/app/ui/PremiumActivity.java
 */
public class PremiumActivity extends AsanniActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GoogleAuthClient.currentUser() == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_premium);
        SystemBarsHelper.apply(this, findViewById(R.id.premium_root));
        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        show(Entitlements.current());
        Entitlements.refresh(e -> {
            if (!isDestroyed()) show(e);
        });
    }

    private void show(Entitlement e) {
        TextView t = findViewById(R.id.premium_current);
        if (e.isActive(System.currentTimeMillis())) {
            t.setText(getString(R.string.premium_current_active, e.planId,
                    DateUtils.formatDateTime(this, e.expiresAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR)));
        } else {
            t.setText(R.string.premium_current_free);
        }
    }
}