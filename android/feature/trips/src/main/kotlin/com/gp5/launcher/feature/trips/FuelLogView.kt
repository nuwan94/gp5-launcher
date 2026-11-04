package com.gp5.launcher.feature.trips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.R as DesignR
import com.gp5.launcher.trips.FillUp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A fill-up with the real economy of the full-to-full stretch it closes (null for partial fills). */
private data class FillRow(val fill: FillUp, val realKmL: Float?)

private fun rows(fills: List<FillUp>): List<FillRow> {
    val asc = fills.sortedBy { it.timeMs }
    var lastFull: Int? = null
    val out = asc.mapIndexed { i, f ->
        var real: Float? = null
        if (f.full) {
            val prev = lastFull
            if (prev != null) {
                val km = f.odometerKm - asc[prev].odometerKm
                val litres = (prev + 1..i).sumOf { asc[it].litres }
                if (km > 0 && litres > 0) real = (km / litres).toFloat()
            }
            lastFull = i
        }
        FillRow(f, real)
    }
    return out.reversed()
}

@Composable
internal fun FuelLogView(vm: TripsViewModel) {
    val fills by vm.fills.collectAsStateWithLifecycle()
    val cal by vm.calibration.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val car by vm.car.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val list = remember(fills) { rows(fills) }
    val reals = list.mapNotNull { it.realKmL }.take(5)
    val real = cal?.realKmL ?: reals.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val last = fills.firstOrNull()
    val kmSince = if (last != null && settings.odometerKm > last.odometerKm) settings.odometerKm - last.odometerKm else null
    val driving = car.isDriving
    val unit = LocalGp5Look.current.economy

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GpCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        listOf("Date" to 1.4f, "Odometer" to 1.1f, "Litres" to 0.9f, "Rs/L" to 0.8f, "Total" to 1f, unit.label to 0.9f).forEach { (h, w) ->
                            Text(h, style = Gp5Type.Caption, modifier = Modifier.weight(w), textAlign = if (h == "Date") TextAlign.Start else TextAlign.End)
                        }
                    }
                    if (list.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No fill-ups yet. Add one at the pump for real km/L and cost.", style = Gp5Type.Label)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.fill.id }) { r -> FillLine(r, unit) }
                    }
                }
            }
            GpCard(Modifier.width(308.dp).fillMaxHeight(), phaseDeg = 220f) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when (reals.size) { 0 -> "Real economy"; 1 -> "Real economy · last full fill"; else -> "Real economy · last ${reals.size} full fills" }, style = Gp5Type.Label)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(real?.let { unit.text(it) } ?: "—", style = Gp5Type.Hero.copy(fontSize = 44.sp, lineHeight = 46.sp), color = Gp5Palette.Text)
                        Text(" ${unit.label}", style = Gp5Type.Body.copy(color = Gp5Palette.Text2), modifier = Modifier.padding(bottom = 6.dp))
                    }
                    val price = last?.pricePerL
                    Text(
                        listOfNotNull(
                            cal?.let { "OBD estimate ${unit.text(it.obdKmL)} ${unit.label}" },
                            if (real != null && price != null) "Rs %.1f per km".format(price / real) else null,
                        ).joinToString(" · ").ifEmpty { "Two full fills give the first real figure" },
                        style = Gp5Type.Caption,
                    )
                    Sparkline(list.mapNotNull { it.realKmL }.reversed(), Modifier.fillMaxWidth().height(44.dp))
                    Text(
                        cal?.let { "Trip economy from OBD is multiplied by ×%.3f to match the pump.".format(it.factor) }
                            ?: "After two full fills, trip economy is calibrated to the pump.",
                        style = Gp5Type.Caption,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("Since last fill-up", style = Gp5Type.Label)
                    Text(
                        when {
                            last == null -> "—"
                            kmSince == null -> "Set the odometer in Settings to track this"
                            real != null -> "%.0f km · about %.1f L used".format(kmSince, kmSince / real)
                            else -> "%.0f km".format(kmSince)
                        },
                        style = Gp5Type.Body, color = Gp5Palette.Text,
                    )
                    val look = LocalGp5Look.current
                    Pill(
                        "Add fill-up", icon = DesignR.drawable.ic_gas, color = if (driving) Gp5Palette.Text3 else look.accent,
                        background = if (driving) Gp5Palette.Surface2 else look.accentSoft, height = 64.dp, enabled = !driving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { if (driving) vm.say("Stop the car to add a fill-up") else adding = true }
                }
            }
        }
        AnimatedVisibility(adding, enter = fadeIn(), exit = fadeOut()) {
            AddFillUp(
                odometer = settings.odometerKm,
                lastPrice = last?.pricePerL,
                suggestLitres = if (kmSince != null && real != null) kmSince / real else null,
                onCancel = { adding = false },
                onSave = { odo, l, p, full -> vm.addFillUp(odo, l, p, full); adding = false; vm.say("Fill-up saved · %.1f L".format(l)) },
            )
        }
    }
}

