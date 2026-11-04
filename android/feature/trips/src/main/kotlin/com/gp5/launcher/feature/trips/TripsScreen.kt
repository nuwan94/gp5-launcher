package com.gp5.launcher.feature.trips

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.R as DesignR
import com.gp5.launcher.core.model.DriveMode
import com.gp5.launcher.trips.Calibration
import com.gp5.launcher.trips.FillUp
import com.gp5.launcher.trips.Trip
import com.gp5.launcher.trips.TripSample
import kotlin.math.roundToInt

/**
 * Trips (DESIGN.md section 16, design/trips.html): tabs Trips / Totals / Fuel log, the live
 * "Recording" chip and Export CSV. Trips record by themselves (TripRecorder) while OBD is connected.
 */
@Composable
fun TripsScreen(vm: TripsViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val live by vm.live.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.export(uri) }
    var hasLocation by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasLocation = r.values.any { it } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SegTabs(listOf("Trips", "Totals", "Fuel log"), tab) { tab = it }
                live?.let { RecordingChip(it.distanceKm) }
                Spacer(Modifier.weight(1f))
                if (!hasLocation) {
                    Pill("Name places", color = LocalGp5Look.current.accent) {
                        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                Pill("Export CSV", icon = DesignR.drawable.ic_file_export) {
                    runCatching { exporter.launch(null) }.onFailure { vm.say("This unit has no file picker") }
                }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(250, delayMillis = 60)) + slideInHorizontally(tween(250)) { 24 * dir })
                        .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -24 * dir })
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = "tab",
            ) { t ->
                when (t) {
                    0 -> TripsView(vm)
                    1 -> TotalsView(vm)
                    else -> FuelLogView(vm)
                }
            }
        }
        Toast(message, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RecordingChip(distanceKm: Double) {
    val look = LocalGp5Look.current
    val pulse = rememberInfiniteTransition(label = "rec")
    val a by pulse.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(look.accentSoft).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(look.accent))
        Text("  Recording · ${km(distanceKm)} km", style = Gp5Type.Label.copy(color = look.accent))
    }
}

