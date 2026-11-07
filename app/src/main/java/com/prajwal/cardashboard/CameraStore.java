package com.prajwal.cardashboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * The speed cameras the dashboard warns about: OpenStreetMap's for India (downloaded once with "Update cameras", then
 * offline) plus the ones marked by hand ("Mark speed camera here"), in one JSON file. The file sits in the public
 * CarDashboard folder when Storage is allowed (kept across an uninstall, like the maps), else inside the app.
 */
final class CameraStore {
    private static final String TAG = "CarDash.Cameras";
    private static final String FILE_NAME = "cameras.json";
    /** OpenStreetMap's query servers, tried in order: the main one rate-limits busy times (HTTP 429). */
    private static final String[] OVERPASS_URLS = {"https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"};
    private static final int TOO_MANY_REQUESTS = 429;
    /** Every fixed speed camera OpenStreetMap has in India, with its limit when mapped. */
    private static final String INDIA_QUERY = "[out:json][timeout:120];area[\"ISO3166-1\"=\"IN\"][admin_level=2]->.in;"
            + "node[\"highway\"=\"speed_camera\"](area.in);out;";
    private static final int TIMEOUT_MS = 150_000;

    /**
     * One camera: where, whether it was marked by hand, its speed limit when known (else null), and for the cameras
     * bundled with the app (res/raw/known_cameras.json) a place name and the fact that its position is approximate.
     */
    static final class Camera {
        final double lat;
        final double lon;
        final boolean mine;
        final String maxspeed;
        final String name;
        final boolean approx;

        Camera(double lat, double lon, boolean mine, String maxspeed) {
            this(lat, lon, mine, maxspeed, null, false);
        }

        Camera(double lat, double lon, boolean mine, String maxspeed, String name, boolean approx) {
            this.lat = lat;
            this.lon = lon;
            this.mine = mine;
            this.maxspeed = maxspeed;
            this.name = name;
            this.approx = approx;
        }
    }

    private static List<Camera> cached;
    private static String cachedUpdated;
    private static File cachedFrom;
    private static long cachedStamp;

    private CameraStore() {
    }

    static File findFile(Context context) {
        File dir = DashboardMap.canUsePublicStorage(context) ? DashboardMap.findPublicMapsDir().getParentFile()
                : context.getFilesDir();
        return new File(dir, FILE_NAME);
    }

