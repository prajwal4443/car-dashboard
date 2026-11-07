package com.prajwal.cardashboard;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * Offline routing over 36 real OpenFreeMap z14 tiles between Kaup and Udupi (src/test/resources/kaup_udupi). The
 * online router (OSRM) gives 15.2 km via NH66 and Nairkere Road for this trip.
 */
public class OfflineRouterTest {
    private static final double KAUP_LAT = 13.2135;
    private static final double KAUP_LON = 74.7495;
    private static final double UDUPI_LAT = 13.3409;
    private static final double UDUPI_LON = 74.7421;
    private static RoadGraph graph;

    @BeforeClass public static void buildGraph() throws IOException {
        RoadGraph.Builder builder = new RoadGraph.Builder();
        for (int x = 11592; x <= 11595; x++) {
            for (int y = 7578; y <= 7586; y++) builder.addTile(x, y, readTile(x, y));
        }
        graph = builder.build();
    }

    private static byte[] readTile(int x, int y) throws IOException {
        String name = "/kaup_udupi/14_" + x + "_" + y + ".pbf";
        try (InputStream in = OfflineRouterTest.class.getResourceAsStream(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertNotNull(name, in);
            byte[] chunk = new byte[8192];
            for (int read; (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    @Test public void kaupToUdupiFollowsNh66LikeTheOnlineRouter() {
        DrivingRoute route = OfflineRouter.findRoute(graph, KAUP_LAT, KAUP_LON, UDUPI_LAT, UDUPI_LON);
        assertNotNull("no offline route", route);
        double km = route.measureLengthMeters() / 1000;
        assertTrue("length " + km + " km", km > 14 && km < 17.5);
        assertTrue("summary " + route.summary, route.summary.contains("NH66"));
        double minutes = route.durationSeconds / 60;
        assertTrue("time " + minutes + " min", minutes > 10 && minutes < 30);
        assertEquals("depart", route.steps.get(0).type);
        assertEquals("arrive", route.steps.get(route.steps.size() - 1).type);
        assertTrue("turns " + route.steps.size(), route.steps.size() >= 3 && route.steps.size() <= 40);
        // The line starts and ends at the roads nearest the two places.
        assertEquals(KAUP_LAT, route.lats[0], 0.005);
        assertEquals(UDUPI_LAT, route.lats[route.lats.length - 1], 0.005);
    }

    @Test public void theWayBackIsAboutAsLong() {
        DrivingRoute there = OfflineRouter.findRoute(graph, KAUP_LAT, KAUP_LON, UDUPI_LAT, UDUPI_LON);
        DrivingRoute back = OfflineRouter.findRoute(graph, UDUPI_LAT, UDUPI_LON, KAUP_LAT, KAUP_LON);
        assertNotNull(back);
        assertEquals(there.measureLengthMeters(), back.measureLengthMeters(), 3000);
    }

    @Test public void placeOutsideTheDownloadHasNoRoute() {
        // Mangaluru: tens of km south of the tiles.
        assertNull(OfflineRouter.findRoute(graph, KAUP_LAT, KAUP_LON, 12.9141, 74.8560));
    }

    @Test public void graphFileKeepsTheRoads() throws IOException {
        File file = File.createTempFile("road-graph", ".bin");
        try {
            graph.save(file);
            RoadGraph loaded = RoadGraph.load(file);
            assertNotNull(loaded);
            assertEquals(graph.countNodes(), loaded.countNodes());
            assertEquals(graph.countEdges(), loaded.countEdges());
            DrivingRoute route = OfflineRouter.findRoute(loaded, KAUP_LAT, KAUP_LON, UDUPI_LAT, UDUPI_LON);
            assertNotNull(route);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void dividedHighwayIsDrivenOnlyItsWay() {
        // NH66 here is two one-way carriageways: some of its edges have no edge back.
        int oneWay = 0;
        for (int node = 0; node < graph.countNodes(); node++) {
            for (int edge = graph.firstEdge[node]; edge < graph.firstEdge[node + 1]; edge++) {
                if (!"trunk".equals(RoadGraph.KINDS[graph.edgeKind[edge]])) continue;
                int to = graph.edgeTo[edge];
                boolean back = false;
                for (int other = graph.firstEdge[to]; other < graph.firstEdge[to + 1]; other++) {
                    if (graph.edgeTo[other] == node) back = true;
                }
                if (!back) oneWay++;
            }
        }
        assertTrue("one-way trunk edges " + oneWay, oneWay > 100);
    }

    @Test public void roundaboutIsOneTurn() {
        DrivingRoute route = OfflineRouter.findRoute(graph, KAUP_LAT, KAUP_LON, UDUPI_LAT, UDUPI_LON);
        for (int i = 1; i < route.steps.size(); i++) {
            double gap = route.steps.get(i).alongMeters - route.steps.get(i - 1).alongMeters;
            assertTrue("turns " + gap + " m apart", gap >= 10);
        }
    }
}
