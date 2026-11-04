package com.gp5.launcher.feature.vehicle

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.Gp5Dimens
import com.gp5.launcher.core.design.Gp5Num
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.ModePill
import com.gp5.launcher.core.model.DriveMode
import com.gp5.launcher.core.model.VehicleState
import com.gp5.launcher.obd.FaultScan
import com.gp5.launcher.obd.VehicleStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------------------------------- view model

/** Last 30 s of each tile value at 2 Hz (nulls are gaps). */
class History(val size: Int = 60) {
    val values = ArrayDeque<Float?>()
    fun add(v: Float?) { values.addLast(v); while (values.size > size) values.removeFirst() }
}

sealed interface FaultUi {
    data object Idle : FaultUi
    data object Scanning : FaultUi
    data class Result(val scan: FaultScan) : FaultUi
    data class Failed(val message: String) : FaultUi
}

@HiltViewModel
class VehicleViewModel @Inject constructor(private val repo: VehicleStateRepository) : ViewModel() {
    val state = repo.state
    val histories = List(6) { History() }
    private val _tick = MutableStateFlow(0)
    /** Bumps every sample so the sparklines redraw. */
    val tick = _tick.asStateFlow()
    private val _faults = MutableStateFlow<FaultUi>(FaultUi.Idle)
    val faults = _faults.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                val s = repo.state.value
                val v = if (s.connected) listOf(s.coolantC, s.intakeC, s.loadPercent, s.throttlePercent, s.batteryVolts, s.economyKmL) else List(6) { null }
                histories.forEachIndexed { i, h -> h.add(v[i]) }
                _tick.value++
                delay(500)
            }
        }
    }

    fun scan() = run { repo.readFaults() }
    fun clear() = run { repo.clearFaults() }

    private fun run(block: suspend () -> FaultScan) = viewModelScope.launch {
        _faults.value = FaultUi.Scanning
        val started = System.currentTimeMillis()
        val r = runCatching { block() }
        delay((2_200 - (System.currentTimeMillis() - started)).coerceAtLeast(0))   // let the sweep finish
        _faults.value = r.fold({ FaultUi.Result(it) }, { FaultUi.Failed(it.message ?: "Scan failed") })
    }
}

// ------------------------------------------------------------------------------------- screen

/**
 * Vehicle (DESIGN.md section 14, design/vehicle.html): speedometer + tachometer, i-DCD power flow,
 * six sensor tiles with 30 s sparklines, and the fault-code bar. Dims with "—" while OBD is not connected.
 */
@Composable
fun VehicleScreen(onOpenObdSettings: () -> Unit = {}, vm: VehicleViewModel = hiltViewModel()) {
    val car by vm.state.collectAsStateWithLifecycle()
    val faults by vm.faults.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val dim by animateFloatAsState(if (car.connected) 1f else 0.45f, tween(300), label = "dim")
    var toast by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(3_000); toast = null } }
    val g = Gp5Dimens.Gutter

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(g)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(g)) {
                GpCard(Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = dim }) {
                    Gauges(car, Modifier.fillMaxSize().padding(12.dp))
                }
                GpCard(Modifier.width(348.dp).fillMaxHeight().graphicsLayer { alpha = dim }, phaseDeg = 120f) {
                    PowerFlow(car, Modifier.fillMaxSize().padding(14.dp))
                }
            }
            Row(Modifier.fillMaxWidth().height(156.dp).graphicsLayer { alpha = dim }, horizontalArrangement = Arrangement.spacedBy(g)) {
                tiles(car, LocalGp5Look.current.economy).forEachIndexed { i, t -> SensorTile(t, vm.histories[i], tick, i * 50f, Modifier.weight(1f).fillMaxHeight()) }
            }
            DiagnosticsBar(
                car, faults,
                onScan = { if (car.connected) vm.scan() else onOpenObdSettings() },
                onClear = { codes ->
                    if (car.speedKmh >= 1f) toast = "Stop the car to clear fault codes" else confirm = codes
                },
                onSettings = onOpenObdSettings,
                modifier = Modifier.fillMaxWidth().height(Gp5Dimens.Dock),
            )
        }
        AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface2).border(1.dp, Gp5Palette.LineStrong, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(toast ?: "", style = Gp5Type.Body.copy(color = Gp5Palette.Text))
            }
        }
        confirm?.let { codes ->
            ConfirmClear(codes, onCancel = { confirm = null }, onConfirm = {
                confirm = null
                if (car.speedKmh >= 1f) toast = "Stop the car to clear fault codes" else { vm.clear(); toast = "Clearing fault codes" }
            })
        }
    }
}

