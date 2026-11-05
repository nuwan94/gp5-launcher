package com.gp5.launcher.car3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the stage camera is, so the road can be drawn in the same perspective as the car.
 * Car frame: x = the car's left, y = up, z = the car's forward, metres, origin under the car.
 */
internal class RoadCam(
    val yawDeg: Float, val dist: Float, val height: Float, val targetY: Float,
    /** Focal length and principal point in canvas pixels. */
    val focalPx: Float, val cx: Float, val cy: Float,
)

/**
 * The road under the car while driving (drawn behind the car, which sits on it): a left-hand lane
 * (Sri Lanka) with the kerb line on the car's left, a dashed centre line on its right and the far
 * edge beyond, scrolling past at the real speed and fading into the distance. Fades in and out
 * with driving; nothing runs while parked.
 */
@Composable
internal fun RoadLayer(scene: CarScene, camera: DrawScope.() -> RoadCam, modifier: Modifier = Modifier) {
    val s by rememberUpdatedState(scene)
    var show by remember { mutableFloatStateOf(if (scene.driving) 1f else 0f) }
    var travel by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableIntStateOf(0) }       // redraws for camera moves
    LaunchedEffect(Unit) {
        while (isActive) {
            snapshotFlow { s.driving }.first { it }        // sleep while parked
            var last = 0L
            while (isActive && (s.driving || show > 0f)) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                    last = now
                    val goal = if (s.driving) 1f else 0f
                    show = (show + (goal - show) * (1f - exp(-dt / 0.45f))).let { if (abs(it - goal) < 0.01f) goal else it }
                    travel = (travel + s.speedKmh / 3.6f * dt) % DASH_PERIOD
                    frame++
                }
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        frame
        if (show > 0f) drawRoad(camera(), travel, show)
    }
}

private const val DASH_PERIOD = 9f      // 3 m dash, 6 m gap
private const val DASH_LEN = 3f
private const val KERB = 1.9f           // kerb line, on the car's left
private const val CENTRE = -1.7f        // dashed centre line, on its right
private const val FAR_EDGE = -5.3f
private const val LINE_W = 0.13f
private const val NEAR = -14f
private const val FAR = 70f

private val Asphalt = Color(0xFF242424)
private val Paint = Color(0xFFD8D8D8)

/** 1 near the car, 0 far away (and a little behind). */
private fun fade(z: Float): Float = when {
    z < 0f -> (1f - (-z - 6f) / 8f).coerceIn(0f, 1f)
    else -> (1f - (z - 14f) / 40f).coerceIn(0f, 1f)
}

private fun DrawScope.drawRoad(c: RoadCam, travel: Float, show: Float) {
    val r = Math.toRadians(c.yawDeg.toDouble())
    val ex = (sin(r) * c.dist).toFloat(); val ey = c.height; val ez = (cos(r) * c.dist).toFloat()
    // camera basis (as Filament's lookAt: right = forward x up)
    var fx = -ex; var fy = c.targetY - ey; var fz = -ez
    val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
    var rx = -fz; var rz = fx
    val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
    val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy

    fun proj(x: Float, z: Float): Offset? {
        val vx = x - ex; val vy = -ey; val vz = z - ez
        val depth = vx * fx + vy * fy + vz * fz
        if (depth < 0.5f) return null
        return Offset(
            c.cx + c.focalPx * (vx * rx + vz * rz) / depth,
            c.cy - c.focalPx * (vx * ux + vy * uy + vz * uz) / depth,
        )
    }

    fun quad(x0: Float, x1: Float, z0: Float, z1: Float, color: Color) {
        val a = proj(x0, z0) ?: return; val b = proj(x1, z0) ?: return
        val d = proj(x1, z1) ?: return; val e = proj(x0, z1) ?: return
        val p = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); lineTo(e.x, e.y); close() }
        drawPath(p, color)
    }

    // asphalt: one polygon along both edges (points behind the camera dropped), faded by screen height
    val left = ArrayList<Offset>(); val right = ArrayList<Offset>()
    var z = NEAR
    while (z <= FAR) {
        proj(KERB + 0.3f, z)?.let { left += it }
        proj(FAR_EDGE - 0.3f, z)?.let { right += it }
        z += 2f
    }
    if (left.size >= 2 && right.size >= 2) {
        val p = Path().apply {
            moveTo(left[0].x, left[0].y); for (o in left.drop(1)) lineTo(o.x, o.y)
            for (o in right.asReversed()) lineTo(o.x, o.y); close()
        }
        val horizon = (proj(0f, FAR) ?: left.last()).y
        val nearY = (proj(0f, 0f) ?: left.first()).y
        drawPath(p, Brush.verticalGradient(
            0f to Color.Transparent, 0.35f to Asphalt.copy(alpha = 0.55f * show), 1f to Asphalt.copy(alpha = 0.9f * show),
            startY = horizon, endY = nearY + (nearY - horizon) * 0.4f,
        ))
    }

    // solid edge lines in 2 m pieces, so each piece can fade with distance
    z = NEAR
    while (z < FAR) {
        val a = fade(z + 1f) * show
        if (a > 0.01f) {
            quad(KERB, KERB + LINE_W, z, z + 2f, Paint.copy(alpha = 0.55f * a))
            quad(FAR_EDGE, FAR_EDGE + LINE_W, z, z + 2f, Paint.copy(alpha = 0.35f * a))
        }
        z += 2f
    }
    // dashed centre line, moving towards the camera as the car drives
    var d = NEAR - travel + DASH_PERIOD
    while (d < FAR) {
        val a = fade(d + DASH_LEN / 2) * show
        if (a > 0.01f) quad(CENTRE, CENTRE + LINE_W, d, d + DASH_LEN, Paint.copy(alpha = 0.7f * a))
        d += DASH_PERIOD
    }
}
