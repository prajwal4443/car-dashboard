package com.prajwal.cardashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.OnCameraTrackingChangedListener;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

import java.util.List;

/**
 * The navigation screen: the trip (TripGuide) on a full-screen map. First the whole route with its distance and
 * time; Start follows the car turn by turn, zooming out with speed. The map can be moved and zoomed by hand at any
 * time (pinch, drag, the + and - buttons, Whole route); Follow the car goes back. Home leaves the trip running on the
 * dashboard's mini map; Exit ends it.
 */
public class NavigationActivity extends Activity {
    private static final String TAG = "CarDash.Navigation";
    private static final int REQUEST_SEARCH = 4401;
    /** While driving, the car sits this far down the screen, so most of it shows the road ahead. */
    private static final float CAR_DOWN_SCREEN = 0.68f;
    private static final double DRIVING_TILT = 45;
    private static final int EASE_MS = 900;
    private static final int OVERVIEW_PADDING_DP = 60;
    /** How much one + or - press changes the zoom while following the car. */
    private static final double ZOOM_STEP = 1;
    /** While driving, the map buttons show this long after a tap on the map. */
    private static final long CONTROLS_SHOWN_MS = 6000;
    /** The arrow's size on the full-screen map: small zoomed out (the whole route), full size while driving. */
    private static final float ARROW_SCALE_FAR = 0.5f;
    private static final float ARROW_SCALE_NEAR = 1.1f;

