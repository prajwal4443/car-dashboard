package com.prajwal.cardashboard;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DashboardAppsTest {
    private static final List<String> NONE = Collections.emptyList();

    @Test public void addedAppsInOrderUpToTheCount() {
        List<String> launchable = Arrays.asList("music", "phone", "radio", "video");
        assertEquals(Arrays.asList("music", "phone", "radio"), DashboardApps.pickTilePackages(
                Arrays.asList("music", "phone", "radio", "video"), launchable, NONE, NONE, 3));
    }
    @Test public void skipsUninstalledRemovedAndExcluded() {
        List<String> launchable = Arrays.asList("maps", "music", "removed", "self");
        assertEquals(Collections.singletonList("music"), DashboardApps.pickTilePackages(
                Arrays.asList("gone", "maps", "removed", "self", "music"), launchable,
                Collections.singletonList("removed"), Arrays.asList("maps", "self"), 3));
    }
    @Test public void noDuplicates() {
        assertEquals(Arrays.asList("music", "phone"), DashboardApps.pickTilePackages(
                Arrays.asList("music", "music", "phone"), Arrays.asList("music", "phone"), NONE, NONE, 3));
    }
    @Test public void nothingInstalledGivesNoTiles() {
        assertTrue(DashboardApps.pickTilePackages(Arrays.asList("a", "b"), NONE, NONE, NONE, 3).isEmpty());
    }
    @Test public void followsThePlayingSessionOverTheMostRecent() {
        assertEquals(2, DashboardApps.pickSessionIndex(new int[] {2, 1, DashboardApps.STATE_PLAYING}));
        assertEquals(1, DashboardApps.pickSessionIndex(new int[] {2, DashboardApps.STATE_BUFFERING}));
    }
    @Test public void nothingPlayingFollowsTheMostRecent() {
        assertEquals(0, DashboardApps.pickSessionIndex(new int[] {2, 1}));
    }
    @Test public void noSessionsIsMinusOne() {
        assertEquals(-1, DashboardApps.pickSessionIndex(new int[0]));
    }
    @Test public void samplesLargeArtDownButNeverBelowTheTarget() {
        assertEquals(2, DashboardApps.calculateSampleSize(1000, 800, 384));
        assertEquals(4, DashboardApps.calculateSampleSize(3000, 1600, 384));
        assertEquals(1, DashboardApps.calculateSampleSize(300, 300, 384));
        assertEquals(1, DashboardApps.calculateSampleSize(0, 0, 384));
    }
    @Test public void reportsFreedMemoryInMegabytes() {
        assertEquals("312 MB", DashboardApps.formatFreedMemory(312L * 1024 * 1024 + 5));
        assertNull(DashboardApps.formatFreedMemory(512 * 1024));
        assertNull(DashboardApps.formatFreedMemory(-40L * 1024 * 1024));
    }
    @Test public void startupRunsOnANewBoot() {
        assertTrue(CarStartup.isStartupDue(7, 6, -1, -1, 1_000));
        assertFalse(CarStartup.isStartupDue(7, 7, -1, -1, 1_000));
    }
    @Test public void startupRunsAfterALongSleepInTheSameBoot() {
        long off = 50_000;
        assertTrue(CarStartup.isStartupDue(7, 7, off, 7, off + CarStartup.MIN_SLEEP_MS));
        assertFalse("a short screen-off is not the car switching off",
                CarStartup.isStartupDue(7, 7, off, 7, off + 5_000));
        assertFalse("a sleep recorded in an earlier boot counts as that boot, already handled",
                CarStartup.isStartupDue(7, 7, off, 6, off + CarStartup.MIN_SLEEP_MS));
    }
    @Test public void unknownBootCountStillCountsALongSleep() {
        assertTrue(CarStartup.isStartupDue(-1, -1, 0, -1, CarStartup.MIN_SLEEP_MS));
    }
    @Test public void backdropTakesTheCoversVividColourDarkened() {
        int[] cover = new int[100];
        java.util.Arrays.fill(cover, 0xFF101010);                      // mostly black
        for (int i = 0; i < 30; i++) cover[i] = 0xFFE03020;             // a red band
        int tint = DashboardApps.calculateBackdropColor(cover);
        int r = (tint >> 16) & 0xFF, g = (tint >> 8) & 0xFF, b = tint & 0xFF;
        assertEquals(0xFF, tint >>> 24);
        assertTrue("red leads", r > g && r > b);
        assertEquals(Math.round(DashboardApps.BACKDROP_VALUE * 255), r);
    }
    @Test public void greyCoverGivesADarkGrey() {
        int[] cover = new int[16];
        java.util.Arrays.fill(cover, 0xFFC8C8C8);
        int tint = DashboardApps.calculateBackdropColor(cover);
        assertEquals((tint >> 16) & 0xFF, tint & 0xFF);
        assertEquals(Math.round(DashboardApps.BACKDROP_VALUE * 255), tint & 0xFF);
    }
    @Test public void hsvRoundTrips() {
        float[] hsv = DashboardApps.convertRgbToHsv(51, 102, 204);
        assertEquals(0xFF3366CC, DashboardApps.convertHsvToRgb(hsv[0], hsv[1], hsv[2]));
    }
    @Test public void aSongWithoutArtAlwaysGetsTheSameOpaqueColours() {
        int[] first = DashboardApps.calculateSongColors("Baanali Jaarida\u0000Ninaada Nayak");
        assertArrayEquals(first, DashboardApps.calculateSongColors("Baanali Jaarida\u0000Ninaada Nayak"));
        for (int colour : first) assertEquals(0xFF, colour >>> 24);
        int tint = first[2];
        assertEquals(Math.round(DashboardApps.BACKDROP_VALUE * 255),
                Math.max((tint >> 16) & 0xFF, Math.max((tint >> 8) & 0xFF, tint & 0xFF)));
    }
    @Test public void differentSongsGetDifferentColours() {
        assertNotEquals(DashboardApps.calculateSongColors("Song A\u0000X")[0],
                DashboardApps.calculateSongColors("Song B\u0000Y")[0]);
    }
    @Test public void artCacheNamesAreStableHexFileNames() {
        String name = DashboardApps.buildArtCacheName("https://m.media-amazon.com/images/I/cover.jpg");
        assertEquals(name, DashboardApps.buildArtCacheName("https://m.media-amazon.com/images/I/cover.jpg"));
        assertTrue(name.matches("[0-9a-f]{40}\\.jpg"));
    }
    @Test public void formatsPositions() {
        assertEquals("0:00", DashboardApps.formatPosition(-5));
        assertEquals("1:24", DashboardApps.formatPosition(84_900));
        assertEquals("1:02:03", DashboardApps.formatPosition(3_723_000));
    }
}
