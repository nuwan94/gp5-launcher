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
import com.gp5.launcher.core.model.NavApp
import com.gp5.launcher.core.data.HeadlightRepository
import com.gp5.launcher.core.data.LightCandidate

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    vehicle: VehicleStateRepository,
    private val lights: HeadlightRepository,
    private val media: com.gp5.launcher.media.MediaRepository,
) : ViewModel() {
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
    fun navApp(a: NavApp) = viewModelScope.launch { repo.setNavApp(a) }
    fun homePlace(p: String) = viewModelScope.launch { repo.setHomePlace(p) }
    fun workPlace(p: String) = viewModelScope.launch { repo.setWorkPlace(p) }
    fun odometer(km: Double) = viewModelScope.launch { repo.setOdometer(km) }
    fun tripEnd(min: Int) = viewModelScope.launch { repo.setTripEndMinutes(min) }
    fun keepTrips(days: Int) = viewModelScope.launch { repo.setKeepTripsDays(days) }
    fun oilChangedAt(km: Double) = viewModelScope.launch { repo.setOilChangedAt(km) }
    fun oilInterval(km: Int) = viewModelScope.launch { repo.setOilInterval(km) }

    val headlights = lights.state
    val nowPlaying = media.state
    val mediaSources = media.sources
    fun allowMedia() = media.requestAccess()
    fun refreshMedia() = media.refresh()
    fun lightsFromUnit(on: Boolean) = viewModelScope.launch { repo.setLightsFromUnit(on) }
    fun lightSource(signal: String) = lights.describe(signal)

    /** Light finder: Idle -> Off snapshot taken -> result. */
    sealed interface Finder {
        data object Idle : Finder
        data object Busy : Finder
        data class WaitingForOn(val off: Map<String, String?>) : Finder
        data class Found(val candidate: LightCandidate) : Finder
        data object Nothing : Finder
    }
    private val _finder = kotlinx.coroutines.flow.MutableStateFlow<Finder>(Finder.Idle)
    val finder: kotlinx.coroutines.flow.StateFlow<Finder> = _finder

    fun finderStart() = viewModelScope.launch { _finder.value = Finder.Busy; _finder.value = Finder.WaitingForOn(lights.snapshot()) }
    fun finderDone() = viewModelScope.launch {
        val off = (_finder.value as? Finder.WaitingForOn)?.off ?: return@launch
        _finder.value = Finder.Busy
        val best = lights.diff(off, lights.snapshot()).firstOrNull()
        if (best == null) _finder.value = Finder.Nothing else { lights.use(best); _finder.value = Finder.Found(best) }
    }
    fun finderReset() = viewModelScope.launch { lights.use(null); _finder.value = Finder.Idle }
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
                SettingRow("Use OBD adapter", "Drive mode and engine data. Off or unplugged: speed comes from GPS") {
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
            Text("Now playing", style = Gp5Type.Title, color = Gp5Palette.Text, modifier = Modifier.padding(top = 24.dp))
            Text("Music apps and phone music over Bluetooth on the Home card", style = Gp5Type.Label, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            val np by vm.nowPlaying.collectAsStateWithLifecycle()
            val sources by vm.mediaSources.collectAsStateWithLifecycle()
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshMedia() }
            Group {
                SettingRow(
                    "Notification access",
                    if (np.hasAccess) "Allowed: the launcher can see what is playing" else "Needed to show music from other apps and Bluetooth",
                ) {
                    if (np.hasAccess) Box(Modifier.size(12.dp).clip(CircleShape).background(Gp5Palette.Ev))
                    else ActionPill("Allow", LocalGp5Look.current.accent) { vm.allowMedia() }
                }
                if (np.hasAccess) {
                    SettingRow(
                        "Seen now",
                        if (sources.isEmpty()) "Nothing yet. Play music on the phone over Bluetooth or open a music app" else sources.joinToString("  ·  "),
                    ) {}
                }
            }
            Text("Headlights", style = Gp5Type.Title, color = Gp5Palette.Text, modifier = Modifier.padding(top = 24.dp))
            Text("Light up the car's lamps when your headlights are on, without OBD", style = Gp5Type.Label, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            val lightsNow by vm.headlights.collectAsStateWithLifecycle()
            val finder by vm.finder.collectAsStateWithLifecycle()
            Group {
                SettingRow("Follow the head unit", "Uses the illumination (ILL) wire through night mode, or the setting found below") {
                    Toggle(s.lightsFromUnit) { vm.lightsFromUnit(it) }
                }
                SettingRow(
                    "Now: " + if (lightsNow.on) "lights on" else "lights off",
                    if (lightsNow.fromUnit) "From: ${lightsNow.source}" else if (s.lightsFromUnit) "Manual (tap the light button on Home)" else "Manual",
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (lightsNow.on) LocalGp5Look.current.accent else Gp5Palette.Text3))
                }
                val f = finder
                SettingRow(
                    "Find the light signal",
                    when (f) {
                        SettingsViewModel.Finder.Idle -> "Signal: ${vm.lightSource(s.lightSignal)}. With the headlights OFF, tap Start"
                        SettingsViewModel.Finder.Busy -> "Reading the head unit settings…"
                        is SettingsViewModel.Finder.WaitingForOn -> "Now switch the headlights ON, wait 2 s, then tap Done"
                        is SettingsViewModel.Finder.Found -> "Using ${f.candidate.label}"
                        SettingsViewModel.Finder.Nothing -> "Nothing changed. This unit may not share the light signal; use the Home button"
                    },
                ) {
                    val accent = LocalGp5Look.current.accent
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.lightSignal.isNotBlank() || f is SettingsViewModel.Finder.Found) {
                            ActionPill("Reset", Gp5Palette.Text2) { vm.finderReset() }
                        }
                        when (f) {
                            is SettingsViewModel.Finder.WaitingForOn -> ActionPill("Done", accent) { vm.finderDone() }
                            SettingsViewModel.Finder.Busy -> {}
                            else -> ActionPill("Start", accent) { vm.finderStart() }
                        }
                    }
                }
            }
            Text("Navigation", style = Gp5Type.Title, color = Gp5Palette.Text, modifier = Modifier.padding(top = 24.dp))
            Text("One-tap Home and Work routes on the Home screen", style = Gp5Type.Label, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            Group {
                SettingRow("Maps app", "Auto uses Google Maps, then Waze") {
                    Segmented(listOf("Auto", "Maps", "Waze"), s.navApp.ordinal) { vm.navApp(NavApp.entries[it]) }
                }
                SettingRow("Home", "An address, place name or lat,lng") {
                    TextSetting(s.homePlace, "e.g. Maharagama", { it.take(120) }) { vm.homePlace(it) }
                }
                SettingRow("Work", "Blank hides the button") {
                    TextSetting(s.workPlace, "e.g. Colombo 03", { it.take(120) }) { vm.workPlace(it) }
                }
            }
            Text("Trips & fuel", style = Gp5Type.Title, color = Gp5Palette.Text, modifier = Modifier.padding(top = 24.dp))
            Text("Trips record by themselves while the OBD adapter is connected", style = Gp5Type.Label, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            Group {
                SettingRow("Odometer", if (s.odometerKm > 0) "Each trip adds its distance" else "Type the km shown on the dashboard") {
                    TextSetting(
                        if (s.odometerKm > 0) "%.0f".format(s.odometerKm) else "", "km",
                        { it.filter(Char::isDigit).take(7) },
                    ) { vm.odometer(it.toDoubleOrNull() ?: 0.0) }
                }
                SettingRow("End a trip after", "Time stopped with the car still on") {
                    val options = listOf(5, 10, 20)
                    Segmented(options.map { "$it min" }, options.indexOf(s.tripEndMinutes).coerceAtLeast(0)) { vm.tripEnd(options[it]) }
                }
                SettingRow("Keep trips", "Starred trips are always kept") {
                    val options = listOf(30, 90, 365, 0)
                    Segmented(listOf("30 d", "90 d", "1 yr", "Always"), options.indexOf(s.keepTripsDays).coerceAtLeast(0)) { vm.keepTrips(options[it]) }
                }
                SettingRow("Oil change every", "Shown in Trips > Totals") {
                    val options = listOf(5000, 7500, 10000)
                    Segmented(listOf("5,000", "7,500", "10,000"), options.indexOf(s.oilIntervalKm).coerceAtLeast(0)) { vm.oilInterval(options[it]) }
                }
                SettingRow(
                    "Oil changed",
                    when {
                        s.odometerKm <= 0 -> "Set the odometer first"
                        s.oilChangedAtKm <= 0 -> "Not recorded yet"
                        else -> "At %,.0f km · next at %,.0f km".format(s.oilChangedAtKm, s.oilChangedAtKm + s.oilIntervalKm)
                    },
                ) {
                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Gp5Palette.Surface1)
                            .clickable(enabled = s.odometerKm > 0) { vm.oilChangedAt(s.odometerKm) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Changed today", style = Gp5Type.Label.copy(color = if (s.odometerKm > 0) LocalGp5Look.current.accent else Gp5Palette.Text3)) }
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

@Composable
private fun ActionPill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Gp5Palette.Surface1).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Gp5Type.Label.copy(color = color)) }
}
