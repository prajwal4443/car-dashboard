package com.prajwal.cardashboard;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Download map area" in dashboard settings: a square around the car saved for offline use, for every map style, one
 * style after another (the road tiles are shared, so only the first style downloads them; the others add their own
 * small style files). One area is several MapLibre offline regions sharing an area id in their metadata; the settings
 * page lists and deletes areas, not regions.
 *
 * OpenFreeMap's offline rules hold: at most MapArea.TILE_LIMIT tiles per download, at most 4 parallel requests (see
 * DashboardMap.prepare), and a User-Agent naming this app.
 */
final class MapDownloads {
    private static final String TAG = "CarDash.MapDownloads";

    /** Progress of the running download, 0 to 100, for the settings row. */
    interface Progress {
        void update(int percent);

        /** Finished: null on success, else what went wrong. */
        void done(String error);
    }

    /** The running download's progress, 0 to 100; -1 with none. Kept here so any screen can show it. */
    private static int percent = -1;
    /** The screen showing the progress now (dashboard settings), told of every change; null with none. */
    private static Runnable watcher;

    /** One saved area, all its styles together. */
    static final class Area {
        final String id;
        final String name;
        final String date;
        final List<OfflineRegion> regions = new ArrayList<>();

        Area(String id, String name, String date) {
            this.id = id;
            this.name = name;
            this.date = date;
        }

        /** The square that was downloaded (every style's region covers the same one); null if it cannot be read. */
        LatLngBounds lookupBounds() {
            for (OfflineRegion region : regions) {
                if (region.getDefinition() instanceof OfflineTilePyramidRegionDefinition) {
                    return ((OfflineTilePyramidRegionDefinition) region.getDefinition()).getBounds();
                }
            }
            return null;
        }
    }

    /** The download in progress: its area id and the region downloading now; null with none. */
    private static String runningArea;
    private static OfflineRegion runningRegion;
    private static boolean stopRequested;

    private MapDownloads() {
    }

    static int lookupPercent() {
        return percent;
    }

    /** A screen follows the progress while it is open; null when it closes. */
    static void watch(Runnable onChange) {
        watcher = onChange;
    }

    private static void notifyWatcher() {
        Runnable onChange = watcher;
        if (onChange != null) onChange.run();
    }

    /** Reports the running download to the progress fields, and its end as a message on screen. */
    private static Progress buildReporter(Context app) {
        return new Progress() {
            @Override
            public void update(int now) {
                if (percent < 0 || now == percent) return;   // stopped, or no change
                percent = now;
                notifyWatcher();
            }

            @Override
            public void done(String error) {
                percent = -1;
                PlaceIndexService.rebuild(app);   // search the new area offline
                android.widget.Toast.makeText(app, error == null ? app.getString(R.string.set_download_done)
                        : app.getString(R.string.set_download_failed, error), android.widget.Toast.LENGTH_LONG).show();
                notifyWatcher();
            }
        };
    }

    /**
     * Stops the download in progress and deletes what it saved so far (every style's region of that area), so no half
     * area is left. Runs {@code done} when finished, also with nothing to stop.
     */
    static void stop(Context context, Runnable done) {
        String area = runningArea;
        if (area == null) {
            done.run();
            return;
        }
        stopRequested = true;
        OfflineRegion region = runningRegion;
        if (region != null) {
            region.setObserver(null);
            region.setDownloadState(OfflineRegion.STATE_INACTIVE);
        }
        runningArea = null;
        runningRegion = null;
        percent = -1;
        notifyWatcher();
        listAreas(context, areas -> {
            for (Area saved : areas) {
                if (saved.id.equals(area)) {
                    deleteArea(saved, done);
                    return;
                }
            }
            done.run();
        });
    }

