# Car Dashboard

A car-style Home screen for Android head units (and phones): a live map, music, your apps, and navigation that also
works **without internet** from map areas you download.

Made for the many Android head units sold for cars (a full Android tablet behind the dashboard, Android 8 or newer).
It is not an Android Auto or Android Automotive OS app.

## Features

- **Home dashboard**: clock and greeting, a live map card, a Now playing card (album art, controls, the background
  tinted from the art), big tiles for your apps, and All apps. Hold a tile to remove it; add apps from All apps.
- **Live map**: follows the car with a navigation arrow, zooms out with speed, shows your speed. Map styles: Dark, Dark
  blue, Light, White, or off.
- **Place search**: online (Photon) and **offline** from the downloaded maps: towns and villages, petrol pumps,
  temples, hospitals, hotels and more by name or kind ("petrol", "temple").
- **Navigation**: the whole route and other ways to choose from, then turn by turn: next-turn card, voice guidance
  (mute with the speaker button), the road driven greyed out, rerouting, arrival. Home keeps the trip running on the
  dashboard's map.
- **Offline routes**: from the roads in the downloaded maps, when there is no internet.
- **Map downloads**: pick a square on a map (up to about 40 × 40 km each); see the downloaded squares, delete them.
  On Android 10 and older they are kept in a public folder (`CarDashboard/maps`) and survive reinstalling the app.
- **Speed camera alerts**: offline, from OpenStreetMap, your own marked cameras, and bundled radar zones; a spoken
  warning before each.
- **Startup music**: the music app plays (or shows its song) when the car starts.
- **AutoPlay**: CarPlay and Android Auto from a Home tile, through the separate [CarLink](https://github.com/F-e-n-y-x/CarLink)
  app. Speed camera alerts keep running over it; our navigation's voice is quiet while a phone is projected.

## Install

1. Download the latest APK, [car-dashboard.apk](https://github.com/prajwal4443/car-dashboard/releases/latest/download/car-dashboard.apk)
   (every version: [Releases](https://github.com/prajwal4443/car-dashboard/releases)), and install it on the head
   unit. Each version installs over the one before, keeping your maps and settings.
2. Pick **Car Dashboard** as the Home app when Android asks (or later in Dashboard settings).
3. Allow **location** (the map, navigation, cameras) and, for Now playing and the next-turn banner, **notification
   access** (Dashboard settings shows the way).
4. For offline use: **Dashboard settings → Download map area**, while online.
5. For AutoPlay (optional): tap **AutoPlay** on Home. It downloads the latest [CarLink](https://github.com/F-e-n-y-x/CarLink)
   from CarLink's own GitHub release, checks its checksum and signing key, and Android asks you to confirm the
   install (the first time, allow Car Dashboard to install apps). Go through CarLink's setup. In CarLink's settings, turn **Start with the car** off: with no phone connected, the car then starts on
   this dashboard, and CarLink comes to the front by itself when a phone connects (Bluetooth or USB). On a tablet,
   which phones don't reconnect to by Bluetooth, tap AutoPlay once per drive. Hide the tile in Dashboard settings.

## Build

With Docker (nothing else to install):

```
tools/build.sh testReleaseUnitTest lintRelease assembleRelease
```

Or with a local Android SDK (JDK 17, SDK 34): `./gradlew assembleRelease`. The APK is
`app/build/outputs/apk/release/app-release.apk`. ARM only (`armeabi-v7a`, `arm64-v8a`), Android 8.0 (API 26) or newer.

## How it is put together

All code is in `app/src/main/java/com/prajwal/cardashboard`:

| Area | Classes |
|---|---|
| Home screen | `DashboardActivity`, `DashboardAppsActivity`, `DashboardSettingsActivity`, `HomeTiles`, `NowPlaying`, `DashboardWidget`, `CarStartup` |
| Map | `DashboardMap`, `MapZoom`, `RouteLayers` |
| Map downloads | `MapArea`, `MapDownloads`, `MapAreaPickerActivity`, `SquareFrame` |
| Search | `PlaceSearchActivity`, `PlaceIndex`, `OnlineSearch`, `VectorTileReader`, `PlaceIndexService` |
| Navigation | `NavigationActivity`, `TripGuide`, `DrivingRoute`, `RouteFinder` (online), `OfflineRouter` + `RoadGraph` (offline), `TurnAnnouncer`, `VoiceGuide` |
| Speed cameras | `CameraAlertService`, `CameraAlerts`, `CameraStore` |
| AutoPlay | `AutoPlay` (opens CarLink; `MusicListener` notices a projected phone from CarLink's notification) |

Pure logic (routing, search, turns, zoom, camera alerts) is plain Java with unit tests in `app/src/test`, several on
real map tiles (`app/src/test/resources`).

Offline search and routing read the downloaded vector tiles from MapLibre's offline database. That happens in a
separate process (`PlaceIndexService`), because MapLibre bundles its own SQLite and two copies must not open one file
in the same process.

## Limits

- Offline routes know road kinds, one-way streets and names, but not turn restrictions or traffic; the online router
  is better where there is internet.
- House addresses are rarely in OpenStreetMap for India: search finds places, roads and points of interest.
- The online services (Photon, FOSSGIS OSRM, OpenFreeMap) are free community services: use them fairly.

## Contributing

Issues and pull requests are welcome. Keep `tools/build.sh testReleaseUnitTest lintRelease assembleRelease` passing
(zero lint errors), and add a unit test for new logic.

## License

MIT, see `LICENSE`. Third-party code, fonts, icons and data: see `NOTICE.md`.
