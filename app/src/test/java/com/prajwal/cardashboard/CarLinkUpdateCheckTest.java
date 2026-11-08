package com.prajwal.cardashboard;

import org.junit.Test;

import static org.junit.Assert.*;

/** When CarLink's update check runs (CarLinkUpdateCheck.isCheckDue), and how versions compare. */
public class CarLinkUpdateCheckTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long HOUR = 60L * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    @Test public void neverCheckedIsDue() {
        assertTrue(CarLinkUpdateCheck.isCheckDue(NOW, 0, 0));
    }
    @Test public void checkedThreeDaysAgoWaits() {
        assertFalse(CarLinkUpdateCheck.isCheckDue(NOW, NOW - 3 * DAY, NOW - 3 * DAY));
    }
    @Test public void sevenDaysAfterACheckIsDue() {
        assertTrue(CarLinkUpdateCheck.isCheckDue(NOW, NOW - 7 * DAY, NOW - 7 * DAY));
    }
    @Test public void internetBackAfterTenDaysChecksAtOnce() {
        assertTrue(CarLinkUpdateCheck.isCheckDue(NOW, NOW - 10 * DAY, NOW - 10 * DAY));
    }
    @Test public void aFailedAttemptRetriesAfterAnHourOnly() {
        long lastSuccess = NOW - 10 * DAY;
        assertFalse(CarLinkUpdateCheck.isCheckDue(NOW, lastSuccess, NOW - 20 * 60 * 1000));
        assertTrue(CarLinkUpdateCheck.isCheckDue(NOW, lastSuccess, NOW - HOUR));
    }
    @Test public void aClockThatWentBackwardsIsDue() {
        assertTrue(CarLinkUpdateCheck.isCheckDue(NOW - 400 * DAY, NOW, NOW));
    }
    @Test public void versionsCompareByNumberNotText() {
        assertTrue(CarLinkUpdateCheck.compareVersions("1.0.9", "1.0.13") < 0);
        assertTrue(CarLinkUpdateCheck.compareVersions("1.1", "1.0.13") > 0);
        assertEquals(0, CarLinkUpdateCheck.compareVersions("1.0.13", "1.0.13"));
        assertEquals(0, CarLinkUpdateCheck.compareVersions("1.0", "1.0.0"));
        assertTrue(CarLinkUpdateCheck.compareVersions("1.0.13", "1.0.14-beta") < 0);
    }
}
