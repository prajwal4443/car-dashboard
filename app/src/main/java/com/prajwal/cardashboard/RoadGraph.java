package com.prajwal.cardashboard;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The roads of the downloaded map as a graph for offline routing (OfflineRouter), built from the most detailed
 * vector tiles (z14 "transportation" lines, names from "transportation_name") and kept as a file next to the maps.
 *
 * Nodes are the lines' points in whole-world units at z14 (4096 per tile, about 0.6 m), so a road crossing a tile
 * edge meets itself: neighbouring tiles share their overlap. A road's end that stops on the middle of another road
 * (whose point there the tile left out) is joined to it when within SNAP_UNITS and on the same level. A line cut
 * at a tile's buffer edge ends on the same road from the neighbouring tile (a long straight stretch has no point of
 * its own in the overlap): it is joined to that road of the same kind within CUT_SNAP_UNITS. Lines that only cross
 * (a flyover) are not joined. Kept as plain arrays: the head unit has little memory. Plain Java, so it
 * runs in unit tests.
 */
final class RoadGraph {
    static final int ZOOM = 14;
    /** Road kinds (OpenMapTiles "class"); an edge's kind is its index here. Speeds in km/h for the route time. */
    static final String[] KINDS = {"motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track"};
    static final double[] KIND_KMH = {90, 70, 55, 45, 35, 25, 15, 10};
    private static final int UNITS_PER_TILE = 4096;
    private static final double WORLD_UNITS = (double) UNITS_PER_TILE * (1 << ZOOM);
    private static final int FILE_MAGIC = 0x524F4144;   // "ROAD"
    private static final int FILE_VERSION = 2;
    /** Roads joined into a group at least this big (or NETWORK_SHARE of the biggest) are the road network. */
    private static final int NETWORK_MIN_NODES = 1000;
    private static final double NETWORK_SHARE = 0.05;
    /** A dangling road end is joined to another road this close (units, about 3.5 m). */
    private static final double SNAP_UNITS = 6;
    /** A line cut at a tile edge lies on its neighbour's copy: this close (units, about a metre). */
    private static final double CUT_SNAP_UNITS = 2;
    /** A name line this close to an edge's middle names it (units). */
    private static final double NAME_UNITS = 6;
    private static final int GRID_UNITS = 64;

    final double[] nodeLat;
    final double[] nodeLon;
    /** Roads meeting at each node: 2 on a plain bend, more at a junction (capped at 127). */
    final byte[] nodeDegree;
    /**
     * 1 for nodes on the road network, 0 for small pieces the tiles left unjoined (a lane, a stub): a trip starts
     * and ends on the network, or it could not get anywhere.
     */
    final byte[] nodeOnNetwork;
    /** Edges leaving node i: firstEdge[i] up to firstEdge[i + 1]. */
    final int[] firstEdge;
    final int[] edgeTo;
    final float[] edgeMeters;
    final byte[] edgeKind;
    /** Index into names for the road's name and its number (-1 for none). */
    final int[] edgeName;
    final int[] edgeRef;
    final String[] names;

    private RoadGraph(double[] nodeLat, double[] nodeLon, byte[] nodeDegree, byte[] nodeOnNetwork, int[] firstEdge,
                      int[] edgeTo, float[] edgeMeters, byte[] edgeKind, int[] edgeName, int[] edgeRef, String[] names) {
        this.nodeLat = nodeLat;
        this.nodeLon = nodeLon;
        this.nodeDegree = nodeDegree;
        this.nodeOnNetwork = nodeOnNetwork;
        this.firstEdge = firstEdge;
        this.edgeTo = edgeTo;
        this.edgeMeters = edgeMeters;
        this.edgeKind = edgeKind;
        this.edgeName = edgeName;
        this.edgeRef = edgeRef;
        this.names = names;
    }

    int countNodes() {
        return nodeLat.length;
    }

    int countEdges() {
        return edgeTo.length;
    }

