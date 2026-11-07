package com.prajwal.cardashboard;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * What the car dashboard does when the car starts: play the picked music app.
 *
 * "The car starts" is a new boot, or the first Home after the screen was off at least MIN_SLEEP_MS: head units mostly
 * sleep with the ignition off rather than reboot. The sleep is written to storage when the screen goes off, so a
 * launcher killed overnight still sees it, and is claimed once by whichever comes first, Home reappearing or the screen
 * turning on.
 *
 * Music, each step given a few seconds to start playback before the next: the app's own session if it has one, its
 * media browser service (how car screens resume it), a media button press to its receiver (how a headset does). If
 * nothing plays, the app is opened so it restores its player, play is pressed once its session appears, and Home
 * comes back, inside Android's grace period for starting activities.
 *
 * The dashboard runs this only once Home is back on screen after a start (some units show a camera app first):
 * Android lets an app open other apps only while it is in front.
 */
final class CarStartup {
    private static final String TAG = "CarDash.CarStartup";
    private static final String PREFS = "dashboard";
    private static final String MUSIC_KEY = "startup_music_app";
    private static final String BOOT_KEY = "startup_boot";
    private static final String SLEEP_AT_KEY = "sleep_started_at";
    private static final String SLEEP_BOOT_KEY = "sleep_boot";
    private static final String LAST_RUN_KEY = "startup_last_run";
    /** A screen-off at least this long counts as the car having been switched off. */
    static final long MIN_SLEEP_MS = 60 * 1000L;
    private static final long PLAY_CHECK_MS = 5000;
    /** The media browser step starts the app's player from cold, so it gets longer before the next step. */
    private static final long BROWSER_CHECK_MS = 8000;
    private static final long BROWSER_HOLD_MS = 15000;
    private static final long SESSION_WAIT_MS = 7000;
    private static final long SESSION_POLL_MS = 500;

    /** Created on first use, on the main thread's looper (not at class load, so the pure checks run in unit tests). */
    private static Handler handler;

    private CarStartup() {
    }

    static String lookupMusicApp(Context context) {
        return prefs(context).getString(MUSIC_KEY, null);
    }

