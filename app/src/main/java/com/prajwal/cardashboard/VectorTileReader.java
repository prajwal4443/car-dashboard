package com.prajwal.cardashboard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads the features of a Mapbox vector tile (the protobuf format OpenFreeMap serves and MapLibre stores in its
 * offline database): each wanted layer's features with their tags and first point. Enough for place search; it does
 * not decode whole lines or polygons. Plain Java, so it runs in unit tests.
 */
final class VectorTileReader {
    /** A tile's own coordinate range when its layer does not say (the spec's default). */
    private static final int DEFAULT_EXTENT = 4096;
    private static final int WIRE_VARINT = 0;
    private static final int WIRE_64BIT = 1;
    private static final int WIRE_BYTES = 2;
    private static final int WIRE_32BIT = 5;

    /** One feature: its layer, tags, and first point as a share of the tile (0..1 from the left and from the top). */
    static final class TileFeature {
        final String layer;
        final Map<String, Object> tags;
        final double shareX;
        final double shareY;

        TileFeature(String layer, Map<String, Object> tags, double shareX, double shareY) {
            this.layer = layer;
            this.tags = tags;
            this.shareX = shareX;
            this.shareY = shareY;
        }

        String lookupText(String key) {
            Object tag = tags.get(key);
            return tag instanceof String ? (String) tag : null;
        }
    }

    /** One line feature: its tags and its parts, each part as tile coordinates {x0, y0, x1, y1, …}. */
    static final class TileLine {
        final Map<String, Object> tags;
        final List<int[]> parts;
        final int extent;

        TileLine(Map<String, Object> tags, List<int[]> parts, int extent) {
            this.tags = tags;
            this.parts = parts;
            this.extent = extent;
        }

        String lookupText(String key) {
            Object tag = tags.get(key);
            return tag instanceof String ? (String) tag : null;
        }

        /** A number tag (oneway is 1 or -1), or 0 without one. */
        long lookupNumber(String key) {
            Object tag = tags.get(key);
            return tag instanceof Long ? (Long) tag : 0;
        }
    }

    private VectorTileReader() {
    }

    /** The line features of one layer ("transportation", say), every part of each with all its points. */
    static List<TileLine> readLines(byte[] tile, String wantedLayer) {
        List<TileLine> lines = new ArrayList<>();
        ProtoCursor cursor = new ProtoCursor(tile, 0, tile.length);
        while (cursor.hasMore()) {
            int key = (int) cursor.readVarint();
            if (key >>> 3 == 3 && (key & 7) == WIRE_BYTES) {
                readLineLayer(cursor.readBytesCursor(), wantedLayer, lines);
            } else {
                cursor.skip(key & 7);
            }
        }
        return lines;
    }

    private static void readLineLayer(ProtoCursor layer, String wantedLayer, List<TileLine> lines) {
        String name = null;
        int extent = DEFAULT_EXTENT;
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<ProtoCursor> rawFeatures = new ArrayList<>();
        while (layer.hasMore()) {
            int key = (int) layer.readVarint();
            int field = key >>> 3;
            int wire = key & 7;
            if (field == 1 && wire == WIRE_BYTES) {
                name = layer.readText();
            } else if (field == 2 && wire == WIRE_BYTES) {
                rawFeatures.add(layer.readBytesCursor());
            } else if (field == 3 && wire == WIRE_BYTES) {
                keys.add(layer.readText());
            } else if (field == 4 && wire == WIRE_BYTES) {
                values.add(readValue(layer.readBytesCursor()));
            } else if (field == 5 && wire == WIRE_VARINT) {
                extent = (int) layer.readVarint();
            } else {
                layer.skip(wire);
            }
        }
        if (!wantedLayer.equals(name)) return;
        for (ProtoCursor raw : rawFeatures) {
            Map<String, Object> tags = new HashMap<>();
            List<int[]> parts = null;
            int type = 0;
            while (raw.hasMore()) {
                int key = (int) raw.readVarint();
                int field = key >>> 3;
                int wire = key & 7;
                if (field == 2 && wire == WIRE_BYTES) {
                    ProtoCursor packed = raw.readBytesCursor();
                    while (packed.hasMore()) {
                        int keyIndex = (int) packed.readVarint();
                        int valueIndex = (int) packed.readVarint();
                        if (keyIndex < keys.size() && valueIndex < values.size()) {
                            tags.put(keys.get(keyIndex), values.get(valueIndex));
                        }
                    }
                } else if (field == 3 && wire == WIRE_VARINT) {
                    type = (int) raw.readVarint();
                } else if (field == 4 && wire == WIRE_BYTES) {
                    parts = readLineParts(raw.readBytesCursor());
                } else {
                    raw.skip(wire);
                }
            }
            if (type == 2 && parts != null && !parts.isEmpty()) lines.add(new TileLine(tags, parts, extent));
        }
    }