    /** Collects tiles, then builds the graph from them all. */
    static final class Builder {
        private final Map<Long, Integer> nodeIds = new HashMap<>();
        private long[] nodeX = new long[1024];
        private long[] nodeY = new long[1024];
        private int nodeCount;
        /** Roads (undirected), five ints each: from, to, kind, oneway (1 along, -1 against, 0 both), level. */
        private int[] roads = new int[1024 * 5];
        private int roadCount;
        private final Map<Long, Boolean> roadSeen = new HashMap<>();
        /** Road line ends: node, level, kind, and 1 when cut at the tile's buffer edge (0 for a real end). */
        private final List<int[]> lineEnds = new ArrayList<>();
        /** Name lines per grid cell: segments {x0, y0, x1, y1, nameIndex, refIndex}. */
        private final Map<Long, List<long[]>> nameCells = new HashMap<>();
        private final List<String> names = new ArrayList<>();
        private final Map<String, Integer> nameIndex = new HashMap<>();

        /** Adds one z14 tile (x, y in the usual XYZ scheme): its drivable roads and its road names. */
        Builder addTile(int x, int y, byte[] tile) {
            for (VectorTileReader.TileLine line : VectorTileReader.readLines(tile, "transportation")) {
                int kind = Arrays.asList(KINDS).indexOf(line.lookupText("class"));
                if (kind < 0) continue;   // paths, rail, ferries: not for driving
                int oneway = (int) Math.signum(line.lookupNumber("oneway"));
                int level = describeLevel(line.lookupText("brunnel"));
                for (int[] part : line.parts) {
                    int previous = -1;
                    for (int i = 0; i < part.length; i += 2) {
                        int node = findNode(toWorld(x, part[i], line.extent), toWorld(y, part[i + 1], line.extent));
                        if (previous >= 0 && previous != node) addRoad(previous, node, kind, oneway, level);
                        previous = node;
                    }
                    noteEnd(x, y, part[0], part[1], line.extent, level, kind);
                    noteEnd(x, y, part[part.length - 2], part[part.length - 1], line.extent, level, kind);
                }
            }
            for (VectorTileReader.TileLine line : VectorTileReader.readLines(tile, "transportation_name")) {
                int name = rememberName(line.lookupText("name"));
                int ref = rememberName(line.lookupText("ref"));
                if (name < 0 && ref < 0) continue;
                for (int[] part : line.parts) {
                    for (int i = 0; i + 3 < part.length; i += 2) {
                        long[] segment = {toWorld(x, part[i], line.extent), toWorld(y, part[i + 1], line.extent),
                                toWorld(x, part[i + 2], line.extent), toWorld(y, part[i + 3], line.extent), name, ref};
                        addToCells(nameCells, segment);
                    }
                }
            }
            return this;
        }

        private static long toWorld(int tile, int units, int extent) {
            return (long) tile * UNITS_PER_TILE + Math.round((double) units * UNITS_PER_TILE / extent);
        }

        private static int describeLevel(String brunnel) {
            if (brunnel == null) return 0;
            switch (brunnel) {
                case "bridge": return 1;
                case "tunnel": return 2;
                default: return 3;   // ford
            }
        }

        private void noteEnd(int x, int y, int px, int py, int extent, int level, int kind) {
            int cut = px <= 0 || py <= 0 || px >= extent || py >= extent ? 1 : 0;
            lineEnds.add(new int[] {findNode(toWorld(x, px, extent), toWorld(y, py, extent)), level, kind, cut});
        }

        private int findNode(long worldX, long worldY) {
            long key = (worldX << 32) | (worldY & 0xFFFFFFFFL);
            Integer known = nodeIds.get(key);
            if (known != null) return known;
            if (nodeCount == nodeX.length) {
                nodeX = Arrays.copyOf(nodeX, nodeCount * 2);
                nodeY = Arrays.copyOf(nodeY, nodeCount * 2);
            }
            nodeX[nodeCount] = worldX;
            nodeY[nodeCount] = worldY;
            nodeIds.put(key, nodeCount);
            return nodeCount++;
        }

        private void addRoad(int from, int to, int kind, int oneway, int level) {
            long pair = from < to ? ((long) from << 32) | to : ((long) to << 32) | from;
            if (roadSeen.put(pair, Boolean.TRUE) != null) return;   // the same road, from a neighbouring tile
            if ((roadCount + 1) * 5 > roads.length) roads = Arrays.copyOf(roads, roads.length * 2);
            int at = roadCount * 5;
            roads[at] = from;
            roads[at + 1] = to;
            roads[at + 2] = kind;
            roads[at + 3] = oneway;
            roads[at + 4] = level;
            roadCount++;
        }