    /** The app to play at startup, or none with null. */
    static void saveMusicApp(Context context, String pkg) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (pkg == null) editor.remove(MUSIC_KEY);
        else editor.putString(MUSIC_KEY, pkg);
        editor.apply();
    }

    /** Forgets every dashboard choice and record kept in its preferences (Reset everything in dashboard settings). */
    static void clearSettings(Context context) {
        prefs(context).edit().clear().apply();
    }

    /** What the last startup did, for the Now playing hold menu; null before the first one. */
    static String lookupLastRun(Context context) {
        return prefs(context).getString(LAST_RUN_KEY, null);
    }

    /** The screen went off: the start of a possible sleep, kept until claimed. */
    static void recordSleep(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(SLEEP_AT_KEY)) return;
        prefs.edit().putLong(SLEEP_AT_KEY, SystemClock.elapsedRealtime())
                .putInt(SLEEP_BOOT_KEY, countBoots(context)).apply();
    }

    /** True once for each new boot or long sleep; claims it, so the other trigger finds nothing to do. */
    /** How many times the device has started up (Android counts them): a new number means a new boot. */
    private static int countBoots(Context context) {
        return android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
    }

    static boolean claimStartup(Context context) {
        SharedPreferences prefs = prefs(context);
        int boot = countBoots(context);
        boolean due = isStartupDue(boot, prefs.getInt(BOOT_KEY, -1), prefs.getLong(SLEEP_AT_KEY, -1),
                prefs.getInt(SLEEP_BOOT_KEY, -1), SystemClock.elapsedRealtime());
        prefs.edit().putInt(BOOT_KEY, boot).remove(SLEEP_AT_KEY).remove(SLEEP_BOOT_KEY).apply();
        return due;
    }

    /** A boot not seen before, or a sleep in this boot that lasted at least MIN_SLEEP_MS. */
    static boolean isStartupDue(int boot, int handledBoot, long sleepAt, int sleepBoot, long now) {
        if (boot >= 0 && boot != handledBoot) return true;
        return sleepAt >= 0 && sleepBoot == boot && now - sleepAt >= MIN_SLEEP_MS;
    }

    /** Starts the picked app playing (see the class comment). */
    static void playMusic(Context context) {
        String pkg = lookupMusicApp(context);
        if (pkg == null || pkg.isEmpty() || findPlayingPackage(context) != null) return;
        tryStep(context, pkg, 0);
    }

    /**
     * Step {@code step} of: the app's running player, its media browser service (the Android Auto entry point, which
     * starts the player without the app's screen), its media button receiver. Each one that could be tried gets its
     * wait, then the next step runs unless something plays. After the last, the app is opened.
     */
    private static void tryStep(Context context, String pkg, int step) {
        String how;
        long wait = PLAY_CHECK_MS;
        if (step == 0) {
            how = playViaSession(context, pkg);
        } else if (step == 1) {
            how = playViaBrowser(context, pkg);
            wait = BROWSER_CHECK_MS;
        } else if (step == 2) {
            how = playViaButton(context, pkg);
        } else {
            openAndPlay(context, pkg);
            return;
        }
        if (how == null) {
            tryStep(context, pkg, step + 1);
            return;
        }
        lookupHandler().postDelayed(() -> {
            if (findPlayingPackage(context) != null) {
                recordRun(context, context.getString(R.string.startup_played, how));
            } else {
                tryStep(context, pkg, step + 1);
            }
        }, wait);
    }

    private static String playViaSession(Context context, String pkg) {
        MediaController session = findSession(context, pkg);
        if (session == null) return null;
        session.getTransportControls().play();
        return context.getString(R.string.startup_via_session);
    }

    /** Connects to the app's MediaBrowserService and presses play on the session it hands over. */
    private static String playViaBrowser(Context context, String pkg) {
        List<ResolveInfo> services = context.getPackageManager().queryIntentServices(
                new Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0);
        if (services.isEmpty()) return null;
        ComponentName service = new ComponentName(pkg, services.get(0).serviceInfo.name);
        MediaBrowser[] browser = new MediaBrowser[1];
        browser[0] = new MediaBrowser(context, service, new MediaBrowser.ConnectionCallback() {
            @Override
            public void onConnected() {
                try {
                    new MediaController(context, browser[0].getSessionToken()).getTransportControls().play();
                } catch (RuntimeException e) {
                    Log.w(TAG, "media browser play failed", e);
                }
                // Playback keeps its own foreground service; the connection is only needed to press play.
                lookupHandler().postDelayed(browser[0]::disconnect, BROWSER_HOLD_MS);
            }

            @Override
            public void onConnectionFailed() {
                Log.i(TAG, pkg + " refused the media browser connection");
            }
        }, null);
        browser[0].connect();
        return context.getString(R.string.startup_via_browser);
    }

    /** A media button press to the app's media button receiver, as a headset sends it. */
    private static String playViaButton(Context context, String pkg) {
        List<ResolveInfo> receivers = context.getPackageManager().queryBroadcastReceivers(
                new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0);
        if (receivers.isEmpty()) return null;
        ComponentName receiver = new ComponentName(pkg, receivers.get(0).activityInfo.name);
        sendPlayKey(context, receiver, KeyEvent.ACTION_DOWN);
        sendPlayKey(context, receiver, KeyEvent.ACTION_UP);
        return context.getString(R.string.startup_via_button);
    }

    /** Opens the app so it restores its player, presses play once its session appears, then goes back Home. */
    private static void openAndPlay(Context context, String pkg) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            recordRun(context, context.getString(R.string.startup_failed));
            return;
        }
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + pkg, e);
            recordRun(context, context.getString(R.string.startup_failed));
            return;
        }
        long deadline = SystemClock.uptimeMillis() + SESSION_WAIT_MS;
        Runnable[] waitForSession = new Runnable[1];
        waitForSession[0] = () -> {
            MediaController session = findSession(context, pkg);
            if (session == null && SystemClock.uptimeMillis() < deadline) {
                lookupHandler().postDelayed(waitForSession[0], SESSION_POLL_MS);
                return;
            }
            if (session != null) session.getTransportControls().play();
            recordRun(context, context.getString(session != null ? R.string.startup_opened_played
                    : R.string.startup_opened_only));
            returnHome(context);
        };
        lookupHandler().postDelayed(waitForSession[0], SESSION_POLL_MS);
    }

    private static void returnHome(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not return Home", e);
        }
    }

    /** The package of the session that is playing now, or null (also when sessions can't be read). */
    static String findPlayingPackage(Context context) {
        for (MediaController session : listSessions(context)) {
            PlaybackState state = session.getPlaybackState();
            if (state != null && DashboardApps.isPlaying(state.getState())) return session.getPackageName();
        }
        return null;
    }

    private static MediaController findSession(Context context, String pkg) {
        for (MediaController session : listSessions(context)) {
            if (pkg.equals(session.getPackageName())) return session;
        }
        return null;
    }

    private static List<MediaController> listSessions(Context context) {
        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions == null) return Collections.emptyList();
        try {
            return sessions.getActiveSessions(new ComponentName(context, MusicListener.class));
        } catch (SecurityException notAllowed) {
            return Collections.emptyList();
        }
    }

    private static void sendPlayKey(Context context, ComponentName receiver, int action) {
        long now = SystemClock.uptimeMillis();
        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)));
    }

    private static void recordRun(Context context, String outcome) {
        String at = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        prefs(context).edit().putString(LAST_RUN_KEY, at + " · " + outcome).apply();
    }

    private static Handler lookupHandler() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
