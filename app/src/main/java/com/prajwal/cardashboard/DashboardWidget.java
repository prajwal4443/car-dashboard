package com.prajwal.cardashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * One Android widget on the car dashboard, in place of the Now playing card (Amazon Music's player widget, say).
 *
 * Picking: a list of every installed widget, then Android's own "allow to add widgets" prompt when the launcher is not
 * yet allowed to bind them (ACTION_APPWIDGET_BIND), then the widget's own setup screen if it has one. The widget id is
 * kept in the dashboard's preferences, so the widget survives restarts; removing it frees the id.
 */
final class DashboardWidget {
    private static final String TAG = "CarDash.Widget";
    private static final int HOST_ID = 0xCA2;
    static final int REQUEST_BIND = 4101;
    static final int REQUEST_CONFIGURE = 4102;
    private static final String PREFS = "dashboard";
    private static final String WIDGET_KEY = "widget_id";
    /** The id being bound or set up, kept across the Home activity being recreated during the system prompts. */
    private static final String PENDING_KEY = "widget_pending_id";

    private final Activity activity;
    private final FrameLayout slot;
    private final Runnable changed;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    private final SharedPreferences prefs;

    DashboardWidget(Activity activity, FrameLayout slot, Runnable changed) {
        this.activity = activity;
        this.slot = slot;
        this.changed = changed;
        manager = AppWidgetManager.getInstance(activity);
        host = new AppWidgetHost(activity.getApplicationContext(), HOST_ID);
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    void start() {
        try {
            host.startListening();
        } catch (RuntimeException e) {
            Log.w(TAG, "widget host could not start", e);
        }
    }

    void stop() {
        try {
            host.stopListening();
        } catch (RuntimeException ignored) {
        }
    }

    /** The picked widget's name ("Amazon Music · Player"), or null with none picked. For dashboard settings. */
    static String describePicked(Context context) {
        int id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(WIDGET_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id);
        return info != null ? describe(context.getPackageManager(), info) : null;
    }

    /** Takes the widget off the dashboard (the Now playing card comes back) and frees its id. */
    static void removePicked(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int id = prefs.getInt(WIDGET_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        prefs.edit().remove(WIDGET_KEY).remove(PENDING_KEY).apply();
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) new AppWidgetHost(context, HOST_ID).deleteAppWidgetId(id);
    }

    /** Frees every widget id this dashboard ever took, including half-finished picks. For Reset everything. */
    static void resetHost(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(WIDGET_KEY).remove(PENDING_KEY).apply();
        new AppWidgetHost(context, HOST_ID).deleteHost();
    }

    /** A widget is picked and its app is still installed. */
    boolean isActive() {
        int id = prefs.getInt(WIDGET_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        return id != AppWidgetManager.INVALID_APPWIDGET_ID && manager.getAppWidgetInfo(id) != null;
    }

    /** Puts the picked widget in the slot, sized to it. */
    void show() {
        slot.removeAllViews();
        int id = prefs.getInt(WIDGET_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null) return;
        AppWidgetHostView view = host.createView(activity, id, info);
        view.setPadding(0, 0, 0, 0);
        slot.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        slot.post(() -> {
            float density = activity.getResources().getDisplayMetrics().density;
            int width = (int) (slot.getWidth() / density);
            int height = (int) (slot.getHeight() / density);
            if (width > 0 && height > 0) view.updateAppWidgetSize(null, width, height, width, height);
        });
    }

    /** The list of installed widgets; picking one binds it, asking Android for permission when needed. */
    void pick() {
        PackageManager pm = activity.getPackageManager();
        List<AppWidgetProviderInfo> providers = new ArrayList<>(manager.getInstalledProviders());
        if (providers.isEmpty()) {
            Toast.makeText(activity, R.string.dash_widget_none, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (AppWidgetProviderInfo provider : providers) labels.add(describe(pm, provider));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) order.add(i);
        order.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        CharSequence[] items = new CharSequence[order.size()];
        for (int i = 0; i < items.length; i++) items[i] = labels.get(order.get(i));
        new AlertDialog.Builder(activity)
                .setTitle(R.string.dash_widget_pick_title)
                .setItems(items, (dialog, which) -> bind(providers.get(order.get(which))))
                .show();
    }

    /** Back to the Now playing card: frees the widget's id. */
    void remove() {
        int id = prefs.getInt(WIDGET_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        prefs.edit().remove(WIDGET_KEY).apply();
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(id);
        slot.removeAllViews();
        changed.run();
    }

    /** From onActivityResult: the answer to the bind prompt or the widget's setup screen. False when not ours. */
    boolean handleResult(int request, int result) {
        if (request != REQUEST_BIND && request != REQUEST_CONFIGURE) return false;
        int id = prefs.getInt(PENDING_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return true;
        if (result != Activity.RESULT_OK) {
            host.deleteAppWidgetId(id);
            prefs.edit().remove(PENDING_KEY).apply();
            return true;
        }
        if (request == REQUEST_BIND) configure(id);
        else save(id);
        return true;
    }

    private void bind(AppWidgetProviderInfo provider) {
        int id = host.allocateAppWidgetId();
        prefs.edit().putInt(PENDING_KEY, id).apply();
        if (manager.bindAppWidgetIdIfAllowed(id, provider.provider)) {
            configure(id);
            return;
        }
        try {
            activity.startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider), REQUEST_BIND);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no widget permission prompt on this unit", e);
            host.deleteAppWidgetId(id);
            prefs.edit().remove(PENDING_KEY).apply();
            Toast.makeText(activity, R.string.dash_widget_not_allowed, Toast.LENGTH_LONG).show();
        }
    }

    /** The widget's own setup screen when it has one, else straight onto the dashboard. */
    private void configure(int id) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "widget setup screen would not open; adding the widget as it is", e);
            }
        }
        save(id);
    }

    private void save(int id) {
        int old = prefs.getInt(WIDGET_KEY, AppWidgetManager.INVALID_APPWIDGET_ID);
        prefs.edit().putInt(WIDGET_KEY, id).remove(PENDING_KEY).apply();
        if (old != AppWidgetManager.INVALID_APPWIDGET_ID && old != id) host.deleteAppWidgetId(old);
        changed.run();
    }

    private static String describe(PackageManager pm, AppWidgetProviderInfo provider) {
        String widget = provider.loadLabel(pm);
        String app;
        try {
            app = pm.getApplicationLabel(pm.getApplicationInfo(provider.provider.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            app = provider.provider.getPackageName();
        }
        return widget == null || widget.equals(app) ? app : app + " · " + widget;
    }
}