    private MapView mapView;
    private MapLibreMap map;
    private Style style;
    private View root;
    private TextView turnDistance;
    private TextView turnText;
    private TextView speed;
    private TextView remaining;
    private TextView routeState;
    private View start;
    private View recenter;
    private View panel;
    private View turn;
    /** True while the camera follows the car; false after the map is moved by hand or shows the whole route. */
    private boolean following;
    /** Zoom chosen by speed (MapZoom), and the +/- presses added to it while following. */
    private double speedZoom = MapZoom.ZOOMS[0];
    private double zoomOffset;
    /** The routes already framed, so the overview is framed once per new answer and not on every GPS fix. */
    private List<DrivingRoute> framedRoutes;
    private LinearLayout choices;
    private View zoomIn;
    private View zoomOut;
    private View overview;
    private View change;
    /** Until then (uptime ms) the map buttons show while driving and following. */
    private long controlsShownUntil;
    private final Runnable hideControls = this::showButtons;
    private final Runnable tripWatcher = this::showTrip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TripGuide.isActive()) {
            finish();
            return;
        }
        DashboardMap.prepare(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_navigation);
        root = findViewById(R.id.navRoot);
        turnDistance = findViewById(R.id.navTurnDistance);
        turnText = findViewById(R.id.navTurnText);
        speed = findViewById(R.id.navSpeed);
        remaining = findViewById(R.id.navRemaining);
        routeState = findViewById(R.id.navRouteState);
        start = findViewById(R.id.navStart);
        recenter = findViewById(R.id.navRecenter);
        panel = findViewById(R.id.navPanel);
        turn = findViewById(R.id.navTurn);
        choices = findViewById(R.id.navChoices);
        zoomIn = findViewById(R.id.navZoomIn);
        zoomOut = findViewById(R.id.navZoomOut);
        overview = findViewById(R.id.navOverview);
        change = findViewById(R.id.navSearch);
        start.setOnClickListener(v -> {
            TripGuide.startDriving();
            followCar();
        });
        findViewById(R.id.navExit).setOnClickListener(v -> {
            TripGuide.stop();
            finish();
        });
        findViewById(R.id.navSearch).setOnClickListener(v ->
                startActivityForResult(new Intent(this, PlaceSearchActivity.class), REQUEST_SEARCH));
        ImageButton voiceButton = findViewById(R.id.navVoice);
        showVoiceButton(voiceButton);
        voiceButton.setOnClickListener(v -> {
            boolean muted = !VoiceGuide.isMuted(this);
            VoiceGuide.saveMuted(this, muted);
            showVoiceButton(voiceButton);
            Toast.makeText(this, muted ? R.string.nav_voice_muted : R.string.nav_voice_on, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.navZoomIn).setOnClickListener(v -> zoomBy(ZOOM_STEP));
        findViewById(R.id.navZoomOut).setOnClickListener(v -> zoomBy(-ZOOM_STEP));
        findViewById(R.id.navOverview).setOnClickListener(v -> showWholeRoute());
        recenter.setOnClickListener(v -> {
            zoomOffset = 0;
            followCar();
        });
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) view.post(this::placeCar);
        });
        if (!DashboardMap.hasLocationPermission(this)) Toast.makeText(this, R.string.nav_no_location,
                Toast.LENGTH_LONG).show();
        mapView = findViewById(R.id.navMap);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(loaded -> {
            map = loaded;
            loaded.getUiSettings().setLogoEnabled(false);
            loaded.getUiSettings().setAttributionEnabled(false);
            loaded.getUiSettings().setCompassEnabled(false);
            loaded.addOnMapClickListener(where -> {
                if (TripGuide.isDriving()) {
                    showControlsForAWhile();
                    return true;
                }
                int tapped = RouteLayers.findTappedRoute(loaded, loaded.getProjection().toScreenLocation(where));
                if (tapped < 0) return false;
                TripGuide.chooseRoute(tapped);
                return true;
            });
            Location car = TripGuide.lookupLastFix();
            LatLng place = new LatLng(TripGuide.lookupDestinationLat(), TripGuide.lookupDestinationLon());
            loaded.setCameraPosition(new CameraPosition.Builder()
                    .target(car != null ? new LatLng(car.getLatitude(), car.getLongitude()) : place)
                    .zoom(12).build());
            String picked = DashboardMap.lookupStyle(this);
            loaded.setStyle(new Style.Builder().fromUri(DashboardMap.buildStyleUrl(
                    DashboardMap.STYLE_OFF.equals(picked) ? DashboardMap.STYLE_DARK : picked)), ready -> {
                style = ready;
                RouteLayers.addLayers(ready, 7f);
                showArrow();
                showTrip();
                if (TripGuide.isDriving()) followCar();
            });
        });
    }

    /** The GPS arrow; dragging or pinching the map stops following the car, and Follow the car shows up. */
    @SuppressLint("MissingPermission")
    private void showArrow() {
        if (!DashboardMap.hasLocationPermission(this)) return;
        try {
            LocationComponent location = map.getLocationComponent();
            LocationComponentOptions look = LocationComponentOptions.builder(this)
                    .minZoomIconScale(ARROW_SCALE_FAR).maxZoomIconScale(ARROW_SCALE_NEAR).build();
            location.activateLocationComponent(LocationComponentActivationOptions.builder(this, style)
                    .locationComponentOptions(look).useDefaultLocationEngine(true).build());
            location.setLocationComponentEnabled(true);
            location.setRenderMode(RenderMode.GPS);
            location.addOnCameraTrackingChangedListener(new OnCameraTrackingChangedListener() {
                @Override
                public void onCameraTrackingDismissed() {
                    following = false;
                    showButtons();
                }

                @Override
                public void onCameraTrackingChanged(int currentMode) {
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "location arrow off", e);
        }
    }

    /** Speaker or crossed-out speaker; its description says what a tap does. */
    private void showVoiceButton(ImageButton voiceButton) {
        boolean muted = VoiceGuide.isMuted(this);
        voiceButton.setImageResource(muted ? R.drawable.ic_voice_off : R.drawable.ic_voice_on);
        voiceButton.setContentDescription(getString(muted ? R.string.nav_unmute : R.string.nav_mute));
    }

    /** Everything from TripGuide: the route, the next turn, what is left; and the zoom for the speed. */
    private void showTrip() {
        if (!TripGuide.isActive()) {
            finish();
            return;
        }
        boolean driving = TripGuide.isDriving();
        MusicListener.Turn next = TripGuide.describeNextTurn();
        // Before Start the whole trip is on the map and the turn banner would hide part of it.
        turn.setVisibility(driving && next != null ? View.VISIBLE : View.GONE);
        if (next != null) {
            turnDistance.setText(next.distance);
            turnText.setText(next.instruction);
        }
        remaining.setText(TripGuide.describeRemaining());
        String state = TripGuide.describeRouteState();
        routeState.setText(driving ? state : TripGuide.lookupDestinationName() + (state.isEmpty() ? "" : " · " + state));
        showChoices();
        showButtons();
        Location fix = TripGuide.lookupLastFix();
        if (fix != null && fix.hasSpeed()) showSpeed(fix.getSpeed() * 3.6);
        if (style == null) return;
        RouteLayers.showTrip(style);
        List<DrivingRoute> offered = TripGuide.lookupRoutes();
        if (!driving && !offered.isEmpty() && offered != framedRoutes) {
            framedRoutes = offered;
            panel.post(this::showWholeRoute);   // new routes: all of them on screen, once the panel has its chips
        }
        if (following && fix != null && fix.hasSpeed()) {
            double zoom = MapZoom.chooseZoom(speedZoom, fix.getSpeed() * 3.6);
            if (zoom != speedZoom) {
                speedZoom = zoom;
                applyFollowZoom();
            }
        }
    }

    /** One chip per way the router offered ("20 km · 17 min"), the chosen one highlighted; before Start only. */
    private void showChoices() {
        List<DrivingRoute> offered = TripGuide.lookupRoutes();
        boolean show = !TripGuide.isDriving() && offered.size() > 1;
        choices.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        choices.removeAllViews();
        for (int i = 0; i < offered.size(); i++) {
            DrivingRoute way = offered.get(i);
            TextView chip = (TextView) LayoutInflater.from(this).inflate(R.layout.nav_route_choice, choices, false);
            chip.setText(TripGuide.formatDistance(way.measureLengthMeters()) + " · "
                    + TripGuide.formatDuration(way.durationSeconds));
            chip.setActivated(i == TripGuide.lookupChosenRoute());
            int index = i;
            chip.setOnClickListener(v -> TripGuide.chooseRoute(index));
            choices.addView(chip);
        }
    }

    /**
     * Before Start: everything. While driving and following the car: only the turn and a thin bar, the map buttons
     * for a moment after a tap on the map. After moving the map by hand: the map buttons and Follow the car.
     */
    private void showButtons() {
        boolean driving = TripGuide.isDriving();
        boolean controls = !driving || !following || android.os.SystemClock.uptimeMillis() < controlsShownUntil;
        int controlsShown = controls ? View.VISIBLE : View.GONE;
        zoomIn.setVisibility(controlsShown);
        zoomOut.setVisibility(controlsShown);
        overview.setVisibility(controlsShown);
        recenter.setVisibility(following ? View.GONE : View.VISIBLE);
        start.setVisibility(driving ? View.GONE : View.VISIBLE);
        change.setVisibility(driving ? View.GONE : View.VISIBLE);
        routeState.setVisibility(driving ? View.GONE : View.VISIBLE);
        int padding = Math.round((driving ? 6 : 12) * getResources().getDisplayMetrics().density);
        panel.setPadding(panel.getPaddingLeft(), padding, panel.getPaddingRight(), padding);
        remaining.setTextSize(driving ? 18 : 22);
    }

    private void showControlsForAWhile() {
        controlsShownUntil = android.os.SystemClock.uptimeMillis() + CONTROLS_SHOWN_MS;
        showButtons();
        root.removeCallbacks(hideControls);
        root.postDelayed(hideControls, CONTROLS_SHOWN_MS);
    }

    private void showSpeed(double kmh) {
        String number = Integer.toString((int) Math.round(Math.max(0, kmh)));
        SpannableString text = new SpannableString(number + "\nkm/h");
        text.setSpan(new RelativeSizeSpan(0.45f), number.length(), text.length(), 0);
        speed.setText(text);
        speed.setVisibility(View.VISIBLE);
    }

    /** Follows the car, tilted, at the speed's zoom plus any +/- presses. */
    private void followCar() {
        if (map == null || style == null) return;
        try {
            LocationComponent location = map.getLocationComponent();
            if (!location.isLocationComponentActivated()) return;
            following = true;
            // The whole-route view leaves its padding on the camera, which would centre the car higher up.
            map.moveCamera(CameraUpdateFactory.paddingTo(0, 0, 0, 0));
            location.setCameraMode(CameraMode.TRACKING_GPS, EASE_MS, speedZoom + zoomOffset, null, DRIVING_TILT,
                    null);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not follow the car", e);
        }
        showButtons();
    }

    private void applyFollowZoom() {
        try {
            map.getLocationComponent().zoomWhileTracking(speedZoom + zoomOffset, EASE_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "zoom skipped", e);
        }
    }

    /** +/-: while following, shifts the speed's zoom; otherwise zooms the map where it is. */
    private void zoomBy(double step) {
        if (map == null) return;
        if (following) {
            zoomOffset += step;
            applyFollowZoom();
            if (TripGuide.isDriving()) showControlsForAWhile();   // keep them while pressing
        } else {
            map.animateCamera(step > 0 ? CameraUpdateFactory.zoomIn() : CameraUpdateFactory.zoomOut(), EASE_MS);
        }
    }

    /** The whole route (or the car and the place without one) on screen, above the panel. */
    private void showWholeRoute() {
        if (map == null || style == null) return;
        try {
            LocationComponent location = map.getLocationComponent();
            if (location.isLocationComponentActivated()) location.setCameraMode(CameraMode.NONE);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not stop following", e);
        }
        following = false;
        showButtons();
        LatLngBounds.Builder area = new LatLngBounds.Builder();
        area.include(new LatLng(TripGuide.lookupDestinationLat(), TripGuide.lookupDestinationLon()));
        Location car = TripGuide.lookupLastFix();
        int points = 1;
        for (DrivingRoute way : TripGuide.lookupRoutes()) {
            for (int i = 0; i < way.lats.length; i++) area.include(new LatLng(way.lats[i], way.lons[i]));
            points += way.lats.length;
        }
        if (car != null) {
            area.include(new LatLng(car.getLatitude(), car.getLongitude()));
            points++;
        }
        ViewGroup.MarginLayoutParams frame = (ViewGroup.MarginLayoutParams) mapView.getLayoutParams();
        int padding = Math.round(OVERVIEW_PADDING_DP * getResources().getDisplayMetrics().density);
        int hiddenBottom = Math.max(0, -frame.bottomMargin);
        try {
            if (points < 2) {
                map.easeCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                        .target(new LatLng(TripGuide.lookupDestinationLat(), TripGuide.lookupDestinationLon()))
                        .zoom(14).tilt(0).bearing(0).build()), EASE_MS);
                return;
            }
            // Clear of the turn banner above (while driving), the map buttons on the right and the panel below.
            int top = padding + (turn.getVisibility() == View.VISIBLE ? turn.getBottom() : 0);
            int bottom = padding + hiddenBottom + panel.getHeight();
            int room = mapView.getHeight() - top - bottom;
            int least = mapView.getHeight() / 4;
            if (room < least) top = Math.max(0, top - (least - room));   // a short screen: keep a quarter for the map
            map.easeCamera(CameraUpdateFactory.newLatLngBounds(area.build(), padding, top, padding * 2, bottom),
                    EASE_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not show the whole route", e);
        }
    }

    /**
     * The car is drawn at the map's centre while following; the map view reaches below the screen so its centre
     * (the car) sits CAR_DOWN_SCREEN of the way down.
     */
    private void placeCar() {
        int height = root.getHeight();
        if (height == 0) return;
        int carY = Math.round(height * CAR_DOWN_SCREEN);
        ViewGroup.MarginLayoutParams frame = (ViewGroup.MarginLayoutParams) mapView.getLayoutParams();
        frame.topMargin = 0;
        frame.bottomMargin = height - 2 * carY;
        mapView.requestLayout();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SEARCH) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        framedRoutes = null;
        TripGuide.start(this, data.getStringExtra(PlaceSearchActivity.EXTRA_NAME),
                data.getDoubleExtra(PlaceSearchActivity.EXTRA_LAT, 0),
                data.getDoubleExtra(PlaceSearchActivity.EXTRA_LON, 0));
        showWholeRoute();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView == null) return;
        mapView.onResume();
        TripGuide.watch(tripWatcher);
        showTrip();
    }

    @Override
    protected void onPause() {
        TripGuide.unwatch(tripWatcher);
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mapView != null) mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }
}