// ------------------------------------------------------------------------------------- gauges

@Composable
private fun Gauges(car: VehicleState, modifier: Modifier) {
    val look = LocalGp5Look.current
    val speed by animateFloatAsState(if (car.connected) car.speedKmh else 0f, tween(220), label = "speed")
    val rpm by animateFloatAsState(if (car.connected) car.rpm.toFloat() else 0f, tween(220), label = "rpm")
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            Dial(speed / 160f, max = 160, major = 20, stroke = 12.dp, color = look.accent, redFrom = null, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (car.connected) "${speed.roundToInt()}" else "—", style = Gp5Type.Hero.copy(fontSize = 64.sp), color = Gp5Palette.Text)
                Text("km/h", style = Gp5Type.Label)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                if (car.connected) ModePill(car.mode) else NoDataPill()
            }
        }
        Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
            val engineOn = car.connected && car.rpm >= 300
            Dial(if (engineOn) rpm / 7000f else 0f, max = 7, major = 1, stroke = 10.dp,
                color = if (rpm > 5500) Color(0xFFE24B4A) else look.accent, redFrom = 6f / 7f, Modifier.fillMaxSize(), showTip = engineOn)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (car.connected) "%.1f".format(rpm / 1000f) else "—", style = Gp5Type.Display.copy(fontSize = 44.sp), color = Gp5Palette.Text)
                Text("× 1000 rpm", style = Gp5Type.Label)
            }
            Text(
                when {
                    !car.connected -> "No data"
                    !engineOn -> if (car.speedKmh >= 1f) "Engine off · EV" else "Engine off"
                    car.rpm > 5500 -> "High rpm"
                    else -> "Engine running"
                },
                style = Gp5Type.Caption, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            )
        }
    }
}