@Composable
internal fun Toast(text: String?, modifier: Modifier) {
    AnimatedVisibility(text != null, modifier.padding(bottom = 20.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            text.orEmpty(), style = Gp5Type.Body, color = Gp5Palette.Text,
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface2).padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------- trips view

@Composable
private fun TripsView(vm: TripsViewModel) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val fills by vm.fills.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    if (trips.isEmpty()) {
        GpCard(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No trips yet", style = Gp5Type.Title, color = Gp5Palette.Text)
                Text(
                    "Trips record by themselves when the car moves with the OBD adapter connected.",
                    style = Gp5Type.Label, modifier = Modifier.padding(top = 6.dp),
                )
            }
            UndoBar(undo != null, vm::undoDelete, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GpCard(Modifier.width(340.dp).fillMaxHeight()) {
            TripList(trips, selected?.id, calibration, vm::select)
        }
        GpCard(Modifier.weight(1f).fillMaxHeight(), phaseDeg = 160f) {
            selected?.let { TripDetail(it, trips, calibration, fills, vm) }
            UndoBar(undo != null, vm::undoDelete, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun UndoBar(show: Boolean, onUndo: () -> Unit, modifier: Modifier) {
    val look = LocalGp5Look.current
    AnimatedVisibility(show, modifier.padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface2).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Trip deleted", style = Gp5Type.Body, color = Gp5Palette.Text)
            Spacer(Modifier.width(12.dp))
            Pill("Undo", color = look.accent, background = look.accentSoft, height = 40.dp, onClick = onUndo)
        }
    }
}

private sealed interface ListItem {
    data class Day(val label: String, val km: Double) : ListItem
    data class Row(val trip: Trip) : ListItem
}

@Composable
private fun TripList(trips: List<Trip>, selectedId: Long?, cal: Calibration?, onSelect: (Long) -> Unit) {
    val items = remember(trips) {
        trips.groupBy { dayStart(it.startMs) }.flatMap { (_, day) ->
            listOf<ListItem>(ListItem.Day(dayLabel(day.first().startMs), day.sumOf { it.distanceKm })) + day.map { ListItem.Row(it) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        items(items, key = { if (it is ListItem.Row) "t${it.trip.id}" else "d${(it as ListItem.Day).label}" }) { item ->
            when (item) {
                is ListItem.Day -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(item.label.uppercase(), style = Gp5Type.Caption.copy(fontSize = 11.sp, letterSpacing = 1.sp))
                    Spacer(Modifier.weight(1f))
                    Text("${km(item.km)} KM", style = Gp5Type.Caption.copy(fontSize = 11.sp))
                }
                is ListItem.Row -> TripRow(item.trip, item.trip.id == selectedId, cal) { onSelect(item.trip.id) }
            }
        }
    }
}

@Composable
private fun TripRow(t: Trip, selected: Boolean, cal: Calibration?, onClick: () -> Unit) {
    val look = LocalGp5Look.current
    val bg by animateColorAsState(if (selected) Gp5Palette.Surface2 else Color.Transparent, tween(250), label = "row")
    val edge by animateColorAsState(if (selected) look.accent else Color.Transparent, tween(250), label = "edge")
    Row(
        Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(edge))
        Spacer(Modifier.width(10.dp))
        EvRing(t.evShare, live = t.open, known = t.hasModes)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(route(t), style = Gp5Type.Body.copy(fontSize = 15.sp), color = Gp5Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (t.open) "Recording · ${minutes(t.durationS)}" else "${hm(t.startMs)} · ${minutes(t.durationS)}",
                style = Gp5Type.Caption.copy(color = if (t.open) look.accent else Gp5Palette.Text2),
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(km(t.distanceKm), style = Gp5Type.Title.copy(fontSize = 18.sp), color = Gp5Palette.Text)
                Text(" km", style = Gp5Type.Caption)
            }
            Text(economyText(t, cal), style = Gp5Type.Caption)
        }
        if (t.kept) Text("★", color = look.accent, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
    }
}

/** Calibrated trip economy; "—" while recording or without fuel data. */
internal fun tripEconomy(t: Trip, cal: Calibration?): Float? = if (t.open) null else t.economy?.let { it * (cal?.factor ?: 1f) }

private fun economyText(t: Trip, cal: Calibration?): String = when {
    t.open -> "—"
    t.fuelL == 0.0 && t.distanceKm > 0 -> "All EV"
    else -> tripEconomy(t, cal)?.let { "%.1f km/L".format(it) } ?: "— km/L"
}

@Composable
private fun TripDetail(t: Trip, all: List<Trip>, cal: Calibration?, fills: List<FillUp>, vm: TripsViewModel) {
    val look = LocalGp5Look.current
    val samples by vm.samples.collectAsStateWithLifecycle()
    val done = all.filter { !it.open && it.id != t.id }
    val eco = tripEconomy(t, cal)
    val avgEco = done.mapNotNull { tripEconomy(it, cal) }.takeIf { it.size >= 2 }?.average()?.toFloat()
    val avgEv = done.filter { it.hasModes }.map { it.evShare }.takeIf { it.size >= 2 }?.average()?.toFloat()
    val price = fills.firstOrNull { it.timeMs <= t.startMs }?.pricePerL ?: fills.lastOrNull()?.pricePerL
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(route(t), style = Gp5Type.Title.copy(fontSize = 22.sp), color = Gp5Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${dayLabel(t.startMs)} · ${hm(t.startMs)} – ${if (t.open) "now" else hm(t.endMs)} · ${minutes(t.durationS)}", style = Gp5Type.Label)
            }
            RoundIcon(if (t.kept) DesignR.drawable.ic_star_filled else DesignR.drawable.ic_star, if (t.kept) "Unstar" else "Keep", tint = if (t.kept) look.accent else Gp5Palette.Text) { vm.star(t) }
            Spacer(Modifier.width(10.dp))
            RoundIcon(DesignR.drawable.ic_trash, "Delete", enabled = !t.open) { vm.delete(t) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Distance", km(t.distanceKm), "km", when { t.avgSpeed < 30 -> "City trip"; t.avgSpeed > 55 -> "Highway"; else -> "Mixed roads" }, modifier = Modifier.weight(1f))
            val ecoNote = if (eco != null && avgEco != null) delta(eco / avgEco - 1f, "vs your avg", percent = true) else null
            Kpi(
                "Economy", eco?.let { "%.1f".format(it) } ?: if (t.fuelL == 0.0) "EV" else "—", if (eco != null) "km/L" else "",
                ecoNote?.first ?: when { t.open -> "When the trip ends"; t.fuelL == null -> "No fuel data from the car"; cal == null -> "OBD estimate"; else -> "Pump calibrated" },
                ecoNote?.second ?: Gp5Palette.Text3, Modifier.weight(1f),
            )
            val evNote = if (avgEv != null) delta((t.evShare - avgEv) * 100, "pts vs avg", percent = false) else null
            if (t.hasModes) {
                Kpi("EV driving", "${(t.evShare * 100).roundToInt()}", "%", evNote?.first ?: "${km(t.evKm)} km on battery", evNote?.second ?: Gp5Palette.Text3, Modifier.weight(1f))
            } else {
                Kpi("EV driving", "—", "", "GPS only: plug in OBD for modes", modifier = Modifier.weight(1f))
            }
            Kpi("Avg speed", "${t.avgSpeed.roundToInt()}", "km/h", "Max ${t.maxSpeed.roundToInt()} km/h", modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed and drive mode", style = Gp5Type.Label)
                Spacer(Modifier.weight(1f))
                Legend("EV", Gp5Palette.Ev); Legend("Engine", look.accent); Legend("Regen", Gp5Palette.Regen)
            }
            SpeedChart(samples, t.startMs, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp))
        }
        ShareBar(t)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val litres = t.fuelL?.let { l -> l / (cal?.factor ?: 1f) }
            Foot("Fuel used", litres?.let { "%.2f L".format(it) } ?: "—", Modifier.weight(1f))
            Foot("Cost", if (litres != null && price != null) rupees(litres * price) else "—", Modifier.weight(1f))
            Foot("Stopped", minutes(t.stoppedS), Modifier.weight(1f))
            Foot("Max coolant", t.maxCoolant?.let { "${it.roundToInt()} °C" } ?: "—", Modifier.weight(1f))
        }
    }
}

private fun delta(v: Float, what: String, percent: Boolean): Pair<String, Color> {
    val up = v >= 0
    val n = if (percent) "${(kotlin.math.abs(v) * 100).roundToInt()}%" else "${kotlin.math.abs(v).roundToInt()}"
    return "${if (up) "▲" else "▼"} $n $what" to if (up) Gp5Palette.Ev else Gp5Palette.Warn
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(" $label", style = Gp5Type.Caption)
    }
}

