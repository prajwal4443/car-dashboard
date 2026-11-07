package com.prajwal.cardashboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes without internet over the downloaded roads (RoadGraph): the fastest way by road kind speeds (A*), with its
 * turns worked out from the road shapes and names, as a DrivingRoute like the online router's. Plain Java, so it
 * runs in unit tests.
 */
final class OfflineRouter {
    /**
     * The car joins the nearest downloaded road up to this far (parked off a mapped road, or an indoor GPS fix), like
     * the online router; the place up to MAX_PLACE_METERS. Further: the downloads do not reach.
     */
    static final double MAX_START_METERS = 3000;
    static final double MAX_PLACE_METERS = 1000;
    /** A bend of more than this at a junction is a turn. */
    private static final double TURN_DEGREES = 30;
    private static final double SLIGHT_DEGREES = 60;
    private static final double SHARP_DEGREES = 135;
    private static final double TOP_SPEED_MPS = 90 / 3.6;
    /** Junctions closer than this are one manoeuvre (a roundabout, a staggered crossing): one turn for all. */
    private static final double SAME_TURN_METERS = 40;

    /** Where a point meets the road network: on the directed edge from node to edgeTo[edge], share of the way. */
    private static final class Snap {
        final int from;
        final int edge;
        final double share;
        final double lat;
        final double lon;

