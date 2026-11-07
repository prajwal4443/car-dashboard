package com.prajwal.cardashboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Place search from the dashboard map. Offline it searches the downloaded maps (PlaceIndex) as you type; with
 * internet it also asks Photon (OnlineSearch) once typing pauses, listed first. Picking a result hands it back to the
 * dashboard (EXTRA_NAME, EXTRA_LAT, EXTRA_LON), which marks it on the map.
 */
public class PlaceSearchActivity extends Activity {
    static final String EXTRA_NAME = "name";
    static final String EXTRA_LAT = "lat";
    static final String EXTRA_LON = "lon";
    private static final String TAG = "CarDash.PlaceSearch";
    private static final int OFFLINE_LIMIT = 25;
    private static final long OFFLINE_DELAY_MS = 120;
    /** Online search waits for typing to pause, to keep to Photon's fair use. */
    private static final long ONLINE_DELAY_MS = 700;
    private static final int MIN_ONLINE_LETTERS = 3;
    /** How often to look for a finished index rebuild (a file date check). */
    private static final long INDEX_CHECK_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText query;
    private TextView status;
    private LinearLayout results;
    private Location here;
    private volatile List<PlaceIndex.Place> offlinePlaces = Collections.emptyList();
    private long loadedIndexTime = -1;
    private boolean indexLoaded;
    private boolean online;
    /** The online answer and the text it was for: shown only while that text is still in the box. */
    private List<PlaceIndex.Place> onlinePlaces = Collections.emptyList();
    private String onlineQuery = "";
    private boolean onlineFailed;
    private final Runnable offlineSearch = this::showResults;
    private final Runnable onlineSearch = this::startOnlineSearch;
    private final Runnable indexCheck = new Runnable() {
        @Override
        public void run() {
            loadIndex();
            handler.postDelayed(this, INDEX_CHECK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DashboardMap.prepare(this);
        setContentView(R.layout.activity_place_search);
        query = findViewById(R.id.searchQuery);
        status = findViewById(R.id.searchStatus);
        results = findViewById(R.id.searchResults);
        findViewById(R.id.searchCancel).setOnClickListener(v -> finish());
        here = DashboardMap.findLastLocation(this);
        online = OnlineSearch.hasInternet(this);
        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                handler.removeCallbacks(offlineSearch);
                handler.removeCallbacks(onlineSearch);
                handler.postDelayed(offlineSearch, OFFLINE_DELAY_MS);
                if (online) handler.postDelayed(onlineSearch, ONLINE_DELAY_MS);
            }
        });
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            handler.removeCallbacks(onlineSearch);
            if (online) startOnlineSearch();
            return true;
        });
        if (PlaceIndexService.isIndexStale(this)) PlaceIndexService.rebuild(this);
        loadIndex();
        showResults();
        query.requestFocus();
    }

    /**
     * A touch outside the search box puts the keyboard away and the cursor out of the box (the touch still reaches
     * what it was on, a result row say); touching the box brings both back.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && query.hasFocus()) {
            Rect box = new Rect();
            query.getGlobalVisibleRect(box);
            if (!box.contains((int) event.getRawX(), (int) event.getRawY())) {
                query.clearFocus();
                getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        online = OnlineSearch.hasInternet(this);
        indexCheck.run();   // picks up a rebuild that finishes while this is open
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(indexCheck);
        super.onPause();
    }

    /** Reads the offline index in the background when its file is new or changed since it was last read. */
    private void loadIndex() {
        File file = PlaceIndexService.findIndexFile(this);
        long changed = file.isFile() ? file.lastModified() : 0;
        if (changed == loadedIndexTime) return;
        loadedIndexTime = changed;
        worker.execute(() -> {
            List<PlaceIndex.Place> loaded;
            try {
                loaded = PlaceIndex.loadIndex(file);
            } catch (IOException e) {
                Log.w(TAG, "offline index unreadable", e);
                loaded = Collections.emptyList();
            }
            List<PlaceIndex.Place> ready = loaded;
            runOnUiThread(() -> {
                offlinePlaces = ready;
                indexLoaded = true;
                showResults();
            });
        });
    }

    private void startOnlineSearch() {
        String text = query.getText().toString().trim();
        if (text.length() < MIN_ONLINE_LETTERS || text.equals(onlineQuery)) return;
        Location near = here;
        worker.execute(() -> {
            List<PlaceIndex.Place> found;
            boolean failed = false;
            try {
                found = OnlineSearch.searchPlaces(text, near);
            } catch (IOException e) {
                Log.i(TAG, "online search failed, offline results only", e);
                found = Collections.emptyList();
                failed = true;
            }
            List<PlaceIndex.Place> answer = found;
            boolean answerFailed = failed;
            runOnUiThread(() -> {
                onlineQuery = text;
                onlinePlaces = answer;
                onlineFailed = answerFailed;
                showResults();
            });
        });
    }

    private void showResults() {
        loadIndex();
        String text = query.getText().toString().trim();
        results.removeAllViews();
        status.setText(describeSearch());
        if (text.isEmpty()) return;
        if (online && text.equals(onlineQuery) && !onlinePlaces.isEmpty()) {
            addHeading(R.string.search_online_heading);
            for (PlaceIndex.Place place : onlinePlaces) addResult(place);
        }
        List<PlaceIndex.Place> offline = PlaceIndex.findMatches(offlinePlaces, text,
                here != null ? here.getLatitude() : 0, here != null ? here.getLongitude() : 0, here != null,
                OFFLINE_LIMIT);
        if (!offline.isEmpty()) {
            addHeading(R.string.search_offline_heading);
            for (PlaceIndex.Place place : offline) addResult(place);
        }
        if (results.getChildCount() == 0 && indexLoaded) {
            TextView none = new TextView(this);
            none.setText(getString(R.string.search_nothing, text));
            none.setTextColor(getColor(R.color.dash_text_dim));
            none.setTextSize(15);
            none.setPadding(0, 24, 0, 0);
            results.addView(none);
        }
    }

    /** Where this search looks: online and the downloaded maps, or the downloaded maps only (and how much). */
    private String describeSearch() {
        if (!indexLoaded) return getString(R.string.search_loading);
        boolean failedNow = onlineFailed && query.getText().toString().trim().equals(onlineQuery);
        if (offlinePlaces.isEmpty()) {
            return getString(online && !failedNow ? R.string.search_online_only : R.string.search_no_index);
        }
        int offlineText = online && !failedNow ? R.string.search_online_and_offline : R.string.search_offline_only;
        return getString(offlineText, offlinePlaces.size());
    }

    private void addHeading(int text) {
        TextView heading = (TextView) LayoutInflater.from(this).inflate(R.layout.dash_settings_heading, results, false);
        heading.setText(text);
        results.addView(heading);
    }

    private void addResult(PlaceIndex.Place place) {
        View row = LayoutInflater.from(this).inflate(R.layout.dash_settings_row, results, false);
        ((TextView) row.findViewById(R.id.dashSettingTitle)).setText(place.name);
        ((TextView) row.findViewById(R.id.dashSettingValue)).setText(describePlace(place));
        row.setOnClickListener(v -> pick(place));
        results.addView(row);
    }

    /** "Petrol pump · near Kaup · 3.2 km". */
    private String describePlace(PlaceIndex.Place place) {
        List<String> parts = new ArrayList<>();
        parts.add(PlaceIndex.describeKind(place));
        if (!place.area.isEmpty()) parts.add(getString(R.string.search_near, place.area));
        if (here != null) {
            double meters = CameraAlerts.measureDistanceMeters(here.getLatitude(), here.getLongitude(), place.lat,
                    place.lon);
            parts.add(meters < 1000 ? String.format(Locale.getDefault(), "%.0f m", meters)
                    : String.format(Locale.getDefault(), "%.1f km", meters / 1000));
        }
        return String.join(" · ", parts);
    }

    private void pick(PlaceIndex.Place place) {
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_NAME, place.name)
                .putExtra(EXTRA_LAT, place.lat)
                .putExtra(EXTRA_LON, place.lon));
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
