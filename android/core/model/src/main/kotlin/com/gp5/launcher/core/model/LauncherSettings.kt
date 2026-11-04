package com.gp5.launcher.core.model

enum class RailSide { Left, Right }

/** Home car stage: live Filament 3D, the Blender turntable, or pick by GPU (Auto). */
enum class CarRenderer { Auto, Live, Turntable }

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
