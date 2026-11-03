package com.gp5.launcher.core.model

enum class RailSide { Left, Right }

/** User settings that shape the whole UI (Settings > Display). Persisted in DataStore. */
data class LauncherSettings(
    val accentArgb: Long = ACCENT_RS_RED,
    val railSide: RailSide = RailSide.Left,
    val goldTrim: Boolean = true,
    val calmMotion: Boolean = false,
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
