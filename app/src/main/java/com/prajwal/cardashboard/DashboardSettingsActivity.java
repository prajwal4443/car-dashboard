package com.prajwal.cardashboard;

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
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_settings);
        rows = findViewById(R.id.dashSettingsRows);
        findViewById(R.id.dashSettingsClose).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        showRows();
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
        addRow(R.string.set_song_info, getString(hasNotificationAccess() ? R.string.set_allowed : R.string.set_not_allowed),
                v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addRow(R.string.set_maps_app, describeApp(DashboardActivity.MAPS_APP), null);
        int homeApps = DashboardActivity.buildHomePackages(this, DashboardActivity.findLaunchableApps(pm)).size();
        addRow(R.string.set_home_apps, getString(R.string.set_home_apps_value, homeApps), null);
        String widget = DashboardWidget.describePicked(this);
        addRow(R.string.set_widget, widget != null ? widget : getString(R.string.set_widget_none), null);
        long[] art = NowPlaying.measureArtCache(this);
        addRow(R.string.set_posters, getString(R.string.set_posters_value, art[0], formatMegabytes(art[1])), null);

        addHeading(R.string.set_heading_fix);
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

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName mine = new ComponentName(this, MusicListener.class);
        for (String entry : enabled.split(":")) {
            if (mine.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private static String formatMegabytes(long bytes) {
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
