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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxHeight
import com.gp5.launcher.core.design.R as DesignR
import androidx.compose.animation.togetherWith
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
    private val apps: com.gp5.launcher.core.data.AppsRepository,
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

    fun economy(u: com.gp5.launcher.core.model.EconomyUnit) = viewModelScope.launch { repo.setEconomyUnit(u) }
    fun welcome(on: Boolean) = viewModelScope.launch { repo.setWelcome(on) }
    fun driverName(n: String) = viewModelScope.launch { repo.setDriverName(n) }
    fun welcomeVoice(on: Boolean) = viewModelScope.launch { repo.setWelcomeVoice(on) }
    fun welcomeMusic(on: Boolean) = viewModelScope.launch { repo.setWelcomeMusic(on) }
    fun playWelcome() = com.gp5.launcher.core.data.WelcomeTrigger.fire()

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: kotlinx.coroutines.flow.StateFlow<String?> = _message
    private var messageJob: kotlinx.coroutines.Job? = null
    fun say(text: String) {
        _message.value = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch { kotlinx.coroutines.delay(3_000); _message.value = null }
    }

    /** Settings and the dock / hidden apps as one JSON file (trips and the fuel log are separate: Trips > Export CSV). */
    fun backup(context: android.content.Context, uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val json = org.json.JSONObject().put("format", "gp5-launcher-backup").put("version", 1)
                .put("settings", repo.backup()).put("apps", apps.backup())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toString(2).toByteArray()) } ?: error("could not write")
            }
        }.onSuccess { say("Settings saved") }.onFailure { say("Backup failed: ${it.message}") }
    }

    fun restore(context: android.content.Context, uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("could not read")
            }
            val json = org.json.JSONObject(text)
            require(json.optString("format") == "gp5-launcher-backup") { "not a GP5 launcher backup" }
            json.optJSONObject("settings")?.let { repo.restore(it) }
            json.optJSONObject("apps")?.let { apps.restore(it) }
        }.onSuccess { say("Settings restored") }.onFailure { say("Restore failed: ${it.message}") }
    }

    /** Back to defaults; trips and the fuel log are kept. */
    fun resetLauncher() = viewModelScope.launch {
        repo.reset()
        apps.restore(org.json.JSONObject())
        say("Launcher reset. Trips and the fuel log are kept")
    }
}

/** Settings categories (DESIGN.md section 17): a 268 dp list and the selected page. */
private enum class Page(val title: String, val sub: String, val icon: Int) {
    Obd("Vehicle & OBD", "The OBD adapter (optional: speed falls back to GPS)", DesignR.drawable.ic_rail_vehicle),
    Display("Display", "Colour, layout, motion and headlights", DesignR.drawable.ic_palette),
    Units("Units", "How economy is shown", DesignR.drawable.ic_ruler),
    Navigation("Navigation", "One-tap Home and Work routes", DesignR.drawable.ic_map),
    HomeCar("Home & car", "The 3D car and now playing", DesignR.drawable.ic_car),
    Trips("Trips & fuel", "Odometer, trip rules, oil reminder", DesignR.drawable.ic_rail_trips),
    Data("Data & backup", "Back up, restore or reset the launcher", DesignR.drawable.ic_database),
    About("About", "Version, head unit and licences", DesignR.drawable.ic_info),
}

