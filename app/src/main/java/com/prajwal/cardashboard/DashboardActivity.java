package com.prajwal.cardashboard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A car-style Home for touch head units: clock, a Maps card (a tap opens the navigation app), a Now playing card,
 * and a scrolling row of big Home apps plus All apps. Picked as Home in the Home app
 * chooser ("Car Dashboard"); it also has an ordinary app icon.
 *
 * Home apps: the apps added with "Add to Home" (HomeTiles), minus the ones removed. Hold an app to remove it; add
 * apps from All apps (DashboardAppsActivity). Maps: MAPS_APP.
 */
public class DashboardActivity extends Activity {
    static final String TAG = "CarDash.Dashboard";
    /** The navigation app the Maps card opens. */
    static final String MAPS_APP = "com.google.android.apps.maps";
    /** At most this many Home apps; the row scrolls sideways past the first few. */
    private static final int MAX_HOME_APPS = 24;
    /** Startup waits this long after Home is back on screen, for audio and the music app to be ready. */
    private static final long STARTUP_DELAY_MS = 2500;
    /** Clear reports the memory freed after this long, once the killed processes are gone. */
    private static final long CLEAR_REPORT_DELAY_MS = 1000;
    private static final long ART_COLOR_FADE_MS = 700;

    private NowPlaying nowPlaying;
    private DashboardWidget widget;
    private View card;
    private View widgetArea;
    private LinearLayout tiles;
    private TextView greeting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver screenWatcher;
    private ValueAnimator backgroundFade;
    /** A start was claimed; startup runs when Home is next on screen. */
    private boolean startupPending;
    private boolean resumed;
    private int shownBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        shownBackground = getColor(R.color.dash_background);
        tiles = findViewById(R.id.dashTiles);
        greeting = findViewById(R.id.dashGreeting);
        View settings = findViewById(R.id.dashSettings);
        if (settings != null) {
            settings.setOnClickListener(v -> startApp(this, new Intent(this, DashboardSettingsActivity.class)));
        }
        View clear = findViewById(R.id.dashClear);
        if (clear != null) clear.setOnClickListener(v -> clearBackgroundApps());
        View maps = findViewById(R.id.dashMaps);
        maps.setOnClickListener(v -> openMaps());
        findViewById(R.id.dashAllApps).setOnClickListener(v -> openAllApps());
        card = findViewById(R.id.nowPlaying);
        widgetArea = findViewById(R.id.dashWidgetArea);
        if (widgetArea != null) {
            widget = new DashboardWidget(this, findViewById(R.id.dashWidgetSlot), this::showCardOrWidget);
            findViewById(R.id.dashWidgetMenu).setOnClickListener(this::showWidgetMenu);
        }
        try {
            nowPlaying = new NowPlaying(this, card, widget != null ? widget::pick : null, this::showArtColor);
        } catch (RuntimeException e) {
            Log.w(TAG, "Now playing card off", e);
            card.setVisibility(View.GONE);
        }
        showCardOrWidget();
        watchScreen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        claimStartup();
        if (nowPlaying != null) nowPlaying.start();
        if (widget != null) widget.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        runPendingStartup();
        showGreeting();
        showCardOrWidget();   // the widget may have been taken off in dashboard settings
        fillTiles();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Stopped because the screen went off (the car switched off): the start of a sleep, for the next startup.
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) CarStartup.recordSleep(this);
        if (nowPlaying != null) nowPlaying.stop();
        if (widget != null) widget.stop();
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (widget != null && widget.handleResult(requestCode, resultCode)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Tints the dashboard's background toward the album art's colour (the default navy with no art), fading over
     * ART_COLOR_FADE_MS: a soft wash from that colour at the top left to the default at the bottom right.
     */
    private void showArtColor(Integer color) {
        int target = color != null ? color : getColor(R.color.dash_background);
        if (target == shownBackground) return;
        if (backgroundFade != null) backgroundFade.cancel();
        int base = getColor(R.color.dash_background);
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {shownBackground, base});
        getWindow().setBackgroundDrawable(wash);
        backgroundFade = ValueAnimator.ofArgb(shownBackground, target);
        backgroundFade.setDuration(ART_COLOR_FADE_MS);
        backgroundFade.addUpdateListener(animation -> {
            int now = (int) animation.getAnimatedValue();
            wash.setColors(new int[] {now, base});
            getWindow().setStatusBarColor(now);
        });
        backgroundFade.start();
        shownBackground = target;
    }

    /** The picked widget in the Now playing card's place, or the card when no widget is picked. */
    private void showCardOrWidget() {
        boolean useWidget = widget != null && widget.isActive();
        if (widgetArea != null) widgetArea.setVisibility(useWidget ? View.VISIBLE : View.GONE);
        if (nowPlaying != null) card.setVisibility(useWidget ? View.GONE : View.VISIBLE);
        if (useWidget) widget.show();
    }

    private void showWidgetMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.dash_widget_change);
        menu.getMenu().add(0, 2, 0, R.string.dash_widget_remove);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) widget.pick();
            else widget.remove();
            return true;
        });
        menu.show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (backgroundFade != null) backgroundFade.cancel();
        if (screenWatcher != null) unregisterReceiver(screenWatcher);
        if (nowPlaying != null) nowPlaying.release();
        super.onDestroy();
    }

    /** The screen going off starts a sleep; the screen coming on may end one (see CarStartup). */
    private void watchScreen() {
        screenWatcher = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) CarStartup.recordSleep(context);
                else claimStartup();
            }
        };
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenWatcher, screen);
    }

    /** After a new boot or a long sleep: startup waits for Home to be on screen (see runPendingStartup). */
    private void claimStartup() {
        if (!CarStartup.claimStartup(this)) return;
        startupPending = true;
        if (resumed) runPendingStartup();
    }

    /** From onResume: the startup music, only while Home is in front (Android lets an app open others only then). */
    private void runPendingStartup() {
        if (!startupPending) return;
        startupPending = false;
        handler.postDelayed(() -> CarStartup.playMusic(this), STARTUP_DELAY_MS);
    }

    /**
     * The Clear pill: ends the background processes of every app on the unit's app list except this launcher and the
     * app playing music, then reports the memory freed. Recents keeps its cards; only the processes go.
     */
    private void clearBackgroundApps() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(before);
        String playing = CarStartup.findPlayingPackage(this);
        for (String pkg : findLaunchableApps(getPackageManager()).keySet()) {
            if (pkg.equals(getPackageName()) || pkg.equals(playing)) continue;
            manager.killBackgroundProcesses(pkg);
        }
        handler.postDelayed(() -> {
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(after);
            String freed = DashboardApps.formatFreedMemory(after.availMem - before.availMem);
            Toast.makeText(this, freed != null ? getString(R.string.dash_cleared, freed)
                    : getString(R.string.dash_cleared_plain), Toast.LENGTH_SHORT).show();
        }, CLEAR_REPORT_DELAY_MS);
    }

    /** As Home there is nothing to go back to; opened from its app icon (another Home is the default), Back closes it. */
    @Override
    public void onBackPressed() {
        if (!isDefaultHome()) finish();
    }

    private boolean isDefaultHome() {
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && home.activityInfo != null
                && getPackageName().equals(home.activityInfo.packageName)
                && DashboardActivity.class.getName().equals(home.activityInfo.name);
    }

    /** Every app with a launcher entry, by package (the first entry of each). */
    static Map<String, ResolveInfo> findLaunchableApps(PackageManager pm) {
        Map<String, ResolveInfo> launchable = new HashMap<>();
        for (ResolveInfo app : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (app.activityInfo != null) launchable.putIfAbsent(app.activityInfo.packageName, app);
        }
        return launchable;
    }

    /** The Home apps in order: the added apps by name; never this app or the Maps app (the map card is Maps). */
    static List<String> buildHomePackages(Context context, Map<String, ResolveInfo> launchable) {
        PackageManager pm = context.getPackageManager();
        Map<String, String> labels = new HashMap<>();
        List<String> picks = new ArrayList<>(HomeTiles.loadAdded(context));
        for (String pkg : picks) {
            ResolveInfo app = launchable.get(pkg);
            labels.put(pkg, app != null ? app.loadLabel(pm).toString() : pkg);
        }
        picks.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        return DashboardApps.pickTilePackages(picks, launchable.keySet(), HomeTiles.loadRemoved(context),
                Arrays.asList(context.getPackageName(), MAPS_APP), MAX_HOME_APPS);
    }

    /** Adds or removes an app from Home, with a message when the choice could not be saved. */
    static boolean saveHomeChoice(Context context, String pkg, boolean onHome) {
        if (HomeTiles.save(context, pkg, onHome)) return true;
        Toast.makeText(context, R.string.home_save_failed, Toast.LENGTH_LONG).show();
        return false;
    }

    static void launch(Context context, ResolveInfo app) {
        startApp(context, new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(app.activityInfo.packageName, app.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /** Opens an activity in its own task, with a message when it can't be opened. */
    static void startApp(Context context, Intent intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + intent, e);
            Toast.makeText(context, R.string.set_cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void showGreeting() {
        if (greeting == null) return;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int text = hour < 12 ? R.string.good_morning : hour < 17 ? R.string.good_afternoon : R.string.good_evening;
        greeting.setText(getString(text).toUpperCase(Locale.getDefault()));
    }

    private void fillTiles() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> launchable = findLaunchableApps(pm);
        tiles.removeAllViews();
        for (String pkg : buildHomePackages(this, launchable)) {
            ResolveInfo app = launchable.get(pkg);
            View tile = addTile(app.loadIcon(pm), app.loadLabel(pm));
            tile.setOnClickListener(v -> launch(this, app));
            tile.setOnLongClickListener(v -> {
                showTileMenu(v, pkg);
                return true;
            });
        }
    }

    private View addTile(Drawable icon, CharSequence label) {
        View tile = LayoutInflater.from(this).inflate(R.layout.dash_tile, tiles, false);
        ((ImageView) tile.findViewById(R.id.dashTileIcon)).setImageDrawable(icon);
        ((TextView) tile.findViewById(R.id.dashTileLabel)).setText(label);
        tile.setContentDescription(label);
        tiles.addView(tile);
        return tile;
    }

    private void showTileMenu(View anchor, String pkg) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.dash_remove_home);
        menu.setOnMenuItemClickListener(item -> {
            if (saveHomeChoice(this, pkg, false)) fillTiles();
            return true;
        });
        menu.show();
    }

    private void openAllApps() {
        startApp(this, new Intent(this, DashboardAppsActivity.class));
    }

    /** The Maps card: the navigation app, full screen. */
    private void openMaps() {
        Intent maps = getPackageManager().getLaunchIntentForPackage(MAPS_APP);
        if (maps == null) {
            Toast.makeText(this, getString(R.string.cant_open, MAPS_APP), Toast.LENGTH_SHORT).show();
            return;
        }
        startApp(this, maps);
    }
}