@Composable
private fun Foot(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = Gp5Type.Caption)
        Text(value, style = Gp5Type.Body.copy(fontSize = 15.sp), color = Gp5Palette.Text, maxLines = 1)
    }
}

@Composable
private fun ShareBar(t: Trip) {
    val look = LocalGp5Look.current
    val total = t.movingS.coerceAtLeast(1).toFloat()
    Column {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Gp5Palette.Surface2)) {
            listOf(t.evS to Gp5Palette.Ev, t.engineS to look.accent, t.regenS to Gp5Palette.Regen, t.unknownS to Gp5Palette.Text3).forEach { (s, c) ->
                if (s > 0) Box(Modifier.weight(s / total).fillMaxHeight().background(c))
            }
            val rest = 1f - t.movingS / total
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
        Row(Modifier.padding(top = 4.dp)) {
            Text(
                listOfNotNull(
                    "EV ${(t.evS / total * 100).roundToInt()}%", "Engine ${(t.engineS / total * 100).roundToInt()}%", "Regen ${(t.regenS / total * 100).roundToInt()}%",
                    if (t.unknownS > 0) "GPS only ${(t.unknownS / total * 100).roundToInt()}%" else null,
                ).joinToString("  ·  "),
                style = Gp5Type.Caption,
            )
            Spacer(Modifier.weight(1f))
            Text("share of driving time", style = Gp5Type.Caption.copy(color = Gp5Palette.Text3))
        }
    }
}

/**
 * Speed trace filled by drive mode (EV green, engine accent, regen blue, stops grey), 20 km/h
 * gridlines and five clock labels. Drag or tap to scrub: dashed cursor, dot and a tooltip.
 */