/**
 * Settings (DESIGN.md section 17, design/settings.html): category list with a live one-line summary,
 * the page on the right. Everything saves to DataStore ("gp5.settings") as it changes.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val car by vm.vehicle.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Page.Obd) }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GpCard(Modifier.width(268.dp).fillMaxHeight()) {
                val version = appVersion()
                CategoryList(page, { page = it }) { p -> summary(p, s, car, np.hasAccess, version) }
            }
            GpCard(Modifier.weight(1f).fillMaxHeight(), phaseDeg = 140f) {
                Column(Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250, delayMillis = 60)) +
                                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(250)) { 10 * dir })
                                .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                        },
                        label = "page",
                    ) { p ->
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
                                Text(p.title, style = Gp5Type.Title.copy(fontSize = Gp5Type.Title.fontSize * 1.1f), color = Gp5Palette.Text)
                                Text(p.sub, style = Gp5Type.Label)
                            }
                            if (car.isDriving && p in setOf(Page.Obd, Page.Data)) {
                                Text(
                                    "Some options are locked while driving",
                                    style = Gp5Type.Label.copy(color = Gp5Palette.Warn),
                                    modifier = Modifier.fillMaxWidth().background(Gp5Palette.Warn.copy(alpha = 0.12f)).padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            }
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                                when (p) {
                                    Page.Obd -> ObdPage(vm, s, car)
                                    Page.Display -> DisplayPage(vm, s)
                                    Page.Units -> UnitsPage(vm, s)
                                    Page.Navigation -> NavigationPage(vm, s)
                                    Page.HomeCar -> HomeCarPage(vm, s)
                                    Page.Trips -> TripsPage(vm, s)
                                    Page.Data -> DataPage(vm, car.isDriving)
                                    Page.About -> AboutPage()
                                }
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(message != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Text(
                message.orEmpty(), style = Gp5Type.Body, color = Gp5Palette.Text,
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface2).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

private fun summary(p: Page, s: LauncherSettings, car: VehicleState, mediaAccess: Boolean, version: String): Pair<String, Color?> = when (p) {
    Page.Obd -> if (!s.obdEnabled) "Off · speed from GPS" to null else linkTitle(car) to (if (car.connected) Gp5Palette.Ev else null)
    Page.Display -> "${LauncherSettings.ACCENTS.firstOrNull { it.first == s.accentArgb }?.second ?: "Custom"} · rail ${s.railSide.name.lowercase()}" to null
    Page.Units -> "km/h · °C · ${s.economyUnit.label}" to null
    Page.Navigation -> (listOfNotNull(s.homePlace.takeIf { it.isNotBlank() }?.let { "Home" }, s.workPlace.takeIf { it.isNotBlank() }?.let { "Work" })
        .joinToString(" + ").ifEmpty { "No places set" } + " · " + when (s.navApp) { NavApp.Auto -> "Auto"; NavApp.GoogleMaps -> "Maps"; NavApp.Waze -> "Waze" }) to null
    Page.HomeCar -> "${when (s.carRenderer) { CarRenderer.Auto -> "Auto 3D"; CarRenderer.Live -> "Live 3D"; CarRenderer.Turntable -> "Turntable" }} · ${if (mediaAccess) "music on" else "music needs access"}" to null
    Page.Trips -> (if (s.odometerKm > 0) "%,.0f km".format(java.util.Locale.UK, s.odometerKm) else "Odometer not set") + " · ends after ${s.tripEndMinutes} min" to null
    Page.Data -> "Settings JSON · reset" to null
    Page.About -> "Version $version" to null
}

@Composable
private fun CategoryList(selected: Page, onSelect: (Page) -> Unit, summary: (Page) -> Pair<String, Color?>) {
    val look = LocalGp5Look.current
    val y by animateDpAsState(60.dp * selected.ordinal, spring(dampingRatio = 0.8f), label = "plate")
    Box(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(Modifier.offset(y = y).padding(horizontal = 8.dp).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp)).background(Gp5Palette.Surface2)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(look.accent))
        }
        Column {
            Page.entries.forEach { p ->
                val (line, color) = summary(p)
                Row(
                    Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp)).clickable { onSelect(p) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(p.icon), null,
                        tint = if (p == selected) look.accent else Gp5Palette.Text2, modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(p.title, style = Gp5Type.Body.copy(fontSize = 15.sp), color = Gp5Palette.Text, maxLines = 1)
                        Text(line, style = Gp5Type.Caption.copy(color = color ?: Gp5Palette.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, sub: String? = null) {
    Text(title, style = Gp5Type.Body.copy(color = Gp5Palette.Text2), modifier = Modifier.padding(top = 16.dp))
    if (sub != null) Text(sub, style = Gp5Type.Caption, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ObdPage(vm: SettingsViewModel, s: LauncherSettings, car: VehicleState) {
    val context = LocalContext.current
    Spacer(Modifier.height(8.dp))
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
}

@Composable
private fun DisplayPage(vm: SettingsViewModel, s: LauncherSettings) {
    Spacer(Modifier.height(8.dp))
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
    Section("Welcome", "A greeting when the car starts")
    Group {
        SettingRow("Welcome on start", "Plays for a few seconds when the head unit starts or wakes (not while driving)") {
            Toggle(s.welcome) { vm.welcome(it) }
        }
        SettingRow("Your name", "Shown as \"Good morning, ${s.driverName}\"") {
            TextSetting(if (s.driverName == LauncherSettings.DEFAULT_NAME) "" else s.driverName, LauncherSettings.DEFAULT_NAME, { it.take(24) }) { vm.driverName(it) }
        }
        SettingRow("Spoken greeting", "Says \"Good morning, <name>\" through the head unit's text-to-speech") {
            Toggle(s.welcomeVoice) { vm.welcomeVoice(it) }
        }
        SettingRow("Welcome music", "A short chime; music from your phone dips under it") {
            Toggle(s.welcomeMusic) { vm.welcomeMusic(it) }
        }
        SettingRow("Preview", "Play the welcome now") {
            ActionPill("Play", LocalGp5Look.current.accent) { vm.playWelcome() }
        }
    }
    Section("Headlights", "Light up the car's lamps when your headlights are on, without OBD")
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
}

@Composable
private fun UnitsPage(vm: SettingsViewModel, s: LauncherSettings) {
    Spacer(Modifier.height(8.dp))
    val unit = s.economyUnit
    Group {
        SettingRow("Fuel economy", "Trips, totals, fuel log and the Vehicle screen") {
            Segmented(listOf("km/L", "L/100 km"), unit.ordinal) { vm.economy(com.gp5.launcher.core.model.EconomyUnit.entries[it]) }
        }
        SettingRow("Preview", "A typical GP5 trip: ${unit.text(22.4f)} ${unit.label}; highway ${unit.text(19.6f)} ${unit.label}") {}
        SettingRow("Speed, temperature, distance", "km/h, °C and km (Sri Lanka)") {}
    }
}

@Composable
private fun NavigationPage(vm: SettingsViewModel, s: LauncherSettings) {
    Spacer(Modifier.height(8.dp))
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
}

@Composable
private fun HomeCarPage(vm: SettingsViewModel, s: LauncherSettings) {
    Section("Car", "The 3D car on Home")
    val active by vm.activeRenderer.collectAsStateWithLifecycle()
    Group {
        SettingRow("3D view", if (active.isEmpty()) "Auto picks live 3D when the unit has OpenGL ES 3" else "Showing: $active") {
            Segmented(listOf("Auto", "Live 3D", "Turntable"), s.carRenderer.ordinal) { vm.carRenderer(CarRenderer.entries[it]) }
        }
    }
    Section("Now playing", "Music apps and phone music over Bluetooth on the Home card")
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
}

@Composable
private fun TripsPage(vm: SettingsViewModel, s: LauncherSettings) {
    Spacer(Modifier.height(8.dp))
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

@Composable
private fun DataPage(vm: SettingsViewModel, driving: Boolean) {
    val context = LocalContext.current
    val accent = LocalGp5Look.current.accent
    var confirm by remember { mutableStateOf(false) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.backup(context, uri)
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restore(context, uri)
    }
    fun locked(run: () -> Unit) { if (driving) vm.say("Stop the car to change this") else run() }
    Spacer(Modifier.height(8.dp))
    Group {
        SettingRow("Back up settings", "Look, OBD, places, units, dock and hidden apps, as a JSON file (a USB drive works)") {
            ActionPill("Save", if (driving) Gp5Palette.Text3 else accent) {
                locked { runCatching { save.launch("gp5-settings-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json") }.onFailure { vm.say("This unit has no file picker") } }
            }
        }
        SettingRow("Restore settings", "From a backup file. Replaces the current settings") {
            ActionPill("Restore", if (driving) Gp5Palette.Text3 else accent) {
                locked { runCatching { open.launch(arrayOf("application/json", "text/plain", "*/*")) }.onFailure { vm.say("This unit has no file picker") } }
            }
        }
        SettingRow("Trips and fuel log", "Export them as CSV from the Trips screen") {}
    }
    Section("Reset")
    Group {
        if (!confirm) {
            SettingRow("Reset launcher", "Settings, dock and hidden apps go back to defaults. Trips and the fuel log are kept") {
                ActionPill("Reset", if (driving) Gp5Palette.Text3 else Color(0xFFE24B4A)) { locked { confirm = true } }
            }
        } else {
            SettingRow("Reset everything to defaults?", "This cannot be undone. Back up first if unsure") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill("Cancel", Gp5Palette.Text2) { confirm = false }
                    ActionPill("Reset now", Color(0xFFE24B4A)) { confirm = false; vm.resetLauncher() }
                }
            }
        }
    }
}

