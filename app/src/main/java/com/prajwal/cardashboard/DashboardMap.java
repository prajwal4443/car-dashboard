package com.prajwal.cardashboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.storage.FileSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * The live map in the dashboard's Maps card: MapLibre drawing OpenFreeMap's vector tiles (OpenStreetMap data, free, no
 * key). It follows the car with a GPS navigation arrow that turns with the heading. Tiles once seen stay in MapLibre's
 * cache for offline use. A tap anywhere on the card opens the navigation app full screen; the map takes no gestures.
 * While the navigation app guides, its next turn shows on top (MusicListener reads its navigation notification).
 *
 * The style is picked in dashboard settings, from OpenFreeMap's: STYLE_DARK (the default), STYLE_DARK_BLUE (Fiord),
 * STYLE_WHITE (Positron), STYLE_LIGHT (Liberty, the most like Google Maps); or STYLE_OFF (the old picture, no map, no
 * network). Areas saved with "Download map area" (MapDownloads) show offline in every style.
 */
final class DashboardMap {
    private static final String TAG = "CarDash.Map";
    static final String STYLE_DARK = "ofm_dark";
    static final String STYLE_DARK_BLUE = "ofm_fiord";
    static final String STYLE_WHITE = "ofm_white";
    static final String STYLE_LIGHT = "ofm_liberty";
    static final String STYLE_OFF = "off";
    /** The styles that draw a map, in settings order (dark first); downloads save each of them. */
    static final String[] MAP_STYLES = {STYLE_DARK, STYLE_DARK_BLUE, STYLE_LIGHT, STYLE_WHITE};
    static final String[] STYLES = {STYLE_DARK, STYLE_DARK_BLUE, STYLE_LIGHT, STYLE_WHITE, STYLE_OFF};
    /** OpenFreeMap's offline rule: at most this many requests at once. */
    private static final int MAX_PARALLEL_REQUESTS = 4;
    private static boolean prepared;
    static final int REQUEST_LOCATION = 4201;
    /** Downloaded areas and the cache live here when Storage is allowed: Android keeps it across an uninstall. */
    static final String PUBLIC_MAPS_DIR = "CarDashboard/maps";
    private static final String PREFS = "dashboard";
    private static final String STYLE_KEY = "map_style";
    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/";
    private static final double FOLLOW_ZOOM = MapZoom.ZOOMS[0];
    private static final double FOLLOW_TILT = 35;
    /** Where the map opens with no position yet: the middle of India, zoomed out. */
    private static final LatLng NO_FIX_CENTER = new LatLng(20.59, 78.96);
    private static final double NO_FIX_ZOOM = 4;
    private static final long SPEED_INTERVAL_MS = 1000;
    /** Where the car sits on the map card, measured up from its bottom edge (0 at the bottom, 1 at the top). */
    private static final float CAR_FROM_BOTTOM = 0.2f;
    private static final long ZOOM_EASE_MS = 1500;
    private static final long CACHE_MAX_BYTES = 300L * 1024 * 1024;
    /** The navigation arrow at this size: MapLibre's own is sized for a full-screen map. */
    private static final float ARROW_SCALE = 0.7f;

