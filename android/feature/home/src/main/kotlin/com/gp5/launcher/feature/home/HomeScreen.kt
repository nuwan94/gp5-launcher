package com.gp5.launcher.feature.home

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gp5.launcher.car3d.CarScene
import com.gp5.launcher.car3d.CarStage as Car3d
import com.gp5.launcher.core.data.AppsRepository
import com.gp5.launcher.core.data.HeadlightRepository
import com.gp5.launcher.core.data.Navigator
import com.gp5.launcher.core.data.SettingsRepository
import com.gp5.launcher.core.data.WeatherRepository
import com.gp5.launcher.core.design.AppIcon
import com.gp5.launcher.core.design.CompassDial
import com.gp5.launcher.core.design.SpeedGauge
import com.gp5.launcher.core.design.Gp5Motion
import com.gp5.launcher.core.design.Gp5Num
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.IconLoader
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.ModePill
import com.gp5.launcher.core.design.R as DesignR
import com.gp5.launcher.core.design.WeatherIcon
import com.gp5.launcher.core.design.pressScale
import com.gp5.launcher.core.model.LauncherSettings
import com.gp5.launcher.core.model.MediaState
import com.gp5.launcher.core.model.NavStep
import com.gp5.launcher.core.model.SpeedSource
import com.gp5.launcher.core.model.VehicleState
import com.gp5.launcher.core.model.Weather
import com.gp5.launcher.media.MediaRepository
import com.gp5.launcher.media.NavigationRepository
import com.gp5.launcher.obd.GpsSpeed
import com.gp5.launcher.obd.VehicleStateRepository
import com.gp5.launcher.trips.TripRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class HomeViewModel @Inject constructor(
    vehicle: VehicleStateRepository,
    settings: SettingsRepository,
    headlights: HeadlightRepository,
    weather: WeatherRepository,
    navigation: NavigationRepository,
    gps: GpsSpeed,
    trips: TripRecorder,
) : ViewModel() {
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    val lights = headlights.state
    val state = vehicle.state
    val weather = weather.weather
    val step = navigation.step
    val place = gps.place
    val trip = trips.live
}

/**
 * Home: speed and the drive on the left, the car side-on (parked) or from behind on the road
 * (driving) filling the right, and the turn card while navigating, else the weather.
 */
