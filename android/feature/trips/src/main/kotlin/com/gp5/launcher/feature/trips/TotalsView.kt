package com.gp5.launcher.feature.trips

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.R as DesignR
import com.gp5.launcher.trips.Calibration
import com.gp5.launcher.trips.FillUp
import com.gp5.launcher.trips.Trip
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private class Bucket(val label: String, val from: Long, val to: Long) {
    var trips: List<Trip> = emptyList()
}

/** Week: the last 7 days. Month: the last 5 weeks. Year: the last 12 months. Oldest first. */
private fun buckets(period: Int, now: Long): List<Bucket> {
    val cal = Calendar.getInstance()
    return when (period) {
        0 -> (6 downTo 0).map { back ->
            cal.timeInMillis = dayStart(now); cal.add(Calendar.DAY_OF_YEAR, -back)
            val from = cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1)
            Bucket(SimpleDateFormat("EEE", Locale.getDefault()).format(Date(from)), from, cal.timeInMillis)
        }
        1 -> (4 downTo 0).map { back ->
            cal.timeInMillis = dayStart(now); cal.add(Calendar.DAY_OF_YEAR, -6 - back * 7)
            val from = cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 7)
            Bucket(SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(from)), from, cal.timeInMillis)
        }
        else -> (11 downTo 0).map { back ->
            cal.timeInMillis = dayStart(now); cal.set(Calendar.DAY_OF_MONTH, 1); cal.add(Calendar.MONTH, -back)
            val from = cal.timeInMillis; cal.add(Calendar.MONTH, 1)
            Bucket(SimpleDateFormat("MMM", Locale.getDefault()).format(Date(from)), from, cal.timeInMillis)
        }
    }
}

private fun economyOf(trips: List<Trip>, cal: Calibration?): Float? {
    val withFuel = trips.filter { it.fuelL != null }
    val litres = withFuel.sumOf { it.fuelL ?: 0.0 }
    val km = withFuel.sumOf { it.distanceKm }
    return if (litres > 0.05 && km > 1) (km / litres).toFloat() * (cal?.factor ?: 1f) else null
}

private fun evShareOf(trips: List<Trip>): Float? {
    val moving = trips.sumOf { it.modeS }
    return if (moving > 0) trips.sumOf { it.evS }.toFloat() / moving else null
}

@Composable
internal fun TotalsView(vm: TripsViewModel) {
    val all by vm.trips.collectAsStateWithLifecycle()
    val cal by vm.calibration.collectAsStateWithLifecycle()
    val fills by vm.fills.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableIntStateOf(0) }
    var metric by rememberSaveable { mutableIntStateOf(0) }
    val trips = all.filter { !it.open }
    val bars = remember(trips, period) {
        val now = System.currentTimeMillis()
        buckets(period, now).onEach { b -> b.trips = trips.filter { it.startMs >= b.from && it.startMs < b.to } }
    }
    val inPeriod = bars.flatMap { it.trips }
    val km = inPeriod.sumOf { it.distanceKm }
    val litres = inPeriod.sumOf { it.fuelL ?: 0.0 } / (cal?.factor ?: 1f)
    val eco = economyOf(inPeriod, cal)
    val ev = evShareOf(inPeriod)
    val price = fills.firstOrNull()?.pricePerL
    val periodName = listOf("last 7 days", "last 5 weeks", "last 12 months")[period]
    val unit = LocalGp5Look.current.economy

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigKpi("Distance", km(km), "km", periodName, Modifier.weight(1f))
            BigKpi("Avg economy", eco?.let { unit.text(it) } ?: "—", unit.label, if (litres > 0) "%.1f L used".format(litres) else "No fuel data yet", Modifier.weight(1f))
            BigKpi("EV driving", ev?.let { "${(it * 100).roundToInt()}" } ?: "—", "%", "${km(inPeriod.sumOf { it.evKm })} km on battery", Modifier.weight(1f))
            BigKpi(
                "Fuel cost", if (price != null && litres > 0) rupees(litres * price) else "—", "",
                if (price != null && litres > 0 && km > 0) "Rs %.1f per km".format(litres * price / km) else "Add a fill-up for prices", Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GpCard(Modifier.weight(1f).fillMaxHeight(), phaseDeg = 60f) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SegTabs(listOf("Week", "Month", "Year"), period, width = 80.dp) { period = it }
                        Spacer(Modifier.weight(1f))
                        SegTabs(listOf(unit.label, "EV %", "km"), metric, width = if (unit == com.gp5.launcher.core.model.EconomyUnit.KmPerL) 72.dp else 96.dp) { metric = it }
                    }
                    val values = bars.map { b ->
                        when (metric) {
                            0 -> economyOf(b.trips, cal)?.let { unit.of(it) }
                            1 -> evShareOf(b.trips)?.times(100f)
                            else -> b.trips.sumOf { it.distanceKm }.toFloat().takeIf { it > 0f }
                        }
                    }
                    Bars(bars.map { it.label }, values, unit = listOf(unit.label, "%", "km")[metric], key = period, modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp))
                }
            }
            GpCard(Modifier.width(308.dp).fillMaxHeight(), phaseDeg = 200f) {
                Records(trips, cal, settings.odometerKm, settings.oilChangedAtKm, settings.oilIntervalKm)
            }
        }
    }
}

