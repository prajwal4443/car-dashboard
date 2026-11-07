package com.prajwal.cardashboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The trip in progress: where to, the route (RouteFinder), and the car's progress along it from the GPS. It lives
 * apart from any screen, so the trip goes on when Home brings the dashboard back: the full navigation screen
 * (NavigationActivity) and the dashboard's mini map both show it. Off the route for a while, it asks for a new one.
 * Everything here runs on the main thread; watchers are told after each change.
 */
final class TripGuide {
    private static final String TAG = "CarDash.Trip";
    private static final long GPS_INTERVAL_MS = 1000;
    /** Further than this from the route line, OFF_ROUTE_FIXES fixes in a row, means a new route is needed. */
    private static final double OFF_ROUTE_METERS = 60;
    private static final int OFF_ROUTE_FIXES = 3;
    /** Off the route, a new one is asked for only once the car has moved this far since the last one. */
    private static final double REROUTE_MOVED_METERS = 100;
    /** At most one route request this often (rerouting, or retrying without internet). */
    private static final long ROUTE_RETRY_MS = 15_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ROUTER = Executors.newSingleThreadExecutor();
    private static final Set<Runnable> WATCHERS = new LinkedHashSet<>();

    private static Context app;
    private static boolean active;
    private static boolean driving;
    private static String destinationName = "";
    private static double destinationLat;
    private static double destinationLon;
    /** The ways the router offered (fastest first) and the one chosen; route is the chosen one. */
    private static List<DrivingRoute> routes = new ArrayList<>();
    private static int chosenRoute;
    private static DrivingRoute route;
    private static DrivingRoute.Progress progress;
    private static Location lastFix;
    /** Where the last route was asked from. */
    private static Location lastRouteFrom;
    private static boolean findingRoute;
    private static boolean routeFailed;
    private static long lastRouteRequest = -ROUTE_RETRY_MS;
    private static int offRouteCount;
    /** Spoken turns (VoiceGuide), and whether the arrival was said. */
    private static final TurnAnnouncer ANNOUNCER = new TurnAnnouncer();
    private static boolean arrivalSpoken;
    /** Which request is current: an answer to an older one (a trip since replaced) is dropped. */
    private static int tripNumber;
    private static LocationManager gps;
    private static final LocationListener FIX_LISTENER = new LocationListener() {
        @Override
        public void onLocationChanged(Location fix) {
            followFix(fix);
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

    private TripGuide() {
    }

    /** Starts a trip to a place (replacing any trip in progress): finds the route and follows the GPS. */
    static void start(Context context, String name, double lat, double lon) {
        app = context.getApplicationContext();
        tripNumber++;
        active = true;
        driving = false;
        destinationName = name;
        destinationLat = lat;
        destinationLon = lon;
        routes = new ArrayList<>();
        chosenRoute = 0;
        route = null;
        progress = null;
        routeFailed = false;
        offRouteCount = 0;
        lastRouteRequest = -ROUTE_RETRY_MS;
        Location known = DashboardMap.findLastLocation(app);   // the newest: the trip starts where the car is now
        if (known != null && (lastFix == null || known.getTime() > lastFix.getTime())) lastFix = known;
        startGps();
        requestRoute(lastFix);
        notifyWatchers();
    }

    /** Ends the trip: no route, no GPS. */
    static void stop() {
        tripNumber++;
        active = false;
        driving = false;
        routes = new ArrayList<>();
        route = null;
        progress = null;
        findingRoute = false;
        stopGps();
        VoiceGuide.stopSpeaking();
        notifyWatchers();
    }

    /** Turn by turn on (after the overview), so the map follows the car. */
    static void startDriving() {
        driving = true;
        ANNOUNCER.reset();
        arrivalSpoken = false;
        VoiceGuide.speak(app, app.getString(R.string.nav_voice_start, destinationName));
        notifyWatchers();
    }

    /** Where the car is along the chosen route, or null before the first GPS fix on it. */
    static DrivingRoute.Progress lookupProgress() {
        return progress;
    }

    static boolean isActive() {
        return active;
    }

    static boolean isDriving() {
        return driving;
    }

    static String lookupDestinationName() {
        return destinationName;
    }

    static double lookupDestinationLat() {
        return destinationLat;
    }

    static double lookupDestinationLon() {
        return destinationLon;
    }

    /** Every way the router offered; empty while finding them or without internet. */
    static List<DrivingRoute> lookupRoutes() {
        return routes;
    }

    static int lookupChosenRoute() {
        return chosenRoute;
    }

    /** Picks one of the offered ways (before driving; once driving the route is set). */
    static void chooseRoute(int index) {
        if (driving || index < 0 || index >= routes.size() || index == chosenRoute) return;
        chosenRoute = index;
        route = routes.get(index);
        progress = lastFix != null ? route.followPosition(lastFix.getLatitude(), lastFix.getLongitude()) : null;
        notifyWatchers();
    }

    /** The chosen route, or null while it is being found or without one (no internet). */
    static DrivingRoute lookupRoute() {
        return route;
    }

    static Location lookupLastFix() {
        return lastFix;
    }

    /** Tells {@code watcher} after every change of the trip, until unwatched. */
    static void watch(Runnable watcher) {
        WATCHERS.add(watcher);
    }

    static void unwatch(Runnable watcher) {
        WATCHERS.remove(watcher);
    }

    private static void notifyWatchers() {
        for (Runnable watcher : new ArrayList<>(WATCHERS)) watcher.run();
    }

    @SuppressLint("MissingPermission")
    private static void startGps() {
        if (gps != null || !DashboardMap.hasLocationPermission(app)) return;
        gps = app.getSystemService(LocationManager.class);
        try {
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0, FIX_LISTENER,
                    Looper.getMainLooper());
        } catch (RuntimeException e) {
            Log.w(TAG, "no GPS for the trip", e);
            gps = null;
        }
    }

    private static void stopGps() {
        if (gps != null) gps.removeUpdates(FIX_LISTENER);
        gps = null;
    }

    private static void followFix(Location fix) {
        lastFix = fix;
        if (!active) return;
        if (route == null) {
            requestRoute(fix);   // none yet: try again (internet may be back)
        } else {
            progress = route.followPosition(fix.getLatitude(), fix.getLongitude());
            offRouteCount = progress.offRouteMeters > OFF_ROUTE_METERS ? offRouteCount + 1 : 0;
            // Still parked off the road (the route starts at the nearest road): the same route would come back.
            boolean moved = lastRouteFrom == null || fix.distanceTo(lastRouteFrom) > REROUTE_MOVED_METERS;
            if (offRouteCount >= OFF_ROUTE_FIXES && !progress.arrived && moved) requestRoute(fix);
            if (driving) speakGuidance(fix);
        }
        notifyWatchers();
    }

    /** The arrival, or the next turn when it is time (TurnAnnouncer). */
    private static void speakGuidance(Location fix) {
        if (progress.arrived) {
            if (!arrivalSpoken) VoiceGuide.speak(app, app.getString(R.string.nav_voice_arrived, destinationName));
            arrivalSpoken = true;
            return;
        }
        String words = ANNOUNCER.chooseAnnouncement(progress.nextStep, progress.toNextStepMeters,
                fix.hasSpeed() ? fix.getSpeed() : 0);
        if (words != null) VoiceGuide.speak(app, words);
    }

    private static void requestRoute(Location from) {
        if (from == null || findingRoute) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRouteRequest < ROUTE_RETRY_MS) return;
        if (!OnlineSearch.hasInternet(app)) {
            routeFailed = true;
            return;
        }
        lastRouteRequest = now;
        lastRouteFrom = from;
        findingRoute = true;
        int forTrip = tripNumber;
        double fromLat = from.getLatitude();
        double fromLon = from.getLongitude();
        double toLat = destinationLat;
        double toLon = destinationLon;
        boolean offerOthers = !driving;   // other ways to choose from before driving; a reroute takes the fastest
        ROUTER.execute(() -> {
            List<DrivingRoute> found = null;
            try {
                found = RouteFinder.findRoutes(fromLat, fromLon, toLat, toLon, offerOthers);
            } catch (IOException e) {
                Log.w(TAG, "no route", e);
            }
            List<DrivingRoute> answer = found;
            MAIN.post(() -> {
                if (forTrip != tripNumber) return;
                findingRoute = false;
                routeFailed = answer == null;
                if (answer != null) {
                    if (driving) {
                        ANNOUNCER.reset();   // a new route while driving: its turns from the start
                        VoiceGuide.speak(app, app.getString(R.string.nav_voice_rerouted));
                    }
                    routes = answer;
                    chosenRoute = 0;
                    route = answer.get(0);
                    offRouteCount = 0;
                    progress = lastFix != null ? route.followPosition(lastFix.getLatitude(), lastFix.getLongitude())
                            : null;
                }
                notifyWatchers();
            });
        });
    }

