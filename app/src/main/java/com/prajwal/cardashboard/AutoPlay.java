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

    /** A CarLink install is running (download, checks, handing over); a second tap waits for it. */
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    /** The user went to allow installs from this app; the install starts when they are back (resumeInstall). */
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

    /** Opens CarLink; when it isn't installed, offers to install its latest release (offerInstall). */
    static void open(Activity activity) {
        Intent carLink = activity.getPackageManager().getLaunchIntentForPackage(CARLINK_PACKAGE);
        if (carLink != null) {
            DashboardActivity.startApp(activity, carLink);
            return;
        }
        offerInstall(activity);
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
     * it allowed, resumeInstall (from the activity's onResume) carries on.
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

    /** From onResume: back from "Install unknown apps", the waiting install starts if it is now allowed. */
    static void resumeInstall(Activity activity) {
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
        if (changed) Log.i(TAG, session ? "phone projected: our navigation voice is quiet" : "projection ended");
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