@Composable
private fun FillLine(r: FillRow, unit: com.gp5.launcher.core.model.EconomyUnit) {
    val f = r.fill
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(f.timeMs))
        Column(Modifier.weight(1.4f)) {
            Text(date, style = Gp5Type.Body.copy(fontSize = 14.sp), color = Gp5Palette.Text)
            if (!f.full) Text("Partial", style = Gp5Type.Caption.copy(fontSize = 11.sp, color = Gp5Palette.Warn))
        }
        Cell(if (f.odometerKm > 0) "%,.0f".format(Locale.UK, f.odometerKm) else "—", 1.1f)
        Cell("%.2f".format(f.litres), 0.9f)
        Cell("%.0f".format(f.pricePerL), 0.8f)
        Cell("%,.0f".format(Locale.UK, f.total), 1f)
        Cell(r.realKmL?.let { unit.text(it) } ?: if (f.full) "—" else "next full", 0.9f, color = if (r.realKmL != null) Gp5Palette.Ev else Gp5Palette.Text3)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Gp5Palette.Line))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, color: Color = Gp5Palette.Text) {
    Text(text, style = Gp5Type.Body.copy(fontSize = 14.sp), color = color, textAlign = TextAlign.End, modifier = Modifier.weight(weight), maxLines = 1)
}

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier) {
    if (values.size < 2) { Box(modifier); return }
    Canvas(modifier) {
        val lo = values.min() * 0.97f; val hi = values.max() * 1.03f
        fun y(v: Float) = size.height - size.height * (v - lo) / (hi - lo).coerceAtLeast(0.01f)
        val step = size.width / (values.size - 1)
        val p = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * step, y(v)) } }
        drawPath(p, Gp5Palette.Ev, style = Stroke(2.dp.toPx()))
        drawCircle(Gp5Palette.Ev, 3.dp.toPx(), Offset((values.size - 1) * step, y(values.last())))
    }
}

/**
 * Add fill-up (trips.html dialog): litres and price steppers with hold-to-repeat, no keyboard.
 * The odometer comes from the trip recorder; litres start at the estimate since the last fill.
 */
@Composable
private fun AddFillUp(odometer: Double, lastPrice: Double?, suggestLitres: Double?, onCancel: () -> Unit, onSave: (Double, Double, Double, Boolean) -> Unit) {
    val look = LocalGp5Look.current
    var litres by remember { mutableDoubleStateOf(((suggestLitres ?: 20.0) * 10).toInt() / 10.0) }
    var price by remember { mutableDoubleStateOf(lastPrice ?: 300.0) }
    var odo by remember { mutableDoubleStateOf(odometer) }
    var full by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(12.dp)).background(Gp5Palette.Surface1).clickable(enabled = false) {}.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add fill-up", style = Gp5Type.Title, color = Gp5Palette.Text)
            Stepper("Litres", "%.1f L".format(litres), onMinus = { n -> litres = (litres - 0.1 * n).coerceAtLeast(0.1) }, onPlus = { n -> litres = (litres + 0.1 * n).coerceAtMost(60.0) })
            Stepper("Price per litre", "Rs %.0f".format(price), onMinus = { n -> price = (price - n).coerceAtLeast(1.0) }, onPlus = { n -> price = (price + n).coerceAtMost(2000.0) })
            Stepper("Odometer", if (odo > 0) "%,.0f km".format(Locale.UK, odo) else "Not set", onMinus = { n -> odo = (odo - n).coerceAtLeast(0.0) }, onPlus = { n -> odo += n })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filled to full", style = Gp5Type.Body, color = Gp5Palette.Text, modifier = Modifier.weight(1f))
                SegTabs(listOf("Full", "Partial"), if (full) 0 else 1, width = 96.dp) { full = it == 0 }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total", style = Gp5Type.Label)
                Text("  " + rupees(litres * price), style = Gp5Type.Display.copy(fontSize = 28.sp), color = Gp5Palette.Text, modifier = Modifier.weight(1f))
                Pill("Cancel", height = 56.dp, onClick = onCancel)
                Spacer(Modifier.width(10.dp))
                Pill("Save", color = look.accent, background = look.accentSoft, height = 56.dp) { onSave(odo, litres, price, full) }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: (Int) -> Unit, onPlus: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Gp5Type.Body, color = Gp5Palette.Text, modifier = Modifier.weight(1f))
        StepButton(DesignR.drawable.ic_minus, "Less", onMinus)
        Text(value, style = Gp5Type.Title, color = Gp5Palette.Text, textAlign = TextAlign.Center, modifier = Modifier.width(150.dp))
        StepButton(DesignR.drawable.ic_plus, "More", onPlus)
    }
}
