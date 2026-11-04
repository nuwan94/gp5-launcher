package com.gp5.launcher.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gp5.launcher.core.data.SettingsRepository
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.model.LauncherSettings
import com.gp5.launcher.core.model.RailSide
import com.gp5.launcher.core.model.CarRenderer
import com.gp5.launcher.car3d.LiveHealth
import com.gp5.launcher.core.model.ObdLink
import com.gp5.launcher.core.model.VehicleState
import com.gp5.launcher.obd.VehicleStateRepository
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository, vehicle: VehicleStateRepository) : ViewModel() {
    val vehicle = vehicle.state
    fun obdEnabled(on: Boolean) = viewModelScope.launch { repo.setObdEnabled(on) }
    fun obdAddress(a: String) = viewModelScope.launch { repo.setObdAddress(a) }
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherSettings())
    fun accent(argb: Long) = viewModelScope.launch { repo.setAccent(argb) }
    fun rail(side: RailSide) = viewModelScope.launch { repo.setRailSide(side) }
    fun gold(on: Boolean) = viewModelScope.launch { repo.setGoldTrim(on) }
    fun calm(on: Boolean) = viewModelScope.launch { repo.setCalmMotion(on) }
    fun carRenderer(r: CarRenderer) = viewModelScope.launch { repo.setCarRenderer(r) }
    val activeRenderer = LiveHealth.active
}

