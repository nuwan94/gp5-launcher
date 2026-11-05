package com.gp5.launcher.car3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import com.gp5.launcher.core.design.Gp5Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Roadside scenery for the chase view: coconut palms, leafy trees, bushes and road signs (signs on
 * the left verge, as in Sri Lanka), placed by a fixed pseudo-random pattern along the road and moving
 * past at the real speed. Flat, quiet shapes in the theme's colours (deep greens by day, near-black
 * silhouettes at night) so they read as place, not decoration that competes with the car.
 */
internal fun DrawScope.drawScenery(p: Projector, distance: Float, show: Float) {
    val items = ArrayList<Item>(48)
    val first = floor((distance + NEAR_Z) / SLOT).toInt()
    val last = floor((distance + FAR_Z) / SLOT).toInt()
    for (i in first..last) {
        val z = i * SLOT - distance
        for (side in 0..1) {
            val h = hash(i * 2 + side)
            val kind = when {
                side == 0 && h % 11 == 0 -> Kind.Sign
                h % 7 < 3 -> Kind.Palm
                h % 7 < 5 -> Kind.Tree
                h % 7 == 5 -> Kind.Bush
                else -> null
            } ?: continue
            val jitterZ = ((h shr 8) % 100) / 100f * SLOT * 0.7f
            val out = ((h shr 4) % 100) / 100f
            val x = if (kind == Kind.Sign) 2.6f
                else if (side == 0) 3.4f + out * 4f              // left verge (car's left)
                else -6.3f - out * 4f                            // beyond the far edge
            val scale = 0.8f + ((h shr 12) % 100) / 250f
            items += Item(kind, x, z + jitterZ, scale, h)
        }
    }
    val night = Gp5Palette.night
    // grass verges on both sides, so everything stands on ground rather than on the studio floor
    verge(p, 2.2f, 12f, show, night)
    verge(p, -5.6f, -16f, show, night)
    // far to near, so nearer things cover farther ones
    items.sortByDescending { it.z }
    val leaf = lerp(Color(0xFF5E7F49), Color(0xFF1C2219), night)
    val leafHi = lerp(Color(0xFF7C9C5E), Color(0xFF252C21), night)
    val trunk = lerp(Color(0xFF7A6248), Color(0xFF1E1B18), night)
    for (it in items) {
        val a = (fadeScenery(it.z) * show).coerceIn(0f, 1f)
        if (a < 0.02f) continue
        val base = p.proj(it.x, 0f, it.z) ?: continue
        val top = p.proj(it.x, 1f, it.z) ?: continue
        val m = base.y - top.y                                   // pixels per metre at this depth
        if (m < 0.6f) continue
        when (it.kind) {
            Kind.Palm -> palm(base, m * it.scale, it.seed, trunk.copy(alpha = a), leaf.copy(alpha = a), leafHi.copy(alpha = a))
            Kind.Tree -> tree(base, m * it.scale, it.seed, trunk.copy(alpha = a), leaf.copy(alpha = a), leafHi.copy(alpha = a))
            Kind.Bush -> bush(base, m * it.scale, leaf.copy(alpha = a), leafHi.copy(alpha = a))
            Kind.Sign -> sign(base, m, it.seed, a, night)
        }
    }
}

private enum class Kind { Palm, Tree, Bush, Sign }
private class Item(val kind: Kind, val x: Float, val z: Float, val scale: Float, val seed: Int)

private const val SLOT = 7f
private const val NEAR_Z = -12f
private const val FAR_Z = 75f

/** Visible from far ahead until a few metres in front of the car, so nothing looms over the camera. */
private fun fadeScenery(z: Float): Float = when {
    z < 9f -> ((z - 3f) / 6f).coerceIn(0f, 1f)
    else -> (1f - (z - 34f) / 40f).coerceIn(0f, 1f)
}

private fun DrawScope.verge(p: Projector, x0: Float, x1: Float, show: Float, night: Float) {
    val near = ArrayList<Offset>(); val far = ArrayList<Offset>()
    var z = -10f
    while (z <= FAR_Z) {
        p.proj(x0, 0f, z)?.let { near += it }
        p.proj(x1, 0f, z)?.let { far += it }
        z += 3f
    }
    if (near.size < 2 || far.size < 2) return
    val path = Path().apply {
        moveTo(near[0].x, near[0].y); for (o in near.drop(1)) lineTo(o.x, o.y)
        for (o in far.asReversed()) lineTo(o.x, o.y); close()
    }
    val grass = lerp(Color(0xFFCBCFB6), Color(0xFF171914), night)
    val horizon = (p.proj(0f, 0f, FAR_Z) ?: near.last()).y
    val bottom = (p.proj(0f, 0f, 0f) ?: near.first()).y
    drawPath(path, androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to grass.copy(alpha = 0f), 0.3f to grass.copy(alpha = 0.6f * show), 1f to grass.copy(alpha = 0.9f * show),
        startY = horizon, endY = bottom + (bottom - horizon) * 0.4f,
    ))
}

private fun hash(i: Int): Int {
    var x = i * 0x45d9f3b
    x = (x xor (x ushr 16)) * 0x45d9f3b
    x = x xor (x ushr 16)
    return x and 0x7fffffff
}

