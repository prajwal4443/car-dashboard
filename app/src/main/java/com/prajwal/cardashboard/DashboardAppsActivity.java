package com.prajwal.cardashboard;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The car dashboard's All apps page: every app as a big icon, full screen. Tap opens it full screen; hold to add it
 * to the dashboard's Home row or take it off. Apps already on Home carry a check.
 */
public class DashboardAppsActivity extends Activity {
    private final List<ResolveInfo> apps = new ArrayList<>();
    private final Set<String> onHome = new HashSet<>();
    private AppsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_apps);
        findViewById(R.id.dashAppsClose).setOnClickListener(v -> finish());
        GridView grid = findViewById(R.id.dashAppsGrid);
        adapter = new AppsAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            DashboardActivity.launch(this, apps.get(position));
            finish();
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            // Maps has its own card on the dashboard and is never a Home tile: nothing to add or remove.
            if (apps.get(position).activityInfo.packageName.equals(DashboardActivity.MAPS_APP)) return false;
            showMenu(view, apps.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> launchable = DashboardActivity.findLaunchableApps(pm);
        onHome.clear();
        onHome.addAll(DashboardActivity.buildHomePackages(this, launchable));
        Set<String> excluded = new HashSet<>();
        excluded.add(getPackageName());
        apps.clear();
        for (ResolveInfo app : launchable.values()) {
            if (!excluded.contains(app.activityInfo.packageName)) apps.add(app);
        }
        apps.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        ((TextView) findViewById(R.id.dashAppsCount)).setText(getString(R.string.dash_apps_count, apps.size()));
        adapter.notifyDataSetChanged();
    }

    private void showMenu(View anchor, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        boolean home = onHome.contains(pkg);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(home ? R.string.dash_remove_home : R.string.dash_add_home);
        menu.setOnMenuItemClickListener(item -> {
            if (DashboardActivity.saveHomeChoice(this, pkg, !home)) loadApps();
            return true;
        });
        menu.show();
    }

    private final class AppsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public ResolveInfo getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View cell = convertView != null ? convertView
                    : LayoutInflater.from(DashboardAppsActivity.this).inflate(R.layout.dash_app_cell, parent, false);
            ResolveInfo app = apps.get(position);
            PackageManager pm = getPackageManager();
            CharSequence label = app.loadLabel(pm);
            ((ImageView) cell.findViewById(R.id.dashAppIcon)).setImageDrawable(app.loadIcon(pm));
            ((TextView) cell.findViewById(R.id.dashAppLabel)).setText(label);
            cell.findViewById(R.id.dashAppOnHome)
                    .setVisibility(onHome.contains(app.activityInfo.packageName) ? View.VISIBLE : View.GONE);
            cell.setContentDescription(label);
            return cell;
        }
    }
}
