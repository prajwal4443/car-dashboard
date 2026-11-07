package com.prajwal.cardashboard;

import org.junit.Test;

import static org.junit.Assert.*;

public class MapZoomTest {
    private static final double STREET = MapZoom.ZOOMS[0];
    private static final double TOWN = MapZoom.ZOOMS[1];
    private static final double ROAD = MapZoom.ZOOMS[2];
    private static final double HIGHWAY = MapZoom.ZOOMS[3];

    @Test public void widensAsTheSpeedRises() {
        assertEquals(STREET, MapZoom.chooseZoom(STREET, 20), 0);
        assertEquals(TOWN, MapZoom.chooseZoom(STREET, 45), 0);
        assertEquals(ROAD, MapZoom.chooseZoom(STREET, 75), 0);
        assertEquals(HIGHWAY, MapZoom.chooseZoom(STREET, 100), 0);
    }

    @Test public void staysWideUntilClearlySlower() {
        assertEquals("just under 60 keeps the wider view", ROAD, MapZoom.chooseZoom(ROAD, 57), 0);
        assertEquals("well under 60 closes in", TOWN, MapZoom.chooseZoom(ROAD, 50), 0);
        assertEquals("stopping at a signal returns to street level", STREET, MapZoom.chooseZoom(HIGHWAY, 0), 0);
    }

    @Test public void anOddZoomCountsAsTheNearestStep() {
        assertEquals(HIGHWAY, MapZoom.chooseZoom(13.5, 95), 0);
        assertEquals(STREET, MapZoom.chooseZoom(15.9, 10), 0);
    }
}
