package com.prajwal.cardashboard;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

/** Search over a real OpenFreeMap tile: z14 x11593 y7585, Kaup on NH66 (src/test/resources). */
public class PlaceIndexTest {
    private static final int Z = 14;
    private static final int X = 11593;
    private static final int Y = 7585;
    private static final double KAUP_LAT = 13.2135;
    private static final double KAUP_LON = 74.7495;

    private static byte[] readKaupTile() throws IOException {
        try (InputStream in = PlaceIndexTest.class.getResourceAsStream("/kaup_14_11593_7585.pbf");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertNotNull("test tile missing", in);
            byte[] chunk = new byte[4096];
            for (int read; (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    private static List<PlaceIndex.Place> buildKaupPlaces() throws IOException {
        return PlaceIndex.mergeDuplicates(PlaceIndex.extractPlaces(readKaupTile(), Z, X, Y));
    }

    private static double findTileNorth(int y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / Math.pow(2, Z)))));
    }

    @Test public void petrolPumpLandsInsideItsTile() throws IOException {
        PlaceIndex.Place pump = PlaceIndex.findMatches(buildKaupPlaces(), "Bharat Petroleum", KAUP_LAT, KAUP_LON,
                true, 5).get(0);
        assertEquals("fuel", pump.kind);
        assertEquals("Petrol pump", PlaceIndex.describeKind(pump));
        double west = X / Math.pow(2, Z) * 360 - 180;
        double east = (X + 1) / Math.pow(2, Z) * 360 - 180;
        assertTrue("longitude " + pump.lon, pump.lon >= west && pump.lon <= east);
        assertTrue("latitude " + pump.lat, pump.lat <= findTileNorth(Y) && pump.lat >= findTileNorth(Y + 1));
    }

    @Test public void kaupIsFoundByItsEnglishAndLocalName() throws IOException {
        List<PlaceIndex.Place> places = buildKaupPlaces();
        for (String query : new String[] {"Kaup", "kapu", "KAU"}) {
            List<PlaceIndex.Place> found = PlaceIndex.findMatches(places, query, KAUP_LAT, KAUP_LON, true, 5);
            assertFalse(query, found.isEmpty());
            assertEquals(query, "Kaup", found.get(0).name);
            assertEquals("Village", PlaceIndex.describeKind(found.get(0)));
        }
    }

    @Test public void kindWordsFindPlacesWithoutTheirName() throws IOException {
        List<PlaceIndex.Place> places = buildKaupPlaces();
        assertEquals("Bharat Petroleum", PlaceIndex.findMatches(places, "petrol", KAUP_LAT, KAUP_LON, true, 5)
                .get(0).name);
        assertEquals("Jumma Masjid Muloor", PlaceIndex.findMatches(places, "mosque", KAUP_LAT, KAUP_LON, true, 5)
                .get(0).name);
        assertTrue(PlaceIndex.findMatches(places, "temple", KAUP_LAT, KAUP_LON, true, 5).isEmpty());
    }

    @Test public void pointsOfInterestKnowTheirVillage() throws IOException {
        PlaceIndex.Place pump = PlaceIndex.findMatches(buildKaupPlaces(), "petrol", KAUP_LAT, KAUP_LON, true, 5)
                .get(0);
        assertFalse(pump.area.isEmpty());
    }

    @Test public void roadNumberMatchesWithOrWithoutSpace() throws IOException {
        List<PlaceIndex.Place> places = buildKaupPlaces();
        for (String query : new String[] {"NH66", "nh 66"}) {
            List<PlaceIndex.Place> found = PlaceIndex.findMatches(places, query, KAUP_LAT, KAUP_LON, true, 5);
            assertEquals(query, 1, found.size());
            assertEquals("Road", PlaceIndex.describeKind(found.get(0)));
        }
    }

    @Test public void sameRoadFromNeighbouringTilesIsListedOnce() throws IOException {
        List<PlaceIndex.Place> both = PlaceIndex.extractPlaces(readKaupTile(), Z, X, Y);
        both.addAll(PlaceIndex.extractPlaces(readKaupTile(), Z, X, Y));   // the same tile twice: every name repeats
        assertEquals(buildKaupPlaces().size(), PlaceIndex.mergeDuplicates(both).size());
    }

    @Test public void nothingMatchesNonsense() throws IOException {
        assertTrue(PlaceIndex.findMatches(buildKaupPlaces(), "zzqx", KAUP_LAT, KAUP_LON, true, 5).isEmpty());
        assertTrue(PlaceIndex.findMatches(buildKaupPlaces(), " - ", KAUP_LAT, KAUP_LON, true, 5).isEmpty());
    }

    @Test public void indexFileKeepsEveryPlace() throws IOException {
        List<PlaceIndex.Place> places = buildKaupPlaces();
        File file = File.createTempFile("place-index", ".tsv");
        try {
            PlaceIndex.saveIndex(file, places);
            List<PlaceIndex.Place> loaded = PlaceIndex.loadIndex(file);
            assertEquals(places.size(), loaded.size());
            assertEquals(places.get(0).name, loaded.get(0).name);
            assertEquals(places.get(0).lat, loaded.get(0).lat, 1e-12);
            assertEquals(places.get(0).searchNames, loaded.get(0).searchNames);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void storedTileIsUnpackedFromZlib() throws IOException {
        byte[] tile = readKaupTile();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(packed)) {
            zlib.write(tile);
        }
        assertArrayEquals(tile, VectorTileReader.unpackStoredTile(packed.toByteArray(), true));
        assertArrayEquals(tile, VectorTileReader.unpackStoredTile(tile, false));
    }
}
