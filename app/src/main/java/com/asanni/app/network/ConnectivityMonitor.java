package com.asanni.app.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-wide "is there usable internet?" monitor.
 *
 * Location: app/src/main/java/com/asanni/app/network/ConnectivityMonitor.java  (NEW in phase 5)
 *
 * - Wi-Fi OR mobile data: any network counts; being off Wi-Fi is NOT "offline".
 * - "Online" means Android has VALIDATED that the network really reaches the internet
 *   (NET_CAPABILITY_VALIDATED), so a Wi-Fi with no internet or a hotel login page counts as offline.
 * - Event-driven (NetworkCallback): no polling, no repeated pings, no background work.
 * - It's only a hint: Firebase calls can still fail, and screens also react to those failures.
 */
public final class ConnectivityMonitor {

    public interface Listener {
        void onConnectivityChanged(boolean online);
    }

    private static ConnectivityMonitor instance;

    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean online;
    private boolean started;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            update(hasInternet(caps));
        }

        @Override
        public void onLost(Network network) {
            update(checkNow()); // another network may still be up
        }
    };

    private ConnectivityMonitor(Context context) {
        cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        online = checkNow();
    }

    public static synchronized ConnectivityMonitor get(Context context) {
        if (instance == null) instance = new ConnectivityMonitor(context);
        instance.start();
        return instance;
    }

    private synchronized void start() {
        if (started || cm == null) return;
        try {
            cm.registerDefaultNetworkCallback(callback); // one app-wide callback (API 24+)
            started = true;
        } catch (RuntimeException e) {
            // Some devices limit callbacks; isOnline() still checks on demand.
        }
    }

    /** Fresh check right now (used by Retry and before network-only actions). */
    public boolean isOnline() {
        boolean now = checkNow();
        update(now); // listeners hear about it even if the callback hasn't fired yet
        return now;
    }

    /**
     * Lenient check for actions the user asked for: true when there is any network that claims
     * internet, even if Android hasn't validated it yet (some networks block Android's check while
     * Firebase still works). If the action then fails, the screen shows the offline page anyway.
     */
    public boolean mayHaveInternet() {
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private synchronized void update(boolean nowOnline) {
        if (nowOnline == online) return;
        online = nowOnline;
        main.post(() -> {
            for (Listener l : listeners) l.onConnectivityChanged(nowOnline);
        });
    }

    private boolean checkNow() {
        if (cm == null) return true; // can't tell: don't block the user
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        return hasInternet(cm.getNetworkCapabilities(n));
    }

    private static boolean hasInternet(NetworkCapabilities caps) {
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}