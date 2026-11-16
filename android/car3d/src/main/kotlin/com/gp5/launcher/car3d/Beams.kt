package com.gp5.launcher.car3d

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import com.gp5.launcher.core.design.Gp5Palette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Projects points in the car frame (x = car's left, y = up, z = forward, metres) like the stage camera. */
internal class Projector(c: RoadCam) {
    private val ex: Float; private val ey = c.height; private val ez: Float
    private val fx: Float; private val fy: Float; private val fz: Float
    private val rx: Float; private val rz: Float
    private val ux: Float; private val uy: Float; private val uz: Float
    private val focal = c.focalPx; private val cx = c.cx; private val cy = c.cy
    /** The camera is in front of the car (its front shows): beams come towards it. */
    val facingFront: Boolean

    init {
        val r = Math.toRadians(c.yawDeg.toDouble())
        ex = (sin(r) * c.dist).toFloat(); ez = (cos(r) * c.dist).toFloat()
        var a = -ex; var b = c.targetY - ey; var d = -ez
        val l = sqrt(a * a + b * b + d * d); a /= l; b /= l; d /= l
        fx = a; fy = b; fz = d
        var px = -d; var pz = a
        val pl = sqrt(px * px + pz * pz); px /= pl; pz /= pl
        rx = px; rz = pz
        ux = -rz * fy; uy = rz * fx - rx * fz; uz = rx * fy
        facingFront = ez > 0.5f
    }

    fun proj(x: Float, y: Float, z: Float): Offset? {
        val vx = x - ex; val vy = y - ey; val vz = z - ez
        val depth = vx * fx + vy * fy + vz * fz
        if (depth < 0.5f) return null
        return Offset(cx + focal * (vx * rx + vz * rz) / depth, cy - focal * (vx * ux + vy * uy + vz * uz) / depth)
    }
}

/**
 * Headlight beams from the car's own lamps (positions from fit-gp5.glb: the GP5_Head_* lenses at
 * x = ±0.62 m, 0.75 m up, 1.78 m forward): a pool of light on the floor ahead of the car and a soft
 * cone of light in the air from each lamp, in the stage camera's perspective, fading in and out with
 * the lamps. [front] = false draws what lies behind the car on screen (the pool, and the cones when
 * seen from behind), true the cones in front of the car (seen from its front). Fainter by day.
 */
@Composable
internal fun BeamLayer(scene: CarScene, camera: DrawScope.() -> RoadCam, front: Boolean, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(if (scene.lightsOn) 1f else 0f, tween(450), label = "beams")
    Canvas(modifier.fillMaxSize()) {
        if (level <= 0.001f) return@Canvas
        val p = Projector(camera())
        val k = level * (0.35f + 0.65f * Gp5Palette.night)
        if (!front) drawPool(p, k)
        if (front == p.facingFront) for (side in floatArrayOf(-1f, 1f)) drawCone(p, side, k)
    }
}

private val Beam = Color(0xFFFFF4DE)        // warm white (halogen / LED projector)
private const val LAMP_X = 0.62f
private const val LAMP_Y = 0.75f
private const val LAMP_Z = 1.78f

private fun DrawScope.drawPool(p: Projector, k: Float) {
    // light on the floor from the bumper down the road: nested ellipses (each a little brighter
    // towards the middle), so the falloff follows the pool's shape in perspective with no hard edge.
    // Shorter when the car faces the camera, so the pool never reaches past it.
    val reach = if (p.facingFront) 0.4f else 1f
    val rings = 30
    for (j in 0 until rings) {
        val f = 1f - j / rings.toFloat()                 // 1 = outer edge
        val half = 0.5f + 2.5f * f                        // half-width, m
        val len = (1.0f + 7.0f * f) * reach               // half-length, m
        val mid = 1.9f + len * 0.9f
        val path = Path(); var first = true
        for (i in 0..36) {
            val t = i / 36f * 2f * Math.PI.toFloat()
            val o = p.proj(sin(t) * half, 0f, mid + cos(t) * len) ?: continue
            if (first) { path.moveTo(o.x, o.y); first = false } else path.lineTo(o.x, o.y)
        }
        if (first) continue
        path.close()
        drawPath(path, Beam.copy(alpha = 0.016f * k))
    }
}

private fun DrawScope.drawCone(p: Projector, side: Float, k: Float) {
    // a cone of light in the air from the lens, aimed slightly down and out: nested hulls (soft sides),
    // each fading along its length from the lens, so it reads as lit haze rather than a shape
    val len = if (p.facingFront) 3.2f else 5f
    val ax = side * LAMP_X; val ay = LAMP_Y; val az = LAMP_Z + 0.05f
    val bx = side * (LAMP_X + 0.06f * len); val by = LAMP_Y - 0.1f * len; val bz = LAMP_Z + len
    val a = p.proj(ax, ay, az) ?: return
    val b = p.proj(bx, by, bz) ?: return
    val r = maxOf(hypot(b.x - a.x, b.y - a.y), 1f)
    val layers = 6
    for (j in 0 until layers) {
        val w = 0.35f + 0.65f * (j + 1) / layers           // this layer's width, relative
        val pts = ArrayList<Offset>(28)
        for (i in 0 until 14) {
            val t = i / 14f * 2f * Math.PI.toFloat()
            p.proj(ax + cos(t) * 0.06f, ay + sin(t) * 0.04f, az)?.let { pts += it }
            p.proj(bx + cos(t) * 0.22f * len * w, by + sin(t) * 0.11f * len * w, bz)?.let { pts += it }
        }
        val hull = hull(pts)
        if (hull.size < 3) continue
        val path = Path().apply { moveTo(hull[0].x, hull[0].y); for (o in hull.drop(1)) lineTo(o.x, o.y); close() }
        drawPath(path, Brush.radialGradient(
            0f to Beam.copy(alpha = 0.05f * k), 0.5f to Beam.copy(alpha = 0.02f * k), 1f to Beam.copy(alpha = 0f),
            center = a, radius = r * 1.1f,
        ))
    }
    // the lens glows where the beam leaves it
    val g = r * 0.07f + 4f
    drawCircle(Brush.radialGradient(listOf(Beam.copy(alpha = 0.5f * k), Beam.copy(alpha = 0f)), center = a, radius = g), g, a)
}

/** Convex hull (monotone chain), counter-clockwise. */
private fun hull(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val ps = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>(); val upper = ArrayList<Offset>()
    for (p in ps) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1); lower += p }
    for (p in ps.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1); upper += p }
    return lower.dropLast(1) + upper.dropLast(1)
}

