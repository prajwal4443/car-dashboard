package com.prajwal.cardashboard;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.function.Consumer;

/**
 * Notification access for the car dashboard ("Dashboard music"):
 * - the Now playing card: Android only lists other apps' media sessions to an enabled notification listener;
 * - the map's next-turn banner: the navigation app's own navigation notification (only MAPS_APP's, nothing else);
 * - AutoPlay: whether CarLink is projecting a phone, from its session notification (only CarLink's).
 *
 * Enable it in Settings, Apps, Special app access, Notification access.
 */
public class MusicListener extends NotificationListenerService {
    /** The next turn as the navigation app shows it: distance ("300 m"), instruction, turn arrow (may be null). */
    static final class Turn {
        final CharSequence distance;
        final CharSequence instruction;
        final Icon icon;

        Turn(CharSequence distance, CharSequence instruction, Icon icon) {
            this.distance = distance;
            this.instruction = instruction;
            this.icon = icon;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static String navigationPackage = "com.google.android.apps.maps";
    private static Turn lastTurn;
    private static Consumer<Turn> turnWatcher;

    /** Whether the user has given this listener notification access (Android's Notification access screen). */
    static boolean hasAccess(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName mine = new ComponentName(context, MusicListener.class);
        for (String entry : enabled.split(":")) {
            if (mine.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    /** The dashboard follows the turn banner while it is on screen; null stops. Hands over the current turn at once. */
    static void watchTurns(String mapsPackage, Consumer<Turn> watcher) {
        navigationPackage = mapsPackage;
        turnWatcher = watcher;
        if (watcher != null) watcher.accept(lastTurn);
    }

    @Override
    public void onListenerConnected() {
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return;
        for (StatusBarNotification posted : active) onNotificationPosted(posted);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification posted) {
        if (AutoPlay.CARLINK_PACKAGE.equals(posted.getPackageName())) {
            AutoPlay.trackNotification(this, posted.getKey(), posted.getNotification(), true);
            return;
        }
        if (!isNavigation(posted)) return;
        Bundle extras = posted.getNotification().extras;
        CharSequence distance = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence instruction = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (distance == null && instruction == null) return;
        publish(new Turn(distance, instruction, posted.getNotification().getLargeIcon()));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification removed) {
        if (AutoPlay.CARLINK_PACKAGE.equals(removed.getPackageName())) {
            AutoPlay.trackNotification(this, removed.getKey(), removed.getNotification(), false);
            return;
        }
        if (isNavigation(removed)) publish(null);
    }

    private static boolean isNavigation(StatusBarNotification posted) {
        if (!navigationPackage.equals(posted.getPackageName())) return false;
        Notification notification = posted.getNotification();
        return Notification.CATEGORY_NAVIGATION.equals(notification.category)
                || (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private static void publish(Turn turn) {
        MAIN.post(() -> {
            lastTurn = turn;
            Consumer<Turn> watcher = turnWatcher;
            if (watcher != null) watcher.accept(turn);
        });
    }
}
