package com.prajwal.cardashboard;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Speed camera alerts in the background, offline: follows the GPS while the dashboard's camera alerts are on, and
 * when a camera (CameraStore) comes within the alert distance ahead of the car, posts a heads-up notification with
 * sound and says "Speed camera ahead" (CameraAlerts decides when). Runs as a foreground location service, so it keeps
 * going while Google Maps or any other app is in front.
 */
public class CameraAlertService extends Service {
    private static final String TAG = "CarDash.CameraAlerts";
    private static final String PREFS = "dashboard";
    private static final String ON_KEY = "camera_alerts";
    private static final String DISTANCE_KEY = "camera_alert_meters";
    static final int DEFAULT_ALERT_METERS = 100;
    private static final String RUNNING_CHANNEL = "camera_alerts_running";
    private static final String ALERT_CHANNEL = "camera_alerts";
    private static final int RUNNING_ID = 4401;
    private static final int ALERT_ID = 4402;
    private static final long GPS_INTERVAL_MS = 1000;
    private static final float GPS_MIN_METERS = 5;
    private static final long ALERT_SHOWN_MS = 20_000;
    private static final String ACTION_TEST = "com.prajwal.cardashboard.action.TEST_CAMERA_ALERT";
    private static final long TEST_DELAY_MS = 1500;