    /** A line geometry's parts: each MoveTo starts one, its LineTo points follow (zigzag deltas throughout). */
    private static List<int[]> readLineParts(ProtoCursor geometry) {
        List<int[]> parts = new ArrayList<>();
        int[] current = null;
        int used = 0;
        long x = 0;
        long y = 0;
        while (geometry.hasMore()) {
            long command = geometry.readVarint();
            int id = (int) (command & 7);
            int count = (int) (command >>> 3);
            if (id == 7) continue;   // ClosePath: not in lines
            for (int i = 0; i < count && geometry.hasMore(); i++) {
                x += decodeZigzag(geometry.readVarint());
                y += decodeZigzag(geometry.readVarint());
                if (id == 1) {
                    if (current != null && used >= 4) parts.add(java.util.Arrays.copyOf(current, used));
                    current = new int[16];
                    used = 0;
                }
                if (current == null) continue;
                if (used + 2 > current.length) current = java.util.Arrays.copyOf(current, current.length * 2);
                current[used++] = (int) x;
                current[used++] = (int) y;
            }
        }
        if (current != null && used >= 4) parts.add(java.util.Arrays.copyOf(current, used));
        return parts;
    }

    /**
     * The tile bytes as MapLibre's offline database holds them: zlib-packed when its {@code compressed} column says
     * so, and possibly gzipped as the server sent them as well.
     */
    static byte[] unpackStoredTile(byte[] stored, boolean compressed) throws IOException {
        byte[] tile = compressed ? readAll(new InflaterInputStream(new ByteArrayInputStream(stored))) : stored;
        boolean gzipped = tile.length > 2 && (tile[0] & 0xFF) == 0x1F && (tile[1] & 0xFF) == 0x8B;
        return gzipped ? readAll(new GZIPInputStream(new ByteArrayInputStream(tile))) : tile;
    }

    /** The features of the layers named in {@code wantedLayers}; a feature without a point is skipped. */
    static List<TileFeature> readFeatures(byte[] tile, Set<String> wantedLayers) {
        List<TileFeature> features = new ArrayList<>();
        ProtoCursor cursor = new ProtoCursor(tile, 0, tile.length);
        while (cursor.hasMore()) {
            int key = (int) cursor.readVarint();
            if (key >>> 3 == 3 && (key & 7) == WIRE_BYTES) {
                readLayer(cursor.readBytesCursor(), wantedLayers, features);
            } else {
                cursor.skip(key & 7);
            }
        }
        return features;
    }

    private static void readLayer(ProtoCursor layer, Set<String> wantedLayers, List<TileFeature> features) {
        String name = null;
        int extent = DEFAULT_EXTENT;
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<ProtoCursor> rawFeatures = new ArrayList<>();
        while (layer.hasMore()) {
            int key = (int) layer.readVarint();
            int field = key >>> 3;
            int wire = key & 7;
            if (field == 1 && wire == WIRE_BYTES) {
                name = layer.readText();
            } else if (field == 2 && wire == WIRE_BYTES) {
                rawFeatures.add(layer.readBytesCursor());
            } else if (field == 3 && wire == WIRE_BYTES) {
                keys.add(layer.readText());
            } else if (field == 4 && wire == WIRE_BYTES) {
                values.add(readValue(layer.readBytesCursor()));
            } else if (field == 5 && wire == WIRE_VARINT) {
                extent = (int) layer.readVarint();
            } else {
                layer.skip(wire);
            }
        }
        if (name == null || !wantedLayers.contains(name)) return;
        for (ProtoCursor raw : rawFeatures) {
            TileFeature feature = readFeature(raw, name, extent, keys, values);
            if (feature != null) features.add(feature);
        }
    }

