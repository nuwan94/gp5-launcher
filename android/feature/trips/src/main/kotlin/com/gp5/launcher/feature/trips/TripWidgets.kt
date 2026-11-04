package com.gp5.launcher.feature.trips

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.model.DriveMode
import com.gp5.launcher.trips.Trip
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------------------------------- format

internal fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

internal fun minutes(s: Int): String = when {
    s >= 3600 -> "%d h %02d min".format(s / 3600, s / 60 % 60)
    else -> "${(s + 30) / 60} min"
}

internal fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun dayLabel(ms: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val d = dayStart(ms)
    return when {
        d == today -> "Today"
        today - d in 1..(26 * 3_600_000L) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

internal fun route(t: Trip): String = when {
    t.fromName != null && t.toName != null && t.fromName != t.toName -> "${t.fromName} → ${t.toName}"
    t.fromName != null -> "Around ${t.fromName}"
    t.toName != null -> "To ${t.toName}"
    else -> "Trip at ${hm(t.startMs)}"
}

internal fun km(v: Double): String = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
internal fun rupees(v: Double): String = "Rs %,.0f".format(Locale.UK, v)

internal fun modeColor(mode: Int, accent: Color): Color = when (DriveMode.entries.getOrNull(mode)) {
    DriveMode.Ev -> Gp5Palette.Ev
    DriveMode.Regen -> Gp5Palette.Regen
    DriveMode.Ready -> Gp5Palette.Text3
    null -> Gp5Palette.Text2                       // GPS-only sample: mode unknown
    else -> accent
}

// ------------------------------------------------------------------------------------------ widgets

/** Tabs with a sliding accent plate (trips.html .seg). */
@Composable
internal fun SegTabs(options: List<String>, selected: Int, width: Dp = 112.dp, onSelect: (Int) -> Unit) {
    val look = LocalGp5Look.current
    val x by animateDpAsState(width * selected, spring(dampingRatio = 0.7f), label = "tab")
    Box(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface1).padding(4.dp)) {
        Box(Modifier.offset(x = x).size(width, 40.dp).clip(RoundedCornerShape(20.dp)).background(look.accentSoft))
        Row {
            options.forEachIndexed { i, label ->
                Box(Modifier.width(width).height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(label, style = Gp5Type.Label.copy(color = if (i == selected) look.accent else Gp5Palette.Text2, fontSize = 14.sp))
                }
            }
        }
    }
}

/** Ring showing the EV share of driving time, with the % inside. */
@Composable
internal fun EvRing(share: Float, live: Boolean, known: Boolean = true, size: Dp = 40.dp) {
    val look = LocalGp5Look.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 4.dp.toPx()
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(Gp5Palette.Surface2, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            drawArc(if (live) look.accent else Gp5Palette.Ev, -90f, 360f * share.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(if (known) "${(share * 100).toInt()}" else "—", style = Gp5Type.Caption.copy(color = Gp5Palette.Text, fontSize = 12.sp))
    }
}

/** KPI tile: label, big value + unit, one line of context. */
@Composable
internal fun Kpi(label: String, value: String, unit: String, note: String, noteColor: Color = Gp5Palette.Text3, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Gp5Palette.Surface2).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = Gp5Type.Caption, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Gp5Type.Display.copy(fontSize = 26.sp, lineHeight = 30.sp), color = Gp5Palette.Text, maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", style = Gp5Type.Label, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(note, style = Gp5Type.Caption.copy(color = noteColor, fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round icon button (Keep / Delete). */
@Composable
internal fun RoundIcon(icon: Int, label: String, tint: Color = Gp5Palette.Text, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Gp5Palette.Surface2).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), label, tint = if (enabled) tint else Gp5Palette.Text3, modifier = Modifier.size(22.dp)) }
}

/** Pill button with an optional icon. */
@Composable
internal fun Pill(text: String, icon: Int? = null, color: Color = Gp5Palette.Text, background: Color = Gp5Palette.Surface1, height: Dp = 48.dp, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(background).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) color else Gp5Palette.Text3
        if (icon != null) Icon(painterResource(icon), null, tint = c, modifier = Modifier.size(20.dp).padding(end = 0.dp))
        if (icon != null) Box(Modifier.width(8.dp))
        Text(text, style = Gp5Type.Label.copy(color = c, fontSize = 14.sp))
    }
}

/**
 * 56 dp stepper button: one step on press, then repeats while held (×10 after 10 repeats, ×1000 after 60,
 * ×100 after 30), like the prototype. No keyboard needed.
 */
@Composable
internal fun StepButton(icon: Int, label: String, onStep: (Int) -> Unit) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Gp5Palette.Surface2).pointerInput(Unit) {
            detectTapGestures(onPress = {
                step(1)
                val repeat = scope.launch {
                    delay(400)
                    var n = 0
                    while (true) { step(if (n >= 60) 1000 else if (n >= 30) 100 else if (n >= 10) 10 else 1); n++; delay(80) }
                }
                tryAwaitRelease()
                repeat.cancel()
            })
        },
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), label, tint = Gp5Palette.Text, modifier = Modifier.size(24.dp)) }
}