@Composable
private fun SpeedChart(samples: List<TripSample>, startMs: Long, modifier: Modifier) {
    val look = LocalGp5Look.current
    var scrub by remember { mutableFloatStateOf(-1f) }
    if (samples.size < 2) {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(Gp5Palette.Surface2), contentAlignment = Alignment.Center) {
            Text(if (samples.isEmpty()) "No speed trace for this trip" else "Collecting…", style = Gp5Type.Label)
        }
        return
    }
    val maxT = samples.last().t.coerceAtLeast(1)
    val top = ((samples.maxOf { it.speed } / 20f).toInt() + 1).coerceAtLeast(2) * 20f
    val labelColor = Gp5Palette.Text3.toArgb()
    Canvas(
        modifier
            .pointerInput(samples) { detectTapGestures { scrub = it.x } }
            .pointerInput(samples) { detectDragGestures(onDragEnd = { }, onDrag = { c, _ -> scrub = c.position.x }) },
    ) {
        val left = 28.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val w = size.width - left; val h = bottom
        fun x(t: Int) = left + w * t / maxT
        fun y(v: Float) = bottom - h * (v / top)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = labelColor; textSize = 10.sp.toPx() }
        // gridlines every 20 km/h
        var g = 0f
        while (g <= top) {
            drawLine(Gp5Palette.Line, Offset(left, y(g)), Offset(size.width, y(g)), 1f)
            drawContext.canvas.nativeCanvas.drawText("${g.toInt()}", 0f, y(g) + 4.dp.toPx(), text)
            g += 20f
        }
        // fill each run of one mode under the trace
        var i = 0
        while (i < samples.size - 1) {
            val mode = samples[i].mode
            val moving = samples[i].speed >= 1f
            var j = i
            while (j < samples.size - 1 && samples[j + 1].mode == mode && (samples[j + 1].speed >= 1f) == moving) j++
            val end = (j + 1).coerceAtMost(samples.size - 1)
            val c = modeColor(mode, look.accent)
            if (moving) {
                val p = Path().apply {
                    moveTo(x(samples[i].t), bottom)
                    for (k in i..end) lineTo(x(samples[k].t), y(samples[k].speed))
                    lineTo(x(samples[end].t), bottom); close()
                }
                drawPath(p, c.copy(alpha = 0.28f))
            }
            val line = Path().apply { moveTo(x(samples[i].t), y(samples[i].speed)); for (k in i..end) lineTo(x(samples[k].t), y(samples[k].speed)) }
            drawPath(line, if (moving) c else Gp5Palette.Text3, style = Stroke(2.dp.toPx()))
            i = end
        }
        // five clock labels
        for (k in 0..4) {
            val t = maxT * k / 4
            val label = hm(startMs + t * 1000L)
            val tw = text.measureText(label)
            drawContext.canvas.nativeCanvas.drawText(label, (x(t) - tw / 2).coerceIn(left, size.width - tw), size.height - 2.dp.toPx(), text)
        }
        // scrub cursor
        if (scrub >= left) {
            val t = ((scrub - left) / w * maxT).toInt().coerceIn(0, maxT)
            val s = samples.minBy { kotlin.math.abs(it.t - t) }
            val cx = x(s.t); val cy = y(s.speed)
            drawLine(Gp5Palette.Text2, Offset(cx, 0f), Offset(cx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(modeColor(s.mode, look.accent), 5.dp.toPx(), Offset(cx, cy))
            val mode = when (DriveMode.entries.getOrNull(s.mode)) { DriveMode.Ev -> "EV"; DriveMode.Regen -> "Regen"; DriveMode.Assist -> "Assist"; DriveMode.Ready -> "Stopped"; DriveMode.Charging -> "Charging"; null -> "GPS"; else -> "Engine" }
            val tip = "${hm(startMs + s.t * 1000L)} · ${s.speed.roundToInt()} km/h · $mode"
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = Gp5Palette.Text.toArgb(); textSize = 12.sp.toPx() }
            val tw = tp.measureText(tip) + 16.dp.toPx()
            val tx = (cx - tw / 2).coerceIn(left, size.width - tw)
            drawRoundRect(Gp5Palette.Surface2, Offset(tx, 2.dp.toPx()), androidx.compose.ui.geometry.Size(tw, 24.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(tip, tx + 8.dp.toPx(), 18.dp.toPx(), tp)
        }
    }
}
