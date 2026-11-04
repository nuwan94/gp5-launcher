package com.gp5.launcher.car3d

import kotlin.math.abs
import kotlin.math.exp

/**
 * Camera yaw for both car views: follows the finger closely, keeps a little momentum after release,
 * then (4 s later) goes back to the automatic motion: slow orbit when parked, chase angle when driving.
 * The yaw is kept unwrapped internally; [angle] is 0..360.
 */
internal class YawController(initial: Float) {
    private var yaw = initial
    private var target = initial
    private var velocity = 0f                 // deg/s, from the drag
    private var touching = false
    private var lastTouchNs = 0L
    private var holdUntilNs = 0L

    val angle: Float get() = ((yaw % 360f) + 360f) % 360f

    /** [dxFraction] = horizontal finger movement as a fraction of the stage width. */
    fun drag(dxFraction: Float, nowNs: Long) {
        val d = -dxFraction * DEG_PER_WIDTH
        if (!touching) target = yaw
        target += d
        if (lastTouchNs != 0L) {
            val dt = (nowNs - lastTouchNs) / 1e9f
            if (dt in 0.001f..0.1f) velocity = velocity * 0.4f + (d / dt) * 0.6f
        }
        lastTouchNs = nowNs
        touching = true
        holdUntilNs = nowNs + HOLD_NS
    }

    fun release(nowNs: Long) {
        touching = false
        if (nowNs - lastTouchNs > 120_000_000L) velocity = 0f         // finger rested before lifting: no fling
        lastTouchNs = 0L
        velocity = velocity.coerceIn(-MAX_FLING, MAX_FLING)
        yaw = target                                                  // land where the finger left it
        holdUntilNs = nowNs + HOLD_NS
    }

    /** Advances by [dt] seconds. Returns true while the yaw is changing. */
    fun step(dt: Float, nowNs: Long, driving: Boolean, ambient: Boolean): Boolean {
        val before = yaw
        when {
            touching -> yaw += (target - yaw) * (1f - exp(-dt / 0.045f))
            abs(velocity) > 3f -> {
                yaw += velocity * dt
                velocity *= exp(-dt / 0.35f)
                holdUntilNs = nowNs + HOLD_NS
            }
            else -> {
                velocity = 0f
                if (nowNs > holdUntilNs) {
                    if (driving) {
                        var d = (CarCamera.CHASE_YAW - angle) % 360f
                        if (d > 180f) d -= 360f; if (d < -180f) d += 360f
                        if (abs(d) > 0.05f) yaw += d * (1f - exp(-dt / 1.1f))
                    } else if (ambient) {
                        yaw += 360f / CarCamera.ORBIT_SECONDS * dt
                    }
                }
            }
        }
        return abs(yaw - before) > 0.002f
    }

    private companion object {
        const val DEG_PER_WIDTH = 220f
        const val MAX_FLING = 540f
        const val HOLD_NS = 4_000_000_000L
    }
}
