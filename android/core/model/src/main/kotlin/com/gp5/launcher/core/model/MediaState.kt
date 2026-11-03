package com.gp5.launcher.core.model

/** Now playing, from the active MediaSession (DESIGN.md section 6.1). */
data class MediaState(
    val title: String = "",
    val artist: String = "",
    val source: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)