@Composable
private fun AboutPage() {
    val active by LiveHealth.active.collectAsStateWithLifecycle()
    Spacer(Modifier.height(8.dp))
    Group {
        SettingRow("GP5 Launcher", "Version ${appVersion()} · github.com/nuwan94/gp5-launcher") {}
        SettingRow("Head unit", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})") {}
        SettingRow("3D renderer", active.ifEmpty { "Not started yet (open Home)" }) {}
    }
    Section("3D model")
    Group {
        SettingRow("Honda Fit GP5", "Model by Ddiaz Design, CC BY-NC-SA 4.0, modified (paint, lamps, badges). Same licence") {}
        SettingRow("Trademarks", "Honda, Fit and the H mark are Honda trademarks. Personal project, not affiliated with Honda") {}
    }
    Section("Open-source licences")
    Group {
        listOf(
            "Jetpack Compose, AndroidX, Room, DataStore" to "Apache License 2.0",
            "Filament and gltfio (Google)" to "Apache License 2.0",
            "Dagger / Hilt, Kotlin coroutines" to "Apache License 2.0",
            "Tabler Icons" to "MIT License",
        ).forEach { (name, licence) -> SettingRow(name, licence) {} }
    }
}

private var cachedVersion: String? = null

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return cachedVersion ?: runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty().also { cachedVersion = it }
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