    /**
     * Downloads an area (picked on the map) for every map style, one after another; lookupPercent and the watcher
     * follow it, and its end shows as a message.
     */
    static void download(Context context, MapArea area) {
        DashboardMap.prepare(context);
        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(new LatLng(area.north, area.east))
                .include(new LatLng(area.south, area.west))
                .build();
        String id = Long.toString(System.currentTimeMillis());
        runningArea = id;
        stopRequested = false;
        percent = 0;
        notifyWatcher();
        String date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date());
        String size = String.format(Locale.getDefault(), "%.0f × %.0f km", area.measureWidthKm(), area.measureHeightKm());
        Context app = context.getApplicationContext();
        Progress progress = buildReporter(app);
        Handler main = new Handler(Looper.getMainLooper());
        double latitude = (area.north + area.south) / 2;
        double longitude = (area.east + area.west) / 2;
        // The place name needs the network (Android's geocoder): look it up off the main thread, then download on it
        // (MapLibre's offline calls belong on the main thread).
        new Thread(() -> {
            String name = describePlace(app, latitude, longitude) + " · " + size;
            main.post(() -> downloadStyle(app, bounds, id, name, date, 0, progress));
        }, "map-area-name").start();
    }

    private static void downloadStyle(Context context, LatLngBounds bounds, String id, String name, String date,
                                      int styleIndex, Progress progress) {
        String[] styles = DashboardMap.MAP_STYLES;
        if (stopRequested || !id.equals(runningArea)) return;   // stopped: stop() reports and cleans up
        if (styleIndex == styles.length) {
            runningArea = null;
            runningRegion = null;
            progress.done(null);
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        OfflineTilePyramidRegionDefinition definition = new OfflineTilePyramidRegionDefinition(
                DashboardMap.buildStyleUrl(styles[styleIndex]), bounds, MapArea.MIN_ZOOM, MapArea.MAX_ZOOM, density);
        byte[] metadata = buildMetadata(id, name, date, styles[styleIndex]);
        OfflineManager.getInstance(context).createOfflineRegion(definition, metadata,
                new OfflineManager.CreateOfflineRegionCallback() {
                    @Override
                    public void onCreate(OfflineRegion region) {
                        if (stopRequested || !id.equals(runningArea)) {   // stopped while the region was being made
                            region.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
                                @Override
                                public void onDelete() {
                                }

                                @Override
                                public void onError(String error) {
                                    Log.w(TAG, "could not delete a stopped region: " + error);
                                }
                            });
                            return;
                        }
                        runningRegion = region;
                        region.setObserver(new OfflineRegion.OfflineRegionObserver() {
                            @Override
                            public void onStatusChanged(OfflineRegionStatus status) {
                                long required = Math.max(1, status.getRequiredResourceCount());
                                double styleShare = (double) status.getCompletedResourceCount() / required;
                                progress.update((int) (100 * (styleIndex + styleShare) / styles.length));
                                if (!status.isComplete()) return;
                                region.setObserver(null);
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                downloadStyle(context, bounds, id, name, date, styleIndex + 1, progress);
                            }

                            @Override
                            public void onError(OfflineRegionError error) {
                                Log.w(TAG, "download error: " + error.getReason() + " " + error.getMessage());
                            }

                            @Override
                            public void mapboxTileCountLimitExceeded(long limit) {
                                region.setObserver(null);
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                runningArea = null;
                                runningRegion = null;
                                progress.done(context.getString(R.string.set_download_too_big, limit));
                            }
                        });
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                    }

                    @Override
                    public void onError(String error) {
                        runningArea = null;
                        runningRegion = null;
                        progress.done(error);
                    }
                });
    }

    /** The saved areas, newest first, each with its regions for every style. */
    static void listAreas(Context context, java.util.function.Consumer<List<Area>> result) {
        DashboardMap.prepare(context);
        OfflineManager.getInstance(context).listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
            @Override
            public void onList(OfflineRegion[] regions) {
                Map<String, Area> areas = new LinkedHashMap<>();
                for (OfflineRegion region : regions) {
                    try {
                        JSONObject meta = new JSONObject(new String(region.getMetadata(), StandardCharsets.UTF_8));
                        String id = meta.getString("area");
                        Area area = areas.get(id);
                        if (area == null) {
                            area = new Area(id, meta.optString("name"), meta.optString("date"));
                            areas.put(id, area);
                        }
                        area.regions.add(region);
                    } catch (JSONException e) {
                        Log.w(TAG, "offline region without dashboard metadata", e);
                    }
                }
                List<Area> newestFirst = new ArrayList<>(areas.values());
                newestFirst.sort((a, b) -> b.id.compareTo(a.id));
                result.accept(newestFirst);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "could not list offline regions: " + error);
                result.accept(new ArrayList<>());
            }
        });
    }

    /** Deletes every style's region of an area. */
    static void deleteArea(Area area, Runnable done) {
        if (area.regions.isEmpty()) {
            done.run();
            return;
        }
        OfflineRegion region = area.regions.remove(0);
        region.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
            @Override
            public void onDelete() {
                deleteArea(area, done);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "could not delete an offline region: " + error);
                deleteArea(area, done);
            }
        });
    }

    private static byte[] buildMetadata(String id, String name, String date, String style) {
        try {
            return new JSONObject().put("area", id).put("name", name).put("date", date).put("style", style)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "Brahmavar" from Android's geocoder when it answers, else the coordinates. */
    private static String describePlace(Context context, double latitude, double longitude) {
        try {
            List<Address> found = new Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1);
            if (found != null && !found.isEmpty()) {
                Address place = found.get(0);
                String town = place.getLocality() != null ? place.getLocality() : place.getSubAdminArea();
                if (town != null) return town;
            }
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "no place name for the download", e);
        }
        return String.format(Locale.ROOT, "%.3f, %.3f", latitude, longitude);
    }
}
