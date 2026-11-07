package com.prajwal.cardashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A driving route (from RouteFinder): its line, its turns, and where the car is along it. Plain Java, so it runs in
 * unit tests.
 */
final class DrivingRoute {
    /** Within this of the end the drive is over. */
    static final double ARRIVED_METERS = 30;

    /** One turn (OSRM's maneuver): what to do, onto which road, and where. */
    static final class Step {
        final String type;
        final String modifier;
        final String name;
        final String ref;
        final int exit;
        final double lat;
        final double lon;
        /** How far along the route it is, in metres from the start. */
        double alongMeters;

        Step(String type, String modifier, String name, String ref, int exit, double lat, double lon) {
            this.type = type;
            this.modifier = modifier;
            this.name = name;
            this.ref = ref;
            this.exit = exit;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Where the car is along the route. */
    static final class Progress {
        final double travelledMeters;
        final double remainingMeters;
        /** How far the car is from the route line. */
        final double offRouteMeters;
        /** The next turn, or null when only the arrival is left. */
        final Step nextStep;
        final double toNextStepMeters;
        final boolean arrived;

        Progress(double travelledMeters, double remainingMeters, double offRouteMeters, Step nextStep,
                 double toNextStepMeters, boolean arrived) {
            this.travelledMeters = travelledMeters;
            this.remainingMeters = remainingMeters;
            this.offRouteMeters = offRouteMeters;
            this.nextStep = nextStep;
            this.toNextStepMeters = toNextStepMeters;
            this.arrived = arrived;
        }
    }

    final double[] lats;
    final double[] lons;
    final double durationSeconds;
    /** The main roads, e.g. "NH66, Nairkere Road". */
    final String summary;
    final List<Step> steps;
    /** Distance from the start to each point of the line. */
    private final double[] alongMeters;

    DrivingRoute(double[] lats, double[] lons, double durationSeconds, String summary, List<Step> steps) {
        if (lats.length < 2 || lats.length != lons.length) throw new IllegalArgumentException("route needs a line");
        this.lats = lats;
        this.lons = lons;
        this.durationSeconds = durationSeconds;
        this.summary = cleanRoadNames(summary);
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        alongMeters = new double[lats.length];
        for (int i = 1; i < lats.length; i++) {
            alongMeters[i] = alongMeters[i - 1]
                    + CameraAlerts.measureDistanceMeters(lats[i - 1], lons[i - 1], lats[i], lons[i]);
        }
        placeStepsAlongLine();
    }

    double measureLengthMeters() {
        return alongMeters[alongMeters.length - 1];
    }

    /**
     * The part of the line from {@code fromMeters} to {@code toMeters} along it, as {lats, lons}: for drawing the
     * road driven (grey) apart from the road ahead.
     */
    double[][] cutLine(double fromMeters, double toMeters) {
        double from = Math.max(0, Math.min(fromMeters, measureLengthMeters()));
        double to = Math.max(from, Math.min(toMeters, measureLengthMeters()));
        java.util.List<double[]> points = new ArrayList<>();
        points.add(findPointAt(from));
        for (int i = 0; i < lats.length; i++) {
            if (alongMeters[i] > from && alongMeters[i] < to) points.add(new double[] {lats[i], lons[i]});
        }
        points.add(findPointAt(to));
        double[][] line = new double[2][points.size()];
        for (int i = 0; i < points.size(); i++) {
            line[0][i] = points.get(i)[0];
            line[1][i] = points.get(i)[1];
        }
        return line;
    }

    private double[] findPointAt(double meters) {
        for (int i = 0; i + 1 < lats.length; i++) {
            if (meters <= alongMeters[i + 1]) {
                double length = alongMeters[i + 1] - alongMeters[i];
                double share = length == 0 ? 0 : (meters - alongMeters[i]) / length;
                return new double[] {lats[i] + (lats[i + 1] - lats[i]) * share, lons[i] + (lons[i + 1] - lons[i]) * share};
            }
        }
        return new double[] {lats[lats.length - 1], lons[lons.length - 1]};
    }

    /** Each turn's distance from the start: the nearest line point, searched onward from the turn before. */
    private void placeStepsAlongLine() {
        int from = 0;
        for (Step step : steps) {
            int best = from;
            double bestMeters = Double.MAX_VALUE;
            for (int i = from; i < lats.length; i++) {
                double meters = CameraAlerts.measureDistanceMeters(step.lat, step.lon, lats[i], lons[i]);
                if (meters < bestMeters) {
                    bestMeters = meters;
                    best = i;
                }
            }
            step.alongMeters = alongMeters[best];
            from = best;
        }
    }

    /** The car at lat/lon: the nearest point on the line, what is left, and the next turn. */
    Progress followPosition(double lat, double lon) {
        double metersPerLat = 111_320;
        double metersPerLon = 111_320 * Math.cos(Math.toRadians(lat));
        double bestOff = Double.MAX_VALUE;
        double travelled = 0;
        for (int i = 0; i + 1 < lats.length; i++) {
            double ax = (lons[i] - lon) * metersPerLon;
            double ay = (lats[i] - lat) * metersPerLat;
            double bx = (lons[i + 1] - lon) * metersPerLon;
            double by = (lats[i + 1] - lat) * metersPerLat;
            double dx = bx - ax;
            double dy = by - ay;
            double lengthSquared = dx * dx + dy * dy;
            double share = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
            double offX = ax + dx * share;
            double offY = ay + dy * share;
            double off = Math.sqrt(offX * offX + offY * offY);
            if (off < bestOff) {
                bestOff = off;
                travelled = alongMeters[i] + (alongMeters[i + 1] - alongMeters[i]) * share;
            }
        }
        double remaining = Math.max(0, measureLengthMeters() - travelled);
        Step next = null;
        for (Step step : steps) {
            if ("depart".equals(step.type)) continue;
            if (step.alongMeters > travelled + 5) {
                next = step;
                break;
            }
        }
        double toNext = next != null ? next.alongMeters - travelled : remaining;
        return new Progress(travelled, remaining, bestOff, next, toNext, remaining <= ARRIVED_METERS);
    }

    /** Time left, scaled from the whole route's time by the distance left. */
    double estimateSecondsLeft(Progress progress) {
        double length = measureLengthMeters();
        return length == 0 ? 0 : durationSeconds * progress.remainingMeters / length;
    }

    /** What to do at a turn, in words: "Turn left onto NH66", "At the roundabout, take exit 2". */
    static String describeStep(Step step) {
        String road = describeRoad(step);
        String onto = road.isEmpty() ? "" : " onto " + road;
        String side = step.modifier.isEmpty() ? "" : describeDirection(step.modifier);
        switch (step.type) {
            case "depart":
                return road.isEmpty() ? "Start driving" : "Start on " + road;
            case "arrive":
                return "Arrive at the destination";
            case "roundabout":
            case "rotary":
                return step.exit > 0 ? "At the roundabout, take exit " + step.exit + onto : "Enter the roundabout";
            case "exit roundabout":
            case "exit rotary":
                return "Leave the roundabout" + onto;
            case "fork":
                return "Keep " + describeSide(step.modifier) + " at the fork" + onto;
            case "merge":
                return "Merge" + onto;
            case "on ramp":
                return "Take the ramp" + onto;
            case "off ramp":
                return "Take the exit" + (step.modifier.isEmpty() ? "" : " on the " + describeSide(step.modifier))
                        + onto;
            case "end of road":
                return "At the end of the road, " + side.toLowerCase(java.util.Locale.ROOT) + onto;
            case "new name":
            case "continue":
                return "Continue" + onto;
            default:   // "turn" and anything newer
                return (side.isEmpty() ? "Continue" : side) + onto;
        }
    }

    /** "NH66", "Nairkere Road", or "Nairkere Road (SH1)". */
    private static String describeRoad(Step step) {
        String name = cleanRoadNames(step.name);
        String ref = cleanRoadNames(step.ref);
        if (name.isEmpty()) return ref;
        if (ref.isEmpty() || name.equals(ref)) return name;
        return name + " (" + ref + ")";
    }

    /**
     * Road names as the router sends them, made readable: each name's first line, and in English only when it
     * starts in English (OpenStreetMap often adds the Kannada name after it), and each road once: "Sirsi - Kumta
     * Highway, Sirsi - Kumta Highway ಸಿರ್ಸಿ…" becomes "Sirsi - Kumta Highway". A name only in Kannada stays.
     */
    static String cleanRoadNames(String names) {
        java.util.LinkedHashSet<String> kept = new java.util.LinkedHashSet<>();
        for (String part : names.split("[,;]")) {
            String first = cutAtOtherScript(part.trim().split("\n", 2)[0]).trim();
            if (!first.isEmpty()) kept.add(first);
        }
        return String.join(", ", kept);
    }

    /** The text up to where a script other than Latin begins, when it begins in Latin; otherwise all of it. */
    private static String cutAtOtherScript(String text) {
        boolean latinSeen = false;
        for (int i = 0; i < text.length(); ) {
            int letter = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(letter);
            if (script == Character.UnicodeScript.LATIN) {
                latinSeen = true;
            } else if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) {
                return latinSeen ? text.substring(0, i) : text;
            }
            i += Character.charCount(letter);
        }
        return text;
    }

    private static String describeDirection(String modifier) {
        switch (modifier) {
            case "left": return "Turn left";
            case "right": return "Turn right";
            case "slight left": return "Turn slightly left";
            case "slight right": return "Turn slightly right";
            case "sharp left": return "Turn sharp left";
            case "sharp right": return "Turn sharp right";
            case "uturn": return "Make a U-turn";
            default: return "Continue straight";
        }
    }

    private static String describeSide(String modifier) {
        return modifier.contains("left") ? "left" : modifier.contains("right") ? "right" : "straight";
    }
}
