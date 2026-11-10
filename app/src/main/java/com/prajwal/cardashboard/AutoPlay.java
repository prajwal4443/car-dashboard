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
 * connects (reconnectOnCarStart). With Accessibility on, a "Connecting to your phone…" cover hides CarLink until the
 * phone is on screen (ConnectingCover, CarLinkScreenReader). The permissions this needs are asked as prompts when
 * AutoPlay is tapped.
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
    /** Under the cover, how often CarLink's screen is looked at. */
    static final long COVER_POLL_MS = 500;

    /** What the car-start cover does next (chooseCoverStep). */
    enum CoverStep { KEEP, SHOW_CARLINK, GO_HOME }
    /** Main-thread handler for the car-start check, made on first use (unit tests have no main Looper). */
    private static Handler mainHandler;
    private static Runnable reconnectCheck;
    /** The car-start reconnect being watched: where to come back to, what runs then, and until when (elapsed ms). */
    private static Context reconnectContext;
    private static Runnable reconnectWhenNoPhone;
    private static long reconnectStartedAt;
    private static long reconnectEndsAt;
    /** The user went to a permission's switch from AutoPlay's prompt; CarLink opens once they are back. */
    private static boolean openAfterPermission;
    /** Each permission is asked at most once per tap of AutoPlay. */
    private static boolean askedNotificationAccess;
    private static boolean askedOverlay;
    private static boolean askedAccessibility;

    /** A CarLink install is running (download, checks, handing over); a second tap waits for it. */
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    /**
     * Handed to Android's installer and not answered yet (CarLinkInstallReceiver). The dashboard comes back to the
     * front before Android has finished, still seeing the old CarLink: no update popup until the answer.
     */
    private static volatile boolean awaitingInstaller;
    /** Told on the main thread when Android has answered an install (the dashboard, to refresh its popup). */
    private static Runnable onInstallAnswered;
    /** The user went to allow installs from this app; the install starts when they are back (resumeAfterSettings). */
    private static boolean installAfterPermission;
    /** The "Install AutoPlay?" question while it is on screen, so a second tap doesn't stack another. */
    private static AlertDialog offerDialog;
    /** The install flow's dialog title: installing CarLink, or updating it. */
    private static int installTitle = R.string.autoplay_install_title;
    /**
     * Keys of CarLink's notifications that show a running session. CarPlay's is posted when CarLink starts trying,
     * so it means "connecting or connected"; Android Auto's "projected" one means on screen (PROJECTED_KEYS too).
     */
    private static final Set<String> SESSION_KEYS = new HashSet<>();
    private static final Set<String> PROJECTED_KEYS = new HashSet<>();
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

    /** An install or update of CarLink is downloading, being checked, or waiting for Android's answer. */
    static boolean isInstallBusy() {
        return INSTALLING.get() || awaitingInstaller;
    }

    static void setOnInstallAnswered(Runnable listener) {
        onInstallAnswered = listener;
    }

    /** From CarLinkInstallReceiver (main thread): Android has answered, installed or not. */
    static void onInstallerAnswered() {
        awaitingInstaller = false;
        Runnable listener = onInstallAnswered;
        if (listener != null) listener.run();
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
        askedAccessibility = false;
        askForPermissionsThenOpen(activity);
    }

    /**
     * The permissions as their own prompts, one at a time, each going straight to Android's switch for this app:
     * notification access (to see that a phone is connected), "Display over other apps" (to come back to Home
     * when no phone connects at car start, and for the cover) and Accessibility (to see when the phone is on screen,
     * so the cover can hide CarLink while it connects). "Not now" goes on to CarLink; coming back from the switch carries on
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
        if (!askedAccessibility && !CarLinkScreenReader.isEnabled(activity)) {
            askedAccessibility = true;
            askThenOpen(activity, R.string.autoplay_ask_accessibility, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
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
     * which reconnects the last phone only while its own screen is in front. False when nothing was done (AutoPlay
     * hidden or off, never connected, CarLink missing, or no notification access to see a phone).
     *
     * With "Display over other apps" and Accessibility, ConnectingCover goes up first and CarLink connects under it:
     * the cover goes once the phone is on screen, or Home comes back (chooseCoverStep). Without them CarLink is seen
     * connecting, and Home comes back when CarLink gives up, or hasn't started by RECONNECT_WAIT_MS. Either way
     * {@code whenNoPhone} (the startup music) runs when Home comes back.
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
        lookupMainHandler().removeCallbacks(reconnectCheck);
        reconnectContext = app;
        reconnectWhenNoPhone = whenNoPhone;
        reconnectStartedAt = SystemClock.elapsedRealtime();
        reconnectEndsAt = reconnectStartedAt + RECONNECT_LIMIT_MS;
        boolean covered = CarLinkScreenReader.isEnabled(app) && ConnectingCover.show(app, AutoPlay::showCarLinkFromCover);
        Log.i(TAG, "car start: opening AutoPlay to reconnect the last phone" + (covered ? ", under the cover" : ""));
        DashboardActivity.startApp(activity, carLink);
        if (covered) {
            reconnectCheck = AutoPlay::checkUnderCover;
            lookupMainHandler().postDelayed(reconnectCheck, COVER_POLL_MS);
        } else {
            reconnectCheck = () -> {
                if (isSessionRunning()) {
                    Log.i(TAG, "car start: AutoPlay is connecting or connected, it stays");
                    return;   // a failure from here ends the session: returnHome via trackNotification
                }
                returnHome("CarLink hasn't started connecting in " + RECONNECT_WAIT_MS / 1000 + " s");
            };
            lookupMainHandler().postDelayed(reconnectCheck, RECONNECT_WAIT_MS);
        }
        return true;
    }

    /**
     * Under the cover: the phone on screen (CarPlay's session plus a CarLink window with no text, or Android Auto's
     * "projected") shows CarLink; no session by RECONNECT_WAIT_MS, or none on screen by RECONNECT_LIMIT_MS, brings
     * Home back. A session that ends early brings Home back through trackNotification.
     */
    static CoverStep chooseCoverStep(boolean session, boolean projected, CarLinkScreenReader.Screen screen,
                                     long elapsedMs) {
        if (projected || (session && screen == CarLinkScreenReader.Screen.PROJECTING)) return CoverStep.SHOW_CARLINK;
        if (elapsedMs >= RECONNECT_LIMIT_MS) return CoverStep.GO_HOME;
        if (!session && elapsedMs >= RECONNECT_WAIT_MS) return CoverStep.GO_HOME;
        return CoverStep.KEEP;
    }

    private static void checkUnderCover() {
        if (reconnectContext == null) return;
        boolean session;
        boolean projected;
        synchronized (AutoPlay.class) {
            session = !SESSION_KEYS.isEmpty();
            projected = !PROJECTED_KEYS.isEmpty();
        }
        long elapsed = SystemClock.elapsedRealtime() - reconnectStartedAt;
        CarLinkScreenReader.Screen screen = CarLinkScreenReader.findScreen();
        switch (chooseCoverStep(session, projected, screen, elapsed)) {
            case SHOW_CARLINK:
                Log.i(TAG, "car start: phone on screen after " + elapsed / 1000 + " s, the cover goes");
                endReconnect();
                ConnectingCover.hide();
                break;
            case GO_HOME:
                returnHome(session ? "the phone isn't on screen in " + RECONNECT_LIMIT_MS / 1000 + " s"
                        : "CarLink hasn't started connecting in " + RECONNECT_WAIT_MS / 1000 + " s");
                break;
            default:
                lookupMainHandler().postDelayed(reconnectCheck, COVER_POLL_MS);
        }
    }

    /** The cover's AutoPlay button: CarLink is shown as it is, and stays (no coming back to Home). */
    private static void showCarLinkFromCover() {
        Log.i(TAG, "car start: CarLink shown from the cover");
        endReconnect();
        ConnectingCover.hide();
    }

    /** Stops watching the car-start reconnect. */
    private static void endReconnect() {
        reconnectContext = null;
        reconnectWhenNoPhone = null;
        lookupMainHandler().removeCallbacks(reconnectCheck);
    }

    /**
     * Ends a car-start reconnect without a phone: Home comes back (if allowed) and the startup music follows. The
     * cover stays until Home is on screen (onHomeShown), so CarLink isn't seen on the way out.
     */
    private static void returnHome(String reason) {
        Context app = reconnectContext;
        Runnable whenNoPhone = reconnectWhenNoPhone;
        if (app == null) return;
        endReconnect();
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "car start: " + reason + ", but Display over other apps is off: AutoPlay stays in front");
            ConnectingCover.hide();
            return;
        }
        Log.i(TAG, "car start: " + reason + ", back to Home");
        DashboardActivity.startApp(app, new Intent(app, DashboardActivity.class));
        lookupMainHandler().postDelayed(ConnectingCover::hide, COVER_HIDE_FALLBACK_MS);
        whenNoPhone.run();
    }

    /** If Home somehow doesn't come back, the cover still goes after this. */
    private static final long COVER_HIDE_FALLBACK_MS = 3_000;

    /** From the dashboard's onResume: Home is on screen, so a cover left from a car start goes. */
    static void onHomeShown() {
        if (reconnectContext == null) ConnectingCover.hide();
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
                .setPositiveButton(R.string.autoplay_install, (dialog, which) -> {
                    installTitle = R.string.autoplay_install_title;
                    startInstallWhenAllowed(activity);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> offerDialog = null)
                .show();
    }

    /**
     * Updates CarLink to its latest release (CarLinkUpdateCheck found it): the same checked download, and Android's
     * installer asks "update this app?". CarLink's own settings stay.
     */
    static void installUpdate(Activity activity) {
        if (INSTALLING.get()) {
            Toast.makeText(activity, R.string.autoplay_install_running, Toast.LENGTH_SHORT).show();
            return;
        }
        installTitle = R.string.autoplay_update_title;
        startInstallWhenAllowed(activity);
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
                .setTitle(installTitle)
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
                .setTitle(installTitle)
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
                awaitingInstaller = true;
                try {
                    CarLinkInstaller.install(activity, apk);
                } catch (IOException | RuntimeException e) {
                    awaitingInstaller = false;
                    throw e;
                }
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
                        .setTitle(installTitle)
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
        boolean projected = session && isProjectedNotification(notification.extras.getCharSequence(Notification.EXTRA_TEXT),
                lookupAndroidAutoSessionText(context));
        boolean changed;
        synchronized (AutoPlay.class) {
            changed = session ? SESSION_KEYS.add(key) : SESSION_KEYS.remove(key);
            if (projected) PROJECTED_KEYS.add(key); else PROJECTED_KEYS.remove(key);
        }
        if (!changed) return;
        Log.i(TAG, session ? "phone projected: our navigation voice is quiet" : "projection ended");
        if (session && !hasConnected(context)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONNECTED_KEY, true).apply();
        }
        if (!session) CarLinkUpdateCheck.notifyIfDue(context);   // an update found during the projection
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

    /** Android Auto's "projected" text: unlike CarPlay's, it means the phone is on screen. */
    static boolean isProjectedNotification(CharSequence text, String androidAutoSessionText) {
        return text != null && androidAutoSessionText != null && text.toString().equals(androidAutoSessionText);
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
