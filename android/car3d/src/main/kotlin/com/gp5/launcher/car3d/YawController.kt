package com.gp5.launcher.car3d

import kotlin.math.abs
import kotlin.math.exp

/**
 * Camera yaw for both car views. Not touchable: parked it rests on the front three-quarter
 * ([CarCamera.HERO_YAW]); driving it swings round the car's left side to the chase view from behind
 * ([CarCamera.CHASE_YAW]) in about 2 s, and back slowly (about 6 s) once the car has stopped.
 * [calm] snaps instead of swinging.
 * [angle] is 0..360.
 */
internal class YawController(initial: Float) {
    private var yaw = initial

    val angle: Float get() = ((yaw % 360f) + 360f) % 360f

    /** Advances by [dt] seconds. Returns true while the yaw is changing. */
    fun step(dt: Float, driving: Boolean, calm: Boolean): Boolean {
        val goal = if (driving) CarCamera.CHASE_YAW else CarCamera.HERO_YAW
        var d = (goal - angle) % 360f
        if (d > 180f) d -= 360f; if (d < -180f) d += 360f
        if (d == 0f) return false
        yaw += if (calm || abs(d) < 0.05f) d else d * (1f - exp(-dt / if (driving) 0.7f else 2.0f))
        return true
    }
}
