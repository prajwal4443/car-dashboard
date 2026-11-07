package com.prajwal.cardashboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The apps chosen for the dashboard's Home tiles: added with "Add to Home" and taken off with "Remove from Home"
 * (hold a tile, or in All apps). A removal wins over an earlier addition and the other way round. Kept in the app's
 * preferences, one package per line in the order chosen.
 */
final class HomeTiles {
    private static final String PREFS = "home_tiles";
    private static final String ADDED_KEY = "added";
    private static final String REMOVED_KEY = "removed";

    private HomeTiles() {
    }

    static Set<String> loadAdded(Context context) {
        return readList(prefs(context), ADDED_KEY);
    }

    static Set<String> loadRemoved(Context context) {
        return readList(prefs(context), REMOVED_KEY);
    }

    /** Puts an app on Home or takes it off; false when the choice could not be saved. */
    static boolean save(Context context, String pkg, boolean onHome) {
        SharedPreferences prefs = prefs(context);
        Set<String> added = readList(prefs, ADDED_KEY);
        Set<String> removed = readList(prefs, REMOVED_KEY);
        if (onHome) {
            added.add(pkg);
            removed.remove(pkg);
        } else {
            removed.add(pkg);
            added.remove(pkg);
        }
        return prefs.edit()
                .putString(ADDED_KEY, String.join("\n", added))
                .putString(REMOVED_KEY, String.join("\n", removed))
                .commit();
    }

    /** Forgets every Add to Home and Remove from Home choice (Dashboard settings, Reset Home apps). */
    static void clearAll(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static Set<String> readList(SharedPreferences prefs, String key) {
        String saved = prefs.getString(key, "");
        if (saved.isEmpty()) return new LinkedHashSet<>();
        return new LinkedHashSet<>(Arrays.asList(saved.split("\n")));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
