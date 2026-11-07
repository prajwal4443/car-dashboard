package com.prajwal.cardashboard;

import org.junit.Test;

import static org.junit.Assert.*;

public class MapAreaTest {
    private static final double BRAHMAVAR_LAT = 13.44;
    private static final double BRAHMAVAR_LON = 74.75;

    @Test public void squareIsCentredAndSizedInKilometres() {
        MapArea area = MapArea.buildSquare(BRAHMAVAR_LAT, BRAHMAVAR_LON, 50);
        assertEquals(BRAHMAVAR_LAT, (area.north + area.south) / 2, 1e-9);
        assertEquals(BRAHMAVAR_LON, (area.east + area.west) / 2, 1e-9);
        assertEquals(50 / 111.32, area.north - area.south, 1e-9);
        assertTrue("longitude span widens away from the equator", area.east - area.west > area.north - area.south);
    }

    @Test public void allOfferedSizesStayInsideOpenFreeMapsLimit() {
        for (int km : new int[] {25, 50, 100}) {
            long tiles = MapArea.buildSquare(BRAHMAVAR_LAT, BRAHMAVAR_LON, km).countTiles();
            assertTrue(km + " km: " + tiles + " tiles", tiles <= MapArea.TILE_LIMIT);
        }
    }

    @Test public void biggerAreasNeedMoreTiles() {
        long small = MapArea.buildSquare(BRAHMAVAR_LAT, BRAHMAVAR_LON, 25).countTiles();
        long large = MapArea.buildSquare(BRAHMAVAR_LAT, BRAHMAVAR_LON, 100).countTiles();
        assertTrue(large > small * 8);
    }

    @Test public void aPickedBoxMeasuresAndCountsLikeASquare() {
        MapArea square = MapArea.buildSquare(BRAHMAVAR_LAT, BRAHMAVAR_LON, 40);
        MapArea picked = MapArea.fromBounds(square.north, square.south, square.east, square.west);
        assertEquals(40, picked.measureWidthKm(), 0.5);
        assertEquals(40, picked.measureHeightKm(), 0.01);
        assertEquals(square.countTiles(), picked.countTiles());
    }

    @Test public void countsEveryZoomFromTheWholeWorldDown() {
        // A tiny square still needs one tile per zoom level, at least.
        assertTrue(MapArea.buildSquare(BRAHMAVAR_LAT, BRAHMAVAR_LON, 0.01).countTiles()
                >= MapArea.MAX_ZOOM - MapArea.MIN_ZOOM + 1);
    }
}