    private final Activity activity;
    private final FrameLayout slot;
    private final View picture;
    private final TextView credit;
    private final View turn;
    private final ImageView turnIcon;
    private final TextView turnDistance;
    private final TextView turnText;
    /** The car's speed in the map's top left corner (km/h), shown once the GPS gives one. */
    private final TextView speed;
    private MapView mapView;
    private MapLibreMap map;
    /** The car's speed, for MapZoom: from the GPS while the map is on screen. */
    private LocationManager speedSource;
    private Location lastSpeedFix;
    private double shownZoom = FOLLOW_ZOOM;
    private final android.location.LocationListener speedListener = new android.location.LocationListener() {
        @Override
        public void onLocationChanged(Location fix) {
            followSpeed(fix);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private Style style;
    private String shownStyle;
    private boolean started;
    private boolean resumed;
    private static boolean askedThisLaunch;

    DashboardMap(Activity activity, View card) {
        this.activity = activity;
        card.setClipToOutline(true);   // the map stays inside the card's rounded corners
        slot = card.findViewById(R.id.dashMapSlot);
        picture = card.findViewById(R.id.dashMapPicture);
        credit = card.findViewById(R.id.dashMapCredit);
        turn = card.findViewById(R.id.dashTurn);
        turnIcon = card.findViewById(R.id.dashTurnIcon);
        turnDistance = card.findViewById(R.id.dashTurnDistance);
        turnText = card.findViewById(R.id.dashTurnText);
        speed = card.findViewById(R.id.dashSpeed);
        prepare(activity);
    }

    /**
     * MapLibre, once per process: the cache size, and OpenFreeMap's offline rules (MapArea.TILE_LIMIT tiles per
     * download, MAX_PARALLEL_REQUESTS at once, a User-Agent naming this app).
     */
    static void prepare(Context context) {
        if (prepared) return;
        prepared = true;
        Context app = context.getApplicationContext();
        MapLibre.getInstance(app);
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequestsPerHost(MAX_PARALLEL_REQUESTS);
        String version;
        try {
            version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        String agent = "CarDashboard/" + version + " (" + app.getPackageName() + ")";
        HttpRequestUtil.setOkHttpClient(new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addNetworkInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder().header("User-Agent", agent).build()))
                .build());
        OfflineManager offline = OfflineManager.getInstance(app);
        offline.setOfflineMapboxTileCountLimit(MapArea.TILE_LIMIT);
        offline.setMaximumAmbientCacheSize(CACHE_MAX_BYTES, null);
        usePublicStorage(app, inPublicFolder -> { });
    }

    static String buildStyleUrl(String style) {
        switch (style) {
            case STYLE_DARK_BLUE: return STYLE_URL + "fiord";
            case STYLE_WHITE: return STYLE_URL + "positron";
            case STYLE_LIGHT: return STYLE_URL + "liberty";
            default: return STYLE_URL + "dark";
        }
    }

    /** The picked style; the default for none picked, or one this version no longer has. */
    static String lookupStyle(Context context) {
        String picked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STYLE_KEY, STYLE_DARK);
        for (String known : STYLES) if (known.equals(picked)) return picked;
        return STYLE_DARK;
    }

