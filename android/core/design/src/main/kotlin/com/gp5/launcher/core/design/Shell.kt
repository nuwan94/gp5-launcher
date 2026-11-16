package com.gp5.launcher.core.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val ClockFmt = SimpleDateFormat("h:mm", Locale.UK)
private val AmPmFmt = SimpleDateFormat("a", Locale.UK)
private val LongDateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.UK)

/** The time, ticking on the minute. */
@Composable
fun rememberNow(): Date {
    val now by produceState(Date()) {
        while (true) { value = Date(); delay(60_000L - System.currentTimeMillis() % 60_000L) }
    }
    return now
}

/**
 * 44 dp top bar: Home and the date on the left, a large clock in the middle, status and quick
 * actions on the right (weather, OBD / GPS / phone link, search, theme).
 */
@Composable
fun TopBar(
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onTheme: () -> Unit,
    modifier: Modifier = Modifier,
    weather: (@Composable RowScope.() -> Unit)? = null,
    chips: @Composable RowScope.() -> Unit = {},
) {
    val now = rememberNow()
    Box(modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 18.dp)) {
        Row(
            Modifier.align(Alignment.CenterStart).height(TopBarHeight).clip(RoundedCornerShape(12.dp)).clickable(onClick = onHome).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_home), "Home", tint = Gp5Palette.Text2, modifier = Modifier.size(26.dp))
            Text(LongDateFmt.format(now), style = Gp5Type.Label, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp))
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.Bottom) {
            Text(ClockFmt.format(now), style = Gp5Type.Title.copy(fontFamily = Gp5Num, fontSize = 36.sp), color = Gp5Palette.Text)
            Text(" " + AmPmFmt.format(now).uppercase(), style = Gp5Type.Caption.copy(fontSize = 14.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            weather?.invoke(this)
            chips()
            BarButton(R.drawable.ic_search, "Search", onSearch)
            BarButton(R.drawable.ic_contrast, "Theme", onTheme)
        }
    }
}

val TopBarHeight: Dp = 60.dp
val DockHeight: Dp = 96.dp

@Composable
private fun BarButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(Gp5Palette.Surface1).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, tint = Gp5Palette.Text, modifier = Modifier.size(28.dp))
    }
}

/** One dock button. */
class DockItem(val label: String, @DrawableRes val icon: Int, val selected: Boolean, val onClick: () -> Unit)

/**
 * The bottom dock on every screen: now playing on the left ([leading]), then large labelled buttons
 * (the current screen highlighted in the accent). Gold edge with gold trim on.
 */
