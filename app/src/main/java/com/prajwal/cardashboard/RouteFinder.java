package com.prajwal.cardashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Driving routes with internet: the OSRM car router FOSSGIS runs for openstreetmap.org (routing.openstreetmap.de),
 * free for reasonable use by clients that say who they are. Called off the main thread.
 */
final class RouteFinder {
    private static final String ROUTER_URL = "https://routing.openstreetmap.de/routed-car/route/v1/driving/";
    private static final String USER_AGENT = "CarDashboard (com.prajwal.cardashboard)";
    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_ALTERNATIVES = 3;
    private static OkHttpClient client;

    private RouteFinder() {
    }

    /**
     * Routes by car from one point to another, fastest first. With {@code alternatives}, other ways as well (up to
     * MAX_ALTERNATIVES, only where the router finds real ones).
     */
    static List<DrivingRoute> findRoutes(double fromLat, double fromLon, double toLat, double toLon,
                                         boolean alternatives) throws IOException {
        String url = ROUTER_URL + String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat)
                + "?overview=full&geometries=geojson&steps=true"
                + (alternatives ? "&alternatives=" + MAX_ALTERNATIVES : "");
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = lookupClient().newCall(request).execute()) {
            if (response.body() == null) throw new IOException("router sent nothing");
            return parseRoutes(response.body().string());
        }
    }

    private static List<DrivingRoute> parseRoutes(String answer) throws IOException {
        try {
            JSONObject reply = new JSONObject(answer);
            if (!"Ok".equals(reply.optString("code"))) {
                throw new IOException("no route: " + reply.optString("code") + " " + reply.optString("message"));
            }
            JSONArray found = reply.getJSONArray("routes");
            List<DrivingRoute> routes = new ArrayList<>();
            for (int i = 0; i < found.length(); i++) routes.add(parseRoute(found.getJSONObject(i)));
            if (routes.isEmpty()) throw new IOException("router sent no route");
            return routes;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("route not understood", e);
        }
    }

    private static DrivingRoute parseRoute(JSONObject route) throws JSONException {
        JSONArray line = route.getJSONObject("geometry").getJSONArray("coordinates");
        double[] lats = new double[line.length()];
        double[] lons = new double[line.length()];
        for (int i = 0; i < line.length(); i++) {
            JSONArray point = line.getJSONArray(i);
            lons[i] = point.getDouble(0);
            lats[i] = point.getDouble(1);
        }
        List<DrivingRoute.Step> steps = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        JSONArray legs = route.getJSONArray("legs");
        for (int leg = 0; leg < legs.length(); leg++) {
            JSONObject part = legs.getJSONObject(leg);
            if (summary.length() == 0) summary.append(part.optString("summary"));
            JSONArray partSteps = part.getJSONArray("steps");
            for (int i = 0; i < partSteps.length(); i++) steps.add(parseStep(partSteps.getJSONObject(i)));
        }
        return new DrivingRoute(lats, lons, route.optDouble("duration"), summary.toString(), steps);
    }

    private static DrivingRoute.Step parseStep(JSONObject step) throws JSONException {
        JSONObject maneuver = step.getJSONObject("maneuver");
        JSONArray where = maneuver.getJSONArray("location");
        return new DrivingRoute.Step(maneuver.optString("type"), maneuver.optString("modifier"),
                step.optString("name"), step.optString("ref"), maneuver.optInt("exit", 0), where.getDouble(1),
                where.getDouble(0));
    }

    private static synchronized OkHttpClient lookupClient() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }
}
