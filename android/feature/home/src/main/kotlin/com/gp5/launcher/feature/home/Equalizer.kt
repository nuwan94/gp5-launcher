package com.gp5.launcher.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gp5.launcher.core.model.VisualizerStyle
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * The equalizer animation. [read] fills the bands from the sound playing and returns false when it
 * has none (no permission, or the app hides its sound): the bands then move on their own, to a beat,
 * while [playing]. Settles to nothing when the music stops.
 */
@Composable
fun Equalizer(style: VisualizerStyle, playing: Boolean, color: Color, read: (FloatArray) -> Boolean, modifier: Modifier = Modifier, bands: Int = 28) {
    if (style == VisualizerStyle.Off) return
    val level = remember(bands) { FloatArray(bands) }
    val target = remember(bands) { FloatArray(bands) }
    val peak = remember(bands) { FloatArray(bands) }
    var frame by remember { mutableIntStateOf(0) }
    val isPlaying by rememberUpdatedState(playing)
    val reader by rememberUpdatedState(read)
    LaunchedEffect(bands) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last < 33_000_000L) return@withFrameNanos       // about 30 fps is plenty
                last = now
                val t = now / 1e9
                val live = isPlaying && reader(target)
                if (!live) synth(target, t, isPlaying)
                var moving = false
                for (i in 0 until bands) {
                    val k = if (target[i] > level[i]) 0.55f else 0.16f        // quick rise, slow fall
                    level[i] += (target[i] - level[i]) * k
                    peak[i] = max(level[i], peak[i] - 0.012f)
                    if (level[i] > 0.004f || peak[i] > 0.004f) moving = true
                }
                if (moving || isPlaying) frame++
            }
        }
    }
    Canvas(modifier) {
        frame // redraw on every new frame
        when (style) {
            VisualizerStyle.Bars -> bars(level, peak, color)
            VisualizerStyle.Mirror -> mirror(level, color)
            VisualizerStyle.Wave -> wave(level, color)
            VisualizerStyle.Dots -> dots(level, color)
            VisualizerStyle.Off -> Unit
        }
    }
}

/** Music-like motion without the sound: bass that pumps to a 120 bpm beat, mids and highs that wander. */
private fun synth(out: FloatArray, t: Double, playing: Boolean) {
    if (!playing) { out.fill(0f); return }
    val n = out.size
    val beat = exp(-((t * 2.0) % 1.0) * 7.0).toFloat()                    // a kick every half second
    val off = exp(-(((t * 2.0) + 0.5) % 1.0) * 9.0).toFloat()              // and a hat between
    for (i in 0 until n) {
        val f = i / (n - 1f)
        val base = 0.62f - 0.38f * f
        val wander = 0.22f * (sin(t * 2.3 + i * 0.9) * sin(t * 1.1 + i * 0.37)).toFloat()
        val jitter = 0.10f * sin(t * 13.0 + i * 2.7).toFloat()
        val kick = if (f < 0.3f) 0.42f * beat * (1f - f / 0.3f) else 0f
        val hat = if (f > 0.65f) 0.30f * off * ((f - 0.65f) / 0.35f) else 0f
        out[i] = (base * (0.55f + 0.45f * beat) + wander + jitter + kick + hat).coerceIn(0.03f, 1f)
    }
}

private fun DrawScope.bars(level: FloatArray, peak: FloatArray, color: Color) {
    val n = level.size
    val slot = size.width / n
    val w = slot * 0.62f
    val r = CornerRadius(w / 2, w / 2)
    val brush = Brush.verticalGradient(listOf(color, color.copy(alpha = color.alpha * 0.35f)), 0f, size.height)
    for (i in 0 until n) {
        if (level[i] < 0.01f && peak[i] < 0.01f) continue
        val h = max(w, level[i] * size.height)
        val x = i * slot + (slot - w) / 2
        drawRoundRect(brush, Offset(x, size.height - h), Size(w, h), r)
        val py = size.height - peak[i] * size.height - 3.dp.toPx()
        if (peak[i] > 0.05f) drawLine(color, Offset(x, py), Offset(x + w, py), 2.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.mirror(level: FloatArray, color: Color) {
    val n = level.size
    val slot = size.width / n
    val w = slot * 0.5f
    val mid = size.height / 2
    val brush = Brush.verticalGradient(
        listOf(color.copy(alpha = 0f), color, color.copy(alpha = 0f)), 0f, size.height,
    )
    for (i in 0 until n) {
        // loudest in the middle: bass at the centre, highs out to both sides
        val j = kotlin.math.abs(i - (n - 1) / 2f).let { (it * 2).toInt().coerceIn(0, n - 1) }
        if (level[j] < 0.01f) continue
        val h = max(w, level[j] * mid * 0.95f)
        drawRoundRect(brush, Offset(i * slot + (slot - w) / 2, mid - h), Size(w, h * 2), CornerRadius(w / 2, w / 2))
    }
}

private fun DrawScope.wave(level: FloatArray, color: Color) {
    val n = level.size
    fun path(scale: Float, shift: Int, closed: Boolean): Path = Path().apply {
        val pts = List(n) { i ->
            val v = level[(i + shift).mod(n)] * scale
            Offset(size.width * i / (n - 1f), size.height * 0.96f - (0.15f + v * 1.25f).coerceAtMost(1f) * size.height * 0.92f)
        }
        moveTo(0f, if (closed) size.height else pts[0].y)
        if (closed) lineTo(pts[0].x, pts[0].y)
        for (i in 1 until n) {
            val a = pts[i - 1]; val b = pts[i]
            val mx = (a.x + b.x) / 2
            cubicTo(mx, a.y, mx, b.y, b.x, b.y)
        }
        if (closed) { lineTo(size.width, size.height); close() }
    }
    drawPath(path(0.7f, 5, true), Brush.verticalGradient(listOf(color.copy(alpha = color.alpha * 0.35f), Color.Transparent), 0f, size.height))
    drawPath(path(1f, 0, true), Brush.verticalGradient(listOf(color.copy(alpha = color.alpha * 0.7f), color.copy(alpha = 0f)), 0f, size.height))
    drawPath(path(1f, 0, false), color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.dots(level: FloatArray, color: Color) {
    val n = level.size
    val slot = size.width / n
    val d = slot * 0.5f
    val rows = (size.height / (d * 1.9f)).toInt().coerceAtLeast(3)
    val step = size.height / rows
    for (i in 0 until n) {
        val lit = (level[i] * 1.4f * rows).toInt().coerceAtMost(rows)
        val cx = i * slot + slot / 2
        for (row in 0 until rows) {
            val cy = size.height - step * (row + 0.5f)
            val on = row < lit
            val a = if (!on) 0.10f else if (row == lit - 1) 1f else 0.45f + 0.4f * row / rows
            drawCircle(color.copy(alpha = color.alpha * a), d / 2, Offset(cx, cy))
        }
    }
}

