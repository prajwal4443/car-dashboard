package com.prajwal.cardashboard;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class CameraAlertsTest {
    // A car at Brahmavar heading north at 50 km/h; one degree of latitude is about 111 km.
    private static final double LAT = 13.44;
    private static final double LON = 74.75;
    private static final float DRIVING = 14f;
    private static final double METERS_PER_DEGREE = 111_195;

    private static double north(double meters) {
        return LAT + meters / METERS_PER_DEGREE;
    }

    /** Every camera warned 100 m ahead. */
    private static double[] zones(int cameras) {
        double[] meters = new double[cameras];
        java.util.Arrays.fill(meters, 100);
        return meters;
    }

    @Test public void approximateCamerasWarnFromTheirWiderZone() {
        double[] lat = {north(500)};
        double[] lon = {LON};
        assertEquals("100 m camera stays quiet at 500 m", -1, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0,
                DRIVING, lat, lon, new HashSet<>(), zones(1)));
        assertEquals("700 m zone warns at 500 m", 0, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, DRIVING,
                lat, lon, new HashSet<>(), new double[] {CameraAlerts.APPROX_ZONE_METERS}));
    }

    @Test public void measuresDistanceAndBearing() {
        assertEquals(80, CameraAlerts.measureDistanceMeters(LAT, LON, north(80), LON), 0.5);
        assertEquals(0, CameraAlerts.measureBearing(LAT, LON, north(80), LON), 0.01);
        assertEquals(90, CameraAlerts.measureBearing(LAT, LON, LAT, LON + 0.001), 0.01);
    }

    @Test public void announcesACameraAheadWithinTheAlertDistance() {
        Set<Integer> announced = new HashSet<>();
        assertEquals(0, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, DRIVING,
                new double[] {north(90)}, new double[] {LON}, announced, zones(1)));
    }

    @Test public void staysQuietBeyondTheDistanceBehindOrParked() {
        Set<Integer> none = new HashSet<>();
        double[] lon = {LON};
        assertEquals("too far", -1, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, DRIVING,
                new double[] {north(150)}, lon, none, zones(1)));
        assertEquals("behind the car", -1, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, DRIVING,
                new double[] {north(-60)}, lon, none, zones(1)));
        assertEquals("parked", -1, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, 1f,
                new double[] {north(60)}, lon, none, zones(1)));
    }

    @Test public void picksTheNearestAndNeverRepeatsUntilPassed() {
        double[] lat = {north(95), north(40)};
        double[] lon = {LON, LON};
        Set<Integer> announced = new HashSet<>();
        double[] zone = zones(lat.length);
        int first = CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, DRIVING, lat, lon, announced, zone);
        assertEquals(1, first);
        announced.add(first);
        assertEquals(0, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, DRIVING, lat, lon, announced, zone));
        announced.add(0);
        assertEquals(-1, CameraAlerts.findCameraToAnnounce(LAT, LON, true, 0, DRIVING, lat, lon, announced, zone));
        CameraAlerts.forgetPassed(north(1200), LON, lat, lon, announced);
        assertTrue("both left 1 km behind", announced.isEmpty());
    }

    @Test public void withoutAHeadingDistanceAloneDecides() {
        assertEquals(0, CameraAlerts.findCameraToAnnounce(LAT, LON, false, 0, DRIVING,
                new double[] {north(-60)}, new double[] {LON}, new HashSet<>(), zones(1)));
    }

    @Test public void aheadWrapsAroundNorth() {
        assertTrue(CameraAlerts.isAhead(350, 20));
        assertTrue(CameraAlerts.isAhead(10, 330));
        assertFalse(CameraAlerts.isAhead(0, 180));
    }
}
