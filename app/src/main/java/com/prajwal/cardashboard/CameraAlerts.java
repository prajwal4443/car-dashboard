package com.prajwal.cardashboard;

import java.util.Iterator;
import java.util.Set;

/**
 * When to warn about a speed camera: pure geometry over GPS fixes, so it runs in unit tests (CameraAlertService feeds
 * it). A camera is announced once, when it is within the alert distance and ahead of the car (within AHEAD_DEGREES of
 * the heading) while the car moves; it can be announced again only after the car has left it RESET_METERS behind.
 */
final class CameraAlerts {
    /** Below this speed (about 10 km/h) the car is parked or crawling: no alerts. */
    static final float MIN_SPEED_MPS = 2.8f;
    /** A camera counts as ahead within this angle either side of the heading. */
    static final double AHEAD_DEGREES = 50;
    /** Once announced, a camera can be announced again after the car is this far from it. */
    static final double RESET_METERS = 1000;
    /** Cameras known only roughly (a place named in the news): warned this far ahead, as a zone. */
    static final double APPROX_ZONE_METERS = 700;
    private static final double EARTH_RADIUS_M = 6_371_000;

    private CameraAlerts() {
    }

    static double measureDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Compass bearing from the first point to the second, 0-360 (0 north, 90 east). */
    static double measureBearing(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** True when {@code target} lies within AHEAD_DEGREES of {@code heading} (both compass bearings). */
    static boolean isAhead(double heading, double target) {
        double difference = Math.abs(((target - heading) % 360 + 540) % 360 - 180);
        return difference <= AHEAD_DEGREES;
    }

    /**
     * The camera to announce for this fix, or -1: the nearest one within its own warning distance
     * ({@code alertMeters[i]}) that is ahead and not yet announced, while the car moves. Without a heading (some fixes
     * carry none) distance alone decides.
     */
    static int findCameraToAnnounce(double lat, double lon, boolean hasHeading, double heading, float speedMps,
                                    double[] cameraLat, double[] cameraLon, Set<Integer> announced,
                                    double[] alertMeters) {
        if (speedMps < MIN_SPEED_MPS) return -1;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < cameraLat.length; i++) {
            if (announced.contains(i)) continue;
            double distance = measureDistanceMeters(lat, lon, cameraLat[i], cameraLon[i]);
            if (distance > alertMeters[i] || distance >= bestDistance) continue;
            if (hasHeading && !isAhead(heading, measureBearing(lat, lon, cameraLat[i], cameraLon[i]))) continue;
            best = i;
            bestDistance = distance;
        }
        return best;
    }

    /** Forgets announced cameras the car has left RESET_METERS behind, so they can warn again next time. */
    static void forgetPassed(double lat, double lon, double[] cameraLat, double[] cameraLon, Set<Integer> announced) {
        for (Iterator<Integer> it = announced.iterator(); it.hasNext(); ) {
            int i = it.next();
            if (i >= cameraLat.length
                    || measureDistanceMeters(lat, lon, cameraLat[i], cameraLon[i]) > RESET_METERS) {
                it.remove();
            }
        }
    }
}
