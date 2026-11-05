package com.gp5.launcher.core.data

import kotlinx.coroutines.flow.StateFlow

/** The welcome's sound, for Settings: play one cabin announcement to hear it. */
interface WelcomeAudio {
    /** Index into LauncherSettings.ANNOUNCEMENTS being previewed, or null. */
    val previewing: StateFlow<Int?>
    /** Plays announcement [index] (the version for the time of day); again to stop. */
    fun preview(index: Int)
    fun stopPreview()
}
