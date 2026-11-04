# GP5 Launcher (Android)

Native launcher for the Honda Fit GP5 head unit (1024 × 600; Android 7 and newer, typically 8.1–13).
The HTML design prototypes and the design spec are kept outside this repository.

## Stack
Kotlin 2.4 · Jetpack Compose (BOM 2026.09) · Coroutines/Flow · Hilt 2.60 · DataStore · AGP 9.5 (built-in Kotlin) · Gradle 9.8.
Filament 1.77 (gltfio, filament-utils) for the 3D car. Planned: Room (trips, M5), MapLibre (optional mini map).

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
:obd                 VehicleStateRepository: ELM327 Wi-Fi adapter (TCP, bound to the adapter's Wi-Fi network)
:media               Now playing from any app's MediaSession (needs notification access)
:car3d               Home car stage: live Filament 3D (OpenGL ES 3) or Blender turntable (OpenGL ES 2)
:feature:home        Layout B frame with live speed / mode / battery and now playing
:feature:settings    Display > Look: accent, rail side, gold trim, calm motion (live on every screen)
:feature:vehicle     live gauges, i-DCD power flow, sensor tiles, read / clear trouble codes
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
Drag the car on the stage to turn it. Above 5 km/h (from OBD) the gold-trim and logo animations pause.

## OBD (ELM327 Wi-Fi)
1. Plug the adapter in and switch the car on.
2. On the head unit, join the adapter's Wi-Fi (often `WiFi_OBDII`); when Android says it has no internet, choose to stay connected.
3. Settings › OBD adapter shows the link and live data. The default address is `192.168.0.10:35000`.

Read: speed and rpm (~5 Hz), coolant, 12 V battery, fuel level, intake and outside air, hybrid battery (PID 5B, if the car answers). Only supported PIDs are polled; values the car does not report stay hidden. EV / Engine / Regen / Charging are inferred from speed, rpm and acceleration.
Vehicle › Diagnostics reads stored (mode 03) and pending (mode 07) trouble codes; Clear (mode 04) is offered only when stopped.
Without the car: run a fake adapter on the PC and set the address to `10.0.2.2:35000` in the emulator.

## Now playing
Home shows the track, art and controls of whatever app is playing (Spotify, YouTube Music, radio). Android only shares other apps' media sessions with apps that have notification access: tap **Allow** on the Home music card and switch on *GP5 Launcher now playing*.

## 3D car
Home picks the renderer by GPU (Settings › Car › 3D view: Auto / Live 3D / Turntable):
- **Live 3D** (OpenGL ES 3): Filament, studio IBL from `studio.hdr`, 30 fps cap, slow orbit when parked, rear three-quarter chase when driving, wheels spin with speed, lamps fade on. Falls back to the turntable if it fails or drops under ~14 fps for 3 s.
- **Turntable** (OpenGL ES 2 units such as Mali-400 / PowerVR SGX): 72 Cycles-rendered angles, lamps off and on, cross-faded; drag to turn.

The assets are built in Blender 5.2 by local scripts (not in this repo: they need the source model): `build_android.py` makes `fit-gp5.glb` (78 meshes, 100k triangles, metres, front = +Z, nodes `GP5_Wheel_*`, materials `GP5_Head_*` / `GP5_Tail_*`), `render_stage.py` renders `studio.hdr`, the floor shadow and `turntable/`.

## Licences
The 3D car (`car3d/src/main/assets/car/`) is a modified version of "2016 Honda Fit DBA-GK3" by Ddiaz Design (Sketchfab), CC BY-NC-SA 4.0: credit, non-commercial, share-alike. Details in `car3d/src/main/assets/car/LICENSE.txt`.
The Honda emblem is a trademark, used for this personal launcher only. Icons: Tabler (MIT).
