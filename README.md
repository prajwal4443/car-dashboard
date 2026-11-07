# Car Dashboard

A car-style Home screen for Android head units (and phones): a live map, music, your apps, and speed camera alerts, with maps
that work **without internet** from areas you download.

Made for the many Android head units sold for cars (a full Android tablet behind the dashboard, Android 8 or newer).
It is not an Android Auto or Android Automotive OS app.

## Features

- **Home dashboard**: clock and greeting, a live map card, a Now playing card (album art, controls, the background
  tinted from the art), big tiles for your apps, and All apps. Hold a tile to remove it; add apps from All apps.
- **Live map**: follows the car with a navigation arrow, zooms out with speed, shows your speed. Map styles: Dark, Dark
  blue, Light, White, or off.
- **Map downloads**: pick a square on a map (up to about 40 × 40 km each); see the downloaded squares, delete them.
  On Android 10 and older they are kept in a public folder (`CarDashboard/maps`) and survive reinstalling the app.
- **Speed camera alerts**: offline, from OpenStreetMap, your own marked cameras, and bundled radar zones; a spoken
  warning before each.
- **Startup music**: the music app plays (or shows its song) when the car starts.

## Install

1. Download the APK from **Releases** (or build it, below) and install it on the head unit.
2. Pick **Car Dashboard** as the Home app when Android asks (or later in Dashboard settings).
3. Allow **location** (the map, navigation, cameras) and, for Now playing and the next-turn banner, **notification
   access** (Dashboard settings shows the way).
4. For offline use: **Dashboard settings → Download map area**, while online.

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
| Map | `DashboardMap`, `MapZoom` |
| Map downloads | `MapArea`, `MapDownloads`, `MapAreaPickerActivity`, `SquareFrame` |
| Speed cameras | `CameraAlertService`, `CameraAlerts`, `CameraStore` |

Pure logic (zoom, camera alerts, map areas) is plain Java with unit tests in `app/src/test`.

## Limits

- OpenFreeMap are free community services: use them fairly.

## Contributing

Issues and pull requests are welcome. Keep `tools/build.sh testReleaseUnitTest lintRelease assembleRelease` passing
(zero lint errors), and add a unit test for new logic.

## License

MIT, see `LICENSE`. Third-party code, fonts, icons and data: see `NOTICE.md`.
