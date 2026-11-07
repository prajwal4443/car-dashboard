package com.prajwal.cardashboard;

import org.junit.Test;

import static org.junit.Assert.*;

/** Which of CarLink's notifications mean a phone is projected (AutoPlay.isSessionNotification). */
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
}
