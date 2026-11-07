package com.prajwal.cardashboard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline place search from the downloaded map: the names in its tiles (towns and villages, petrol pumps, temples,
 * hospitals and other points of interest, named roads) as a small index file, and the search over it. Words such as
 * "petrol" or "temple" find places by kind as well as by name. Plain Java, so it runs in unit tests.
 */
final class PlaceIndex {
    /** Tile layer (OpenMapTiles) of towns, villages and areas. */
    private static final String PLACE_LAYER = "place";
    /** Tile layers (OpenMapTiles) with searchable names. */
    static final Set<String> NAMED_LAYERS = new HashSet<>(Arrays.asList(PLACE_LAYER, "poi", "transportation_name"));
    /** Search reads the most detailed tiles only: every name is in them. */
    static final int INDEX_ZOOM = 14;
    /** The same name and kind closer than this (degrees, about 2 km) is one entry: roads repeat in every tile. */
    private static final double SAME_SPOT_DEGREES = 0.02;
    /** A result with the same name and kind as a nearer one, within this, is left out (one road, many tiles). */
    private static final double REPEAT_METERS = 5000;
    /** A point of interest is described as "near" the closest town or village within this. */
    private static final double AREA_METERS = 8000;
    private static final Set<String> AREA_KINDS = new HashSet<>(Arrays.asList(
            "city", "town", "village", "suburb", "neighbourhood", "hamlet", "quarter", "locality"));
    private static final String NAME_SEPARATOR = "\u001F";
    /** Words people search with, and the OpenMapTiles kinds (class or subclass) they mean. */
    private static final Map<String, Set<String>> KIND_WORDS = buildKindWords();
    /** How each kind reads in a result line. */
    private static final Map<String, String> KIND_LABELS = buildKindLabels();

    /** One searchable place. */
    static final class Place {
        final String layer;
        final String kind;
        final String detail;
        final double lat;
        final double lon;
        final String name;
        /** The nearest town or village, for a point of interest or road ("" when none is close). */
        final String area;
        /** Every name it is known by, normalised for matching (normalizeText). */
        final List<String> searchNames;

        Place(String layer, String kind, String detail, double lat, double lon, String name, String area,
              List<String> searchNames) {
            this.layer = layer;
            this.kind = kind;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
            this.name = name;
            this.area = area;
            this.searchNames = searchNames;
        }

        Place withArea(String newArea) {
            return new Place(layer, kind, detail, lat, lon, name, newArea, searchNames);
        }
    }

    private PlaceIndex() {
    }

    /** The named places in one tile (z/x/y in the usual XYZ scheme). */
    static List<Place> extractPlaces(byte[] tile, int z, int x, int y) {
        List<Place> places = new ArrayList<>();
        for (VectorTileReader.TileFeature feature : VectorTileReader.readFeatures(tile, NAMED_LAYERS)) {
            Place place = buildPlace(feature, z, x, y);
            if (place != null) places.add(place);
        }
        return places;
    }

