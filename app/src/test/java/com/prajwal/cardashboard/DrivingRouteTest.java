package com.prajwal.cardashboard;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class DrivingRouteTest {
    /** Straight north along a meridian: 0.01° of latitude is about 1113 m. */
    private static DrivingRoute buildNorthRoute() {
        double[] lats = {13.20, 13.21, 13.22};
        double[] lons = {74.75, 74.75, 74.75};
        DrivingRoute.Step depart = new DrivingRoute.Step("depart", "", "", "NH66", 0, 13.20, 74.75);
        DrivingRoute.Step turn = new DrivingRoute.Step("turn", "left", "Temple Road", "", 0, 13.21, 74.75);
        DrivingRoute.Step arrive = new DrivingRoute.Step("arrive", "", "", "", 0, 13.22, 74.75);
        return new DrivingRoute(lats, lons, 240, "NH66", Arrays.asList(depart, turn, arrive));
    }

    @Test public void progressAlongTheLine() {
        DrivingRoute route = buildNorthRoute();
        DrivingRoute.Progress halfway = route.followPosition(13.205, 74.75);
        assertEquals(556, halfway.travelledMeters, 5);
        assertEquals(route.measureLengthMeters() - 556, halfway.remainingMeters, 5);
        assertEquals(0, halfway.offRouteMeters, 1);
        assertEquals("turn", halfway.nextStep.type);
        assertEquals(556, halfway.toNextStepMeters, 5);
        assertFalse(halfway.arrived);
        assertEquals(180, route.estimateSecondsLeft(halfway), 2);
    }

    @Test public void afterTheTurnTheArrivalIsNext() {
        DrivingRoute.Progress past = buildNorthRoute().followPosition(13.215, 74.75);
        assertEquals("arrive", past.nextStep.type);
    }

    @Test public void lineIsCutWhereTheCarIs() {
        DrivingRoute route = buildNorthRoute();
        double half = route.measureLengthMeters() / 2;
        double[][] driven = route.cutLine(0, half);
        double[][] ahead = route.cutLine(half, route.measureLengthMeters());
        assertEquals(13.20, driven[0][0], 1e-9);
        assertEquals(13.21, driven[0][driven[0].length - 1], 1e-6);
        assertEquals(13.21, ahead[0][0], 1e-6);
        assertEquals(13.22, ahead[0][ahead[0].length - 1], 1e-9);
        assertEquals(2, route.cutLine(0, 0)[0].length);   // nothing driven yet: a line of one spot
    }

    @Test public void carBesideTheRoadIsOffRoute() {
        // 0.001° of longitude at 13.2° N is about 108 m.
        DrivingRoute.Progress beside = buildNorthRoute().followPosition(13.205, 74.751);
        assertEquals(108, beside.offRouteMeters, 3);
    }

    @Test public void endOfTheLineIsArrived() {
        assertTrue(buildNorthRoute().followPosition(13.2199, 74.75).arrived);
    }

    @Test public void roadNamesAreShownOnceInTheirFirstScript() {
        assertEquals("Sirsi - Kumta Highway",
                DrivingRoute.cleanRoadNames("Sirsi - Kumta Highway, Sirsi - Kumta Highway\nಸಿರ್ಸಿ - ಕುಮಟಾ ಹೆದ್ದಾರಿ"));
        assertEquals("Sirsi - Kumta Highway",
                DrivingRoute.cleanRoadNames("Sirsi - Kumta Highway, Sirsi - Kumta Highway   ಸಿರ್ಸಿ - ಕುಮಟಾ ಹೆದ್ದಾರಿ"));
        assertEquals("Sirsi - Hubbali Highway",
                DrivingRoute.cleanRoadNames("Sirsi - Hubbali Highway ಸಿರ್ಸಿ - ಹುಬ್ಬಳ್ಳಿ ಹೆದ್ದಾರಿ"));
        assertEquals("ಕಾರ್ ಸ್ಟ್ರೀಟ್", DrivingRoute.cleanRoadNames("ಕಾರ್ ಸ್ಟ್ರೀಟ್"));
        assertEquals("NH66, Nairkere Road", DrivingRoute.cleanRoadNames("NH66, Nairkere Road"));
        assertEquals("", DrivingRoute.cleanRoadNames(""));
    }

    @Test public void turnsInWords() {
        assertEquals("Turn left onto Temple Road",
                DrivingRoute.describeStep(new DrivingRoute.Step("turn", "left", "Temple Road", "", 0, 0, 0)));
        assertEquals("Continue straight onto NH66",
                DrivingRoute.describeStep(new DrivingRoute.Step("turn", "straight", "", "NH66", 0, 0, 0)));
        assertEquals("At the roundabout, take exit 2 onto Car Street",
                DrivingRoute.describeStep(new DrivingRoute.Step("roundabout", "slight left", "Car Street", "", 2, 0, 0)));
        assertEquals("Keep right at the fork",
                DrivingRoute.describeStep(new DrivingRoute.Step("fork", "slight right", "", "", 0, 0, 0)));
        assertEquals("At the end of the road, turn right onto Nairkere Road (SH1)",
                DrivingRoute.describeStep(new DrivingRoute.Step("end of road", "right", "Nairkere Road", "SH1", 0, 0, 0)));
        assertEquals("Arrive at the destination",
                DrivingRoute.describeStep(new DrivingRoute.Step("arrive", "", "", "", 0, 0, 0)));
    }
}