/**
 * Settings (DESIGN.md section 17, design/settings.html). M1 ships Display > Look, which drives the
 * theme live on every screen; the other pages (OBD, units, nav, trips, data, about) follow in M6.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    GpCard(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            val car by vm.vehicle.collectAsStateWithLifecycle()
            val context = LocalContext.current
            Text("OBD adapter", style = Gp5Type.Title.copy(fontSize = Gp5Type.Title.fontSize * 1.1f), color = Gp5Palette.Text)
            Text("ELM327 Wi-Fi: join its Wi-Fi network first (often WiFi_OBDII; choose to stay connected without internet)",
                style = Gp5Type.Label, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            Group {
                SettingRow(linkTitle(car), car.linkDetail.ifBlank { " " }) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(linkColor(car.link)))
                }
                if (car.connected) {
                    SettingRow("Live data", liveLine(car)) {}
                }
                SettingRow("Use OBD adapter", "Speed, mode and engine data on Home and Vehicle") {
                    Toggle(s.obdEnabled) { vm.obdEnabled(it) }
                }
                SettingRow("Adapter address", "Most Wi-Fi adapters use 192.168.0.10:35000") {
                    TextSetting(s.obdAddress, "192.168.0.10:35000", { it.filter { c -> c.isDigit() || c == '.' || c == ':' }.take(21) }) { vm.obdAddress(it) }
                }
                SettingRow("Wi-Fi", "Join the adapter's network") {
                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Gp5Palette.Surface1)
                            .clickable { context.startActivity(Intent(AndroidSettings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Open Wi-Fi settings", style = Gp5Type.Label.copy(color = LocalGp5Look.current.accent)) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Display", style = Gp5Type.Title.copy(fontSize = Gp5Type.Title.fontSize * 1.1f), color = Gp5Palette.Text)
            Text("Colour and layout apply to every screen right away", style = Gp5Type.Label, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            Group {
                SettingRow("Accent colour", "Rail, gauges, buttons and highlights") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LauncherSettings.ACCENTS.forEach { (argb, name) ->
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color(argb))
                                    .then(if (s.accentArgb == argb) Modifier.border(2.dp, Gp5Palette.Text, CircleShape) else Modifier)
                                    .clickable { vm.accent(argb) },
                            )
                        }
                    }
                }
                SettingRow("Rail side", "Right puts it next to the driver (RHD)") {
                    Segmented(listOf("Left", "Right"), if (s.railSide == RailSide.Left) 0 else 1) { vm.rail(if (it == 0) RailSide.Left else RailSide.Right) }
                }
                SettingRow("Gold trim", "Gold edges on cards and the rail, to match the gold badges") {
                    Toggle(s.goldTrim) { vm.gold(it) }
                }
                SettingRow("Calm motion", "Stop ambient animation even when parked") {
                    Toggle(s.calmMotion) { vm.calm(it) }
                }
            }
            Text("Car", style = Gp5Type.Title, color = Gp5Palette.Text, modifier = Modifier.padding(top = 24.dp))
            Text("The 3D car on Home", style = Gp5Type.Label, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            val active by vm.activeRenderer.collectAsStateWithLifecycle()
            Group {
                SettingRow("3D view", if (active.isEmpty()) "Auto picks live 3D when the unit has OpenGL ES 3" else "Showing: $active") {
                    Segmented(listOf("Auto", "Live 3D", "Turntable"), s.carRenderer.ordinal) { vm.carRenderer(CarRenderer.entries[it]) }
                }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Gp5Palette.Surface2)) { content() }
}

@Composable
private fun SettingRow(title: String, sub: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Gp5Type.Body, color = Gp5Palette.Text)
            Text(sub, style = Gp5Type.Label)
        }
        control()
    }
}

private fun linkTitle(c: VehicleState) = when (c.link) {
    ObdLink.Off -> "Off"
    ObdLink.Searching -> "Not connected"
    ObdLink.Connecting -> "Connecting"
    ObdLink.NoCar -> "Adapter found"
    ObdLink.Connected -> "Connected"
}

private fun linkColor(l: ObdLink) = when (l) {
    ObdLink.Connected -> Gp5Palette.Ev
    ObdLink.NoCar, ObdLink.Connecting -> Gp5Palette.Warn
    else -> Gp5Palette.Text3
}

private fun liveLine(c: VehicleState) = listOfNotNull(
    "${c.speedKmh.toInt()} km/h",
    "${c.rpm} rpm",
    c.coolantC?.let { "coolant ${it.toInt()} °C" },
    c.batteryVolts?.let { "%.1f V".format(it) },
    c.fuelPercent?.let { "fuel ${it.toInt()}%" },
    c.socPercent?.let { "battery ${it.toInt()}%" },
).joinToString(" · ")

/** Single-line text setting; [filter] cleans input; saved as you type. */
@Composable
private fun TextSetting(value: String, placeholder: String, filter: (String) -> String, caps: Boolean = false, onChange: (String) -> Unit) {
    val look = LocalGp5Look.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    // follow the stored value only while not typing (the store echoes each keystroke back late)
    LaunchedEffect(value, focused) { if (!focused && value != text) text = value }
    Box(
        Modifier.width(220.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Gp5Palette.Surface1)
            .border(1.dp, Gp5Palette.Line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Text(placeholder, style = Gp5Type.Body.copy(color = Gp5Palette.Text3))
        BasicTextField(
            value = text,
            onValueChange = { v -> val clean = filter(v); text = clean; onChange(clean) },
            singleLine = true,
            textStyle = Gp5Type.Body.copy(color = Gp5Palette.Text),
            cursorBrush = SolidColor(look.accent),
            keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

/** 56 x 32 toggle with a spring thumb (settings.html .tgl). */
@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalGp5Look.current.accent
    val track by animateColorAsState(if (on) accent else Gp5Palette.LineStrong, label = "track")
    val x by animateDpAsState(if (on) 24.dp else 0.dp, spring(dampingRatio = 0.55f), label = "thumb")
    Box(Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(track).clickable { onChange(!on) }) {
        Box(Modifier.offset(x = 3.dp + x, y = 3.dp).size(26.dp).clip(CircleShape).background(Gp5Palette.Text))
    }
}

/** Segmented control with a sliding accent pill (settings.html .segc). */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val look = LocalGp5Look.current
    val w = if (options.size > 2) 100.dp else 76.dp
    val x by animateDpAsState(w * selected, spring(dampingRatio = 0.6f), label = "seg")
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Gp5Palette.Surface1).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(w, 38.dp).clip(RoundedCornerShape(19.dp)).background(look.accentSoft))
        Row {
            options.forEachIndexed { i, label ->
                Box(Modifier.width(w).height(38.dp).clip(RoundedCornerShape(19.dp)).clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(label, style = Gp5Type.Label.copy(color = if (i == selected) look.accent else Gp5Palette.Text2))
                }
            }
        }
    }
}
