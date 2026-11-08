package com.prajwal.cardashboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Looks for a newer CarLink on its GitHub releases, for a head unit that is mostly offline. Android's own check that a
 * network really reaches the internet ("validated") starts it; the check itself runs only when the last successful one
 * was CHECK_EVERY_MS or more ago, so internet after 10 days checks at once and the next waits 7 days from then. A
 * failed check doesn't count, and is retried at most every RETRY_AFTER_FAILURE_MS while online.
 *
 * Found versions are remembered and offered, once per version and never during AutoPlay: a notification banner over
 * whatever app is open (tapping it starts the update), a popup on the dashboard, and Dashboard settings. Installing
 * is AutoPlay's checked install (CarLinkInstaller).
 */
final class CarLinkUpdateCheck {
    private static final String TAG = "CarDash.CarLinkUpdate";
    static final long CHECK_EVERY_MS = 7L * 24 * 60 * 60 * 1000;
    static final long RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000;
    private static final String PREFS = "carlink_updates";
    private static final String CHECKED_KEY = "last_checked";
    private static final String ATTEMPTED_KEY = "last_attempted";
    private static final String LATEST_KEY = "latest_version";
    private static final String DISMISSED_KEY = "dismissed_version";
    private static final String NOTIFIED_KEY = "notified_version";
    private static final String CHANNEL = "carlink_updates";
    private static final int NOTIFICATION_ID = 7301;
    /** On the notification's intent: the dashboard starts the update when it opens. */
    static final String EXTRA_INSTALL_UPDATE = "com.prajwal.cardashboard.INSTALL_CARLINK_UPDATE";

    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static boolean watching;
    /** Told on the main thread when a check found a newer CarLink (the dashboard, while it is on screen). */
    private static Runnable onUpdateFound;

    private CarLinkUpdateCheck() {
    }

    /**
     * Starts following the internet for the life of the app (once): each time Android has validated a network, a check
     * runs if one is due. Android answers at once when a validated network is already up.
     */
    static synchronized void watchInternet(Context context) {
        if (watching) return;
        Context app = context.getApplicationContext();
        ConnectivityManager connectivity = app.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    checkIfDue(app);
                }
            }
        });
        watching = true;
    }

    static void setOnUpdateFound(Runnable listener) {
        onUpdateFound = listener;
    }

    /**
     * Whether a check should run now: never checked, or CHECK_EVERY_MS since the last success, and not within
     * RETRY_AFTER_FAILURE_MS of the last attempt. A clock that went backwards (a head unit's clock reset when the car
     * battery was off) counts as due.
     */
    static boolean isCheckDue(long now, long lastChecked, long lastAttempted) {
        boolean recentAttempt = lastAttempted > 0 && now >= lastAttempted && now - lastAttempted < RETRY_AFTER_FAILURE_MS;
        if (recentAttempt) return false;
        return lastChecked <= 0 || now < lastChecked || now - lastChecked >= CHECK_EVERY_MS;
    }

    /** Below zero when version {@code a} is older than {@code b}: dotted numbers compared part by part. */
    static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int difference = Integer.compare(parsePart(left, i), parsePart(right, i));
            if (difference != 0) return difference;
        }
        return 0;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String digits = parts[index].replaceAll("[^0-9].*$", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The newer CarLink version found by the last check, or null when CarLink is missing or up to date. */
    static String lookupAvailableUpdate(Context context) {
        String installed = AutoPlay.lookupInstalledVersion(context);
        String latest = prefs(context).getString(LATEST_KEY, null);
        if (installed == null || latest == null) return null;
        return compareVersions(installed, latest) < 0 ? latest : null;
    }

    /** The popup is shown once per version; "Later" marks it. */
    static boolean wasDismissed(Context context, String version) {
        return version.equals(prefs(context).getString(DISMISSED_KEY, null));
    }

    static void saveDismissed(Context context, String version) {
        prefs(context).edit().putString(DISMISSED_KEY, version).apply();
    }

    private static void checkIfDue(Context app) {
        if (AutoPlay.lookupInstalledVersion(app) == null) return;   // nothing to update
        SharedPreferences prefs = prefs(app);
        long now = System.currentTimeMillis();
        if (!isCheckDue(now, prefs.getLong(CHECKED_KEY, 0), prefs.getLong(ATTEMPTED_KEY, 0))) return;
        if (!CHECKING.compareAndSet(false, true)) return;
        prefs.edit().putLong(ATTEMPTED_KEY, now).apply();
        new Thread(() -> {
            try {
                String latest = CarLinkInstaller.findLatestRelease().version;
                prefs.edit().putString(LATEST_KEY, latest).putLong(CHECKED_KEY, System.currentTimeMillis()).apply();
                String update = lookupAvailableUpdate(app);
                Log.i(TAG, "checked: latest CarLink " + latest + (update != null ? ", an update" : ", up to date"));
                if (update != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        notifyIfDue(app);
                        Runnable listener = onUpdateFound;
                        if (listener != null) listener.run();
                    });
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "check failed, retried once online again after an hour", e);
            } finally {
                CHECKING.set(false);
            }
        }, "carlink-update-check").start();
    }

    /**
     * The update as a notification banner, once per version. Not while a phone is projected: AutoPlay calls this
     * again when the projection ends. Tapping it opens the dashboard, which starts the update.
     */
    static void notifyIfDue(Context context) {
        String version = lookupAvailableUpdate(context);
        if (version == null || version.equals(prefs(context).getString(NOTIFIED_KEY, null))) return;
        if (AutoPlay.isSessionRunning()) return;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()) {
            Log.w(TAG, "CarLink " + version + " found, but notifications are off: dashboard and settings show it");
            return;
        }
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.autoplay_update_channel), NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, DashboardActivity.class).putExtra(EXTRA_INSTALL_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification banner = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_autoplay)
                .setContentTitle(context.getString(R.string.autoplay_update_title))
                .setContentText(context.getString(R.string.autoplay_update_notification, version))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        notifications.notify(NOTIFICATION_ID, banner);
        prefs(context).edit().putString(NOTIFIED_KEY, version).apply();
        Log.i(TAG, "CarLink " + version + ": update notification shown");
    }

    /** After CarLink was updated: the banner has nothing left to offer. */
    static void cancelNotification(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
