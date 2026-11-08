package com.prajwal.cardashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AutoPlay: CarPlay and Android Auto from the CarLink app (github.com/F-e-n-y-x/CarLink, AGPL-3.0), a separate app
 * this dashboard only opens. Home shows an AutoPlay tile (hidden or shown in Dashboard settings); a tap opens CarLink,
 * or, when it isn't installed, installs its latest release from inside the app (CarLinkInstaller).
 *
 * While a phone is projected, CarLink's engines keep an ongoing notification, which MusicListener (notification
 * access) hands to {@link #trackNotification}. Our navigation voice is quiet then (VoiceGuide), so the phone's own
 * navigation is the only one speaking; speed camera alerts keep speaking.
 *
 * Once a phone has connected, every car start opens AutoPlay to reconnect it, and comes back to Home when none
 * connects (reconnectOnCarStart). The permissions this needs are asked as prompts when AutoPlay is tapped.
 */
final class AutoPlay {
    static final String TAG = "CarDash.AutoPlay";
    static final String CARLINK_PACKAGE = "com.carlink.app";
    /** The CarPlay engine's (DiPlay's) session notification: fixed English text, shown only during a session. */
    static final String CARPLAY_SESSION_TEXT = "CarPlay connection running";
    /** The Android Auto engine's text once a phone is projected (it shows another text while only waiting). */
    private static final String ANDROID_AUTO_SESSION_STRING = "notification_projection_active";
    private static final String PREFS = "autoplay";
    private static final String ON_HOME_KEY = "on_home";
    private static final String RECONNECT_KEY = "reconnect_on_car_start";
    /** A phone has been projected through AutoPlay at least once: from then on, car starts reconnect it. */
    private static final String CONNECTED_KEY = "has_connected";
    /**
     * At car start, Home comes back when CarLink gives up on the phone (its session notification goes), or after
     * RECONNECT_WAIT_MS if it hasn't even started trying. An attempt still running then gets until RECONNECT_LIMIT_MS:
     * wireless CarPlay took 18 s to connect on a test unit, so a fixed 20 s would cut slow connections off.
     */
    static final long RECONNECT_WAIT_MS = 20_000;
    static final long RECONNECT_LIMIT_MS = 45_000;
    /** Main-thread handler for the car-start check, made on first use (unit tests have no main Looper). */
    private static Handler mainHandler;
    private static Runnable reconnectCheck;
    /** The car-start reconnect being watched: where to come back to, what runs then, and until when (elapsed ms). */
    private static Context reconnectContext;
    private static Runnable reconnectWhenNoPhone;
    private static long reconnectEndsAt;
    /** The user went to a permission's switch from AutoPlay's prompt; CarLink opens once they are back. */
    private static boolean openAfterPermission;
    /** Each permission is asked at most once per tap of AutoPlay. */
    private static boolean askedNotificationAccess;
    private static boolean askedOverlay;

    /** A CarLink install is running (download, checks, handing over); a second tap waits for it. */
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    /** The user went to allow installs from this app; the install starts when they are back (resumeAfterSettings). */
    private static boolean installAfterPermission;
    /** The "Install AutoPlay?" question while it is on screen, so a second tap doesn't stack another. */
    private static AlertDialog offerDialog;
    /** Keys of CarLink's notifications that show a running session; any one means a phone is projected. */
    private static final Set<String> SESSION_KEYS = new HashSet<>();
    /** CarLink's own "projected" text in the unit's language, read once from CarLink's resources. */
    private static String androidAutoSessionText;

    private AutoPlay() {
    }

    static boolean isOnHome(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON_HOME_KEY, true);
    }

    /** Shows or hides the AutoPlay tile; false when the choice could not be saved. */
    static boolean saveOnHome(Context context, boolean onHome) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON_HOME_KEY, onHome).commit();
    }

    /** CarLink's version name, or null when it isn't installed. */
    static String lookupInstalledVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(CARLINK_PACKAGE, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Opens CarLink; when it isn't installed, offers to install its latest release (offerInstall). Before opening,
     * asks once per tap for what AutoPlay's car-start reconnect needs (askForPermissions).
     */
    static void open(Activity activity) {
        if (activity.getPackageManager().getLaunchIntentForPackage(CARLINK_PACKAGE) == null) {
            offerInstall(activity);
            return;
        }
        askedNotificationAccess = false;
        askedOverlay = false;
        askForPermissionsThenOpen(activity);
    }

    /**
     * The permissions as their own prompts, one at a time, each going straight to Android's switch for this app:
     * notification access (to see that a phone is connected) and "Display over other apps" (to come back to Home
     * when no phone connects at car start). "Not now" goes on to CarLink; coming back from the switch carries on
     * (resumeAfterSettings).
     */
    private static void askForPermissionsThenOpen(Activity activity) {
        if (!askedNotificationAccess && !MusicListener.hasAccess(activity)) {
            askedNotificationAccess = true;
            askThenOpen(activity, R.string.autoplay_ask_notifications,
                    new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            return;
        }
        if (!askedOverlay && !Settings.canDrawOverlays(activity)) {
            askedOverlay = true;
            askThenOpen(activity, R.string.autoplay_ask_overlay, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        Intent carLink = activity.getPackageManager().getLaunchIntentForPackage(CARLINK_PACKAGE);
        if (carLink != null) DashboardActivity.startApp(activity, carLink);
    }

    private static void askThenOpen(Activity activity, int question, Intent permissionScreen) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.autoplay_name)
                .setMessage(question)
                .setPositiveButton(R.string.autoplay_allow, (dialog, which) -> {
                    openAfterPermission = true;
                    DashboardActivity.startApp(activity, permissionScreen);
                })
                .setNegativeButton(R.string.autoplay_not_now, (dialog, which) -> askForPermissionsThenOpen(activity))
                .show();
    }

    /**
     * At car start (CarStartup's claim, from Home): when a phone has connected through AutoPlay before, opens CarLink,
     * which reconnects the last phone only while its own screen is in front. When CarLink gives up, or hasn't started
     * by RECONNECT_WAIT_MS, Home comes back and {@code whenNoPhone} runs (the startup music). False when nothing was
     * done (AutoPlay hidden or off, never connected, CarLink missing, or no notification access to see a phone).
     */
    static boolean reconnectOnCarStart(Activity activity, Runnable whenNoPhone) {
        Context app = activity.getApplicationContext();
        String skipped = findReconnectBlocker(app);
        if (skipped != null) {
            Log.i(TAG, "car start: no reconnect, " + skipped);
            return false;
        }
        Intent carLink = app.getPackageManager().getLaunchIntentForPackage(CARLINK_PACKAGE);
        if (carLink == null) return false;
        Log.i(TAG, "car start: opening AutoPlay to reconnect the last phone");
        DashboardActivity.startApp(activity, carLink);
        lookupMainHandler().removeCallbacks(reconnectCheck);
        reconnectContext = app;
        reconnectWhenNoPhone = whenNoPhone;
        reconnectEndsAt = SystemClock.elapsedRealtime() + RECONNECT_LIMIT_MS;
        reconnectCheck = () -> {
            if (isSessionRunning()) {
                Log.i(TAG, "car start: AutoPlay is connecting or connected, it stays");
                return;   // a failure from here ends the session: returnHome via trackNotification
            }
            returnHome("CarLink hasn't started connecting in " + RECONNECT_WAIT_MS / 1000 + " s");
        };
        lookupMainHandler().postDelayed(reconnectCheck, RECONNECT_WAIT_MS);
        return true;
    }

    /** Ends a car-start reconnect without a phone: Home comes back (if allowed) and the startup music follows. */
    private static void returnHome(String reason) {
        Context app = reconnectContext;
        Runnable whenNoPhone = reconnectWhenNoPhone;
        if (app == null) return;
        reconnectContext = null;
        reconnectWhenNoPhone = null;
        lookupMainHandler().removeCallbacks(reconnectCheck);
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "car start: " + reason + ", but Display over other apps is off: AutoPlay stays in front");
            return;
        }
        Log.i(TAG, "car start: " + reason + ", back to Home");
        DashboardActivity.startApp(app, new Intent(app, DashboardActivity.class));
        whenNoPhone.run();
    }

    private static synchronized Handler lookupMainHandler() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    /** Why AutoPlay won't reconnect at car start, or null when it will. */
    static String findReconnectBlocker(Context context) {
        if (!isOnHome(context)) return "AutoPlay is hidden";
        if (!isReconnectOn(context)) return "reconnect is off";
        if (!hasConnected(context)) return "no phone has connected through AutoPlay yet";
        if (lookupInstalledVersion(context) == null) return "CarLink is not installed";
        if (!MusicListener.hasAccess(context)) return "no notification access";
        return null;
    }

    static boolean isReconnectOn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RECONNECT_KEY, true);
    }

    static boolean saveReconnect(Context context, boolean on) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(RECONNECT_KEY, on).commit();
    }

    private static boolean hasConnected(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONNECTED_KEY, false);
    }

    /**
     * "Install AutoPlay?", then the latest CarLink downloads with its progress shown, is checked (CarLinkInstaller),
     * and goes to Android's installer, which asks to confirm. One install at a time.
     */
    static void offerInstall(Activity activity) {
        if (INSTALLING.get()) {
            Toast.makeText(activity, R.string.autoplay_install_running, Toast.LENGTH_SHORT).show();
            return;
        }
        if (offerDialog != null && offerDialog.isShowing()) return;   // a second tap while it is up
        offerDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.autoplay_install_title)
                .setMessage(R.string.autoplay_install_question)
                .setPositiveButton(R.string.autoplay_install, (dialog, which) -> startInstallWhenAllowed(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> offerDialog = null)
                .show();
    }

    /**
     * Android must first allow this app to install apps ("Install unknown apps"). Asked before downloading: some
     * units cancel an install that is already waiting when the user goes to that setting. Once the user is back with
     * it allowed, resumeAfterSettings (from the activity's onResume) carries on.
     */
    private static void startInstallWhenAllowed(Activity activity) {
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            startInstall(activity);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.autoplay_install_title)
                .setMessage(R.string.autoplay_install_allow)
                .setPositiveButton(R.string.autoplay_install_open_setting, (dialog, which) -> {
                    installAfterPermission = true;
                    DashboardActivity.startApp(activity, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * From onResume: back from a switch AutoPlay sent the user to. After a permission prompt, the next prompt or
     * CarLink follows; after "Install unknown apps", the waiting install starts if it is now allowed.
     */
    static void resumeAfterSettings(Activity activity) {
        if (openAfterPermission) {
            openAfterPermission = false;
            askForPermissionsThenOpen(activity);
            return;
        }
        if (!installAfterPermission) return;
        installAfterPermission = false;
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            startInstall(activity);
        } else {
            Toast.makeText(activity, R.string.autoplay_install_not_allowed, Toast.LENGTH_LONG).show();
        }
    }

    private static void startInstall(Activity activity) {
        if (!INSTALLING.compareAndSet(false, true)) return;
        TextView message = new TextView(activity);
        message.setText(R.string.autoplay_install_finding);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);
        content.addView(message);
        content.addView(bar);
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(R.string.autoplay_install_title)
                .setView(content)
                .setCancelable(false)
                .show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String failure = null;
            try {
                CarLinkInstaller.Release release = CarLinkInstaller.findLatestRelease();
                String downloading = activity.getString(R.string.autoplay_install_downloading, release.version);
                main.post(() -> {
                    message.setText(downloading);
                    bar.setIndeterminate(false);
                    bar.setMax(100);
                });
                File apk = CarLinkInstaller.downloadVerified(activity, release, (done, total) -> {
                    if (total > 0) main.post(() -> bar.setProgress((int) (done * 100 / total)));
                });
                main.post(() -> message.setText(R.string.autoplay_install_handing_over));
                CarLinkInstaller.install(activity, apk);
                Log.i(TAG, "CarLink " + release.version + " checked and handed to the installer");
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "CarLink not installed", e);
                failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } finally {
                INSTALLING.set(false);
            }
            String reason = failure;
            main.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                progress.dismiss();
                if (reason == null) return;   // Android's install screen comes next (CarLinkInstallReceiver)
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.autoplay_install_title)
                        .setMessage(activity.getString(R.string.autoplay_install_failed, reason))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "carlink-install").start();
    }

    /** True while CarLink projects a phone (CarPlay or Android Auto). Needs notification access, as Now playing does. */
    static synchronized boolean isSessionRunning() {
        return !SESSION_KEYS.isEmpty();
    }

    /** From MusicListener: a notification of CarLink's posted (or updated), or removed when {@code posted} is false. */
    static void trackNotification(Context context, String key, Notification notification, boolean posted) {
        boolean session = posted && isSessionNotification(
                (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0,
                notification.extras.getCharSequence(Notification.EXTRA_TEXT),
                lookupAndroidAutoSessionText(context));
        boolean changed;
        synchronized (AutoPlay.class) {
            changed = session ? SESSION_KEYS.add(key) : SESSION_KEYS.remove(key);
        }
        if (!changed) return;
        Log.i(TAG, session ? "phone projected: our navigation voice is quiet" : "projection ended");
        if (session && !hasConnected(context)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONNECTED_KEY, true).apply();
        }
        if (!session && reconnectContext != null && SystemClock.elapsedRealtime() < reconnectEndsAt) {
            lookupMainHandler().post(() -> returnHome("CarLink couldn't connect the phone"));
        }
    }

    /** Whether one of CarLink's notifications shows a session: ongoing, with either engine's "running" text. */
    static boolean isSessionNotification(boolean ongoing, CharSequence text, String androidAutoSessionText) {
        if (!ongoing || text == null) return false;
        String shown = text.toString();
        return shown.equals(CARPLAY_SESSION_TEXT) || shown.equals(androidAutoSessionText);
    }

    private static String lookupAndroidAutoSessionText(Context context) {
        if (androidAutoSessionText != null) return androidAutoSessionText;
        try {
            Resources carLink = context.getPackageManager().getResourcesForApplication(CARLINK_PACKAGE);
            int id = carLink.getIdentifier(ANDROID_AUTO_SESSION_STRING, "string", CARLINK_PACKAGE);
            if (id == 0) {
                Log.w(TAG, "CarLink has no " + ANDROID_AUTO_SESSION_STRING + "; Android Auto sessions go unnoticed");
                return null;
            }
            androidAutoSessionText = carLink.getString(id);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            Log.w(TAG, "CarLink's texts unreadable; Android Auto sessions go unnoticed", e);
        }
        return androidAutoSessionText;
    }
}