/** 270-degree arc gauge (135 deg -> 405 deg), ticks and numbers, value arc with a white tip. No needle. */
@Composable
private fun Dial(fraction: Float, max: Int, major: Int, stroke: androidx.compose.ui.unit.Dp, color: Color, redFrom: Float?, modifier: Modifier, showTip: Boolean = true) {
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; this.color = Gp5Palette.Text2.toArgb() } }
    Canvas(modifier) {
        val sw = stroke.toPx()
        val r = size.minDimension / 2 - sw
        val c = center
        val box = Size(r * 2, r * 2); val tl = Offset(c.x - r, c.y - r)
        drawArc(Gp5Palette.Surface2, 135f, 270f, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
        if (redFrom != null) drawArc(Color(0x55E24B4A), 135f + 270f * redFrom, 270f * (1 - redFrom), false, tl, box, style = Stroke(sw, cap = StrokeCap.Butt))
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.002f) drawArc(color, 135f, 270f * f, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
        if (showTip && f > 0.002f) {
            val a = Math.toRadians((135f + 270f * f).toDouble())
            drawCircle(Color.White, sw * 0.42f, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        }
        // ticks + numbers
        labelPaint.textSize = 11.sp.toPx()
        val steps = max / major
        for (i in 0..steps) {
            val a = Math.toRadians((135f + 270f * i / steps).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val r1 = r - sw * 1.1f; val r2 = r1 - 6.dp.toPx()
            drawLine(Gp5Palette.LineStrong, Offset(c.x + r1 * ca, c.y + r1 * sa), Offset(c.x + r2 * ca, c.y + r2 * sa), 1.5.dp.toPx())
            val rl = r2 - 10.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText("${i * major}", c.x + rl * ca, c.y + rl * sa + labelPaint.textSize / 3, labelPaint)
        }
    }
}

@Composable
private fun NoDataPill() {
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(Gp5Palette.Surface2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text("NO DATA", style = Gp5Type.Label.copy(color = Gp5Palette.Text3), fontSize = 13.sp, letterSpacing = 0.6.sp) }
}

// ------------------------------------------------------------------------------------- power flow

private enum class Node { Engine, Motor, Wheels, Battery }
private data class Link(val from: Node, val to: Node, val color: Color)

@Composable
private fun PowerFlow(car: VehicleState, modifier: Modifier) {
    val look = LocalGp5Look.current
    val red = Color(0xFFE24B4A); val green = Gp5Palette.Ev; val blue = Gp5Palette.Regen
    val mode = if (car.connected) car.mode else DriveMode.Ready
    val links = when (mode) {
        DriveMode.Ev -> listOf(Link(Node.Battery, Node.Motor, green), Link(Node.Motor, Node.Wheels, green))
        DriveMode.Engine -> listOf(Link(Node.Engine, Node.Motor, red), Link(Node.Motor, Node.Wheels, red), Link(Node.Motor, Node.Battery, red))
        DriveMode.Assist -> listOf(Link(Node.Engine, Node.Motor, red), Link(Node.Battery, Node.Motor, green), Link(Node.Motor, Node.Wheels, red))
        DriveMode.Regen -> listOf(Link(Node.Wheels, Node.Motor, blue), Link(Node.Motor, Node.Battery, blue))
        DriveMode.Charging -> listOf(Link(Node.Engine, Node.Motor, red), Link(Node.Motor, Node.Battery, red))
        DriveMode.Ready -> emptyList()
    }
    val motorLabel = when (mode) {
        DriveMode.Ev -> "Driving"; DriveMode.Engine, DriveMode.Charging -> "Charging"
        DriveMode.Assist -> "Assist"; DriveMode.Regen -> "Regen"; DriveMode.Ready -> "Idle"
    }
    // dashes flow in the energy direction (0.7 s loop); still while driving or with calm motion
    val phase by rememberInfiniteTransition(label = "flow").animateFloat(
        0f, 18f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val moving = look.ambient

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hybrid system", style = Gp5Type.Title, color = Gp5Palette.Text, modifier = Modifier.weight(1f))
            Text(
                when (mode) {
                    DriveMode.Ev -> "Battery driving"; DriveMode.Engine -> "Engine driving"; DriveMode.Assist -> "Engine + motor"
                    DriveMode.Regen -> "Recovering energy"; DriveMode.Charging -> "Engine charging"; DriveMode.Ready -> if (car.connected) "Ready" else "No data"
                },
                style = Gp5Type.Label,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 8.dp)) {
            val w = maxWidth; val h = maxHeight
            val pos = mapOf(
                Node.Engine to (0.17f to 0.24f), Node.Battery to (0.17f to 0.80f),
                Node.Motor to (0.52f to 0.52f), Node.Wheels to (0.86f to 0.52f),
            )
            Canvas(Modifier.fillMaxSize()) {
                fun p(n: Node) = pos.getValue(n).let { Offset(it.first * size.width, it.second * size.height) }
                // idle connections
                for ((a, b) in listOf(Node.Engine to Node.Motor, Node.Battery to Node.Motor, Node.Motor to Node.Wheels)) {
                    drawLine(Gp5Palette.Line, p(a), p(b), 3.dp.toPx(), cap = StrokeCap.Round)
                }
                for (l in links) flowLine(p(l.from), p(l.to), l.color, if (moving) phase.dp.toPx() else 0f)
            }
            for (n in Node.entries) {
                val (fx, fy) = pos.getValue(n)
                val active = links.any { it.from == n || it.to == n }
                val col = links.firstOrNull { it.from == n || it.to == n }?.color ?: Gp5Palette.LineStrong
                val label = when (n) { Node.Engine -> "Engine"; Node.Motor -> "Motor · DCT"; Node.Wheels -> "Wheels"; Node.Battery -> "Battery" }
                val sub = when (n) {
                    Node.Motor -> motorLabel
                    Node.Battery -> car.socPercent?.takeIf { car.connected }?.let { "${it.roundToInt()}%" } ?: ""
                    Node.Engine -> if (car.connected && car.rpm >= 300) "${car.rpm} rpm" else "Off"
                    Node.Wheels -> if (car.connected) "${car.speedKmh.roundToInt()} km/h" else ""
                }
                Column(
                    Modifier.offset(w * fx - 46.dp, h * fy - 26.dp).width(92.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Gp5Palette.Surface2)
                        .border(1.5.dp, if (active) col else Gp5Palette.Line, RoundedCornerShape(10.dp))
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, style = Gp5Type.Label.copy(color = if (active) col else Gp5Palette.Text2, fontWeight = FontWeight.Medium), fontSize = 12.sp)
                    Text(sub.ifEmpty { " " }, style = Gp5Type.Caption.copy(color = Gp5Palette.Text), fontSize = 12.sp)
                }
            }
        }
    }
}

private fun DrawScope.flowLine(a: Offset, b: Offset, color: Color, phase: Float) {
    val path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }
    drawPath(path, color.copy(alpha = 0.25f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()), -phase)))
}

