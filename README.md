# Car Dashboard

A car-style Home screen for Android head units (and phones): the clock, music and your apps, in big touch tiles.

Made for the many Android head units sold for cars (a full Android tablet behind the dashboard, Android 8 or newer).
It is not an Android Auto or Android Automotive OS app.

## Features

- **Home dashboard**: clock and greeting, a Maps card, a Now playing card (album art, controls, the background
  tinted from the art), big tiles for your apps, and All apps. Hold a tile to remove it; add apps from All apps.
- **Startup music**: the music app plays (or shows its song) when the car starts.

## Install

1. Download the APK from **Releases** (or build it, below) and install it on the head unit.
2. Pick **Car Dashboard** as the Home app when Android asks (or later in Dashboard settings).
3. For Now playing, allow **notification access** (Dashboard settings shows the way).

## Build

With Docker (nothing else to install):

```
tools/build.sh testReleaseUnitTest lintRelease assembleRelease
```

Or with a local Android SDK (JDK 17, SDK 34): `./gradlew assembleRelease`. The APK is
`app/build/outputs/apk/release/app-release.apk`. Android 8.0 (API 26) or newer.

## How it is put together

All code is in `app/src/main/java/com/prajwal/cardashboard`:

| Area | Classes |
|---|---|
| Home screen | `DashboardActivity`, `DashboardAppsActivity`, `DashboardSettingsActivity`, `HomeTiles`, `NowPlaying`, `DashboardWidget`, `CarStartup` |

Pure logic (tiles, the music card) is plain Java with unit tests in `app/src/test`.

## Limits

- OpenFreeMap are free community services: use them fairly.

## Contributing

Issues and pull requests are welcome. Keep `tools/build.sh testReleaseUnitTest lintRelease assembleRelease` passing
(zero lint errors), and add a unit test for new logic.

## License

MIT, see `LICENSE`. Third-party code, fonts, icons and data: see `NOTICE.md`.