        Snap(int from, int edge, double share, double lat, double lon) {
            this.from = from;
            this.edge = edge;
            this.share = share;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private OfflineRouter() {
    }

    /** The fastest route by downloaded roads, or null when the start or the place is not on them or not connected. */
    static DrivingRoute findRoute(RoadGraph graph, double fromLat, double fromLon, double toLat, double toLon) {
        if (graph == null || graph.countEdges() == 0) return null;
        Snap start = snapToRoad(graph, fromLat, fromLon, MAX_START_METERS);
        Snap goal = snapToRoad(graph, toLat, toLon, MAX_PLACE_METERS);
        if (start == null || goal == null) return null;
        int[] path = searchPath(graph, start, goal);
        return path == null ? null : buildRoute(graph, start, goal, path);
    }

    /**
     * For the log when no route is found: how far the nearest downloaded road point is (any, and on the network),
     * and the box the downloaded roads cover.
     */
    static String describeCoverage(RoadGraph graph, double lat, double lon) {
        if (graph == null || graph.countNodes() == 0) return "no downloaded roads";
        double nearest = Double.MAX_VALUE;
        double nearestOnNetwork = Double.MAX_VALUE;
        double south = 90;
        double north = -90;
        double west = 180;
        double east = -180;
        for (int node = 0; node < graph.countNodes(); node++) {
            double meters = CameraAlerts.measureDistanceMeters(lat, lon, graph.nodeLat[node], graph.nodeLon[node]);
            nearest = Math.min(nearest, meters);
            if (graph.nodeOnNetwork[node] == 1) nearestOnNetwork = Math.min(nearestOnNetwork, meters);
            south = Math.min(south, graph.nodeLat[node]);
            north = Math.max(north, graph.nodeLat[node]);
            west = Math.min(west, graph.nodeLon[node]);
            east = Math.max(east, graph.nodeLon[node]);
        }
        return String.format(java.util.Locale.ROOT, "%.4f,%.4f: nearest road point %.0f m, on the network %.0f m;"
                + " roads cover %.4f..%.4f N, %.4f..%.4f E", lat, lon, nearest, nearestOnNetwork, south, north, west,
                east);
    }

    /** True when a trip could start here: a downloaded road within MAX_START_METERS. */
    static boolean canStartAt(RoadGraph graph, double lat, double lon) {
        return graph != null && snapToRoad(graph, lat, lon, MAX_START_METERS) != null;
    }

    /** True when a trip could end here: a downloaded road within MAX_PLACE_METERS. */
    static boolean canEndAt(RoadGraph graph, double lat, double lon) {
        return graph != null && snapToRoad(graph, lat, lon, MAX_PLACE_METERS) != null;
    }

    /** The nearest point on a road of the network (RoadGraph.nodeOnNetwork), within {@code maxMeters}. */
    private static Snap snapToRoad(RoadGraph graph, double lat, double lon, double maxMeters) {
        double metersPerLat = 111_320;
        double metersPerLon = 111_320 * Math.cos(Math.toRadians(lat));
        double best = maxMeters;
        Snap found = null;
        for (int node = 0; node < graph.countNodes(); node++) {
            if (graph.nodeOnNetwork[node] == 0) continue;   // a lane or stub the tiles left unjoined
            double ax = (graph.nodeLon[node] - lon) * metersPerLon;
            double ay = (graph.nodeLat[node] - lat) * metersPerLat;
            if (Math.abs(ax) > 5000 || Math.abs(ay) > 5000) continue;   // far: none of its edges can be near
            for (int edge = graph.firstEdge[node]; edge < graph.firstEdge[node + 1]; edge++) {
                int to = graph.edgeTo[edge];
                double bx = (graph.nodeLon[to] - lon) * metersPerLon;
                double by = (graph.nodeLat[to] - lat) * metersPerLat;
                double dx = bx - ax;
                double dy = by - ay;
                double lengthSquared = dx * dx + dy * dy;
                double share = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
                double ox = ax + dx * share;
                double oy = ay + dy * share;
                double meters = Math.sqrt(ox * ox + oy * oy);
                if (meters < best) {
                    best = meters;
                    found = new Snap(node, edge, share,
                            graph.nodeLat[node] + (graph.nodeLat[to] - graph.nodeLat[node]) * share,
                            graph.nodeLon[node] + (graph.nodeLon[to] - graph.nodeLon[node]) * share);
                }
            }
        }
        return found;
    }

    private static double measureSeconds(RoadGraph graph, int edge, double share) {
        return graph.edgeMeters[edge] * share / (RoadGraph.KIND_KMH[graph.edgeKind[edge]] / 3.6);
    }

    /** The edge from one node to another, or -1. */
    private static int findEdge(RoadGraph graph, int from, int to) {
        for (int edge = graph.firstEdge[from]; edge < graph.firstEdge[from + 1]; edge++) {
            if (graph.edgeTo[edge] == to) return edge;
        }
        return -1;
    }

    /**
     * A*: the nodes from the start's edge to the goal's edge, fastest; null when none connects. The path's first
     * node is reached from the start point, its last one leads on to the goal point.
     */
    private static int[] searchPath(RoadGraph graph, Snap start, Snap goal) {
        int startTo = graph.edgeTo[start.edge];
        int goalTo = graph.edgeTo[goal.edge];
        // Start and goal on the same edge, the goal ahead: no node in between.
        if (start.edge == goal.edge && goal.share >= start.share) return new int[0];
        double[] best = new double[graph.countNodes()];
        Arrays.fill(best, Double.MAX_VALUE);
        int[] cameFrom = new int[graph.countNodes()];
        Arrays.fill(cameFrom, -1);
        NodeQueue queue = new NodeQueue();
        // From the start point: ahead along its edge, and back the other way when the road allows it.
        best[startTo] = measureSeconds(graph, start.edge, 1 - start.share);
        queue.add(startTo, best[startTo] + estimateSeconds(graph, startTo, goal));
        int back = findEdge(graph, startTo, start.from);
        if (back >= 0 && measureSeconds(graph, back, start.share) < best[start.from]) {
            best[start.from] = measureSeconds(graph, back, start.share);
            queue.add(start.from, best[start.from] + estimateSeconds(graph, start.from, goal));
        }
        // Onto the goal point: from its edge's start node along it, or from its end node back when allowed.
        int goalBack = findEdge(graph, goalTo, goal.from);
        double bestTotal = Double.MAX_VALUE;
        int lastNode = -1;
        while (!queue.isEmpty()) {
            double estimate = queue.peekKey();
            int node = queue.poll();
            if (estimate >= bestTotal) break;
            double seconds = best[node];
            if (node == goal.from) {
                double total = seconds + measureSeconds(graph, goal.edge, goal.share);
                if (total < bestTotal) {
                    bestTotal = total;
                    lastNode = node;
                }
            }
            if (node == goalTo && goalBack >= 0) {
                double total = seconds + measureSeconds(graph, goalBack, 1 - goal.share);
                if (total < bestTotal) {
                    bestTotal = total;
                    lastNode = node;
                }
            }
            for (int edge = graph.firstEdge[node]; edge < graph.firstEdge[node + 1]; edge++) {
                int next = graph.edgeTo[edge];
                double through = seconds + measureSeconds(graph, edge, 1);
                if (through < best[next]) {
                    best[next] = through;
                    cameFrom[next] = node;
                    queue.add(next, through + estimateSeconds(graph, next, goal));
                }
            }
        }
        if (lastNode < 0) return null;
        List<Integer> nodes = new ArrayList<>();
        for (int node = lastNode; node >= 0; node = cameFrom[node]) {
            nodes.add(0, node);
            if (nodes.size() > graph.countNodes()) return null;   // a loop: should never happen
        }
        int[] path = new int[nodes.size()];
        for (int i = 0; i < path.length; i++) path[i] = nodes.get(i);
        return path;
    }

    private static double estimateSeconds(RoadGraph graph, int node, Snap goal) {
        return CameraAlerts.measureDistanceMeters(graph.nodeLat[node], graph.nodeLon[node], goal.lat, goal.lon)
                / TOP_SPEED_MPS;
    }

    /** The line, its time, its turns (at junctions where the road bends or changes name) and its main roads. */
    private static DrivingRoute buildRoute(RoadGraph graph, Snap start, Snap goal, int[] path) {
        int count = path.length + 2;
        double[] lats = new double[count];
        double[] lons = new double[count];
        lats[0] = start.lat;
        lons[0] = start.lon;
        for (int i = 0; i < path.length; i++) {
            lats[i + 1] = graph.nodeLat[path[i]];
            lons[i + 1] = graph.nodeLon[path[i]];
        }
        lats[count - 1] = goal.lat;
        lons[count - 1] = goal.lon;
        // The edge taken into each point (the first and last stretch use the start's and goal's edges).
        int[] edges = new int[count];
        edges[1] = start.edge;
        for (int i = 1; i < path.length; i++) edges[i + 1] = findEdge(graph, path[i - 1], path[i]);
        edges[count - 1] = path.length == 0 ? start.edge : goal.edge;
        if (path.length == 0) edges[1] = start.edge;

        double seconds = 0;
        Map<Integer, Double> metersByName = new HashMap<>();
        for (int i = 1; i < count; i++) {
            int edge = edges[i];
            if (edge < 0) continue;
            double meters = CameraAlerts.measureDistanceMeters(lats[i - 1], lons[i - 1], lats[i], lons[i]);
            seconds += meters / (RoadGraph.KIND_KMH[graph.edgeKind[edge]] / 3.6);
            int name = graph.edgeRef[edge] >= 0 ? graph.edgeRef[edge] : graph.edgeName[edge];
            if (name >= 0) metersByName.merge(name, meters, Double::sum);
        }

        double[] along = new double[count];
        for (int i = 1; i < count; i++) {
            along[i] = along[i - 1] + CameraAlerts.measureDistanceMeters(lats[i - 1], lons[i - 1], lats[i], lons[i]);
        }
        // Junctions where the road bends or changes name, close ones taken together as one manoeuvre.
        List<int[]> manoeuvres = new ArrayList<>();
        for (int i = 1; i < count - 1; i++) {
            if (edges[i] < 0 || edges[i + 1] < 0 || graph.nodeDegree[path[i - 1]] <= 2) continue;   // a bend
            double turn = measureTurn(lats[i - 1], lons[i - 1], lats[i], lons[i], lats[i + 1], lons[i + 1]);
            boolean renamed = !describeRoadKey(graph, edges[i]).equals(describeRoadKey(graph, edges[i + 1]));
            if (Math.abs(turn) < TURN_DEGREES && !renamed) continue;
            int[] last = manoeuvres.isEmpty() ? null : manoeuvres.get(manoeuvres.size() - 1);
            if (last != null && along[i] - along[last[1]] < SAME_TURN_METERS) {
                last[1] = i;
            } else {
                manoeuvres.add(new int[] {i, i});
            }
        }
        List<DrivingRoute.Step> steps = new ArrayList<>();
        steps.add(buildStep(graph, "depart", "", edges[Math.min(1, count - 1)], lats[0], lons[0]));
        for (int[] manoeuvre : manoeuvres) {
            int first = manoeuvre[0];
            int last = manoeuvre[1];
            double turn = measureTurn(lats[first - 1], lons[first - 1], lats[first], lons[first], lats[last + 1],
                    lons[last + 1]);
            boolean renamed = !describeRoadKey(graph, edges[first]).equals(describeRoadKey(graph, edges[last + 1]));
            if (Math.abs(turn) < TURN_DEGREES && !renamed) continue;   // in and out the same way: no turn after all
            String modifier = describeModifier(turn);
            String type = "straight".equals(modifier) ? "new name" : "turn";
            steps.add(buildStep(graph, type, modifier, edges[last + 1], lats[first], lons[first]));
        }
        steps.add(new DrivingRoute.Step("arrive", "", "", "", 0, lats[count - 1], lons[count - 1]));
        return new DrivingRoute(lats, lons, seconds, describeSummary(graph, metersByName), steps);
    }

    private static DrivingRoute.Step buildStep(RoadGraph graph, String type, String modifier, int edge, double lat,
                                               double lon) {
        String name = edge >= 0 && graph.edgeName[edge] >= 0 ? graph.names[graph.edgeName[edge]] : "";
        String ref = edge >= 0 && graph.edgeRef[edge] >= 0 ? graph.names[graph.edgeRef[edge]] : "";
        return new DrivingRoute.Step(type, modifier, name, ref, 0, lat, lon);
    }

    private static String describeRoadKey(RoadGraph graph, int edge) {
        return graph.edgeName[edge] + "/" + graph.edgeRef[edge];
    }

    /**
     * The bend coming from a to b and leaving toward c, in degrees, positive to the right. Over a manoeuvre c is past
     * its last junction, so the whole of it counts.
     */
    private static double measureTurn(double aLat, double aLon, double bLat, double bLon, double cLat, double cLon) {
        double in = CameraAlerts.measureBearing(aLat, aLon, bLat, bLon);
        double out = CameraAlerts.measureBearing(bLat, bLon, cLat, cLon);
        double turn = out - in;
        while (turn > 180) turn -= 360;
        while (turn < -180) turn += 360;
        return turn;
    }

    private static String describeModifier(double turn) {
        double size = Math.abs(turn);
        String side = turn > 0 ? "right" : "left";
        if (size < TURN_DEGREES) return "straight";
        if (size < SLIGHT_DEGREES) return "slight " + side;
        if (size < SHARP_DEGREES) return side;
        return "sharp " + side;
    }

    /** The two roads driven longest, in route order of importance: "NH66, Nairkere Road". */
    private static String describeSummary(RoadGraph graph, Map<Integer, Double> metersByName) {
        List<Map.Entry<Integer, Double>> longest = new ArrayList<>(metersByName.entrySet());
        longest.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < longest.size() && i < 2; i++) parts.add(graph.names[longest.get(i).getKey()]);
        return String.join(", ", parts);
    }

    /** A min-heap of nodes by key, on plain arrays (A* pushes a node again when it finds a faster way). */
    private static final class NodeQueue {
        private double[] keys = new double[1024];
        private int[] nodes = new int[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        double peekKey() {
            return keys[0];
        }

        void add(int node, double key) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                nodes = Arrays.copyOf(nodes, size * 2);
            }
            int at = size++;
            while (at > 0) {
                int parent = (at - 1) / 2;
                if (keys[parent] <= key) break;
                keys[at] = keys[parent];
                nodes[at] = nodes[parent];
                at = parent;
            }
            keys[at] = key;
            nodes[at] = node;
        }

        int poll() {
            int top = nodes[0];
            size--;
            double key = keys[size];
            int node = nodes[size];
            int at = 0;
            while (true) {
                int child = at * 2 + 1;
                if (child >= size) break;
                if (child + 1 < size && keys[child + 1] < keys[child]) child++;
                if (keys[child] >= key) break;
                keys[at] = keys[child];
                nodes[at] = nodes[child];
                at = child;
            }
            keys[at] = key;
            nodes[at] = node;
            return top;
        }
    }
}