// ------------------------------------------------------------------------------------- tiles

private enum class Tone { Ok, Info, Warn, Alert, None }
private data class Tile(val label: String, val value: String, val unit: String, val status: String, val tone: Tone)

private fun tiles(c: VehicleState, unit: com.gp5.launcher.core.model.EconomyUnit): List<Tile> {
    fun v(x: Float?, fmt: String = "%.0f") = if (!c.connected || x == null) "—" else fmt.format(x)
    val coolant = c.coolantC.takeIf { c.connected }
    val volts = c.batteryVolts.takeIf { c.connected }
    val load = c.loadPercent.takeIf { c.connected }
    val throttle = c.throttlePercent.takeIf { c.connected }
    val ev = c.connected && c.speedKmh >= 3f && c.rpm < 300
    val eco = c.economyKmL.takeIf { c.connected }
    return listOf(
        Tile("Coolant", v(coolant), "°C",
            when { coolant == null -> ""; coolant < 60f -> "Warming up"; coolant > 105f -> "Too hot"; else -> "Normal" },
            when { coolant == null -> Tone.None; coolant < 60f -> Tone.Info; coolant > 105f -> Tone.Alert; else -> Tone.Ok }),
        Tile("Intake air", v(c.intakeC), "°C", if (c.intakeC == null || !c.connected) "" else "Normal", Tone.Ok),
        Tile("Engine load", v(load), "%",
            when { load == null -> ""; c.rpm < 300 -> "Engine off"; load < 30f -> "Light"; load < 70f -> "Moderate"; else -> "Heavy" },
            when { load == null -> Tone.None; load >= 70f -> Tone.Warn; else -> Tone.Ok }),
        Tile("Throttle", v(throttle), "%", when { throttle == null -> ""; throttle < 3f -> "Closed"; else -> "Open" }, Tone.Info),
        Tile("12 V battery", v(volts, "%.1f"), "V",
            when { volts == null -> ""; volts < 12.2f -> "Low"; volts > 14.8f -> "High"; volts >= 13.2f -> "Charging"; else -> "OK" },
            when { volts == null -> Tone.None; volts < 12.2f -> Tone.Warn; volts > 14.8f -> Tone.Alert; else -> Tone.Ok }),
        Tile("Economy", if (ev) "EV" else eco?.let { unit.text(it) } ?: "—", if (ev) "" else unit.label,
            when { ev -> "Engine off"; eco == null -> when { !c.connected -> ""; c.airflowGs == null -> "No airflow data"; c.speedKmh < 3f -> "Stopped"; else -> "" }; else -> "Now" }, if (ev) Tone.Ok else Tone.Info),
    )
}

@Composable
private fun SensorTile(t: Tile, history: History, tick: Int, phase: Float, modifier: Modifier) {
    val toneColor = when (t.tone) { Tone.Ok -> Gp5Palette.Ev; Tone.Info -> Gp5Palette.Regen; Tone.Warn -> Gp5Palette.Warn; Tone.Alert -> Color(0xFFE24B4A); Tone.None -> Gp5Palette.Text3 }
    GpCard(modifier.then(if (t.tone == Tone.Alert) Modifier.border(1.5.dp, toneColor, RoundedCornerShape(Gp5Dimens.RadiusCard)) else Modifier), phaseDeg = phase) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(t.label, style = Gp5Type.Caption)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t.value, style = Gp5Type.Display.copy(fontSize = 30.sp, fontFamily = Gp5Num), color = Gp5Palette.Text)
                if (t.unit.isNotEmpty() && t.value != "—") Text(" ${t.unit}", style = Gp5Type.Label, modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(t.status.ifEmpty { " " }, style = Gp5Type.Caption.copy(color = toneColor))
            Spacer(Modifier.weight(1f))
            Sparkline(history, tick, toneColor.takeIf { t.tone != Tone.None } ?: Gp5Palette.LineStrong, Modifier.fillMaxWidth().height(30.dp))
        }
    }
}

