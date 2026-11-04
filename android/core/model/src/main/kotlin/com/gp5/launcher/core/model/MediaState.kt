package com.gp5.launcher.core.model

/** Now playing, from the active MediaSession (DESIGN.md section 6.1). */
data class MediaState(
    /** False until the user allows notification access (needed to see other apps' media sessions). */
    val hasAccess: Boolean = false,
    /** Package of the app that owns the session, or "" when nothing is playing. */
    val packageName: String = "",
    val title: String = "",
    val artist: String = "",
    /** App label of the session owner, e.g. "Spotify". */
    val source: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** SystemClock.elapsedRealtime() when [positionMs] was reported; the UI extrapolates while playing. */
    val positionAt: Long = 0,
    val speed: Float = 1f,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
) {
    val active: Boolean get() = packageName.isNotEmpty() && title.isNotEmpty()

    fun positionNow(nowElapsed: Long): Long {
        val p = if (playing) positionMs + ((nowElapsed - positionAt) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}
