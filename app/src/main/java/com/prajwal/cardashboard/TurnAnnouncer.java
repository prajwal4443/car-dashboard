package com.prajwal.cardashboard;

import java.util.Locale;

/**
 * When to speak a turn, like a navigation app: "Continue for 12 kilometres" after a turn with a long way to the
 * next, "In 2 kilometres, …" at speed, "In 500 metres, …", and the turn itself just before it (sooner the faster
 * the car). Each once per turn; a later one makes the earlier ones it skipped done. Plain Java, so it runs in unit
 * tests.
 */
final class TurnAnnouncer {
    /** A turn further than this after the last one gets "Continue for …". */
    static final double CONTINUE_METERS = 3000;
    /** The early warning, only at speed (from FAR_FROM_KMH). */
    static final double FAR_METERS = 2000;
    static final double FAR_FROM_KMH = 60;
    /** The middle warning: further out at speed. */
    static final double MID_METERS_SLOW = 500;
    static final double MID_METERS_FAST = 1000;
    /** The turn itself: this many seconds ahead at the car's speed, at least NOW_MIN_METERS. */
    static final double NOW_SECONDS = 7;
    static final double NOW_MIN_METERS = 60;

    private static final int CONTINUE = 1;
    private static final int FAR = 2;
    private static final int MID = 4;
    private static final int NOW = 8;

    private DrivingRoute.Step currentStep;
    private int spoken;

    /** What to say now for the next turn {@code toNextMeters} ahead at {@code speedMps}, or null for nothing. */
    String chooseAnnouncement(DrivingRoute.Step next, double toNextMeters, double speedMps) {
        if (next == null) return null;
        boolean newStep = next != currentStep;
        if (newStep) {
            currentStep = next;
            spoken = 0;
        }
        String instruction = DrivingRoute.describeStep(next);
        double kmh = speedMps * 3.6;
        double nowMeters = Math.max(NOW_MIN_METERS, speedMps * NOW_SECONDS);
        double midMeters = kmh >= FAR_FROM_KMH ? MID_METERS_FAST : MID_METERS_SLOW;
        if (toNextMeters <= nowMeters) {
            String now = speakOnce(NOW, CONTINUE | FAR | MID | NOW, instruction);
            return "arrive".equals(next.type) ? null : now;   // the arrival has its own words (TripGuide)
        }
        if (toNextMeters <= midMeters) {
            return speakOnce(MID, CONTINUE | FAR | MID,
                    "In " + describeDistance(toNextMeters) + ", " + lowerFirst(instruction));
        }
        if (toNextMeters <= FAR_METERS && kmh >= FAR_FROM_KMH) {
            return speakOnce(FAR, CONTINUE | FAR,
                    "In " + describeDistance(toNextMeters) + ", " + lowerFirst(instruction));
        }
        if (newStep && toNextMeters > CONTINUE_METERS) {
            return speakOnce(CONTINUE, CONTINUE, "Continue for " + describeDistance(toNextMeters));
        }
        return null;
    }

    /** Forgets the turns spoken (a new route). */
    void reset() {
        currentStep = null;
        spoken = 0;
    }

    private String speakOnce(int stage, int markDone, String text) {
        if ((spoken & stage) != 0) return null;
        spoken |= markDone;
        return text;
    }

    /** Distance as spoken: "300 metres" (to 50 m), "1.5 kilometres", "12 kilometres". */
    static String describeDistance(double meters) {
        if (meters < 950) return Math.max(50, Math.round(meters / 50) * 50) + " metres";
        double km = meters / 1000;
        if (km < 9.95) {
            double tenths = Math.round(km * 10) / 10.0;
            if (tenths == Math.rint(tenths)) {
                long whole = Math.round(tenths);
                return whole + (whole == 1 ? " kilometre" : " kilometres");
            }
            return String.format(Locale.ROOT, "%.1f kilometres", tenths);
        }
        return Math.round(km) + " kilometres";
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
