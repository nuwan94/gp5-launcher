package com.gp5.launcher.core.model

/** i-DCD drive mode, inferred from OBD (DESIGN.md section 6.4). */
enum class DriveMode { Ready, Ev, Engine, Assist, Regen, Charging }

/** Live car state. Produced by the OBD layer, collected by every screen as a StateFlow. */
data class VehicleState(
    val connected: Boolean = false,
    val speedKmh: Float = 0f,
    val rpm: Int = 0,
    val mode: DriveMode = DriveMode.Ready,
    val socPercent: Float = 62f,
    val coolantC: Float? = null,
    val batteryVolts: Float? = null,
    val avgKmPerL: Float? = null,
) {
    /** Ambient motion pauses and some settings lock above this speed (DESIGN.md section 8). */
    val isDriving: Boolean get() = speedKmh > DRIVING_KMH

    companion object {
        const val DRIVING_KMH = 5f
    }
}
