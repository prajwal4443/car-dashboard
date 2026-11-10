package com.prajwal.cardashboard;

import org.junit.Test;

import static org.junit.Assert.*;

/** Which of CarLink's notifications mean a session, and what the car-start cover does (AutoPlay.chooseCoverStep). */
public class AutoPlayTest {
    private static final String PROJECTED = "Android Auto is projected";

    @Test public void carPlaySessionCounts() {
        assertTrue(AutoPlay.isSessionNotification(true, "CarPlay connection running", PROJECTED));
    }
    @Test public void androidAutoProjectedCounts() {
        assertTrue(AutoPlay.isSessionNotification(true, PROJECTED, PROJECTED));
    }
    @Test public void androidAutoOnlyWaitingDoesNot() {
        assertFalse(AutoPlay.isSessionNotification(true, "Open Headunit is running", PROJECTED));
    }
    @Test public void notOngoingDoesNot() {
        assertFalse(AutoPlay.isSessionNotification(false, "CarPlay connection running", PROJECTED));
    }
    @Test public void noTextDoesNot() {
        assertFalse(AutoPlay.isSessionNotification(true, null, PROJECTED));
    }
    @Test public void unreadableAndroidAutoTextStillFindsCarPlay() {
        assertFalse(AutoPlay.isSessionNotification(true, PROJECTED, null));
        assertTrue(AutoPlay.isSessionNotification(true, "CarPlay connection running", null));
    }

    @Test public void onlyAndroidAutoTextMeansProjected() {
        assertTrue(AutoPlay.isProjectedNotification(PROJECTED, PROJECTED));
        assertFalse(AutoPlay.isProjectedNotification("CarPlay connection running", PROJECTED));
        assertFalse(AutoPlay.isProjectedNotification(PROJECTED, null));
    }

    private static final CarLinkScreenReader.Screen CONNECTING = CarLinkScreenReader.Screen.CONNECTING;
    private static final CarLinkScreenReader.Screen SHOWING = CarLinkScreenReader.Screen.PROJECTING;

    @Test public void carPlayConnectingKeepsTheCover() {
        assertEquals(AutoPlay.CoverStep.KEEP, AutoPlay.chooseCoverStep(true, false, CONNECTING, 3_000));
        assertEquals(AutoPlay.CoverStep.KEEP, AutoPlay.chooseCoverStep(true, false, CONNECTING, 30_000));
    }
    @Test public void carPlayOnScreenShowsCarLink() {
        assertEquals(AutoPlay.CoverStep.SHOW_CARLINK, AutoPlay.chooseCoverStep(true, false, SHOWING, 4_000));
    }
    @Test public void noTextWithoutASessionIsNotAPhone() {
        assertEquals(AutoPlay.CoverStep.KEEP, AutoPlay.chooseCoverStep(false, false, SHOWING, 4_000));
    }
    @Test public void androidAutoProjectedShowsCarLinkWhateverTheScreen() {
        assertEquals(AutoPlay.CoverStep.SHOW_CARLINK,
                AutoPlay.chooseCoverStep(true, true, CarLinkScreenReader.Screen.UNKNOWN, 4_000));
    }
    @Test public void noSessionByTheWaitGoesHome() {
        assertEquals(AutoPlay.CoverStep.KEEP, AutoPlay.chooseCoverStep(false, false, CONNECTING, 19_000));
        assertEquals(AutoPlay.CoverStep.GO_HOME, AutoPlay.chooseCoverStep(false, false, CONNECTING, 20_000));
    }
    @Test public void notOnScreenByTheLimitGoesHome() {
        assertEquals(AutoPlay.CoverStep.GO_HOME, AutoPlay.chooseCoverStep(true, false, CONNECTING, 45_000));
    }
}