/**
 * Night only: a soft studio spotlight on the backdrop behind the car. The car is black, so on the dark
 * stage its outline disappears; a lighter wash behind it gives it a clear silhouette. Fades with the theme.
 */
@Composable
internal fun BackdropLayer(camera: DrawScope.() -> RoadCam, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val n = Gp5Palette.night
        if (n <= 0.01f) return@Canvas
        val p = Projector(camera())
        val c = p.proj(0f, 0.9f, 0f) ?: return@Canvas
        val front = p.proj(0f, 0.9f, 2f) ?: c
        val back = p.proj(0f, 0.9f, -2f) ?: c
        val r = maxOf(hypot(front.x - back.x, front.y - back.y), size.minDimension * 0.5f) * 1.05f
        val centre = Offset(c.x, c.y - r * 0.12f)
        // an ellipse that fades out before every edge of the stage, so no edge of it is ever cut off
        val rx = minOf(r, centre.x, size.width - centre.x).coerceAtLeast(1f)
        val ry = minOf(r, centre.y, size.height - centre.y).coerceAtLeast(1f)
        scale(1f, ry / rx, centre) {
            drawCircle(Brush.radialGradient(
                0f to Spot.copy(alpha = 0.8f * n), 0.45f to Spot.copy(alpha = 0.35f * n), 1f to Spot.copy(alpha = 0f),
                center = centre, radius = rx,
            ), rx, centre)
        }
    }
}

/**
 * A soft contact shadow under the car, on the page itself (the 3D floor plane is hidden). Follows the
 * car's footprint as the camera swings round; fades as the road appears.
 */
@Composable
internal fun ShadowLayer(camera: DrawScope.() -> RoadCam, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val p = Projector(camera())
        val corners = listOf(-1.05f to -2.3f, 1.05f to -2.3f, 1.05f to 2.3f, -1.05f to 2.3f).mapNotNull { (x, z) -> p.proj(x, 0f, z) }
        if (corners.size < 4) return@Canvas
        val l = corners.minOf { it.x }; val rr = corners.maxOf { it.x }
        val t = corners.minOf { it.y }; val b = corners.maxOf { it.y }
        val w = (rr - l) / 2f; val h = ((b - t) / 2f).coerceAtLeast(w * 0.06f)
        if (w < 1f) return@Canvas
        val centre = Offset((l + rr) / 2f, (t + b) / 2f)
        val ink = Color.Black.copy(alpha = if (Gp5Palette.isDay) 0.30f else 0.55f)
        scale(1f, h / w, centre) {
            drawCircle(Brush.radialGradient(0f to ink, 0.55f to ink.copy(alpha = ink.alpha * 0.55f), 1f to ink.copy(alpha = 0f), center = centre, radius = w * 1.05f), w * 1.05f, centre)
        }
    }
}

private val Spot = Color(0xFF4A4945)      // warm grey: lifts the backdrop without looking like a light source
