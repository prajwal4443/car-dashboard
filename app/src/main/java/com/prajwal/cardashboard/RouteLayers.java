package com.prajwal.cardashboard;

import android.graphics.PointF;
import android.graphics.RectF;
import android.location.Location;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * The trip on a map (TripGuide), drawn the same on the navigation screen and the dashboard's mini map: the route as
 * a blue line with a white edge, a dashed straight line to the place while there is no route (or from the car to the
 * road where the route starts, while parked off it), and the place's pin.
 */
final class RouteLayers {
    private static final String SOURCE = "trip";
    /** The other ways the router offered, grey under the chosen one, until driving starts. */
    private static final String OTHER_CASING_LAYER = "trip-other-casing";
    private static final String OTHER_LINE_LAYER = "trip-other-line";
    /** The road already driven, grey, while driving. */
    private static final String DRIVEN_CASING_LAYER = "trip-driven-casing";
    private static final String DRIVEN_LINE_LAYER = "trip-driven-line";
    private static final String CASING_LAYER = "trip-casing";
    private static final String LINE_LAYER = "trip-line";
    private static final String STRAIGHT_LAYER = "trip-straight";
    private static final String PIN_LAYER = "trip-pin";
    private static final String KIND = "kind";
    private static final String KIND_ROUTE = "route";
    private static final String KIND_STRAIGHT = "straight";
    private static final String KIND_PIN = "pin";
    private static final String KIND_OTHER = "other";
    private static final String KIND_DRIVEN = "driven";
    /** Which of TripGuide's routes an other-way line is. */
    private static final String ROUTE_INDEX = "route";
    /** The car further than this from where the route starts (parked off the road) gets a dotted line to it. */
    private static final double CONNECTOR_METERS = 20;
    /** A tap this close (pixels) to a grey line picks it. */
    private static final float TAP_SLOP_PX = 30;

    private RouteLayers() {
    }

    /**
     * Adds the (empty) trip layers. Call it as soon as the style is ready, before the location arrow is switched on,
     * so the arrow stays on top of the route.
     */
    static void addLayers(Style style, float lineWidth) {
        if (style.getSource(SOURCE) != null) return;
        style.addSource(new GeoJsonSource(SOURCE, FeatureCollection.fromFeatures(new ArrayList<>())));
        style.addLayer(new LineLayer(OTHER_CASING_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(lineWidth + 2),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_OTHER)));
        style.addLayer(new LineLayer(OTHER_LINE_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor("#8A94A6"),
                PropertyFactory.lineWidth(lineWidth),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_OTHER)));
        style.addLayer(new LineLayer(DRIVEN_CASING_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(lineWidth + 2),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_DRIVEN)));
        style.addLayer(new LineLayer(DRIVEN_LINE_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor("#9AA3B2"),
                PropertyFactory.lineWidth(lineWidth),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_DRIVEN)));
        style.addLayer(new LineLayer(CASING_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(lineWidth + 3),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_ROUTE)));
        style.addLayer(new LineLayer(LINE_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor("#2979FF"),
                PropertyFactory.lineWidth(lineWidth),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_ROUTE)));
        style.addLayer(new LineLayer(STRAIGHT_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor("#2979FF"),
                PropertyFactory.lineWidth(lineWidth * 0.6f),
                PropertyFactory.lineDasharray(new Float[] {2f, 2f}))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_STRAIGHT)));
        style.addLayer(new CircleLayer(PIN_LAYER, SOURCE).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor("#2979FF"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f))
                .withFilter(Expression.eq(Expression.get(KIND), KIND_PIN)));
    }

    /** Shows TripGuide's trip now (nothing when there is none). */
    static void showTrip(Style style) {
        GeoJsonSource source = style.getSourceAs(SOURCE);
        if (source == null) return;
        List<Feature> parts = new ArrayList<>();
        if (TripGuide.isActive()) {
            Point place = Point.fromLngLat(TripGuide.lookupDestinationLon(), TripGuide.lookupDestinationLat());
            DrivingRoute route = TripGuide.lookupRoute();
            Location car = TripGuide.lookupLastFix();
            if (route != null) {
                List<DrivingRoute> offered = TripGuide.lookupRoutes();
                for (int i = 0; i < offered.size() && !TripGuide.isDriving(); i++) {
                    if (i == TripGuide.lookupChosenRoute()) continue;
                    Feature other = buildPart(buildLine(offered.get(i)), KIND_OTHER);
                    other.addNumberProperty(ROUTE_INDEX, i);
                    parts.add(other);
                }
                DrivingRoute.Progress progress = TripGuide.lookupProgress();
                if (TripGuide.isDriving() && progress != null) {
                    double length = route.measureLengthMeters();
                    parts.add(buildPart(buildLine(route.cutLine(0, progress.travelledMeters)), KIND_DRIVEN));
                    parts.add(buildPart(buildLine(route.cutLine(progress.travelledMeters, length)), KIND_ROUTE));
                } else {
                    parts.add(buildPart(buildLine(route.cutLine(0, route.measureLengthMeters())), KIND_ROUTE));
                }
                // Still off the road where the route begins: dotted from the car to that road.
                boolean notStarted = progress == null || progress.travelledMeters < 1;
                if (car != null && notStarted && CameraAlerts.measureDistanceMeters(car.getLatitude(),
                        car.getLongitude(), route.lats[0], route.lons[0]) > CONNECTOR_METERS) {
                    double[][] toRoad = {{car.getLatitude(), route.lats[0]}, {car.getLongitude(), route.lons[0]}};
                    parts.add(buildPart(buildLine(toRoad), KIND_STRAIGHT));
                }
            } else if (car != null) {
                List<Point> straight = new ArrayList<>();
                straight.add(Point.fromLngLat(car.getLongitude(), car.getLatitude()));
                straight.add(place);
                parts.add(buildPart(Feature.fromGeometry(LineString.fromLngLats(straight)), KIND_STRAIGHT));
            }
            parts.add(buildPart(Feature.fromGeometry(place), KIND_PIN));
        }
        source.setGeoJson(FeatureCollection.fromFeatures(parts));
    }

    private static Feature buildLine(DrivingRoute route) {
        return buildLine(new double[][] {route.lats, route.lons});
    }

    /** A line from {lats, lons}. */
    private static Feature buildLine(double[][] latsLons) {
        List<Point> line = new ArrayList<>(latsLons[0].length);
        for (int i = 0; i < latsLons[0].length; i++) line.add(Point.fromLngLat(latsLons[1][i], latsLons[0][i]));
        return Feature.fromGeometry(LineString.fromLngLats(line));
    }

    /** Which other way (TripGuide's route index) a tap at {@code where} on the map hits, or -1 for none. */
    static int findTappedRoute(MapLibreMap map, PointF where) {
        RectF around = new RectF(where.x - TAP_SLOP_PX, where.y - TAP_SLOP_PX, where.x + TAP_SLOP_PX,
                where.y + TAP_SLOP_PX);
        for (Feature hit : map.queryRenderedFeatures(around, OTHER_LINE_LAYER, OTHER_CASING_LAYER)) {
            if (hit.hasProperty(ROUTE_INDEX)) return hit.getNumberProperty(ROUTE_INDEX).intValue();
        }
        return -1;
    }

    private static Feature buildPart(Feature part, String kind) {
        part.addStringProperty(KIND, kind);
        return part;
    }
}
