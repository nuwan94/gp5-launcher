package com.gp5.launcher.car3d

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** What the car stage shows. Everything else (camera, wheel angle, lamp fade) is animated inside. */
data class CarScene(
    val speedKmh: Float = 0f,
    val lightsOn: Boolean = false,
    /** Motion allowed (calm motion off): the camera swings between the parked and chase views instead of cutting. */
    val ambient: Boolean = true,
    val connected: Boolean = true,
) {
    val driving: Boolean get() = speedKmh > 5f
}

internal object CarCamera {
    /** Yaw convention shared with the Blender turntable: 0 = straight ahead of the car, 90 = its left side. */
    const val HERO_YAW = 35f          // parked hero pose, front three-quarter
    const val CHASE_YAW = 170f        // driving, from behind (slightly off-centre, on a turntable frame)
    const val TURNTABLE_FRAMES = 72
}

/** Live 3D needs OpenGL ES 3.0. Many budget head units (Mali-400, PowerVR SGX) only have 2.0. */
fun supportsLive3d(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
}

/** Session-wide health of the live renderer: once it fails or misses the frame budget, use the turntable. */
object LiveHealth {
    @Volatile var failed: String? = null

    /** Which renderer Home is showing, for Settings. */
    val active = MutableStateFlow("")
}