/** Coconut palm: a thin leaning trunk, about 8 m, and a crown of drooping fronds. */
private fun DrawScope.palm(base: Offset, m: Float, seed: Int, trunk: Color, leaf: Color, leafHi: Color) {
    val lean = (if (seed % 2 == 0) 1f else -1f) * (0.6f + (seed % 5) * 0.15f)
    val h = 6f * m
    val top = Offset(base.x + lean * m, base.y - h)
    val trunkPath = Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(base.x + lean * m * 0.15f, base.y - h * 0.55f, top.x, top.y)
    }
    drawPath(trunkPath, trunk, style = Stroke(width = (0.28f * m).coerceAtLeast(1f), cap = StrokeCap.Round))
    val fronds = 9
    for (f in 0 until fronds) {
        val ang = f / fronds.toFloat() * 2f * Math.PI.toFloat() + seed % 7
        val dx = cos(ang) * 2.6f * m
        val lift = sin(ang) * 0.5f * m
        val tip = Offset(top.x + dx, top.y + 1.3f * m - lift)
        val mid = Offset(top.x + dx * 0.55f, top.y - 0.5f * m - lift * 0.5f)
        val frond = Path().apply { moveTo(top.x, top.y); quadraticTo(mid.x, mid.y, tip.x, tip.y) }
        drawPath(frond, if (f % 2 == 0) leaf else leafHi, style = Stroke(width = (0.32f * m).coerceAtLeast(1f), cap = StrokeCap.Round))
    }
    drawCircle(leaf, 0.35f * m, top)
}

/** Leafy roadside tree (rain tree / mango): short trunk, a broad crown of overlapping blobs. */
private fun DrawScope.tree(base: Offset, m: Float, seed: Int, trunk: Color, leaf: Color, leafHi: Color) {
    val h = (4.5f + (seed % 3)) * m
    drawRect(trunk, Offset(base.x - 0.18f * m, base.y - h * 0.55f), Size(0.36f * m, h * 0.55f))
    val cy = base.y - h * 0.68f
    val w = 1.9f * m
    drawCircle(leaf, w * 0.75f, Offset(base.x - w * 0.55f, cy + w * 0.15f))
    drawCircle(leaf, w * 0.75f, Offset(base.x + w * 0.55f, cy + w * 0.12f))
    drawCircle(leaf, w * 0.9f, Offset(base.x, cy - w * 0.25f))
    drawCircle(leafHi, w * 0.45f, Offset(base.x - w * 0.25f, cy - w * 0.5f))
}

private fun DrawScope.bush(base: Offset, m: Float, leaf: Color, leafHi: Color) {
    drawCircle(leaf, 0.7f * m, Offset(base.x - 0.5f * m, base.y - 0.45f * m))
    drawCircle(leaf, 0.8f * m, Offset(base.x + 0.4f * m, base.y - 0.55f * m))
    drawCircle(leafHi, 0.4f * m, Offset(base.x, base.y - 0.9f * m))
}

/** Road signs on the left verge: speed limit (red ring), a blue information plate, or a warning triangle. */
private fun DrawScope.sign(base: Offset, m: Float, seed: Int, a: Float, night: Float) {
    val post = lerp(Color(0xFF8C8C88), Color(0xFF3A3A38), night).copy(alpha = a)
    val h = 2.2f * m
    drawRect(post, Offset(base.x - 0.04f * m, base.y - h), Size((0.08f * m).coerceAtLeast(1f), h))
    val c = Offset(base.x, base.y - h)
    val face = lerp(Color(0xFFF7F5EF), Color(0xFF8E8C86), night).copy(alpha = a)
    val red = lerp(Color(0xFFC8332F), Color(0xFF6E2A28), night).copy(alpha = a)
    when (abs(seed / 11) % 3) {
        0 -> {
            drawCircle(red, 0.42f * m, c)
            drawCircle(face, 0.31f * m, c)
            drawRect(lerp(Color(0xFF1A1A18), Color(0xFF2A2A28), night).copy(alpha = a), Offset(c.x - 0.12f * m, c.y - 0.04f * m), Size(0.24f * m, 0.08f * m))
        }
        1 -> {
            val blue = lerp(Color(0xFF1F5FA8), Color(0xFF1C2E44), night).copy(alpha = a)
            drawRect(blue, Offset(c.x - 0.7f * m, c.y - 0.45f * m), Size(1.4f * m, 0.6f * m))
            drawRect(face, Offset(c.x - 0.5f * m, c.y - 0.2f * m), Size(1.0f * m, 0.07f * m))
        }
        else -> {
            val tri = Path().apply {
                moveTo(c.x, c.y - 0.5f * m); lineTo(c.x + 0.48f * m, c.y + 0.32f * m); lineTo(c.x - 0.48f * m, c.y + 0.32f * m); close()
            }
            drawPath(tri, red)
            val inner = Path().apply {
                moveTo(c.x, c.y - 0.3f * m); lineTo(c.x + 0.3f * m, c.y + 0.2f * m); lineTo(c.x - 0.3f * m, c.y + 0.2f * m); close()
            }
            drawPath(inner, face)
        }
    }
}
