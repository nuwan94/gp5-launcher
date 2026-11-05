package com.gp5.launcher.core.model

enum class RailSide { Left, Right }

/** Home car stage: live Filament 3D, the Blender turntable, or pick by GPU (Auto). */
enum class CarRenderer { Auto, Live, Turntable }

/** Auto: day theme until the headlights come on (or by the clock without a light signal). */
enum class ThemeMode { Auto, Day, Night }

/** Fuel economy unit (DESIGN.md Q4). Values are kept in km/L and converted for display. */
enum class EconomyUnit(val label: String) {
    KmPerL("km/L"),
    LPer100Km("L/100 km");

    fun of(kmPerL: Float): Float = if (this == KmPerL) kmPerL else 100f / kmPerL
    fun text(kmPerL: Float): String = "%.1f".format(of(kmPerL))
}

/** Which maps app the Home and Work buttons open. Auto: Google Maps, else Waze, else any geo app. */
enum class NavApp { Auto, GoogleMaps, Waze }

/** User settings that shape the whole UI (Settings > Display). Persisted in DataStore. */
data class LauncherSettings(
    val accentArgb: Long = ACCENT_RS_RED,
    val railSide: RailSide = RailSide.Left,
    val goldTrim: Boolean = true,
    val calmMotion: Boolean = false,
    val carRenderer: CarRenderer = CarRenderer.Auto,
    val theme: ThemeMode = ThemeMode.Auto,
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
    val economyUnit: EconomyUnit = EconomyUnit.KmPerL,
    /** Welcome greeting when the head unit starts or wakes (ignition on). */
    val welcome: Boolean = true,
    val driverName: String = DEFAULT_NAME,
    /** Speak the greeting (text-to-speech) and play the welcome chime. */
    val welcomeVoice: Boolean = true,
    val welcomeMusic: Boolean = true,
    /** Cabin announcements left out of the random pick (indexes into [ANNOUNCEMENTS]); new lines start on. */
    val announceOff: Set<Int> = emptySet(),
) {
    companion object {
        const val ACCENT_RS_RED = 0xFFE24B4A
        const val DEFAULT_NAME = "User"
        /** The recorded cabin announcements (res/raw/announce_{morning,afternoon}_N), after "Good morning/afternoon". */
        val ANNOUNCEMENTS = listOf(
            "Classic captain" to "…and welcome aboard your Honda Fit. Please fasten your seatbelt, and enjoy the ride.",
            "Pre-departure checklist" to "…and welcome aboard. Doors closed. Seatbelts fastened. Systems ready. Your Honda Fit is cleared for departure.",
            "Hybrid" to "…and welcome aboard your Honda Fit Hybrid. Electric systems are online, and we're ready for a smooth, quiet journey.",
            "Cabin crew" to "…ladies and gentlemen, and welcome aboard. Please make sure your seatbelt is fastened, and your phone is connected.",
            "Short and premium" to "…and welcome aboard. Your Honda Fit is ready when you are.",
            "Your captain" to "…this is your captain speaking. Welcome aboard flight GP5. Fuel efficient, smooth, and on schedule. Seatbelts on, and let's go.",
            "Seatbelt reminder" to "…welcome aboard your Honda Fit. Please fasten your seatbelt.",
        )
        val ACCENTS = listOf(
            0xFFE24B4A to "RS red",
            0xFFEF9F27 to "Amber",
            0xFF4EA8F2 to "Ice blue",
            0xFF8FD14F to "Hybrid green",
            0xFFC9CDD2 to "Silver",
        )
    }
}