@Composable
private fun Sparkline(h: History, tick: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick
        val vs = h.values.toList()
        val nums = vs.filterNotNull()
        if (nums.size < 2) return@Canvas
        val lo = nums.min(); val hi = nums.max(); val span = (hi - lo).coerceAtLeast(0.5f)
        val dx = size.width / (h.size - 1)
        val start = h.size - vs.size
        val path = Path(); var pen = false
        vs.forEachIndexed { i, v ->
            if (v == null) { pen = false; return@forEachIndexed }
            val x = (start + i) * dx; val y = size.height - (v - lo) / span * (size.height - 4.dp.toPx()) - 2.dp.toPx()
            if (pen) path.lineTo(x, y) else path.moveTo(x, y); pen = true
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ------------------------------------------------------------------------------------- diagnostics

private val KNOWN = mapOf(
    "P0128" to "Coolant below thermostat temperature", "P0171" to "System too lean", "P0300" to "Random misfire",
    "P0420" to "Catalyst efficiency below threshold", "P0441" to "EVAP purge flow", "P0455" to "EVAP large leak",
    "P0456" to "EVAP small leak", "P0457" to "EVAP leak (fuel cap)", "P0A7F" to "Hybrid battery deterioration",
    "P0A80" to "Replace hybrid battery pack", "P0562" to "System voltage low", "U0100" to "Lost communication with ECM",
    "P0301" to "Cylinder 1 misfire", "P0302" to "Cylinder 2 misfire", "P0303" to "Cylinder 3 misfire",
    "P0304" to "Cylinder 4 misfire",
)
private fun describe(code: String) = KNOWN[code] ?: when (code.firstOrNull()) {
    'P' -> "Powertrain"; 'C' -> "Chassis"; 'B' -> "Body"; 'U' -> "Network"; else -> ""
}

@Composable
private fun DiagnosticsBar(car: VehicleState, faults: FaultUi, onScan: () -> Unit, onClear: (List<String>) -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    val look = LocalGp5Look.current
    val red = Color(0xFFE24B4A)
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "x")
    val codes = (faults as? FaultUi.Result)?.scan?.all.orEmpty()
    GpCard(modifier, phaseDeg = 300f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val (dot, title, sub) = when {
                !car.connected -> Triple(Gp5Palette.Text3, "Not connected · reconnecting…", car.linkDetail)
                faults is FaultUi.Scanning -> Triple(look.accent, "Scanning for fault codes…", "Reading stored and pending codes")
                faults is FaultUi.Failed -> Triple(Gp5Palette.Warn, "Scan failed", faults.message)
                faults is FaultUi.Result && codes.isEmpty() -> Triple(Gp5Palette.Ev, "No fault codes", "Stored and pending codes are clear")
                faults is FaultUi.Result -> Triple(red, "${codes.size} fault code${if (codes.size > 1) "s" else ""} · ${codes.first()} ${describe(codes.first())}",
                    codes.drop(1).joinToString(" · ").ifEmpty { if (faults.scan.pending.isNotEmpty()) "Pending: ${faults.scan.pending.joinToString()}" else "Stored" })
                else -> Triple(Gp5Palette.Ev, "Diagnostics", car.linkDetail)
            }
            Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f)) {
                Text(title, style = Gp5Type.Body.copy(color = Gp5Palette.Text), maxLines = 1)
                Text(sub, style = Gp5Type.Caption, maxLines = 1)
            }
            if (car.connected) {
                BarButton("Scan", enabled = faults !is FaultUi.Scanning, onClick = onScan)
                AnimatedVisibility(codes.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                    BarButton("Clear", color = red, onClick = { onClear(codes) })
                }
            } else {
                BarButton("OBD settings", onClick = onSettings)
            }
        }
        if (faults is FaultUi.Scanning) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(sweep).height(3.dp).background(red))
        }
    }
}

@Composable
private fun BarButton(text: String, color: Color = LocalGp5Look.current.accent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(color.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Gp5Type.Label.copy(color = if (enabled) color else Gp5Palette.Text3, fontWeight = FontWeight.Medium)) }
}

@Composable
private fun ConfirmClear(codes: List<String>, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        GpCard(Modifier.width(460.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            Column(Modifier.padding(22.dp)) {
                Text("Clear fault codes?", style = Gp5Type.Title, color = Gp5Palette.Text)
                Spacer(Modifier.height(8.dp))
                codes.forEach { Text("$it  ${describe(it)}", style = Gp5Type.Body.copy(color = Gp5Palette.Text2)) }
                Spacer(Modifier.height(8.dp))
                Text("The check-engine light goes off. A fault that is still present comes back.", style = Gp5Type.Caption)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    BarButton("Cancel", color = Gp5Palette.Text2, onClick = onCancel)
                    BarButton("Clear codes", color = Color(0xFFE24B4A), onClick = onConfirm)
                }
            }
        }
    }
}