@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val car by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lights by vm.lights.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        // the car fills the right two thirds; its left edge melts into the background
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.BottomEnd).padding(top = 96.dp).clipToBounds()) {
            Car3d(
                CarScene(speedKmh = car.speedKmh, lightsOn = lights.on, ambient = !settings.calmMotion, connected = car.hasSpeed, day = Gp5Palette.isDay),
                settings.carRenderer, Modifier.fillMaxSize(),
            )
            val bg = Gp5Palette.Bg
            Canvas(Modifier.fillMaxSize()) {
                // melt the left and top edges into the page
                drawRect(Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0f)), endX = 120.dp.toPx()))
                drawRect(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)), endY = 56.dp.toPx()))
            }
        }
        SpeedColumn(car, place?.bearing, trip, weather, lights.on, settings, Modifier.fillMaxHeight().width(410.dp).padding(start = 8.dp, top = 6.dp))
        Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).width(330.dp)) {
            AnimatedContent(
                targetState = step != null,
                transitionSpec = { (fadeIn(tween(Gp5Motion.Base)) + slideInVertically(tween(Gp5Motion.Base, easing = Gp5Motion.EaseOut)) { -it / 4 }).togetherWith(fadeOut(tween(150))) },
                label = "turnOrWeather",
            ) { navigating ->
                if (navigating) step?.let { TurnCard(it) } else Column {
                    weather?.let { WeatherCard(it) }

                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------- left column

@Composable
private fun SpeedColumn(car: VehicleState, heading: Float?, trip: com.gp5.launcher.trips.LiveTrip?, weather: Weather?, lightsOn: Boolean, settings: LauncherSettings, modifier: Modifier) {
    val look = LocalGp5Look.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeedGauge(car.speedKmh, car.hasSpeed, if (car.speedSource == SpeedSource.Gps) "km/h · GPS" else "km/h", size = 236.dp)
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CompassDial(heading, size = 150.dp)
                if (car.connected) ModePill(car.mode, Modifier.padding(top = 12.dp))
            }
        }
        // engine power from rpm, with OBD
        if (car.connected) {
            val lit by animateFloatAsState((car.rpm / 6000f).coerceIn(0f, 1f), tween(Gp5Motion.Base), label = "power")
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val n = 24
                for (i in 0 until n) {
                    val on = i < (lit * n).roundToInt()
                    Box(Modifier.size(width = 12.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(if (on) look.accent else Gp5Palette.Surface2))
                }
            }
        }
        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Gp5Palette.Line))
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Stat("%.1f km".format(Locale.UK, trip?.distanceKm ?: 0.0), "Trip")
            Stat(trip?.let { elapsed(System.currentTimeMillis() - it.startMs) } ?: "0:00", "Drive", divider = true)
            car.economyKmL?.let { Stat("%.1f".format(Locale.UK, look.economy.of(it)), look.economy.label, divider = true) }
            val temp = weather?.tempC ?: car.ambientC
            if (temp != null) Stat("${temp.roundToInt()}°", weather?.place ?: "Outside", divider = true)
        }
        Places(settings)
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lightsOn) {
                Icon(painterResource(DesignR.drawable.ic_bulb), null, tint = Gp5Palette.Ev, modifier = Modifier.size(18.dp))
                Text("Headlights on", style = Gp5Type.Label.copy(color = Gp5Palette.Ev), modifier = Modifier.padding(start = 6.dp, end = 14.dp))
            }
            car.socPercent?.takeIf { car.connected }?.let { Text("Battery ${it.roundToInt()}%", style = Gp5Type.Label) }
            car.fuelPercent?.takeIf { car.connected }?.let { Text("  ·  Fuel ${it.roundToInt()}%", style = Gp5Type.Label) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, divider: Boolean = false) {
    Row {
        if (divider) Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(40.dp).background(Gp5Palette.Line))
        Column {
            Text(value, style = Gp5Type.Title.copy(fontFamily = Gp5Num, fontSize = 24.sp), color = Gp5Palette.Text, maxLines = 1)
            Text(label, style = Gp5Type.Caption.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun elapsed(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return "%d:%02d h".format(m / 60, m % 60)
}

// ------------------------------------------------------------------------------------- right side

/** The next turn from Google Maps / Waze (phone or head unit). */
@Composable
private fun TurnCard(step: NavStep) {
    val look = LocalGp5Look.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(look.accent).border(1.dp, Color.White.copy(alpha = 0.25f), shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            val bmp = remember(step.iconPng) { step.iconPng?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
            if (bmp != null) Image(bmp, null, Modifier.size(40.dp), contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Color.White))
            else Icon(painterResource(DesignR.drawable.ic_turn_right), null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(step.distance, style = Gp5Type.Display.copy(fontSize = 30.sp, lineHeight = 32.sp), color = Color.White, maxLines = 1)
            Text(step.instruction, style = Gp5Type.Body.copy(fontSize = 15.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(step.eta, step.source).joinToString(" · "), style = Gp5Type.Caption.copy(color = Color.White.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val HourFmt = SimpleDateFormat("h a", Locale.UK)

/** Weather now and the next hours. */
@Composable
private fun WeatherCard(w: Weather) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Gp5Palette.Surface1.copy(alpha = 0.92f)).border(1.dp, Gp5Palette.Line, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(w.code, w.isDay, size = 46.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${w.tempC.roundToInt()}°", style = Gp5Type.Display.copy(fontSize = 32.sp, lineHeight = 34.sp), color = Gp5Palette.Text)
                    Text("  ${w.summary}", style = Gp5Type.Label, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(
                    listOfNotNull(
                        w.place,
                        if (w.todayMaxC != null && w.todayMinC != null) "H ${w.todayMaxC!!.roundToInt()}°  L ${w.todayMinC!!.roundToInt()}°" else null,
                        w.rainChance?.let { "Rain $it%" },
                    ).joinToString(" · "),
                    style = Gp5Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            w.hours.drop(1).take(5).forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(HourFmt.format(Date(h.timeMs)).lowercase(), style = Gp5Type.Caption.copy(fontSize = 11.sp))
                    WeatherIcon(h.code, h.isDay, Modifier.padding(vertical = 4.dp), size = 24.dp)
                    Text("${h.tempC.roundToInt()}°", style = Gp5Type.Label.copy(color = Gp5Palette.Text))
                    h.rainChance?.takeIf { it >= 20 }?.let { Text("$it%", style = Gp5Type.Caption.copy(fontSize = 10.sp, color = Gp5Palette.Regen)) }
                }
            }
        }
    }
}

/** One-tap routes Home / Work (Settings › Navigation). */
@Composable
private fun Places(settings: LauncherSettings) {
    val context = LocalContext.current
    val places = listOfNotNull(
        settings.homePlace.takeIf { it.isNotBlank() }?.let { Triple("Home", it, DesignR.drawable.ic_home) },
        settings.workPlace.takeIf { it.isNotBlank() }?.let { Triple("Work", it, DesignR.drawable.ic_briefcase) },
    )
    if (places.isEmpty()) return
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        places.forEach { (label, place, icon) ->
            val source = remember { MutableInteractionSource() }
            val scale = pressScale(source)
            Row(
                Modifier.weight(1f).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(12.dp))
                    .background(Gp5Palette.Surface1.copy(alpha = 0.92f)).border(1.dp, Gp5Palette.Line, RoundedCornerShape(12.dp))
                    .clickable(source, null) {
                        if (!Navigator.navigate(context, place, settings.navApp)) Toast.makeText(context, "No maps app can start a route", Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), null, tint = LocalGp5Look.current.accent, modifier = Modifier.size(26.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(label, style = Gp5Type.Label.copy(color = Gp5Palette.Text, fontSize = 16.sp))
                    Text(place, style = Gp5Type.Caption.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------- now playing (dock)

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val media: MediaRepository,
    private val apps: AppsRepository,
    private val store: com.gp5.launcher.core.data.SettingsRepository,
    val sound: com.gp5.launcher.media.SoundBands,
) : ViewModel() {
    val state = media.state
    val settings = store.settings.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.gp5.launcher.core.model.LauncherSettings())
    /** Long press on now playing: the next equalizer style. */
    fun nextStyle(): com.gp5.launcher.core.model.VisualizerStyle {
        val all = com.gp5.launcher.core.model.VisualizerStyle.entries
        val next = all[(settings.value.visualizer.ordinal + 1) % all.size]
        viewModelScope.launch { store.setVisualizer(next) }
        return next
    }
    val art = media.art
    fun playPause() = media.playPause()
    fun next() = media.next()
    fun previous() = media.previous()
    fun refresh() = media.refresh()
    fun allow() = media.requestAccess()
    val icons: IconLoader = { pkg, px -> apps.icon(pkg, px) }
    fun open(pkg: String) = apps.launch(pkg, null)
}

/**
 * The dock's now-playing part: art, track, controls and a thin progress line, over the equalizer
 * (long press the track to change its style).
 */
@Composable
fun RowScope.NowPlayingBar(vm: NowPlayingViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val media by vm.state.collectAsStateWithLifecycle()
    val playing = media.active && media.playing
    val live = settings.visualizerLive && settings.visualizer != com.gp5.launcher.core.model.VisualizerStyle.Off && playing
    androidx.compose.runtime.DisposableEffect(live) {
        if (live) vm.sound.acquire()
        onDispose { if (live) vm.sound.release() }
    }
    Box(Modifier.weight(1f).fillMaxHeight()) {
        Equalizer(
            settings.visualizer, playing, LocalGp5Look.current.accent.copy(alpha = if (Gp5Palette.isDay) 0.42f else 0.38f),
            read = { if (live) vm.sound.read(it) else false },
            modifier = Modifier.fillMaxSize().padding(top = 10.dp),
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) { NowPlayingContent(vm, media) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.NowPlayingContent(vm: NowPlayingViewModel, media: MediaState) {
    val art by vm.art.collectAsStateWithLifecycle()
    val look = LocalGp5Look.current
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(media.playing, media.positionAt) {
        now = android.os.SystemClock.elapsedRealtime()
        while (media.playing) { delay(1_000); now = android.os.SystemClock.elapsedRealtime() }
    }
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Gp5Palette.Surface2)
            .clickable { if (media.active) vm.open(media.packageName) else if (!media.hasAccess) vm.allow() },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = art
        when {
            bmp != null && media.active -> Image(remember(bmp) { bmp.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            media.active && media.bluetooth -> Icon(painterResource(DesignR.drawable.ic_bluetooth), null, tint = look.accent, modifier = Modifier.size(24.dp))
            media.active -> AppIcon(media.packageName, 30.dp, vm.icons)
            else -> Icon(painterResource(DesignR.drawable.ic_music), null, tint = Gp5Palette.Text2, modifier = Modifier.size(22.dp))
        }
    }
    Column(
        Modifier.weight(1f).padding(horizontal = 12.dp).combinedClickable(
            onLongClick = { Toast.makeText(context, "Equalizer: ${vm.nextStyle().label}", Toast.LENGTH_SHORT).show() },
        ) { if (media.active) vm.open(media.packageName) else if (!media.hasAccess) vm.allow() },
    ) {
        Text(
            if (media.active) "Now playing · ${media.source}" else "Now playing",
            style = Gp5Type.Caption.copy(fontSize = 11.sp, color = look.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (media.active) media.title else if (media.hasAccess) "Nothing playing" else "Show your music here",
            style = Gp5Type.Body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = Gp5Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (media.active) media.artist else if (media.hasAccess) "Open a music app" else "Tap to allow notification access",
            style = Gp5Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (media.active && media.durationMs > 0) {
            val pos = media.positionNow(now)
            val f by animateFloatAsState((pos.toFloat() / media.durationMs).coerceIn(0f, 1f), tween(900), label = "progress")
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Gp5Palette.Surface2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(look.accent))
            }
        }
    }
    Transport(DesignR.drawable.ic_skip_prev, "Previous", vm::previous, enabled = !media.active || media.canSkipPrevious)
    Transport(if (media.playing) DesignR.drawable.ic_pause else DesignR.drawable.ic_play, if (media.playing) "Pause" else "Play", vm::playPause, primary = true)
    Transport(DesignR.drawable.ic_skip_next, "Next", vm::next, enabled = !media.active || media.canSkipNext)
}

@Composable
private fun Transport(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val look = LocalGp5Look.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(
        Modifier.size(if (primary) 58.dp else 48.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.35f }
            .clip(CircleShape).background(if (primary) look.accent else Color.Transparent)
            .clickable(source, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), label, tint = if (primary) Color.White else Gp5Palette.Text, modifier = Modifier.size(if (primary) 30.dp else 28.dp))
    }
}

@Suppress("unused") private fun MediaState.keep() = this
