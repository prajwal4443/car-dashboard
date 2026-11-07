package com.prajwal.cardashboard;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.maplibre.android.storage.FileSource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the offline search index (PlaceIndex) from the downloaded map: reads MapLibre's offline database, takes the
 * names out of its most detailed tiles, and writes the index file next to the maps.
 *
 * It runs in its own process (":search" in the manifest). MapLibre carries its own copy of SQLite; a second copy (the
 * Android one used here) opening the same file in the same process can release the first one's file locks and damage
 * the database. In another process the two only meet as ordinary readers and writers. The database is opened read
 * only, and a busy or half-written moment is retried a little later.
 */
public class PlaceIndexService extends Service {
    private static final String TAG = "CarDash.PlaceIndex";
    private static final String EXTRA_MAPS_DIR = "maps_dir";
    private static final String INDEX_FILE = "place-index.tsv";
    /** MapLibre's offline database, in its resources cache folder. */
    private static final String MAP_DATABASE = "mbgl-offline.db";
    /** OpenFreeMap's vector tiles (every map style shares them). */
    private static final String TILE_URL_PATTERN = "%tiles.openfreemap.org/%";
    private static final int ATTEMPTS = 3;
    private static final long RETRY_MS = 20_000;

    private static boolean building;
    private static boolean buildAgain;

    /**
     * Rebuilds the index in the background, after a download or a deletion. Not while a download runs (its tiles
     * would be read half-saved), and not when Android refuses a background start: the next search rebuilds then.
     */
    static void rebuild(Context context) {
        if (MapDownloads.lookupPercent() >= 0) return;
        Intent build = new Intent(context, PlaceIndexService.class)
                .putExtra(EXTRA_MAPS_DIR, FileSource.getResourcesCachePath(context));
        try {
            context.startService(build);
        } catch (IllegalStateException e) {
            Log.i(TAG, "index rebuild left for the next search", e);
        }
    }

    static File findIndexFile(Context context) {
        return new File(FileSource.getResourcesCachePath(context), INDEX_FILE);
    }

    /** True when there is no index yet, or the maps changed after it was built. */
    static boolean isIndexStale(Context context) {
        File index = findIndexFile(context);
        File database = new File(FileSource.getResourcesCachePath(context), MAP_DATABASE);
        return !index.isFile() || (database.isFile() && database.lastModified() > index.lastModified());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String mapsDir = intent != null ? intent.getStringExtra(EXTRA_MAPS_DIR) : null;
        if (mapsDir == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        synchronized (PlaceIndexService.class) {
            if (building) {
                buildAgain = true;   // the running build starts over once it is done
                return START_NOT_STICKY;
            }
            building = true;
        }
        new Thread(() -> {
            boolean again;
            do {
                buildWithRetries(new File(mapsDir));
                synchronized (PlaceIndexService.class) {
                    again = buildAgain;
                    buildAgain = false;
                    if (!again) building = false;
                }
            } while (again);
            stopSelf();
        }, "place-index").start();
        return START_NOT_STICKY;
    }

    private static void buildWithRetries(File mapsDir) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                buildIndex(mapsDir);
                return;
            } catch (SQLiteException | IOException | IllegalArgumentException e) {
                Log.w(TAG, "index build attempt " + attempt + " failed", e);
                SystemClock.sleep(RETRY_MS);
            }
        }
    }

    private static void buildIndex(File mapsDir) throws IOException {
        long started = SystemClock.elapsedRealtime();
        File database = new File(mapsDir, MAP_DATABASE);
        List<PlaceIndex.Place> found = new ArrayList<>();
        int tileCount = 0;
        if (database.isFile()) {
            Set<Long> seenTiles = new HashSet<>();
            try (SQLiteDatabase maps = SQLiteDatabase.openDatabase(database.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                 Cursor tiles = maps.rawQuery("SELECT x, y, data, compressed FROM tiles WHERE z = ? AND url_template"
                         + " LIKE ?", new String[] {Integer.toString(PlaceIndex.INDEX_ZOOM), TILE_URL_PATTERN})) {
                while (tiles.moveToNext()) {
                    int x = tiles.getInt(0);
                    int y = tiles.getInt(1);
                    byte[] stored = tiles.getBlob(2);
                    if (stored == null || !seenTiles.add(((long) x << 32) | y)) continue;
                    byte[] tile = VectorTileReader.unpackStoredTile(stored, tiles.getInt(3) != 0);
                    found.addAll(PlaceIndex.extractPlaces(tile, PlaceIndex.INDEX_ZOOM, x, y));
                    tileCount++;
                }
            }
        }
        List<PlaceIndex.Place> places = PlaceIndex.mergeDuplicates(found);
        PlaceIndex.saveIndex(new File(mapsDir, INDEX_FILE), places);
        Log.i(TAG, "indexed " + places.size() + " places from " + tileCount + " tiles in "
                + (SystemClock.elapsedRealtime() - started) + " ms");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
