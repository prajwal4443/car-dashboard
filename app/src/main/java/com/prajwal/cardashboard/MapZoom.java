package com.prajwal.cardashboard;

/**
 * How close the dashboard map follows the car at a given speed, as Google Maps does while driving: street level when
 * slow, wider as the speed rises, so the map does not race past at highway speed. Steps, not a slide, with a margin
 * below each step so the zoom does not flip back and forth around one speed. Pure arithmetic for unit tests.
 */
final class MapZoom {
    /** The zoom for each speed band: under 30, 30-60, 60-90 and over 90 km/h. */
    static final double[] ZOOMS = {16.0, 15.2, 14.4, 13.6};
    /** Where each wider band starts, in km/h. */
    static final double[] FROM_KMH = {30, 60, 90};
    /** A step closer only once the speed is this far below the band's start. */
    static final double MARGIN_KMH = 6;

    private MapZoom() {
    }

    /** The zoom for {@code speedKmh}, given the zoom shown now (one of ZOOMS; anything else is treated as the nearest). */
    static double chooseZoom(double shownZoom, double speedKmh) {
        int shown = findBand(shownZoom);
        int faster = countBandsReached(speedKmh);
        if (faster > shown) return ZOOMS[faster];                 // speeding up: widen at once
        int slower = countBandsReached(speedKmh + MARGIN_KMH);
        return ZOOMS[Math.min(shown, slower)];                     // slowing down: only well below the band
    }

    private static int countBandsReached(double speedKmh) {
        int band = 0;
        for (double start : FROM_KMH) if (speedKmh >= start) band++;
        return band;
    }

    private static int findBand(double zoom) {
        int nearest = 0;
        for (int i = 1; i < ZOOMS.length; i++) {
            if (Math.abs(ZOOMS[i] - zoom) < Math.abs(ZOOMS[nearest] - zoom)) nearest = i;
        }
        return nearest;
    }
}