@Composable
fun Dock(items: List<DockItem>, modifier: Modifier = Modifier, leading: @Composable RowScope.() -> Unit) {
    val look = LocalGp5Look.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().height(DockHeight).clip(shape).background(Gp5Palette.Surface1)
            .border(1.dp, if (look.goldTrim) Gp5Palette.GoldDim else Gp5Palette.Line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(0.55f).fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { leading() }
        Row(Modifier.weight(0.45f).fillMaxHeight()) {
            items.forEach { item ->
                Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 16.dp).background(Gp5Palette.Line))
                DockButton(item, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DockButton(item: DockItem, modifier: Modifier) {
    val look = LocalGp5Look.current
    val bg by animateColorAsState(if (item.selected) look.accentSoft else Color.Transparent, tween(Gp5Motion.Base), label = "dockBg")
    val fg by animateColorAsState(if (item.selected) look.accent else Gp5Palette.Text, tween(Gp5Motion.Base), label = "dockFg")
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Column(
        modifier.fillMaxHeight().padding(5.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).background(bg).clickable(interaction, null, onClick = item.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(item.icon), item.label, tint = fg, modifier = Modifier.size(32.dp))
        Text(item.label, style = Gp5Type.Caption.copy(fontSize = 13.sp), color = if (item.selected) fg else Gp5Palette.Text2, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

// ------------------------------------------------------------------------------------- weather

/**
 * A weather symbol for a WMO code, drawn (sun, moon, cloud, rain, storm, fog), in the theme's text
 * colour with a warm sun.
 */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val ink = Gp5Palette.Text
    val sun = if (Gp5Palette.isDay) Color(0xFFD08A1E) else Color(0xFFF2C14E)
    val moon = Gp5Palette.Text2
    val rain = Gp5Palette.Regen
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.075f
        val clear = code == 0 || code == 1
        val partly = code == 2 || code == 1
        val clouds = code >= 2
        fun body(c: Offset, r: Float) {
            if (isDay) {
                drawCircle(sun, r, c)
                for (i in 0 until 8) {
                    val a = i * Math.PI.toFloat() / 4
                    drawLine(sun, Offset(c.x + cos(a) * r * 1.45f, c.y + sin(a) * r * 1.45f), Offset(c.x + cos(a) * r * 1.85f, c.y + sin(a) * r * 1.85f), w, StrokeCap.Round)
                }
            } else {
                val p = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(c, r * 1.15f))
                    val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.45f), r * 1.0f)) }
                    op(this, cut, androidx.compose.ui.graphics.PathOperation.Difference)
                }
                drawPath(p, moon)
            }
        }
        if (clear && !clouds) { body(Offset(s / 2, s / 2), s * 0.2f); return@Canvas }
        if (partly) body(Offset(s * 0.36f, s * 0.34f), s * 0.15f)
        cloud(ink, Offset(s * 0.54f, s * (if (code >= 45) 0.46f else 0.56f)), s * 0.42f, w)
        when (code) {
            45, 48 -> for (i in 0..1) drawLine(ink, Offset(s * 0.18f, s * (0.78f + i * 0.12f)), Offset(s * 0.82f, s * (0.78f + i * 0.12f)), w, StrokeCap.Round)
            in 51..67, in 80..82 -> for (i in 0..2) {
                val x = s * (0.34f + i * 0.17f)
                drawLine(rain, Offset(x, s * 0.76f), Offset(x - s * 0.05f, s * 0.92f), w, StrokeCap.Round)
            }
            in 71..77 -> for (i in 0..2) drawCircle(ink, w * 0.8f, Offset(s * (0.34f + i * 0.17f), s * 0.86f))
            in 95..99 -> {
                val bolt = Path().apply {
                    moveTo(s * 0.54f, s * 0.66f); lineTo(s * 0.44f, s * 0.82f); lineTo(s * 0.54f, s * 0.82f); lineTo(s * 0.46f, s * 0.98f)
                }
                drawPath(bolt, sun, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}

private fun DrawScope.cloud(c: Color, center: Offset, w: Float, stroke: Float) {
    val h = w * 0.52f
    val l = center.x - w / 2; val b = center.y + h / 2
    val p = Path().apply {
        moveTo(l + h * 0.45f, b)
        lineTo(l + w - h * 0.4f, b)
        arcTo(androidx.compose.ui.geometry.Rect(Offset(l + w - h * 0.8f, b - h * 0.8f), Size(h * 0.8f, h * 0.8f)), 90f, -180f, false)
        arcTo(androidx.compose.ui.geometry.Rect(Offset(l + w * 0.32f, b - h * 1.25f), Size(h * 1.15f, h * 1.15f)), -20f, -150f, false)
        arcTo(androidx.compose.ui.geometry.Rect(Offset(l, b - h * 0.9f), Size(h * 0.9f, h * 0.9f)), -90f, -180f, false)
        close()
    }
    drawPath(p, c.copy(alpha = 0.12f))
    drawPath(p, c, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

// ------------------------------------------------------------------------------------- compass

private val Points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** "NE" for a bearing in degrees. */
fun compassPoint(deg: Float): String = Points[(((deg % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8]

/** The shared dial face: a card circle with a fine edge, as the speed gauge and the compass use. */
private fun DrawScope.dialFace(r: Float) {
    drawCircle(Gp5Palette.Surface1, r)
    drawCircle(Gp5Palette.LineStrong, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
}

/**
 * The compass, in the speed gauge's style: the card turns so the top shows the direction of travel,
 * N in the accent, the heading written in the middle. [heading] null (no GPS heading yet): dimmed.
 */
@Composable
fun CompassDial(heading: Float?, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    val look = LocalGp5Look.current
    val text = rememberTextMeasurer()
    // turn the short way round, smoothly
    val target = heading ?: 0f
    val turned = remember { floatArrayOf(target) }           // unwrapped, so 350° -> 10° turns 20°, not 340°
    val goal = turned[0] + ((((target - turned[0]) % 360f) + 540f) % 360f - 180f)
    turned[0] = goal
    val shown by animateFloatAsState(goal, tween(Gp5Motion.Slow, easing = Gp5Motion.EaseOut), label = "compass")
    val ink = Gp5Palette.Text; val dim = Gp5Palette.Text3; val accent = look.accent
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = if (heading == null) 0.45f else 1f }) {
            val r = this.size.minDimension / 2
            val c = center
            dialFace(r)
            for (i in 0 until 72) {
                val a = Math.toRadians(i * 5.0 - 90 - shown).toFloat()
                val major = i % 18 == 0; val mid = i % 6 == 0
                val r1 = r * (if (major) 0.78f else if (mid) 0.82f else 0.86f); val r2 = r * 0.92f
                drawLine(if (i == 0) accent else if (major || mid) ink.copy(alpha = 0.7f) else dim, Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r2, c.y + sin(a) * r2),
                    (if (major) 2.5.dp else 1.dp).toPx(), StrokeCap.Round)
            }
            // N E S W stay upright and move round with the card
            listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (label, deg) ->
                val a = Math.toRadians(deg - 90.0 - shown).toFloat()
                val l = text.measure(label, TextStyle(fontSize = (r / 7f).toSp(), fontWeight = FontWeight.SemiBold, color = if (deg == 0) accent else ink))
                drawText(l, topLeft = Offset(c.x + cos(a) * r * 0.62f - l.size.width / 2f, c.y + sin(a) * r * 0.62f - l.size.height / 2f))
            }
            // direction of travel: fixed at the top
            val t = Path().apply { moveTo(c.x, r * 0.02f); lineTo(c.x - r * 0.08f, r * 0.16f); lineTo(c.x + r * 0.08f, r * 0.16f); close() }
            drawPath(t, accent)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(heading?.let { compassPoint(it) } ?: "—", style = Gp5Type.Title.copy(fontFamily = Gp5Num, fontSize = (size.value / 6.5f).sp), color = Gp5Palette.Text)
            Text(heading?.let { "${((it % 360f + 360f) % 360f).toInt()}°" } ?: "no heading", style = Gp5Type.Caption.copy(fontSize = 12.sp))
        }
    }
}

/**
 * The speedometer: a 240° gauge from 0 to [max] km/h, ticks every 10 and numbers every 20, the arc
 * filled in the accent up to the speed, and the speed large in the middle with [unit] under it.
 */
@Composable
fun SpeedGauge(speedKmh: Float, hasSpeed: Boolean, unit: String, modifier: Modifier = Modifier, size: Dp = 230.dp, max: Float = 160f) {
    val look = LocalGp5Look.current
    val text = rememberTextMeasurer()
    val shown by animateFloatAsState(if (hasSpeed) speedKmh.coerceIn(0f, max) else 0f, tween(Gp5Motion.Base), label = "gauge")
    val ink = Gp5Palette.Text; val dim = Gp5Palette.Text3; val track = Gp5Palette.Surface2; val accent = look.accent
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            val c = center
            dialFace(r)
            val w = r * 0.07f
            val arcR = r * 0.86f
            val tl = Offset(c.x - arcR, c.y - arcR); val sz = Size(arcR * 2, arcR * 2)
            drawArc(track, 150f, 240f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            if (shown > 0.5f) drawArc(accent, 150f, 240f * shown / max, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            val steps = (max / 10f).toInt()
            for (i in 0..steps) {
                val a = Math.toRadians(150.0 + 240.0 * i / steps).toFloat()
                val major = i % 2 == 0
                val r1 = r * (if (major) 0.68f else 0.72f); val r2 = r * 0.76f
                drawLine(if (i * 10f <= shown) accent else if (major) ink.copy(alpha = 0.6f) else dim,
                    Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r2, c.y + sin(a) * r2), (if (major) 2.dp else 1.dp).toPx(), StrokeCap.Round)
                if (major && i % 4 == 0) {
                    val l = text.measure("${i * 10}", TextStyle(fontSize = (r / 10f).toSp(), color = Gp5Palette.Text2, fontFamily = Gp5Num))
                    drawText(l, topLeft = Offset(c.x + cos(a) * r * 0.56f - l.size.width / 2f, c.y + sin(a) * r * 0.56f - l.size.height / 2f))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = size * 0.04f)) {
            Text(
                if (hasSpeed) "${speedKmh.toInt().coerceAtLeast(0)}" else "—",
                style = Gp5Type.Hero.copy(fontSize = (size.value / 3f).sp, lineHeight = (size.value / 3f).sp, fontWeight = FontWeight.Bold),
                color = if (hasSpeed) ink else dim,
            )
            Text(unit, style = Gp5Type.Label.copy(fontSize = 15.sp))
        }
    }
}
