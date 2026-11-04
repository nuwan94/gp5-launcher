package com.gp5.launcher.core.model

enum class RailSide { Left, Right }

/** Home car stage: live Filament 3D, the Blender turntable, or pick by GPU (Auto). */
enum class CarRenderer { Auto, Live, Turntable }

/** Which maps app the Home and Work buttons open. Auto: Google Maps, else Waze, else any geo app. */
enum class NavApp { Auto, GoogleMaps, Waze }

/** User settings that shape the whole UI (Settings > Display). Persisted in DataStore. */
data class LauncherSettings(
    val accentArgb: Long = ACCENT_RS_RED,
    val railSide: RailSide = RailSide.Left,
    val goldTrim: Boolean = true,
    val calmMotion: Boolean = false,
    val carRenderer: CarRenderer = CarRenderer.Auto,
    /** OBD adapter (ELM327 Wi-Fi). */
    val obdEnabled: Boolean = true,
    val obdAddress: String = "192.168.0.10:35000",
    /** Navigation shortcuts on Home: an address, place name or "lat,lng". Blank hides the button. */
    val navApp: NavApp = NavApp.Auto,
    val homePlace: String = "",
    val workPlace: String = "",
    /** Odometer (km), set once in Settings; the trip recorder adds every trip's distance. 0 = not set. */
    val odometerKm: Double = 0.0,
    /** A trip ends after this long stopped (minutes), or when the car switches off. */
    val tripEndMinutes: Int = 10,
    /** Trips older than this are deleted unless starred. 0 keeps everything. */
    val keepTripsDays: Int = 90,
    /** Engine oil: odometer at the last change and the change interval (km). */
    val oilChangedAtKm: Double = 0.0,
    val oilIntervalKm: Int = 5000,
    /** Headlights follow the head unit (night mode / ILL wire, or a learned setting); off = Home button only. */
    val lightsFromUnit: Boolean = true,
    /** "" = system night mode; else "system|global|secure:key=onValue" found by the light finder. */
    val lightSignal: String = "",
) {
    companion object {
        const val ACCENT_RS_RED = 0xFFE24B4A
        val ACCENTS = listOf(
            0xFFE24B4A to "RS red",
            0xFFEF9F27 to "Amber",
            0xFF4EA8F2 to "Ice blue",
            0xFF8FD14F to "Hybrid green",
            0xFFC9CDD2 to "Silver",
        )
    }
}
