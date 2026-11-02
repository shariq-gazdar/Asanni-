package com.asanni.app.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.asanni.app.R;
import com.asanni.app.auth.GoogleAuthClient;
import com.asanni.app.profile.NotificationPrompt;
import com.google.firebase.auth.FirebaseUser;

/**
 * Explains notifications, then asks Android for the ordinary notification permission.
 * (NOT notification-listener access: ASANNI never reads other apps' notifications.)
 *
 * Location: app/src/main/java/com/asanni/app/ui/NotificationPermissionActivity.java  (NEW in phase 4)
 *
 *   Android 13+   -> POST_NOTIFICATIONS runtime dialog
 *   Android 7-12  -> no runtime permission exists; only shown if notifications were switched
 *                    off for ASANNI, and then offers Android's notification settings
 * Either way the user can always continue to Home. Shown once per user per device.
 * No push notifications are sent yet: alerts are a future feature.
 */
public class NotificationPermissionActivity extends AsanniActivity {

    private static final int REQUEST_NOTIFICATIONS = 7101;

    private enum State { ASK, DENIED, WAITING_FOR_SETTINGS }

    private State state = State.ASK;
    private String uid;
    private EntranceAnimator animator;
    private boolean entrancePlayed;

    private TextView allowLabel;
    private TextView laterButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseUser user = GoogleAuthClient.currentUser();
        if (user == null) {
            AppNavigator.openGetStartedAfterSignOut(this);
            finish();
            return;
        }
        uid = user.getUid();
        setContentView(R.layout.activity_notification_permission);
        SystemBarsHelper.apply(this, findViewById(R.id.notification_root));
        animator = new EntranceAnimator(getContentResolver());
        entrancePlayed = savedInstanceState != null;

        allowLabel = findViewById(R.id.allow_label);
        laterButton = findViewById(R.id.button_later);
        statusText = findViewById(R.id.text_status);
        View allow = findViewById(R.id.button_allow);
        allow.setOnClickListener(v -> onPrimary());
        laterButton.setOnClickListener(v -> onSecondary());
        animator.attachPressFeedback(allow);
        render();
    }

    private void onPrimary() {
        switch (state) {
            case DENIED:
                continueToHome(); // "Continue": denial is fine
                break;
            case WAITING_FOR_SETTINGS:
            case ASK:
            default:
                if (NotificationPrompt.notificationsAllowed(this)) {
                    continueToHome();
                } else if (NotificationPrompt.needsRuntimePermission()) {
                    requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                            REQUEST_NOTIFICATIONS);
                } else {
                    openNotificationSettings(); // Android 12 and older
                }
                break;
        }
    }

    private void onSecondary() {
        if (state == State.DENIED) {
            openNotificationSettings(); // change of mind later is always possible
        } else {
            continueToHome(); // "Not now"
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        NotificationPrompt.markShown(this, uid); // asked once: don't ask on every app start
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            continueToHome();
        } else {
            state = State.DENIED; // no false "enabled" message; user may still continue
            render();
        }
    }

    private void openNotificationSettings() {
        if (!SystemSettingsLinks.openNotificationSettings(this)) return;
        state = State.WAITING_FOR_SETTINGS;
        render();
    }

    private void continueToHome() {
        NotificationPrompt.markShown(this, uid);
        AppNavigator.openHome(this);
    }

    private void render() {
        switch (state) {
            case DENIED:
                allowLabel.setText(R.string.notification_permission_continue);
                laterButton.setText(R.string.notification_permission_open_settings);
                statusText.setText(R.string.notification_permission_denied);
                statusText.setVisibility(View.VISIBLE);
                break;
            case WAITING_FOR_SETTINGS:
                allowLabel.setText(R.string.notification_permission_continue);
                laterButton.setText(R.string.notification_permission_later);
                statusText.setText(R.string.notification_permission_settings_hint);
                statusText.setVisibility(View.VISIBLE);
                break;
            case ASK:
            default:
                allowLabel.setText(R.string.notification_permission_allow);
                laterButton.setText(R.string.notification_permission_later);
                statusText.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from Android settings: if notifications are now on, say so honestly and move on.
        if (state != State.ASK && uid != null && NotificationPrompt.notificationsAllowed(this)) {
            continueToHome();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (animator != null && !entrancePlayed) {
            animator.play(findViewById(R.id.bell), findViewById(R.id.text_title),
                    findViewById(R.id.text_description), findViewById(R.id.bottom_actions));
            entrancePlayed = true;
        }
    }

    @Override
    protected void onDestroy() {
        if (animator != null) animator.cancel();
        super.onDestroy();
    }
}