    private static TileFeature readFeature(ProtoCursor feature, String layer, int extent, List<String> keys,
                                           List<Object> values) {
        Map<String, Object> tags = new HashMap<>();
        double[] firstPoint = null;
        while (feature.hasMore()) {
            int key = (int) feature.readVarint();
            int field = key >>> 3;
            int wire = key & 7;
            if (field == 2 && wire == WIRE_BYTES) {
                ProtoCursor packed = feature.readBytesCursor();
                while (packed.hasMore()) {
                    int keyIndex = (int) packed.readVarint();
                    int valueIndex = (int) packed.readVarint();
                    if (keyIndex < keys.size() && valueIndex < values.size()) {
                        tags.put(keys.get(keyIndex), values.get(valueIndex));
                    }
                }
            } else if (field == 4 && wire == WIRE_BYTES) {
                firstPoint = readFirstPoint(feature.readBytesCursor());
            } else {
                feature.skip(wire);
            }
        }
        if (firstPoint == null) return null;
        return new TileFeature(layer, tags, firstPoint[0] / extent, firstPoint[1] / extent);
    }

    /** The geometry's first MoveTo point, in tile units (its commands are zigzag deltas from 0,0). */
    private static double[] readFirstPoint(ProtoCursor geometry) {
        if (!geometry.hasMore()) return null;
        long command = geometry.readVarint();
        if ((command & 7) != 1 || command >>> 3 < 1 || !geometry.hasMore()) return null;   // 1: MoveTo
        long x = decodeZigzag(geometry.readVarint());
        if (!geometry.hasMore()) return null;
        long y = decodeZigzag(geometry.readVarint());
        return new double[] {x, y};
    }

    private static Object readValue(ProtoCursor value) {
        Object found = null;
        while (value.hasMore()) {
            int key = (int) value.readVarint();
            int field = key >>> 3;
            int wire = key & 7;
            if (field == 1 && wire == WIRE_BYTES) {
                found = value.readText();
            } else if ((field == 4 || field == 5) && wire == WIRE_VARINT) {   // int, uint
                found = value.readVarint();
            } else if (field == 6 && wire == WIRE_VARINT) {                   // sint
                found = decodeZigzag(value.readVarint());
            } else if (field == 7 && wire == WIRE_VARINT) {                   // bool
                found = value.readVarint() != 0;
            } else {
                value.skip(wire);   // float and double: not needed for search
            }
        }
        return found;
    }

    private static long decodeZigzag(long encoded) {
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    private static byte[] readAll(InputStream packed) throws IOException {
        try (InputStream in = packed; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16384];
            for (int read; (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    /** Walks one protobuf message: varints, length-delimited parts, and skipping what is not needed. */
    private static final class ProtoCursor {
        private final byte[] bytes;
        private int position;
        private final int end;

        ProtoCursor(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.position = start;
            this.end = end;
        }

        boolean hasMore() {
            return position < end;
        }

        long readVarint() {
            long number = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= end) throw new IllegalArgumentException("vector tile ends inside a number");
                int next = bytes[position++] & 0xFF;
                number |= (long) (next & 0x7F) << shift;
                if (next < 0x80) return number;
            }
            throw new IllegalArgumentException("vector tile number too long");
        }

        ProtoCursor readBytesCursor() {
            int length = (int) readVarint();
            if (length < 0 || position + length > end) throw new IllegalArgumentException("vector tile part too long");
            ProtoCursor part = new ProtoCursor(bytes, position, position + length);
            position += length;
            return part;
        }

        String readText() {
            ProtoCursor part = readBytesCursor();
            return new String(bytes, part.position, part.end - part.position, StandardCharsets.UTF_8);
        }

        void skip(int wire) {
            if (wire == WIRE_VARINT) {
                readVarint();
            } else if (wire == WIRE_64BIT) {
                position += 8;
            } else if (wire == WIRE_BYTES) {
                readBytesCursor();
            } else if (wire == WIRE_32BIT) {
                position += 4;
            } else {
                throw new IllegalArgumentException("vector tile has unknown wire type " + wire);
            }
        }
    }
}
