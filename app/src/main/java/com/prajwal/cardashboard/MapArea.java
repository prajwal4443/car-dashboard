package com.prajwal.cardashboard;

/**
 * The square of map a "Download map area" covers, and how many tiles that is. Pure arithmetic (Web Mercator tiles),
 * so it runs in unit tests: the count decides which sizes stay inside OpenFreeMap's per-download limit.
 */
final class MapArea {
    /** OpenFreeMap: at most this many tiles per offline download. */
    static final int TILE_LIMIT = 3000;
    /** OpenFreeMap's vector tiles go to zoom 14; closer zooms are drawn from those. */
    static final int MIN_ZOOM = 0;
    static final int MAX_ZOOM = 14;
    private static final double KM_PER_DEGREE_LATITUDE = 111.32;

    final double north;
    final double south;
    final double east;
    final double west;

    private MapArea(double north, double south, double east, double west) {
        this.north = north;
        this.south = south;
        this.east = east;
        this.west = west;
    }

    /** The square {@code sizeKm} on a side, centred on a position. */
    static MapArea buildSquare(double latitude, double longitude, double sizeKm) {
        double halfLat = sizeKm / 2 / KM_PER_DEGREE_LATITUDE;
        double halfLon = sizeKm / 2 / (KM_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude)));
        return new MapArea(Math.min(85, latitude + halfLat), Math.max(-85, latitude - halfLat),
                longitude + halfLon, longitude - halfLon);
    }

    /** Any box, as picked on the map ("Download map area"). */
    static MapArea fromBounds(double north, double south, double east, double west) {
        return new MapArea(Math.min(85, north), Math.max(-85, south), east, west);
    }

    /** Width and height in km, measured through the middle, for the picker's size line. */
    double measureWidthKm() {
        double middle = (north + south) / 2;
        return (east - west) * KM_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(middle));
    }

    double measureHeightKm() {
        return (north - south) * KM_PER_DEGREE_LATITUDE;
    }

    /** Tiles from MIN_ZOOM to MAX_ZOOM covering the square: the count OpenFreeMap's limit applies to. */
    long countTiles() {
        long total = 0;
        for (int zoom = MIN_ZOOM; zoom <= MAX_ZOOM; zoom++) {
            long columns = tileX(east, zoom) - tileX(west, zoom) + 1;
            long rows = tileY(south, zoom) - tileY(north, zoom) + 1;
            total += columns * rows;
        }
        return total;
    }

    private static long tileX(double longitude, int zoom) {
        return (long) Math.floor((longitude + 180) / 360 * (1L << zoom));
    }

    private static long tileY(double latitude, int zoom) {
        double radians = Math.toRadians(latitude);
        double y = (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2;
        return (long) Math.floor(y * (1L << zoom));
    }
}
