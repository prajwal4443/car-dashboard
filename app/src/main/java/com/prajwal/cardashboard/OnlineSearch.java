package com.prajwal.cardashboard;

import android.content.Context;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Place search with internet: Photon (photon.komoot.io), a free OpenStreetMap search made for search-as-you-type,
 * limited to India and biased toward the car's position. Finds more than the downloaded maps (streets, addresses
 * where OpenStreetMap has them). Called off the main thread.
 */
final class OnlineSearch {
    private static final String PHOTON_URL = "https://photon.komoot.io/api/";
    /** India, as minLon,minLat,maxLon,maxLat. */
    private static final String INDIA_BOX = "68.1,6.5,97.4,35.7";
    private static final int RESULT_LIMIT = 15;
    private static final long TIMEOUT_SECONDS = 6;
    /** Photon asks clients to say who they are. */
    private static final String USER_AGENT = "CarDashboard (com.prajwal.cardashboard)";
    private static OkHttpClient client;

    private OnlineSearch() {
    }

    /** True when the active network is checked by Android to reach the internet (not just a Wi-Fi without it). */
    static boolean hasInternet(Context context) {
        ConnectivityManager connections = context.getSystemService(ConnectivityManager.class);
        if (connections == null) return false;
        Network active = connections.getActiveNetwork();
        NetworkCapabilities abilities = active != null ? connections.getNetworkCapabilities(active) : null;
        return abilities != null && abilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Places matching {@code query}, nearest to {@code near} first when it is known: Photon ranks a name that matches
     * better ahead of a nearer place, and while driving the nearer one matters more.
     */
    static List<PlaceIndex.Place> searchPlaces(String query, Location near) throws IOException {
        HttpUrl.Builder url = HttpUrl.get(PHOTON_URL).newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", Integer.toString(RESULT_LIMIT))
                .addQueryParameter("lang", "en")
                .addQueryParameter("bbox", INDIA_BOX);
        if (near != null) {
            url.addQueryParameter("lat", String.format(Locale.ROOT, "%.5f", near.getLatitude()));
            url.addQueryParameter("lon", String.format(Locale.ROOT, "%.5f", near.getLongitude()));
        }
        Request request = new Request.Builder().url(url.build()).header("User-Agent", USER_AGENT).build();
        try (Response response = lookupClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Photon answered " + response.code());
            }
            List<PlaceIndex.Place> places = parsePlaces(response.body().string());
            if (near != null) {
                places.sort((a, b) -> Double.compare(
                        CameraAlerts.measureDistanceMeters(near.getLatitude(), near.getLongitude(), a.lat, a.lon),
                        CameraAlerts.measureDistanceMeters(near.getLatitude(), near.getLongitude(), b.lat, b.lon)));
            }
            return places;
        }
    }

    private static List<PlaceIndex.Place> parsePlaces(String answer) throws IOException {
        try {
            JSONArray features = new JSONObject(answer).optJSONArray("features");
            if (features == null) return Collections.emptyList();
            List<PlaceIndex.Place> places = new ArrayList<>();
            for (int i = 0; i < features.length(); i++) {
                PlaceIndex.Place place = parsePlace(features.getJSONObject(i));
                if (place != null) places.add(place);
            }
            return places;
        } catch (JSONException e) {
            throw new IOException("Photon answer not understood", e);
        }
    }

    private static PlaceIndex.Place parsePlace(JSONObject feature) {
        JSONArray point = feature.optJSONObject("geometry") != null
                ? feature.optJSONObject("geometry").optJSONArray("coordinates") : null;
        JSONObject about = feature.optJSONObject("properties");
        if (point == null || point.length() < 2 || about == null) return null;
        String street = about.optString("street");
        String number = about.optString("housenumber");
        String name = about.optString("name");
        if (name.isEmpty()) name = (number.isEmpty() ? "" : number + " ") + street;
        if (name.trim().isEmpty()) return null;
        String area = "";
        for (String key : new String[] {"city", "district", "county", "state"}) {
            String part = about.optString(key);
            if (!part.isEmpty() && !part.equalsIgnoreCase(name)) {
                area = part;
                break;
            }
        }
        List<String> searchNames = Collections.singletonList(PlaceIndex.normalizeText(name));
        // A street is "highway" plus its road type (unclassified, residential…): all of them read "Road".
        String kind = "highway".equals(about.optString("osm_key")) ? "road" : about.optString("osm_value");
        return new PlaceIndex.Place("online", kind, "", point.optDouble(1),
                point.optDouble(0), name.trim(), area, searchNames);
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
