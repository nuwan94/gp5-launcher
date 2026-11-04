# GP5 Launcher (Android)

Native launcher for the Honda Fit GP5 head unit (1024 × 600; Android 7 and newer, typically 8.1–13).
The HTML design prototypes and the design spec are kept outside this repository.

## Stack
Kotlin 2.4 · Jetpack Compose (BOM 2026.09) · Coroutines/Flow · Hilt 2.60 · DataStore · AGP 9.5 (built-in Kotlin) · Gradle 9.8.
Planned: Room (trips, M5), Filament/SceneView (3D car, M4), MapLibre (optional mini map).

| | |
|---|---|
| `minSdk` | 24 (Android 7): many head units sold as "Android 10" really run 8.1 or 9 |
| `targetSdk` | 33: head units run 10–13; avoids the API 34+ foreground-service-type rules and API 35 forced edge-to-edge until handled on purpose |
| `compileSdk` | 37: required by current AndroidX (Compose 1.12, Hilt 1.4) |

## Modules
```
:app                 HOME activity (MainActivity), Hilt app, LauncherRoot: rail + status bar + screens
:core:design         tokens (tokens.css), theme, gold trim (gold.css), Rail, StatusBar, GpCard, ModePill, Chip
:core:model          VehicleState, DriveMode, MediaState, LauncherSettings
:core:data           SettingsRepository (DataStore "gp5.settings"), AppsRepository (LauncherApps + "gp5.apps")
:obd                 VehicleStateRepository; SimulatedVehicleRepository until the ELM327 transport (M2)
:media               MediaRepository; stub until MediaSession + NotificationListener (M3)
:feature:home        Layout B frame with live speed / mode / battery and now playing
:feature:settings    Display > Look: accent, rail side, gold trim, calm motion (live on every screen)
:feature:vehicle     placeholder (M2)
:feature:trips       placeholder (M5)
:feature:apps        drawer: installed apps, search, categories, 6 x 3 pages, dock / hide / app info
```

## Build
Needs JDK 17+ (the machine has `~/.jdks/openjdk-22.0.2`) and the Android SDK (`local.properties`).
```bash
export JAVA_HOME="$USERPROFILE/.jdks/openjdk-22.0.2"
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # onto a connected head unit or emulator
```
Emulator profile `gp5_headunit` (1024 × 600, 160 dpi, landscape) is set up in `~/.android/avd/`.

## As the launcher
After install, press Home and choose **GP5 Launcher → Always** (or Settings › Apps › Default apps › Home app).
On the stage card, tap to start or stop the demo drive: above 5 km/h the gold-trim and logo animations pause.

## Licences
The 3D car model (added in M4) is CC BY-NC-SA 4.0: credit, non-commercial, share-alike.
The Honda emblem is a trademark, used for this personal launcher only. Icons: Tabler (MIT).