        private int rememberName(String text) {
            if (text == null) return -1;
            String clean = DrivingRoute.cleanRoadNames(text);
            if (clean.isEmpty()) return -1;
            Integer known = nameIndex.get(clean);
            if (known != null) return known;
            names.add(clean);
            nameIndex.put(clean, names.size() - 1);
            return names.size() - 1;
        }

        RoadGraph build() {
            joinDanglingEnds();
            int[] degree = new int[nodeCount];
            int directed = 0;
            for (int r = 0; r < roadCount; r++) {
                int at = r * 5;
                if (roads[at] < 0) continue;   // replaced by its split halves
                degree[roads[at]]++;
                degree[roads[at + 1]]++;
                directed += roads[at + 3] == 0 ? 2 : 1;
            }
            int[] firstEdge = new int[nodeCount + 1];
            for (int r = 0; r < roadCount; r++) {
                int at = r * 5;
                if (roads[at] < 0) continue;
                if (roads[at + 3] >= 0) firstEdge[roads[at] + 1]++;
                if (roads[at + 3] <= 0) firstEdge[roads[at + 1] + 1]++;
            }
            for (int i = 0; i < nodeCount; i++) firstEdge[i + 1] += firstEdge[i];
            int[] fill = Arrays.copyOf(firstEdge, nodeCount);
            int[] edgeTo = new int[directed];
            float[] edgeMeters = new float[directed];
            byte[] edgeKind = new byte[directed];
            int[] edgeName = new int[directed];
            int[] edgeRef = new int[directed];
            double[] nodeLat = new double[nodeCount];
            double[] nodeLon = new double[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                nodeLon[i] = nodeX[i] / WORLD_UNITS * 360 - 180;
                nodeLat[i] = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * nodeY[i] / WORLD_UNITS))));
            }
            for (int r = 0; r < roadCount; r++) {
                int at = r * 5;
                int from = roads[at];
                if (from < 0) continue;
                int to = roads[at + 1];
                float meters = (float) CameraAlerts.measureDistanceMeters(nodeLat[from], nodeLon[from], nodeLat[to],
                        nodeLon[to]);
                int[] naming = findName((nodeX[from] + nodeX[to]) / 2.0, (nodeY[from] + nodeY[to]) / 2.0);
                for (int direction = 0; direction < 2; direction++) {
                    if (direction == 0 && roads[at + 3] < 0) continue;
                    if (direction == 1 && roads[at + 3] > 0) continue;
                    int start = direction == 0 ? from : to;
                    int edge = fill[start]++;
                    edgeTo[edge] = direction == 0 ? to : from;
                    edgeMeters[edge] = meters;
                    edgeKind[edge] = (byte) roads[at + 2];
                    edgeName[edge] = naming[0];
                    edgeRef[edge] = naming[1];
                }
            }
            byte[] nodeDegree = new byte[nodeCount];
            for (int i = 0; i < nodeCount; i++) nodeDegree[i] = (byte) Math.min(127, degree[i]);
            return new RoadGraph(nodeLat, nodeLon, nodeDegree, markNetwork(), firstEdge, edgeTo, edgeMeters, edgeKind,
                    edgeName, edgeRef, names.toArray(new String[0]));
        }

        /** Which nodes are on a big group of joined roads (union-find over the roads, either way). */
        private byte[] markNetwork() {
            int[] parent = new int[nodeCount];
            for (int i = 0; i < nodeCount; i++) parent[i] = i;
            for (int r = 0; r < roadCount; r++) {
                int at = r * 5;
                if (roads[at] < 0) continue;
                int a = findRoot(parent, roads[at]);
                int b = findRoot(parent, roads[at + 1]);
                if (a != b) parent[a] = b;
            }
            int[] size = new int[nodeCount];
            int biggest = 0;
            for (int i = 0; i < nodeCount; i++) biggest = Math.max(biggest, ++size[findRoot(parent, i)]);
            // NETWORK_MIN_NODES, or less in a small download: NETWORK_SHARE of its biggest group.
            int enough = (int) Math.min(NETWORK_MIN_NODES, Math.max(2, Math.ceil(biggest * NETWORK_SHARE)));
            byte[] onNetwork = new byte[nodeCount];
            for (int i = 0; i < nodeCount; i++) onNetwork[i] = (byte) (size[findRoot(parent, i)] >= enough ? 1 : 0);
            return onNetwork;
        }

        private static int findRoot(int[] parent, int node) {
            int root = node;
            while (parent[root] != root) root = parent[root];
            while (parent[node] != root) {
                int next = parent[node];
                parent[node] = root;
                node = next;
            }
            return root;
        }

        /**
         * A road that ends (only one road at that point) on the middle of another road is joined to it: that road is
         * split at the nearest point into two halves meeting the end. A real end joins a road on the same level within
         * SNAP_UNITS; a line cut at a tile edge joins its own continuation (same kind and level) within CUT_SNAP_UNITS.
         */
        private void joinDanglingEnds() {
            int[] degree = new int[nodeCount];
            Map<Long, List<long[]>> roadCells = new HashMap<>();
            for (int r = 0; r < roadCount; r++) {
                int at = r * 5;
                degree[roads[at]]++;
                degree[roads[at + 1]]++;
                addToCells(roadCells, new long[] {nodeX[roads[at]], nodeY[roads[at]], nodeX[roads[at + 1]],
                        nodeY[roads[at + 1]], r, 0});
            }
            for (int[] end : lineEnds) {
                int node = end[0];
                if (degree[node] != 1) continue;
                boolean cut = end[3] == 1;
                long cell = cellKey(Math.floorDiv(nodeX[node], GRID_UNITS), Math.floorDiv(nodeY[node], GRID_UNITS));
                int best = -1;
                double bestUnits = cut ? CUT_SNAP_UNITS : SNAP_UNITS;
                for (long[] segment : collectNear(roadCells, cell)) {
                    int r = (int) segment[4];
                    int at = r * 5;
                    if (roads[at] < 0 || roads[at] == node || roads[at + 1] == node || roads[at + 4] != end[1]) {
                        continue;
                    }
                    if (cut && roads[at + 2] != end[2]) continue;   // a cut line continues as the same kind of road
                    double units = measureToSegment(nodeX[node], nodeY[node], segment);
                    if (units < bestUnits) {
                        bestUnits = units;
                        best = r;
                    }
                }
                if (best < 0) continue;
                int at = best * 5;
                int from = roads[at];
                int to = roads[at + 1];
                int kind = roads[at + 2];
                int oneway = roads[at + 3];
                int level = roads[at + 4];
                roads[at] = -1;   // the whole road is replaced by its two halves, which later ends can join too
                int halvesFrom = roadCount;
                addRoad(from, node, kind, oneway, level);
                addRoad(node, to, kind, oneway, level);
                for (int half = halvesFrom; half < roadCount; half++) {
                    int h = half * 5;
                    addToCells(roadCells, new long[] {nodeX[roads[h]], nodeY[roads[h]], nodeX[roads[h + 1]],
                            nodeY[roads[h + 1]], half, 0});
                }
                degree[node] += 2;
            }
        }

        /** The name and number {nameIndex, refIndex} of the name line nearest a point, within NAME_UNITS. */
        private int[] findName(double worldX, double worldY) {
            long cell = cellKey(Math.floorDiv((long) worldX, GRID_UNITS), Math.floorDiv((long) worldY, GRID_UNITS));
            int[] found = {-1, -1};
            double bestUnits = NAME_UNITS;
            for (long[] segment : collectNear(nameCells, cell)) {
                double units = measureToSegment(worldX, worldY, segment);
                if (units < bestUnits) {
                    bestUnits = units;
                    found[0] = (int) segment[4];
                    found[1] = (int) segment[5];
                }
            }
            return found;
        }
    }

    private static long cellKey(long cellX, long cellY) {
        return (cellX << 32) | (cellY & 0xFFFFFFFFL);
    }

    private static void addToCells(Map<Long, List<long[]>> cells, long[] segment) {
        long minX = Math.floorDiv(Math.min(segment[0], segment[2]), GRID_UNITS);
        long maxX = Math.floorDiv(Math.max(segment[0], segment[2]), GRID_UNITS);
        long minY = Math.floorDiv(Math.min(segment[1], segment[3]), GRID_UNITS);
        long maxY = Math.floorDiv(Math.max(segment[1], segment[3]), GRID_UNITS);
        for (long cx = minX; cx <= maxX; cx++) {
            for (long cy = minY; cy <= maxY; cy++) {
                cells.computeIfAbsent(cellKey(cx, cy), unused -> new ArrayList<>()).add(segment);
            }
        }
    }

    /** The segments in a cell and its eight neighbours. */
    private static List<long[]> collectNear(Map<Long, List<long[]>> cells, long cell) {
        long cellX = cell >> 32;
        long cellY = (int) cell;
        List<long[]> near = new ArrayList<>();
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                List<long[]> found = cells.get(cellKey(cellX + dx, cellY + dy));
                if (found != null) near.addAll(found);
            }
        }
        return near;
    }

    private static double measureToSegment(double px, double py, long[] segment) {
        double ax = segment[0];
        double ay = segment[1];
        double dx = segment[2] - ax;
        double dy = segment[3] - ay;
        double lengthSquared = dx * dx + dy * dy;
        double share = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        double ox = ax + dx * share - px;
        double oy = ay + dy * share - py;
        return Math.sqrt(ox * ox + oy * oy);
    }

    /** Writes the graph, replacing the old file only once the new one is complete. */
    void save(File file) throws IOException {
        File partial = new File(file.getPath() + ".partial");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeInt(names.length);
            for (String name : names) out.writeUTF(name);
            out.writeInt(nodeLat.length);
            for (int i = 0; i < nodeLat.length; i++) {
                out.writeDouble(nodeLat[i]);
                out.writeDouble(nodeLon[i]);
                out.writeByte(nodeDegree[i]);
                out.writeByte(nodeOnNetwork[i]);
            }
            for (int first : firstEdge) out.writeInt(first);
            out.writeInt(edgeTo.length);
            for (int i = 0; i < edgeTo.length; i++) {
                out.writeInt(edgeTo[i]);
                out.writeFloat(edgeMeters[i]);
                out.writeByte(edgeKind[i]);
                out.writeInt(edgeName[i]);
                out.writeInt(edgeRef[i]);
            }
        }
        if (!partial.renameTo(file)) throw new IOException("could not replace " + file);
    }

    /** The graph save wrote; null when there is none (no download yet) or it is from another version. */
    static RoadGraph load(File file) throws IOException {
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) return null;
            String[] names = new String[in.readInt()];
            for (int i = 0; i < names.length; i++) names[i] = in.readUTF();
            int nodes = in.readInt();
            double[] nodeLat = new double[nodes];
            double[] nodeLon = new double[nodes];
            byte[] nodeDegree = new byte[nodes];
            byte[] nodeOnNetwork = new byte[nodes];
            for (int i = 0; i < nodes; i++) {
                nodeLat[i] = in.readDouble();
                nodeLon[i] = in.readDouble();
                nodeDegree[i] = in.readByte();
                nodeOnNetwork[i] = in.readByte();
            }
            int[] firstEdge = new int[nodes + 1];
            for (int i = 0; i <= nodes; i++) firstEdge[i] = in.readInt();
            int edges = in.readInt();
            int[] edgeTo = new int[edges];
            float[] edgeMeters = new float[edges];
            byte[] edgeKind = new byte[edges];
            int[] edgeName = new int[edges];
            int[] edgeRef = new int[edges];
            for (int i = 0; i < edges; i++) {
                edgeTo[i] = in.readInt();
                edgeMeters[i] = in.readFloat();
                edgeKind[i] = in.readByte();
                edgeName[i] = in.readInt();
                edgeRef[i] = in.readInt();
            }
            return new RoadGraph(nodeLat, nodeLon, nodeDegree, nodeOnNetwork, firstEdge, edgeTo, edgeMeters, edgeKind,
                    edgeName, edgeRef, names);
        }
    }
}