    /** The cameras, read once and again whenever the file changes. */
    static synchronized List<Camera> load(Context context) {
        File file = findFile(context);
        if (cached != null && file.equals(cachedFrom) && file.lastModified() == cachedStamp) return cached;
        List<Camera> cameras = new ArrayList<>();
        String updated = null;
        if (file.isFile()) {
            try {
                JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                updated = root.optString("updated", null);
                JSONArray list = root.getJSONArray("cameras");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject one = list.getJSONObject(i);
                    cameras.add(new Camera(one.getDouble("lat"), one.getDouble("lon"), one.optBoolean("mine"),
                            one.has("maxspeed") ? one.getString("maxspeed") : null));
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "could not read " + file, e);
            }
        }
        cameras.addAll(loadKnown(context));
        cached = Collections.unmodifiableList(cameras);
        cachedUpdated = updated;
        cachedFrom = file;
        cachedStamp = file.lastModified();
        return cached;
    }

    /**
     * The cameras bundled with the app, known from police announcements by place name (Udupi district's speed radars,
     * May 2026): always included, never saved to the file, warned as a zone since their position is approximate.
     */
    private static List<Camera> loadKnown(Context context) {
        List<Camera> known = new ArrayList<>();
        try (InputStream in = context.getResources().openRawResource(R.raw.known_cameras)) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) body.write(buffer, 0, count);
            JSONArray list = new JSONObject(body.toString("UTF-8")).getJSONArray("cameras");
            for (int i = 0; i < list.length(); i++) {
                JSONObject one = list.getJSONObject(i);
                known.add(new Camera(one.getDouble("lat"), one.getDouble("lon"), false,
                        one.optString("maxspeed", null), one.optString("name", null), true));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "bundled cameras unreadable", e);
        }
        return known;
    }

    static int countKnown(Context context) {
        int known = 0;
        for (Camera camera : load(context)) if (camera.approx) known++;
        return known;
    }

    /** When OpenStreetMap's cameras were last downloaded, or null if never. */
    static String lookupUpdated(Context context) {
        load(context);
        return cachedUpdated;
    }

    static int countMine(Context context) {
        int mine = 0;
        for (Camera camera : load(context)) if (camera.mine) mine++;
        return mine;
    }

    /** Adds a camera marked by hand at a position. False if it could not be saved. */
    static synchronized boolean addMine(Context context, double lat, double lon) {
        List<Camera> cameras = new ArrayList<>(load(context));
        cameras.add(new Camera(lat, lon, true, null));
        return save(context, cameras, lookupUpdated(context));
    }

    /** Removes every camera marked by hand; OpenStreetMap's stay. */
    static synchronized boolean deleteMine(Context context) {
        List<Camera> kept = new ArrayList<>();
        for (Camera camera : load(context)) if (!camera.mine && !camera.approx) kept.add(camera);
        return save(context, kept, lookupUpdated(context));
    }

    /**
     * Downloads OpenStreetMap's cameras for India (online, off the main thread), replacing the previous download and
     * keeping the ones marked by hand. {@code done} gets null on success, else what went wrong, on the main thread.
     */
    static void updateFromOsm(Context context, Consumer<String> done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = null;
            try {
                JSONArray elements = new JSONObject(fetchOverpass(app)).getJSONArray("elements");
                List<Camera> cameras = new ArrayList<>();
                for (Camera camera : load(app)) if (camera.mine) cameras.add(camera);
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject node = elements.getJSONObject(i);
                    JSONObject tags = node.optJSONObject("tags");
                    String maxspeed = tags != null && tags.has("maxspeed") ? tags.getString("maxspeed") : null;
                    cameras.add(new Camera(node.getDouble("lat"), node.getDouble("lon"), false, maxspeed));
                }
                String updated = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date());
                if (!save(app, cameras, updated)) error = "could not save the camera list";
            } catch (IOException | JSONException e) {
                Log.w(TAG, "camera update failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String result = error;
            main.post(() -> done.accept(result));
        }, "camera-update").start();
    }

    /** The cameras from the first OpenStreetMap server that answers; a plain message when all are busy. */
    private static String fetchOverpass(Context context) throws IOException {
        IOException last = null;
        for (String url : OVERPASS_URLS) {
            try {
                return fetchOverpass(context, url);
            } catch (IOException e) {
                Log.w(TAG, url + " failed: " + e.getMessage());
                last = e;
            }
        }
        throw last;
    }

    private static String fetchOverpass(Context context, String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", "CarDashboard (" + context.getPackageName() + ")");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(("data=" + URLEncoder.encode(INDIA_QUERY, "UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code == TOO_MANY_REQUESTS || code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT) {
                throw new IOException(context.getString(R.string.cam_osm_busy));
            }
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("OpenStreetMap answered " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) body.write(buffer, 0, count);
                return body.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static synchronized boolean save(Context context, List<Camera> cameras, String updated) {
        File file = findFile(context);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        try {
            JSONArray list = new JSONArray();
            for (Camera camera : cameras) {
                if (camera.approx) continue;   // bundled with the app, not part of the saved list
                JSONObject one = new JSONObject().put("lat", camera.lat).put("lon", camera.lon);
                if (camera.mine) one.put("mine", true);
                if (camera.maxspeed != null) one.put("maxspeed", camera.maxspeed);
                list.put(one);
            }
            JSONObject root = new JSONObject().put("cameras", list);
            if (updated != null) root.put("updated", updated);
            File partial = new File(file.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(partial)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!partial.renameTo(file)) return false;
            cached = null;
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "could not save " + file, e);
            return false;
        }
    }
}