    private static Place buildPlace(VectorTileReader.TileFeature feature, int z, int x, int y) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String key : new String[] {"name:en", "name_int", "name:latin", "name", "ref"}) {
            String text = feature.lookupText(key);
            if (text != null && !text.trim().isEmpty()) names.add(text.trim());
        }
        if (names.isEmpty()) return null;
        boolean road = "transportation_name".equals(feature.layer);
        String kind = road ? "road" : orEmpty(feature.lookupText("class"));
        String detail = road ? orEmpty(feature.lookupText("class")) : orEmpty(feature.lookupText("subclass"));
        double tiles = Math.pow(2, z);
        double lon = (x + feature.shareX) / tiles * 360 - 180;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + feature.shareY) / tiles))));
        List<String> searchNames = new ArrayList<>();
        for (String name : names) {
            String normalized = normalizeText(name);
            if (!normalized.isEmpty() && !searchNames.contains(normalized)) searchNames.add(normalized);
        }
        return new Place(feature.layer, kind, detail, lat, lon, names.iterator().next(), "", searchNames);
    }

    /**
     * One entry per place (the same name and kind in neighbouring tiles is kept once), each point of interest and
     * road with its nearest town or village.
     */
    static List<Place> mergeDuplicates(List<Place> found) {
        Map<String, Place> unique = new HashMap<>();
        List<Place> kept = new ArrayList<>();
        for (Place place : found) {
            String key = place.layer + "|" + place.kind + "|" + place.searchNames.get(0) + "|"
                    + Math.round(place.lat / SAME_SPOT_DEGREES) + "|" + Math.round(place.lon / SAME_SPOT_DEGREES);
            if (unique.containsKey(key)) continue;
            unique.put(key, place);
            kept.add(place);
        }
        List<Place> areas = new ArrayList<>();
        for (Place place : kept) if (isArea(place)) areas.add(place);
        List<Place> described = new ArrayList<>(kept.size());
        for (Place place : kept) described.add(isArea(place) ? place : place.withArea(findNearestArea(place, areas)));
        return described;
    }

    private static boolean isArea(Place place) {
        return PLACE_LAYER.equals(place.layer) && AREA_KINDS.contains(place.kind);
    }

    private static String findNearestArea(Place place, List<Place> areas) {
        String nearest = "";
        double best = AREA_METERS;
        for (Place area : areas) {
            if (Math.abs(area.lat - place.lat) > 0.1 || Math.abs(area.lon - place.lon) > 0.1) continue;
            double meters = CameraAlerts.measureDistanceMeters(place.lat, place.lon, area.lat, area.lon);
            if (meters < best) {
                best = meters;
                nearest = area.name;
            }
        }
        return nearest;
    }

    /**
     * Places matching {@code query}, best first: the whole name, then every word found in the name, then matches by
     * kind or by area ("petrol kaup"); nearest first within each. A road found in many tiles is listed once (nearest).
     * Without a position ({@code hasPosition} false) the order within each group is by name.
     */
    static List<Place> findMatches(List<Place> places, String query, double lat, double lon, boolean hasPosition,
                                   int limit) {
        String whole = normalizeText(query);
        List<String> words = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            String normalized = normalizeText(word);
            if (!normalized.isEmpty()) words.add(normalized);
        }
        if (whole.isEmpty() || words.isEmpty()) return Collections.emptyList();
        List<Place> matched = new ArrayList<>();
        Map<Place, Integer> ranks = new HashMap<>();
        Map<Place, Double> distances = new HashMap<>();
        for (Place place : places) {
            int rank = rankMatch(place, whole, words);
            if (rank < 0) continue;
            matched.add(place);
            ranks.put(place, rank);
            distances.put(place, hasPosition ? CameraAlerts.measureDistanceMeters(lat, lon, place.lat, place.lon) : 0);
        }
        matched.sort((a, b) -> {
            int byRank = Integer.compare(ranks.get(a), ranks.get(b));
            if (byRank != 0) return byRank;
            int byDistance = Double.compare(distances.get(a), distances.get(b));
            return byDistance != 0 ? byDistance : a.name.compareToIgnoreCase(b.name);
        });
        List<Place> shown = new ArrayList<>();
        for (Place place : matched) {
            if (isRepeat(place, shown)) continue;
            shown.add(place);
            if (shown.size() >= limit) break;
        }
        return shown;
    }

    /** 0: the whole name; 1: every word in the name; 2: words found by kind or area as well; -1: no match. */
    private static int rankMatch(Place place, String whole, List<String> words) {
        for (String name : place.searchNames) if (name.equals(whole)) return 0;
        for (String name : place.searchNames) if (name.contains(whole)) return 1;
        boolean allInName = true;
        String area = normalizeText(place.area);
        for (String word : words) {
            if (matchesName(place, word)) continue;
            allInName = false;
            if (matchesKind(place, word)) continue;
            if (!area.isEmpty() && area.startsWith(word)) continue;
            return -1;
        }
        return allInName ? 1 : 2;
    }

    private static boolean matchesName(Place place, String word) {
        for (String name : place.searchNames) {
            if (name.startsWith(word)) return true;
            if ((word.length() >= 3 || isDigits(word)) && name.contains(word)) return true;
        }
        return false;
    }

    private static boolean matchesKind(Place place, String word) {
        if (word.length() < 3) return false;
        for (Map.Entry<String, Set<String>> entry : KIND_WORDS.entrySet()) {
            if (!entry.getKey().startsWith(word)) continue;
            if (entry.getValue().contains(place.kind) || entry.getValue().contains(place.detail)) return true;
        }
        return normalizeText(place.kind).startsWith(word) || normalizeText(place.detail).startsWith(word);
    }

    private static boolean isRepeat(Place place, List<Place> shown) {
        for (Place earlier : shown) {
            if (!earlier.kind.equals(place.kind) || !earlier.searchNames.get(0).equals(place.searchNames.get(0))) {
                continue;
            }
            if (CameraAlerts.measureDistanceMeters(earlier.lat, earlier.lon, place.lat, place.lon) < REPEAT_METERS) {
                return true;
            }
        }
        return false;
    }

    /** What a place is, for its result line: "Petrol pump", "Temple", "Village", "Road". */
    static String describeKind(Place place) {
        String label = KIND_LABELS.get(place.kind + "/" + place.detail);
        if (label == null) label = KIND_LABELS.get(place.kind);
        if (label != null) return label;
        String plain = (place.detail.isEmpty() ? place.kind : place.detail).replace('_', ' ');
        if (plain.isEmpty()) return "Place";
        return Character.toUpperCase(plain.charAt(0)) + plain.substring(1);
    }

    /** Lower case letters and digits only: "NH 66" and "nh66" match, as do "Kaup" and "KAUP". */
    static String normalizeText(String text) {
        StringBuilder kept = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int letter = text.codePointAt(i);
            if (Character.isLetterOrDigit(letter)) kept.appendCodePoint(Character.toLowerCase(letter));
            i += Character.charCount(letter);
        }
        return kept.toString();
    }

    private static boolean isDigits(String word) {
        for (int i = 0; i < word.length(); i++) if (!Character.isDigit(word.charAt(i))) return false;
        return true;
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** Writes the index (one place per line), replacing the old file only once the new one is complete. */
    static void saveIndex(File file, List<Place> places) throws IOException {
        File partial = new File(file.getPath() + ".partial");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(partial),
                StandardCharsets.UTF_8))) {
            for (Place place : places) {
                out.write(String.join("\t", place.layer, place.kind, place.detail,
                        Double.toString(place.lat), Double.toString(place.lon), cleanField(place.name),
                        cleanField(place.area), String.join(NAME_SEPARATOR, place.searchNames)));
                out.write('\n');
            }
        }
        if (!partial.renameTo(file)) throw new IOException("could not replace " + file);
    }

    /** The index saveIndex wrote; empty when there is none yet. Damaged lines are skipped. */
    static List<Place> loadIndex(File file) throws IOException {
        List<Place> places = new ArrayList<>();
        if (!file.isFile()) return places;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file),
                StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 8 || fields[7].isEmpty()) continue;
                try {
                    places.add(new Place(fields[0], fields[1], fields[2], Double.parseDouble(fields[3]),
                            Double.parseDouble(fields[4]), fields[5], fields[6],
                            Arrays.asList(fields[7].split(NAME_SEPARATOR))));
                } catch (NumberFormatException ignored) {
                    // a damaged line: the rest of the index still works
                }
            }
        }
        return places;
    }

    private static String cleanField(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static Map<String, Set<String>> buildKindWords() {
        Map<String, Set<String>> words = new HashMap<>();
        addKindWords(words, new String[] {"petrol", "pump", "fuel", "diesel", "bunk", "cng"}, "fuel");
        addKindWords(words, new String[] {"charging", "charger", "ev"}, "charging_station");
        addKindWords(words, new String[] {"hospital"}, "hospital", "clinic");
        addKindWords(words, new String[] {"clinic", "doctor"}, "clinic", "doctors", "hospital");
        addKindWords(words, new String[] {"medical", "pharmacy", "chemist", "medicine"}, "pharmacy", "chemist");
        addKindWords(words, new String[] {"temple", "mandir", "devasthana"}, "hindu");
        addKindWords(words, new String[] {"mosque", "masjid"}, "muslim");
        addKindWords(words, new String[] {"church"}, "christian");
        addKindWords(words, new String[] {"atm"}, "atm");
        addKindWords(words, new String[] {"bank"}, "bank");
        // In India "hotel" also means a restaurant.
        addKindWords(words, new String[] {"hotel"}, "lodging", "hotel", "guest_house", "motel", "hostel",
                "restaurant", "fast_food");
        addKindWords(words, new String[] {"lodge", "stay", "resort"}, "lodging", "hotel", "guest_house", "motel",
                "hostel", "resort");
        addKindWords(words, new String[] {"restaurant", "food", "eat", "meals"}, "restaurant", "fast_food",
                "food_court");
        addKindWords(words, new String[] {"cafe", "coffee", "tea"}, "cafe");
        addKindWords(words, new String[] {"school"}, "school");
        addKindWords(words, new String[] {"college", "university"}, "college", "university");
        addKindWords(words, new String[] {"police"}, "police");
        addKindWords(words, new String[] {"parking"}, "parking");
        addKindWords(words, new String[] {"bus"}, "bus", "bus_station", "bus_stop");
        addKindWords(words, new String[] {"railway", "train", "station"}, "railway", "train_station", "station",
                "halt");
        addKindWords(words, new String[] {"toilet", "restroom"}, "toilets");
        addKindWords(words, new String[] {"toll"}, "toll_booth");
        addKindWords(words, new String[] {"shop", "market", "supermarket", "grocery"}, "shop", "supermarket",
                "marketplace", "grocery", "convenience");
        addKindWords(words, new String[] {"beach"}, "beach");
        addKindWords(words, new String[] {"park"}, "park");
        addKindWords(words, new String[] {"post"}, "post");
        addKindWords(words, new String[] {"village"}, "village", "hamlet");
        addKindWords(words, new String[] {"town", "city"}, "town", "city");
        return words;
    }

    private static void addKindWords(Map<String, Set<String>> words, String[] said, String... kinds) {
        for (String word : said) words.computeIfAbsent(word, unused -> new HashSet<>()).addAll(Arrays.asList(kinds));
    }

    private static Map<String, String> buildKindLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put("fuel", "Petrol pump");
        labels.put("charging_station", "EV charging");
        labels.put("place_of_worship/hindu", "Temple");
        labels.put("place_of_worship/muslim", "Mosque");
        labels.put("place_of_worship/christian", "Church");
        labels.put("place_of_worship", "Place of worship");
        labels.put("hospital", "Hospital");
        labels.put("pharmacy", "Pharmacy");
        labels.put("lodging", "Hotel");
        labels.put("fast_food", "Restaurant");
        labels.put("restaurant", "Restaurant");
        labels.put("cafe", "Café");
        labels.put("atm", "ATM");
        labels.put("bank", "Bank");
        labels.put("road", "Road");
        labels.put("city", "City");
        labels.put("town", "Town");
        labels.put("village", "Village");
        labels.put("hamlet", "Village");
        labels.put("suburb", "Area");
        labels.put("neighbourhood", "Area");
        labels.put("quarter", "Area");
        return labels;
    }
}