    static void saveStyle(Context context, String style) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(STYLE_KEY, style).apply();
    }

    static boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Deletes the map tiles kept for offline use (dashboard settings); places load again online. */
    static void clearCache(Context context, Runnable done) {
        prepare(context);
        OfflineManager.getInstance(context).clearAmbientCache(new OfflineManager.FileSourceCallback() {
            @Override
            public void onSuccess() {
                done.run();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "could not clear the map cache: " + message);
                done.run();
            }
        });
    }

    void start() {
        started = true;
        if (mapView != null) mapView.onStart();
    }

    /**
     * From onResume: builds or restyles the map for the style picked in settings, and follows the car. The first time
     * without location allowed, Android's location prompt comes up by itself (once per launch; holding the card asks
     * again).
     */
    void resume() {
        resumed = true;
        String picked = lookupStyle(activity);
        if (!picked.equals(shownStyle)) applyStyle(picked);
        if (mapView == null) return;
        mapView.onResume();
        startFollowingSpeed();
        if (!askedThisLaunch && askForLocation()) askedThisLaunch = true;
    }

    void pause() {
        resumed = false;
        stopFollowingSpeed();
        if (mapView != null) mapView.onPause();
    }

    /** Speed from the GPS while the map shows, for the zoom (MapZoom). */
    @SuppressLint("MissingPermission")
    private void startFollowingSpeed() {
        if (speedSource != null || !hasLocationPermission(activity)) return;
        speedSource = activity.getSystemService(LocationManager.class);
        if (speedSource == null) return;
        try {
            speedSource.requestLocationUpdates(LocationManager.GPS_PROVIDER, SPEED_INTERVAL_MS, 0, speedListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "no GPS speed for the map zoom", e);
            speedSource = null;
        }
    }

    private void stopFollowingSpeed() {
        if (speedSource != null) speedSource.removeUpdates(speedListener);
        speedSource = null;
        lastSpeedFix = null;
        speed.setVisibility(View.GONE);
    }

    /** "72" over "km/h", in the map's top left corner; below the next-turn banner while that shows. */
    private void showSpeed(double kmh) {
        String number = Integer.toString((int) Math.round(Math.max(0, kmh)));
        SpannableString text = new SpannableString(number + "\nkm/h");
        text.setSpan(new RelativeSizeSpan(0.45f), number.length(), text.length(), 0);
        speed.setText(text);
        speed.setVisibility(View.VISIBLE);
    }

    /** Wider at speed, closer when slow (MapZoom), eased over ZOOM_EASE_MS. */
    private void followSpeed(Location fix) {
        Location previous = lastSpeedFix;
        lastSpeedFix = fix;
        double metersPerSecond;
        if (fix.hasSpeed()) {
            metersPerSecond = fix.getSpeed();
        } else if (previous != null && fix.getTime() > previous.getTime()) {
            metersPerSecond = fix.distanceTo(previous) * 1000.0 / (fix.getTime() - previous.getTime());
        } else {
            return;
        }
        showSpeed(metersPerSecond * 3.6);
        double zoom = MapZoom.chooseZoom(shownZoom, metersPerSecond * 3.6);
        if (zoom == shownZoom || map == null || style == null) return;
        shownZoom = zoom;
        try {
            map.getLocationComponent().zoomWhileTracking(zoom, ZOOM_EASE_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "zoom by speed skipped", e);
        }
    }

    void stop() {
        started = false;
        if (mapView != null) mapView.onStop();
    }

    void release() {
        if (mapView == null) return;
        if (resumed) mapView.onPause();
        if (started) mapView.onStop();
        mapView.onDestroy();
        slot.removeAllViews();
        mapView = null;
        map = null;
        style = null;
    }

    /**
     * Hold the card, or the first Home of a launch: Android's prompts for what the map still lacks, location (to follow
     * the car) and, up to Android 10, Storage (to keep downloaded areas across an uninstall). True when it asked.
     */
    boolean askForLocation() {
        if (STYLE_OFF.equals(shownStyle)) return false;
        List<String> missing = new ArrayList<>();
        if (!hasLocationPermission(activity)) missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (needsStoragePermission(activity)) missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (missing.isEmpty()) return false;
        activity.requestPermissions(missing.toArray(new String[0]), REQUEST_LOCATION);
        return true;
    }

    /** Up to Android 10, without Storage allowed yet: the public map folder is possible but not open to the app. */
    static boolean needsStoragePermission(Context context) {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    static boolean canUsePublicStorage(Context context) {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !needsStoragePermission(context);
    }

    @SuppressWarnings("deprecation")   // the public storage root: exactly what Android 10 with legacy storage allows
    static File findPublicMapsDir() {
        return new File(Environment.getExternalStorageDirectory(), PUBLIC_MAPS_DIR);
    }

    /** True when MapLibre's store (downloaded areas and cache) is in the public folder. */
    static boolean isUsingPublicStorage(Context context) {
        return findPublicMapsDir().getAbsolutePath().equals(FileSource.getResourcesCachePath(context));
    }

    /**
     * Moves MapLibre's store into the public folder when Storage is allowed (it keeps that choice itself from then on);
     * after a reinstall, this finds the areas saved there before. {@code done} gets whether the store is there.
     */
    static void usePublicStorage(Context context, Consumer<Boolean> done) {
        Context app = context.getApplicationContext();
        if (!canUsePublicStorage(app)) {
            done.accept(false);
            return;
        }
        MapLibre.getInstance(app);
        if (isUsingPublicStorage(app)) {
            done.accept(true);
            return;
        }
        File dir = findPublicMapsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "could not make " + dir);
            done.accept(false);
            return;
        }
        FileSource.setResourcesCachePath(dir.getAbsolutePath(), new FileSource.ResourcesCachePathChangeCallback() {
            @Override
            public void onSuccess(String path) {
                done.accept(true);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "map store stays inside the app: " + message);
                done.accept(false);
            }
        });
    }

    /** Location was just allowed: start the arrow now. */
    void onLocationAllowed() {
        if (map != null && style != null) showLocation();
    }

    /** The navigation app's next turn, or null to hide the banner. */
    void showTurn(MusicListener.Turn next) {
        if (next == null) {
            turn.setVisibility(View.GONE);
            moveTopControls(0);
            return;
        }
        turnDistance.setText(next.distance);
        turnText.setText(next.instruction);
        Icon icon = next.icon;
        if (icon != null) turnIcon.setImageDrawable(icon.loadDrawable(activity));
        turnIcon.setVisibility(icon != null ? View.VISIBLE : View.GONE);
        turn.setVisibility(View.VISIBLE);
        turn.post(() -> moveTopControls(turn.getBottom()));   // the top controls move under the banner
    }

    private void applyStyle(String picked) {
        shownStyle = picked;
        if (STYLE_OFF.equals(picked)) {
            release();
            slot.setVisibility(View.GONE);
            credit.setVisibility(View.GONE);
            picture.setVisibility(View.VISIBLE);
            return;
        }
        if (mapView == null) createMap();
        String url = buildStyleUrl(picked);
        style = null;
        mapView.getMapAsync(loaded -> {
            map = loaded;
            loaded.setStyle(new Style.Builder().fromUri(url), ready -> {
                style = ready;
                if (hasLocationPermission(activity)) showLocation();
            });
        });
        credit.setText("© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors");
        credit.setVisibility(View.VISIBLE);
        slot.setVisibility(View.VISIBLE);
        picture.setVisibility(View.GONE);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createMap() {
        mapView = new MapView(activity);
        mapView.onCreate(null);
        if (started) mapView.onStart();
        // The map takes no gestures: a tap anywhere opens the navigation app (the card's own click).
        mapView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) ((View) slot.getParent()).performClick();
            return true;
        });
        slot.removeAllViews();
        slot.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Again whenever the card changes size: before its first layout it has none, and placing then does nothing.
        ((View) slot.getParent()).addOnLayoutChangeListener((card, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) card.post(this::placeCar);
        });
        slot.post(this::placeCar);
        mapView.getMapAsync(loaded -> {
            loaded.getUiSettings().setAllGesturesEnabled(false);
            loaded.getUiSettings().setLogoEnabled(false);
            loaded.getUiSettings().setAttributionEnabled(false);   // the card's own credit line shows it
            loaded.getUiSettings().setCompassEnabled(false);
            Location last = findLastLocation();
            loaded.setCameraPosition(new CameraPosition.Builder()
                    .target(last != null ? new LatLng(last.getLatitude(), last.getLongitude()) : NO_FIX_CENTER)
                    .zoom(last != null ? FOLLOW_ZOOM : NO_FIX_ZOOM)
                    .build());
        });
    }

    /** The GPS navigation arrow, the camera following the car and turning with its heading. */
    @SuppressLint("MissingPermission")
    private void showLocation() {
        if (!hasLocationPermission(activity)) return;
        try {
            LocationComponent location = map.getLocationComponent();
            LocationComponentOptions look = LocationComponentOptions.builder(activity)
                    .minZoomIconScale(ARROW_SCALE).maxZoomIconScale(ARROW_SCALE).build();
            location.activateLocationComponent(LocationComponentActivationOptions.builder(activity, style)
                    .locationComponentOptions(look).useDefaultLocationEngine(true).build());
            location.setLocationComponentEnabled(true);
            location.setRenderMode(RenderMode.GPS);
            location.setCameraMode(CameraMode.TRACKING_GPS);
            location.zoomWhileTracking(shownZoom);
            location.tiltWhileTracking(FOLLOW_TILT);
        } catch (RuntimeException e) {
            Log.w(TAG, "location arrow off", e);
        }
    }

    /**
     * The car is always drawn at the map's centre. Like a navigation app, it belongs low in the visible map,
     * CAR_FROM_BOTTOM of the way up from the card's bottom, so most of the map shows the road ahead. The map view is sized
     * and shifted (reaching past the card where needed, clipped by the card's rounded outline) so its centre lands there.
     */
    private void placeCar() {
        View card = (View) slot.getParent();
        int cardHeight = card.getHeight();
        if (cardHeight == 0) return;
        int carY = Math.round(cardHeight * (1 - CAR_FROM_BOTTOM));
        ViewGroup.MarginLayoutParams frame = (ViewGroup.MarginLayoutParams) slot.getLayoutParams();
        int top = 2 * carY - cardHeight;   // centre = (top + bottom) / 2, bottom at the card's bottom
        if (top <= 0) {
            frame.topMargin = top;
            frame.bottomMargin = 0;
        } else {                           // the car sits low enough that the map must reach below the card instead
            frame.topMargin = 0;
            frame.bottomMargin = cardHeight - 2 * carY;
        }
        slot.requestLayout();
    }

    private void moveTopControls(float down) {
        speed.setTranslationY(down);
    }

    /** The newest position any provider knows, or null (also without location allowed). */
    static Location findLastLocation(Context context) {
        if (!hasLocationPermission(context)) return null;
        LocationManager locations = context.getSystemService(LocationManager.class);
        if (locations == null) return null;
        Location best = null;
        for (String provider : locations.getProviders(true)) {
            try {
                Location fix = locations.getLastKnownLocation(provider);
                if (fix != null && (best == null || fix.getTime() > best.getTime())) best = fix;
            } catch (SecurityException ignored) {
            }
        }
        return best;
    }

    private Location findLastLocation() {
        return findLastLocation(activity);
    }
}