    /** Speed and heading are worked out from the previous fix only when it is at most this old. */
    private static final long MAX_DERIVE_SECONDS = 10;
    private LocationManager locations;
    private Location lastFix;
    private TextToSpeech voice;
    private boolean voiceReady;
    private final Set<Integer> announced = new HashSet<>();
    private List<CameraStore.Camera> cameras;
    private double[] cameraLat = new double[0];
    private double[] cameraLon = new double[0];
    /** Each camera's warning distance: the alert distance, or the wider zone for approximate ones. */
    private double[] cameraZone = new double[0];
    private int zoneMeters = -1;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location fix) {
            check(fix);
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

    static boolean isOn(Context context) {
        return prefs(context).getBoolean(ON_KEY, true);
    }

    static void saveOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(ON_KEY, on).apply();
    }

    static int lookupAlertMeters(Context context) {
        return prefs(context).getInt(DISTANCE_KEY, DEFAULT_ALERT_METERS);
    }

    static void saveAlertMeters(Context context, int meters) {
        prefs(context).edit().putInt(DISTANCE_KEY, meters).apply();
    }

    /**
     * From the dashboard: starts the alerts when they are on, there are cameras and location is allowed; else stops
     * them. Safe to call often.
     */
    static void update(Context context) {
        Intent service = new Intent(context, CameraAlertService.class);
        boolean wanted = isOn(context) && DashboardMap.hasLocationPermission(context)
                && !CameraStore.load(context).isEmpty();
        try {
            if (wanted) context.startForegroundService(service);
            else context.stopService(service);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not " + (wanted ? "start" : "stop") + " camera alerts", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        makeChannels();
        Notification running = new Notification.Builder(this, RUNNING_CHANNEL)
                .setSmallIcon(R.drawable.ic_speed_camera)
                .setContentTitle(getString(R.string.cam_running_title))
                .setContentText(getString(R.string.cam_running_text, CameraStore.load(this).size()))
                .setContentIntent(openDashboard())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(RUNNING_ID, running, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(RUNNING_ID, running);
        }
        voice = new TextToSpeech(this, status -> {
            voiceReady = status == TextToSpeech.SUCCESS;
            if (voiceReady) voice.setLanguage(Locale.getDefault());
        });
        locations = getSystemService(LocationManager.class);
        startGps();
    }

    /** Dashboard settings, "Test camera alert": the real notification, sound and voice, with no camera needed. */
    static void sendTest(Context context) {
        try {
            context.startForegroundService(new Intent(context, CameraAlertService.class).setAction(ACTION_TEST));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start the test alert", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadCameras();   // the list may have changed (update, a marked camera)
        if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            // The voice may still be starting: give it a moment, then alert as for a camera 100 m ahead.
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                alert(new CameraStore.Camera(0, 0, false, null), DEFAULT_ALERT_METERS);
                if (CameraStore.load(this).isEmpty()) stopSelf();   // nothing to watch after the test
            }, TEST_DELAY_MS);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (locations != null) locations.removeUpdates(listener);
        if (voice != null) voice.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MissingPermission")
    private void startGps() {
        if (locations == null || !DashboardMap.hasLocationPermission(this)) {
            stopSelf();
            return;
        }
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, GPS_MIN_METERS, listener,
                    Looper.getMainLooper());
        } catch (RuntimeException e) {
            Log.w(TAG, "no GPS for camera alerts", e);
            stopSelf();
        }
    }

    private void loadCameras() {
        List<CameraStore.Camera> latest = CameraStore.load(this);
        int meters = lookupAlertMeters(this);
        if (latest == cameras && meters == zoneMeters) return;
        cameras = latest;
        zoneMeters = meters;
        cameraLat = new double[latest.size()];
        cameraLon = new double[latest.size()];
        cameraZone = new double[latest.size()];
        for (int i = 0; i < latest.size(); i++) {
            CameraStore.Camera camera = latest.get(i);
            cameraLat[i] = camera.lat;
            cameraLon[i] = camera.lon;
            cameraZone[i] = camera.approx ? Math.max(meters, CameraAlerts.APPROX_ZONE_METERS) : meters;
        }
        announced.clear();
    }

    private void check(Location fix) {
        loadCameras();
        // Some fixes carry no speed or heading: work them out from the previous fix instead.
        Location previous = lastFix;
        lastFix = fix;
        float speed = fix.hasSpeed() ? fix.getSpeed() : 0;
        boolean hasHeading = fix.hasBearing();
        double heading = fix.getBearing();
        long seconds = previous != null
                ? (fix.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos()) / 1_000_000_000L : 0;
        if (previous != null && seconds > 0 && seconds <= MAX_DERIVE_SECONDS) {
            double moved = CameraAlerts.measureDistanceMeters(previous.getLatitude(), previous.getLongitude(),
                    fix.getLatitude(), fix.getLongitude());
            if (!fix.hasSpeed()) speed = (float) (moved / seconds);
            if (!hasHeading && moved > 0) {
                hasHeading = true;
                heading = CameraAlerts.measureBearing(previous.getLatitude(), previous.getLongitude(),
                        fix.getLatitude(), fix.getLongitude());
            }
        }
        CameraAlerts.forgetPassed(fix.getLatitude(), fix.getLongitude(), cameraLat, cameraLon, announced);
        int camera = CameraAlerts.findCameraToAnnounce(fix.getLatitude(), fix.getLongitude(), hasHeading,
                heading, speed, cameraLat, cameraLon, announced, cameraZone);
        if (camera < 0) return;
        announced.add(camera);
        int meters = (int) Math.round(CameraAlerts.measureDistanceMeters(fix.getLatitude(), fix.getLongitude(),
                cameraLat[camera], cameraLon[camera]));
        alert(cameras.get(camera), meters);
    }

    private void alert(CameraStore.Camera camera, int meters) {
        String title = camera.approx ? getString(R.string.cam_zone_title) : getString(R.string.cam_alert_title, meters);
        String limit = camera.maxspeed != null ? getString(R.string.cam_alert_limit, camera.maxspeed) : null;
        String text = camera.approx ? getString(R.string.cam_zone_text, camera.name, limit != null ? limit : "")
                : limit != null ? limit : getString(camera.mine ? R.string.cam_alert_mine : R.string.cam_alert_fixed);
        Notification alert = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_speed_camera)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setContentIntent(openDashboard())
                .setAutoCancel(true)
                .setTimeoutAfter(ALERT_SHOWN_MS)
                .build();
        getSystemService(NotificationManager.class).notify(ALERT_ID, alert);
        if (voiceReady) {
            String spoken = camera.approx
                    ? getString(R.string.cam_say_zone, camera.maxspeed != null ? camera.maxspeed : "")
                    : camera.maxspeed != null ? getString(R.string.cam_say_limit, camera.maxspeed)
                    : getString(R.string.cam_say);
            voice.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "camera");
        }
    }

    private PendingIntent openDashboard() {
        return PendingIntent.getActivity(this, 0, new Intent(this, DashboardActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
    }

    private void makeChannels() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel running = new NotificationChannel(RUNNING_CHANNEL,
                getString(R.string.cam_channel_running), NotificationManager.IMPORTANCE_MIN);
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL,
                getString(R.string.cam_channel_alerts), NotificationManager.IMPORTANCE_HIGH);
        alerts.enableVibration(true);
        notifications.createNotificationChannel(running);
        notifications.createNotificationChannel(alerts);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
