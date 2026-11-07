package com.prajwal.cardashboard;

import org.junit.Test;

import static org.junit.Assert.*;

public class TurnAnnouncerTest {
    private static final double KMH_100 = 100 / 3.6;
    private static final double KMH_30 = 30 / 3.6;

    private static DrivingRoute.Step buildLeftTurn() {
        return new DrivingRoute.Step("turn", "left", "", "NH66", 0, 0, 0);
    }

    @Test public void fastApproachIsAnnouncedThreeTimesThenNeverAgain() {
        TurnAnnouncer announcer = new TurnAnnouncer();
        DrivingRoute.Step turn = buildLeftTurn();
        assertEquals("Continue for 12 kilometres", announcer.chooseAnnouncement(turn, 12_000, KMH_100));
        assertNull(announcer.chooseAnnouncement(turn, 11_000, KMH_100));
        assertEquals("In 2 kilometres, turn left onto NH66", announcer.chooseAnnouncement(turn, 1990, KMH_100));
        assertNull(announcer.chooseAnnouncement(turn, 1500, KMH_100));
        assertEquals("In 1 kilometre, turn left onto NH66", announcer.chooseAnnouncement(turn, 1000, KMH_100));
        assertNull(announcer.chooseAnnouncement(turn, 400, KMH_100));
        // 100 km/h for 7 s is about 194 m.
        assertEquals("Turn left onto NH66", announcer.chooseAnnouncement(turn, 190, KMH_100));
        assertNull(announcer.chooseAnnouncement(turn, 50, KMH_100));
    }

    @Test public void slowApproachSkipsTheEarlyWarning() {
        TurnAnnouncer announcer = new TurnAnnouncer();
        DrivingRoute.Step turn = buildLeftTurn();
        assertNull(announcer.chooseAnnouncement(turn, 1800, KMH_30));
        assertEquals("In 500 metres, turn left onto NH66", announcer.chooseAnnouncement(turn, 495, KMH_30));
        assertEquals("Turn left onto NH66", announcer.chooseAnnouncement(turn, 60, KMH_30));
    }

    @Test public void startingCloseToATurnSaysOnlyTheTurn() {
        TurnAnnouncer announcer = new TurnAnnouncer();
        DrivingRoute.Step turn = buildLeftTurn();
        assertEquals("Turn left onto NH66", announcer.chooseAnnouncement(turn, 40, KMH_30));
        assertNull(announcer.chooseAnnouncement(turn, 400, KMH_30));
    }

    @Test public void eachNewTurnStartsAgain() {
        TurnAnnouncer announcer = new TurnAnnouncer();
        announcer.chooseAnnouncement(buildLeftTurn(), 60, KMH_30);
        DrivingRoute.Step next = new DrivingRoute.Step("turn", "right", "Car Street", "", 0, 0, 0);
        assertEquals("In 450 metres, turn right onto Car Street", announcer.chooseAnnouncement(next, 460, KMH_30));
    }

    @Test public void distancesInWords() {
        assertEquals("300 metres", TurnAnnouncer.describeDistance(310));
        assertEquals("1.5 kilometres", TurnAnnouncer.describeDistance(1520));
        assertEquals("1 kilometre", TurnAnnouncer.describeDistance(980));
        assertEquals("12 kilometres", TurnAnnouncer.describeDistance(12_400));
    }
}
