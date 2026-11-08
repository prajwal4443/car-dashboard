package com.prajwal.cardashboard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The car dashboard's settings page (the Settings pill): what it is set to now, and the ways back to a clean state
 * when something sticks. Every reset asks first. Android's own settings and this app's App info (force stop, clear
 * data) are one tap away for when the dashboard itself is the problem.
 */
public class DashboardSettingsActivity extends Activity {
    private static final int REQUEST_STORAGE = 4301;
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_settings);
        rows = findViewById(R.id.dashSettingsRows);
        findViewById(R.id.dashSettingsClose).setOnClickListener(v -> finish());
    }

    @Override
    protected void onPause() {
        MapDownloads.watch(null);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MapDownloads.watch(() -> runOnUiThread(this::showRows));
        showRows();
        AutoPlay.resumeAfterSettings(this);
    }

    private void showRows() {
        rows.removeAllViews();
        PackageManager pm = getPackageManager();

        addHeading(R.string.set_heading_now);
        String music = CarStartup.lookupMusicApp(this);
        addRow(R.string.set_startup_music, music != null ? describeApp(music) : getString(R.string.set_off),
                music != null ? v -> confirm(R.string.set_startup_music_off_confirm, () -> {
                    CarStartup.saveMusicApp(this, null);
                    done(R.string.np_startup_cleared);
                }) : v -> toast(R.string.np_startup_hint));
        String lastRun = CarStartup.lookupLastRun(this);
        addRow(R.string.set_last_startup, lastRun != null ? lastRun : getString(R.string.set_not_yet), null);
        addRow(R.string.set_song_info, getString(MusicListener.hasAccess(this) ? R.string.set_allowed : R.string.set_not_allowed),
                v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addRow(R.string.set_map_style, getString(describeStyle(DashboardMap.lookupStyle(this))), v -> pickMapStyle());
        addRow(R.string.set_location, getString(DashboardMap.hasLocationPermission(this) ? R.string.set_allowed
                : R.string.dash_maps_allow), null);
        if (!DashboardMap.STYLE_OFF.equals(DashboardMap.lookupStyle(this))) {
            addRow(R.string.set_map_storage, getString(describeMapStorage()), DashboardMap.needsStoragePermission(this)
                    ? v -> requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE)
                    : null);
            addMapDownloadRows();
        }
        addCameraRows();
        addRow(R.string.set_maps_app, describeApp(DashboardActivity.MAPS_APP), null);
        addAutoPlayRows();
        int homeApps = DashboardActivity.buildHomePackages(this, DashboardActivity.findLaunchableApps(pm)).size();
        addRow(R.string.set_home_apps, getString(R.string.set_home_apps_value, homeApps), null);
        String widget = DashboardWidget.describePicked(this);
        addRow(R.string.set_widget, widget != null ? widget : getString(R.string.set_widget_none), null);
        long[] art = NowPlaying.measureArtCache(this);
        addRow(R.string.set_posters, getString(R.string.set_posters_value, art[0], formatMegabytes(art[1])), null);

        addHeading(R.string.set_heading_fix);
        addRow(R.string.set_clear_map, getString(R.string.set_clear_map_detail),
                v -> confirm(R.string.set_clear_map_confirm,
                        () -> DashboardMap.clearCache(this, () -> runOnUiThread(() -> done(R.string.set_done)))));
        addRow(R.string.set_clear_posters, getString(R.string.set_clear_posters_detail),
                v -> confirm(R.string.set_clear_posters_confirm, () -> {
                    NowPlaying.clearArtCache(this);
                    done(R.string.set_done);
                }));
        if (widget != null) {
            addRow(R.string.dash_widget_remove, widget, v -> confirm(R.string.set_remove_widget_confirm, () -> {
                DashboardWidget.removePicked(this);
                done(R.string.set_done);
            }));
        }
        addRow(R.string.set_reset_home, getString(R.string.set_reset_home_detail),
                v -> confirm(R.string.set_reset_home_confirm, () -> {
                    HomeTiles.clearAll(this);
                    done(R.string.set_done);
                }));
        addRow(R.string.set_reset_all, getString(R.string.set_reset_all_detail),
                v -> confirm(R.string.set_reset_all_confirm, () -> {
                    DashboardWidget.resetHost(this);
                    NowPlaying.clearArtCache(this);
                    CarStartup.clearSettings(this);
                    done(R.string.set_done);
                }));
        addRow(R.string.set_home_app, getString(R.string.set_home_app_value, describeCurrentHome()),
                v -> openHomePicker());
        addRow(R.string.set_app_info, getString(R.string.set_app_info_detail), v -> open(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        addRow(R.string.set_android_settings, null, v -> open(new Intent(Settings.ACTION_SETTINGS)));

        String version;
        try {
            version = pm.getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        addRow(R.string.set_version, version, null);
    }

    /** AutoPlay's tile shown or hidden on Home, the CarLink app it opens, and reconnecting at car start. */
    private void addAutoPlayRows() {
        boolean onHome = AutoPlay.isOnHome(this);
        addRow(R.string.set_autoplay, getString(onHome ? R.string.set_autoplay_shown : R.string.set_autoplay_hidden), v -> {
            if (!AutoPlay.saveOnHome(this, !onHome)) toast(R.string.home_save_failed);
            showRows();
        });
        String carLink = AutoPlay.lookupInstalledVersion(this);
        String update = CarLinkUpdateCheck.lookupAvailableUpdate(this);
        if (update != null) {
            addRow(R.string.set_autoplay_app, getString(R.string.set_autoplay_app_update, carLink, update),
                    v -> AutoPlay.installUpdate(this));
        } else {
            addRow(R.string.set_autoplay_app, carLink != null ? getString(R.string.set_autoplay_app_value, carLink)
                    : getString(R.string.set_autoplay_app_missing), v -> AutoPlay.open(this));
        }
        boolean reconnect = AutoPlay.isReconnectOn(this);
        addRow(R.string.set_autoplay_reconnect, describeReconnect(reconnect), v -> {
            if (!AutoPlay.saveReconnect(this, !reconnect)) toast(R.string.home_save_failed);
            showRows();
        });
    }

    /** On or off, and when on, what still stands in its way (the prompts on tapping AutoPlay ask for permissions). */
    private String describeReconnect(boolean reconnect) {
        if (!reconnect) return getString(R.string.set_off);
        String blocker = AutoPlay.findReconnectBlocker(this);
        if (blocker != null) return getString(R.string.set_autoplay_reconnect_waiting, blocker);
        return getString(Settings.canDrawOverlays(this) ? R.string.set_autoplay_reconnect_ready
                : R.string.set_autoplay_reconnect_no_return);
    }

    private void pickMapStyle() {
        String[] styles = DashboardMap.STYLES;
        CharSequence[] names = new CharSequence[styles.length];
        int current = 0;
        for (int i = 0; i < styles.length; i++) {
            names[i] = getString(describeStyle(styles[i]));
            if (styles[i].equals(DashboardMap.lookupStyle(this))) current = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_map_style)
                .setSingleChoiceItems(names, current, (dialog, which) -> {
                    DashboardMap.saveStyle(this, styles[which]);
                    dialog.dismiss();
                    showRows();
                })
                .show();
    }

    private static int describeStyle(String style) {
        switch (style) {
            case DashboardMap.STYLE_DARK_BLUE: return R.string.set_map_dark_blue;
            case DashboardMap.STYLE_LIGHT: return R.string.set_map_light;
            case DashboardMap.STYLE_WHITE: return R.string.set_map_white;
            case DashboardMap.STYLE_OFF: return R.string.set_map_off;
            default: return R.string.set_map_dark;
        }
    }

    /** "Download map area" with the running download's progress, then one row per saved area (tap to see it). */
    private void addMapDownloadRows() {
        int percent = MapDownloads.lookupPercent();
        String status = percent >= 0 ? getString(R.string.set_download_running, percent)
                : getString(R.string.set_download_detail);
        addRow(R.string.set_download, status, percent >= 0
                ? v -> confirm(R.string.set_download_stop_confirm,
                        () -> MapDownloads.stop(this, () -> done(R.string.set_download_stopped)))
                : v -> startActivity(new Intent(this, MapAreaPickerActivity.class)));
        View placeholder = new View(this);
        rows.addView(placeholder);
        MapDownloads.listAreas(this, areas -> {
            int at = rows.indexOfChild(placeholder);
            if (at < 0) return;   // the rows were rebuilt meanwhile
            rows.removeView(placeholder);
            for (MapDownloads.Area area : areas) {
                View row = buildRow(R.string.set_saved_area, getString(R.string.set_saved_area_value, area.name,
                        area.date), v -> startActivity(new Intent(this, MapAreaPickerActivity.class)
                        .putExtra(MapAreaPickerActivity.EXTRA_SHOW_AREA, area.id)));
                rows.addView(row, at++);
            }
        });
    }

    /** Speed camera alerts: on/off, distance, what the list holds, and keeping it up to date. */
    private void addCameraRows() {
        boolean on = CameraAlertService.isOn(this);
        addRow(R.string.cam_alerts, getString(on ? R.string.cam_alerts_on : R.string.set_off), v -> {
            CameraAlertService.saveOn(this, !on);
            CameraAlertService.update(this);
            showRows();
        });
        int meters = CameraAlertService.lookupAlertMeters(this);
        addRow(R.string.cam_distance, getString(R.string.cam_distance_value, meters), v -> pickAlertDistance());
        int all = CameraStore.load(this).size();
        int mine = CameraStore.countMine(this);
        String updated = CameraStore.lookupUpdated(this);
        int known = CameraStore.countKnown(this);
        addRow(R.string.cam_list, getString(R.string.cam_list_value, all, all - mine - known, known, mine,
                updated != null ? updated : getString(R.string.set_not_yet)), null);
        addRow(R.string.cam_update, getString(R.string.cam_update_detail), v -> {
            toast(R.string.cam_updating);
            CameraStore.updateFromOsm(this, error -> {
                if (isFinishing()) return;
                Toast.makeText(this, error == null
                        ? getString(R.string.cam_updated, CameraStore.load(this).size() - CameraStore.countMine(this))
                        : getString(R.string.cam_update_failed, error), Toast.LENGTH_LONG).show();
                CameraAlertService.update(this);
                showRows();
            });
        });
        addRow(R.string.cam_test, getString(R.string.cam_test_detail), v -> CameraAlertService.sendTest(this));
        if (mine > 0) {
            addRow(R.string.cam_delete_mine, getString(R.string.cam_delete_mine_detail, mine),
                    v -> confirm(R.string.cam_delete_mine_confirm, () -> {
                        CameraStore.deleteMine(this);
                        CameraAlertService.update(this);
                        done(R.string.set_done);
                    }));
        }
    }

    private void pickAlertDistance() {
        int[] choices = {50, 100, 200, 300};
        CharSequence[] names = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) names[i] = getString(R.string.cam_distance_value, choices[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.cam_distance)
                .setItems(names, (dialog, which) -> {
                    CameraAlertService.saveAlertMeters(this, choices[which]);
                    showRows();
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STORAGE) DashboardMap.usePublicStorage(this, inPublicFolder -> showRows());
    }

    private int describeMapStorage() {
        if (DashboardMap.isUsingPublicStorage(this)) return R.string.set_map_storage_public;
        if (DashboardMap.needsStoragePermission(this)) return R.string.set_map_storage_ask;
        return R.string.set_map_storage_app;
    }

    private void addHeading(int text) {
        TextView heading = (TextView) LayoutInflater.from(this).inflate(R.layout.dash_settings_heading, rows, false);
        heading.setText(text);
        rows.addView(heading);
    }

    /** One row: a title, the current value under it, and what tapping it does (null: information only). */
    private void addRow(int title, CharSequence value, View.OnClickListener click) {
        rows.addView(buildRow(title, value, click));
    }

    private View buildRow(int title, CharSequence value, View.OnClickListener click) {
        View row = LayoutInflater.from(this).inflate(R.layout.dash_settings_row, rows, false);
        ((TextView) row.findViewById(R.id.dashSettingTitle)).setText(title);
        TextView valueText = row.findViewById(R.id.dashSettingValue);
        valueText.setText(value);
        valueText.setVisibility(value != null ? View.VISIBLE : View.GONE);
        if (click != null) row.setOnClickListener(click);
        else row.setClickable(false);
        return row;
    }

    private void confirm(int question, Runnable action) {
        new AlertDialog.Builder(this)
                .setMessage(question)
                .setPositiveButton(R.string.set_confirm_yes, (dialog, which) -> action.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void done(int message) {
        toast(message);
        showRows();
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast(R.string.set_cant_open);
        }
    }

    /** The Home app Android opens now ("Car dashboard"), or "none set" while it would ask. */
    private String describeCurrentHome() {
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home == null || home.activityInfo == null || "android".equals(home.activityInfo.packageName)) {
            return getString(R.string.set_home_app_none);
        }
        return home.activityInfo.loadLabel(getPackageManager()).toString();
    }

    /**
     * Android's own Default Home app picker: an app cannot give up being Home by itself on Android 10. Falls back to
     * Default apps, then Settings, on units without that screen.
     */
    private void openHomePicker() {
        for (String action : new String[] {Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        toast(R.string.set_cant_open);
    }

    private String describeApp(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.set_app_missing, pkg);
        }
    }

    private static String formatMegabytes(long bytes) {
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
