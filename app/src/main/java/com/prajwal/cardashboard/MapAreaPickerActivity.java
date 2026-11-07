package com.prajwal.cardashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.PointF;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Download map area" picked like in Google Maps: a full-screen map to move and zoom, a square frame in the middle,
 * and the size of what is inside it (tiles, MB) updated as the map moves. The download button works once the area
 * fits OpenFreeMap's per-download limit (MapArea.TILE_LIMIT); the download then runs in the background (MapDownloads)
 * and dashboard settings shows its progress. The areas already downloaded show as green squares.
 *
 * With EXTRA_SHOW_AREA (a saved area's id, from dashboard settings) it shows that area instead: the map on its
 * square, its name, size and date, and a button to delete it.
 */
public class MapAreaPickerActivity extends Activity {
    static final String EXTRA_SHOW_AREA = "show_area";
    private static final String SAVED_SOURCE = "saved-areas";
    private static final String SAVED_FILL_LAYER = "saved-areas-fill";
    private static final String SAVED_LINE_LAYER = "saved-areas-line";
    private static final int SHOW_AREA_PADDING_DP = 48;
    /** Roughly the size of one OpenFreeMap vector tile, for the size estimate. */
    private static final long TILE_KB_ESTIMATE = 25;
    /** Opens over the car's area: about 60 km across in the frame. */
    private static final double START_ZOOM = 9.5;
    private static final LatLng NO_FIX_CENTER = new LatLng(20.59, 78.96);
    private static final double NO_FIX_ZOOM = 4;

    private MapView mapView;
    private MapLibreMap map;
    private View frame;
    private TextView size;
    private TextView download;
    private MapArea picked;
    /** The saved area shown (view mode), or null when picking a new one. */
    private String shownAreaId;
    private MapDownloads.Area shownArea;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DashboardMap.prepare(this);
        setContentView(R.layout.activity_map_area_picker);
        frame = findViewById(R.id.pickFrame);
        size = findViewById(R.id.pickSize);
        download = findViewById(R.id.pickDownload);
        findViewById(R.id.pickCancel).setOnClickListener(v -> finish());
        shownAreaId = getIntent().getStringExtra(EXTRA_SHOW_AREA);
        if (shownAreaId != null) {
            ((TextView) findViewById(R.id.pickTitle)).setText(R.string.pick_saved_title);
            frame.setVisibility(View.GONE);
            download.setText(R.string.pick_delete);
            download.setOnClickListener(v -> deleteShownArea());
        } else {
            download.setOnClickListener(v -> startDownload());
        }
        mapView = findViewById(R.id.pickMap);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(loaded -> {
            map = loaded;
            loaded.getUiSettings().setLogoEnabled(false);
            loaded.getUiSettings().setAttributionEnabled(false);
            loaded.getUiSettings().setRotateGesturesEnabled(false);
            loaded.getUiSettings().setTiltGesturesEnabled(false);
            Location here = DashboardMap.findLastLocation(this);
            loaded.setCameraPosition(new CameraPosition.Builder()
                    .target(here != null ? new LatLng(here.getLatitude(), here.getLongitude()) : NO_FIX_CENTER)
                    .zoom(here != null ? START_ZOOM : NO_FIX_ZOOM)
                    .build());
            String style = DashboardMap.lookupStyle(this);
            loaded.setStyle(new Style.Builder().fromUri(DashboardMap.buildStyleUrl(
                    DashboardMap.STYLE_OFF.equals(style) ? DashboardMap.STYLE_DARK : style)), this::showSavedAreas);
            if (shownAreaId == null) {
                loaded.addOnCameraIdleListener(this::measure);
                frame.post(this::measure);
            }
        });
    }

    /** The areas downloaded so far as green squares; in view mode, the map on the shown one and what it is. */
    private void showSavedAreas(Style ready) {
        MapDownloads.listAreas(this, areas -> {
            if (isFinishing() || isDestroyed()) return;
            List<Feature> squares = new ArrayList<>();
            for (MapDownloads.Area area : areas) {
                LatLngBounds bounds = area.lookupBounds();
                if (bounds == null) continue;
                List<Point> corners = new ArrayList<>();
                corners.add(Point.fromLngLat(bounds.getLonWest(), bounds.getLatNorth()));
                corners.add(Point.fromLngLat(bounds.getLonEast(), bounds.getLatNorth()));
                corners.add(Point.fromLngLat(bounds.getLonEast(), bounds.getLatSouth()));
                corners.add(Point.fromLngLat(bounds.getLonWest(), bounds.getLatSouth()));
                corners.add(corners.get(0));
                squares.add(Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(corners))));
                if (area.id.equals(shownAreaId)) showArea(area, bounds);
            }
            FeatureCollection all = FeatureCollection.fromFeatures(squares);
            ready.addSource(new GeoJsonSource(SAVED_SOURCE, all));
            ready.addLayer(new FillLayer(SAVED_FILL_LAYER, SAVED_SOURCE).withProperties(
                    PropertyFactory.fillColor("#2E7D32"), PropertyFactory.fillOpacity(0.25f)));
            ready.addLayer(new LineLayer(SAVED_LINE_LAYER, SAVED_SOURCE).withProperties(
                    PropertyFactory.lineColor("#43A047"), PropertyFactory.lineWidth(2.5f)));
            if (shownAreaId != null && shownArea == null) size.setText(R.string.pick_saved_gone);
        });
    }

    private void showArea(MapDownloads.Area area, LatLngBounds bounds) {
        shownArea = area;
        size.setText(getString(R.string.pick_saved_value, area.name, area.date));   // the name says place and size
        int padding = Math.round(SHOW_AREA_PADDING_DP * getResources().getDisplayMetrics().density);
        View top = findViewById(R.id.pickTop);
        View bottom = findViewById(R.id.pickBottom);
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding, top.getHeight() + padding, padding,
                bottom.getHeight() + padding), 800);
    }

    private void deleteShownArea() {
        if (shownArea == null) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(R.string.set_delete_area_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> MapDownloads.deleteArea(shownArea, () -> {
                    PlaceIndexService.rebuild(this);   // its places and roads leave offline search and routing
                    Toast.makeText(this, R.string.pick_deleted, Toast.LENGTH_SHORT).show();
                    finish();
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** What the frame holds now: its corners on the map, its size, and whether one download can take it. */
    private void measure() {
        if (map == null || frame.getWidth() == 0) return;
        LatLng topLeft = map.getProjection().fromScreenLocation(new PointF(frame.getLeft(), frame.getTop()));
        LatLng bottomRight = map.getProjection().fromScreenLocation(new PointF(frame.getRight(), frame.getBottom()));
        picked = MapArea.fromBounds(topLeft.getLatitude(), bottomRight.getLatitude(), bottomRight.getLongitude(),
                topLeft.getLongitude());
        long tiles = picked.countTiles();
        String across = String.format(Locale.getDefault(), "%.0f × %.0f km", picked.measureWidthKm(),
                picked.measureHeightKm());
        boolean fits = tiles <= MapArea.TILE_LIMIT;
        size.setText(fits ? getString(R.string.pick_size, across, tiles, Math.max(1, tiles * TILE_KB_ESTIMATE / 1024))
                : getString(R.string.pick_too_big, across, tiles, MapArea.TILE_LIMIT));
        download.setEnabled(fits && MapDownloads.lookupPercent() < 0);
        download.setAlpha(download.isEnabled() ? 1f : 0.4f);
    }

    private void startDownload() {
        MapArea area = picked;
        if (area == null || area.countTiles() > MapArea.TILE_LIMIT) return;
        // Into the public folder first when it can be (kept after an uninstall), then the download.
        DashboardMap.usePublicStorage(this, inPublicFolder -> MapDownloads.download(this, area));
        Toast.makeText(this, R.string.pick_started, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }
}