@Composable
private fun BigKpi(label: String, value: String, unit: String, note: String, modifier: Modifier) {
    GpCard(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(label, style = Gp5Type.Label)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = Gp5Type.Display.copy(fontSize = 32.sp, lineHeight = 36.sp), color = Gp5Palette.Text, maxLines = 1)
                if (unit.isNotEmpty()) Text(" $unit", style = Gp5Type.Body.copy(color = Gp5Palette.Text2), modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(note, style = Gp5Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Bars grow with a 30 ms stagger; the current (last) bar is accent; a dashed line marks the average. */
@Composable
private fun Bars(labels: List<String>, values: List<Float?>, unit: String, key: Int, modifier: Modifier) {
    val look = LocalGp5Look.current
    var tapped by remember(key) { mutableIntStateOf(-1) }
    val present = values.filterNotNull()
    val top = (present.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.15f
    val avg = present.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val grow = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { grow.animateTo(1f, tween(600 + labels.size * 30)) }
    val heights = values.mapIndexed { i, v ->
        animateFloatAsState(((v ?: 0f) / top).coerceIn(0f, 1f), tween(400, delayMillis = i * 30), label = "bar$i").value
    }
    val textColor = Gp5Palette.Text2.toArgb()
    val strong = Gp5Palette.Text.toArgb()
    Canvas(modifier.pointerInput(labels.size) {
        detectTapGestures { p -> tapped = (p.x / (size.width / labels.size)).toInt().coerceIn(0, labels.size - 1) }
    }) {
        val bottom = size.height - 20.dp.toPx()
        val slot = size.width / labels.size
        val bw = slot * 0.56f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.sp.toPx(); color = textColor; textAlign = android.graphics.Paint.Align.CENTER }
        labels.forEachIndexed { i, label ->
            val stagger = ((grow.value * (labels.size + 6) - i) / 6f).coerceIn(0f, 1f)
            val h = bottom * heights[i] * stagger
            val cx = slot * i + slot / 2
            val current = i == labels.lastIndex
            drawRoundRect(
                if (current) look.accent else if (i == tapped) Gp5Palette.Text3 else Gp5Palette.LineStrong,
                Offset(cx - bw / 2, bottom - h), Size(bw, h.coerceAtLeast(2f)), CornerRadius(4.dp.toPx()),
            )
            paint.color = if (current || i == tapped) strong else textColor
            drawContext.canvas.nativeCanvas.drawText(label, cx, size.height - 4.dp.toPx(), paint)
            val v = values[i]
            if ((current || i == tapped) && v != null) {
                drawContext.canvas.nativeCanvas.drawText(if (unit.contains('/')) "%.1f".format(v) else "${v.roundToInt()}", cx, bottom - h - 6.dp.toPx(), paint)
            }
        }
        if (avg != null) {
            val y = bottom - bottom * (avg / top) * grow.value
            drawLine(Gp5Palette.Text3, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            paint.textAlign = android.graphics.Paint.Align.LEFT; paint.color = textColor
            drawContext.canvas.nativeCanvas.drawText("avg ${if (unit.contains('/')) "%.1f".format(avg) else avg.roundToInt().toString()} $unit", 4.dp.toPx(), y - 4.dp.toPx(), paint)
        }
        if (present.isEmpty()) {
            paint.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText("No trips in this period", size.width / 2, bottom / 2, paint)
        }
    }
}

@Composable
private fun Records(trips: List<Trip>, cal: Calibration?, odometer: Double, oilAt: Double, oilEvery: Int) {
    val bestEco = trips.filter { it.distanceKm >= 5 }.mapNotNull { t -> tripEconomy(t, cal)?.let { t to it } }.maxByOrNull { it.second }
    val mostEv = trips.filter { it.distanceKm >= 3 && it.hasModes }.maxByOrNull { it.evShare }
    val longest = trips.maxByOrNull { it.distanceKm }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Records · all time", style = Gp5Type.Label)
        val unit = LocalGp5Look.current.economy
        Record(DesignR.drawable.ic_gas, "Best economy", bestEco?.first?.let(::route) ?: "Trips over 5 km", bestEco?.let { "${unit.text(it.second)} ${unit.label}" } ?: "—")
        Record(DesignR.drawable.ic_rail_vehicle, "Most EV driving", mostEv?.let(::route) ?: "Trips over 3 km", mostEv?.let { "${(it.evShare * 100).roundToInt()}%" } ?: "—")
        Record(DesignR.drawable.ic_rail_trips, "Longest trip", longest?.let(::route) ?: "No trips yet", longest?.let { "${km(it.distanceKm)} km" } ?: "—")
        Record(DesignR.drawable.ic_info, "Odometer", if (odometer > 0) "Updated by the trip recorder" else "Set it in Settings", if (odometer > 0) "%,.0f".format(Locale.UK, odometer) else "—")
        val due = if (odometer > 0 && oilAt > 0) oilAt + oilEvery - odometer else null
        Record(
            DesignR.drawable.ic_gas, if (due != null && due < 0) "Oil change overdue" else "Oil change due",
            if (due == null) "Record it in Settings" else "Every %,d km".format(Locale.UK, oilEvery),
            due?.let { "%,.0f km".format(Locale.UK, kotlin.math.abs(it)) } ?: "—",
            warn = due != null && due < 300,
        )
    }
}

@Composable
private fun Record(icon: Int, title: String, sub: String, value: String, warn: Boolean = false) {
    val look = LocalGp5Look.current
    Row(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp)).background(Gp5Palette.Surface2).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Gp5Palette.Surface1), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = if (warn) Gp5Palette.Warn else look.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, style = Gp5Type.Body.copy(fontSize = 14.sp), color = Gp5Palette.Text, maxLines = 1)
            Text(sub, style = Gp5Type.Caption.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = Gp5Type.Body.copy(fontSize = 15.sp), color = if (warn) Gp5Palette.Warn else Gp5Palette.Text)
    }
}