    /** True when the car is at the place. */
    static boolean hasArrived() {
        if (progress != null) return progress.arrived;
        return lastFix != null && measureStraightMeters() <= DrivingRoute.ARRIVED_METERS;
    }

    /** The next turn for a banner: "300 m" over "Turn left onto NH66", or how things stand without a route. */
    static MusicListener.Turn describeNextTurn() {
        if (!active) return null;
        if (hasArrived()) return new MusicListener.Turn(app.getString(R.string.nav_arrived), destinationName, null);
        if (route == null) {
            int problem = findingRoute ? R.string.nav_finding_route : R.string.nav_no_route;
            CharSequence away = lastFix != null ? formatDistance(measureStraightMeters()) : destinationName;
            return new MusicListener.Turn(away, app.getString(problem), null);
        }
        if (progress == null) return new MusicListener.Turn(destinationName, app.getString(R.string.nav_waiting_gps), null);
        DrivingRoute.Step next = progress.nextStep;
        String instruction = next != null ? DrivingRoute.describeStep(next) : destinationName;
        return new MusicListener.Turn(formatDistance(progress.toNextStepMeters), instruction, null);
    }

    /** "15.2 km · 17 min · arrive 3:45 PM" (straight line without a route). */
    static String describeRemaining() {
        if (!active) return "";
        List<String> parts = new ArrayList<>();
        if (route != null && progress != null) {
            double seconds = route.estimateSecondsLeft(progress);
            parts.add(formatDistance(progress.remainingMeters));
            parts.add(formatDuration(seconds));
            parts.add(app.getString(R.string.nav_arrive_at, DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(new Date(System.currentTimeMillis() + (long) (seconds * 1000)))));
        } else if (route != null) {
            parts.add(formatDistance(route.measureLengthMeters()));
            parts.add(formatDuration(route.durationSeconds));
        } else if (lastFix != null) {
            parts.add(app.getString(R.string.nav_straight, formatDistance(measureStraightMeters())));
        }
        return String.join(" · ", parts);
    }

    /** The route's main roads ("via NH66"), or why there is no route. */
    static String describeRouteState() {
        if (route != null) return route.summary.isEmpty() ? "" : app.getString(R.string.nav_via, route.summary);
        if (findingRoute) return app.getString(R.string.nav_finding_route);
        if (routeFailed || lastFix != null) return app.getString(R.string.nav_no_route);
        return app.getString(R.string.nav_waiting_gps);
    }

    private static double measureStraightMeters() {
        return CameraAlerts.measureDistanceMeters(lastFix.getLatitude(), lastFix.getLongitude(), destinationLat,
                destinationLon);
    }

    static String formatDistance(double meters) {
        if (meters < 1000) return String.format(Locale.getDefault(), "%d m", Math.round(meters / 10) * 10);
        return String.format(Locale.getDefault(), meters < 10_000 ? "%.1f km" : "%.0f km", meters / 1000);
    }

    static String formatDuration(double seconds) {
        long minutes = Math.max(1, Math.round(seconds / 60));
        if (minutes < 60) return String.format(Locale.getDefault(), "%d min", minutes);
        return String.format(Locale.getDefault(), "%d h %d min", minutes / 60, minutes % 60);
    }
}
