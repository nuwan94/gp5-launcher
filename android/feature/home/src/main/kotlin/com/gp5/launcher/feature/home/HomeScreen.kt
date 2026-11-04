package com.gp5.launcher.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gp5.launcher.car3d.CarScene
import com.gp5.launcher.car3d.CarStage as Car3d
import com.gp5.launcher.core.data.AppsRepository
import com.gp5.launcher.core.data.SettingsRepository
import com.gp5.launcher.core.design.AppIcon
import com.gp5.launcher.core.design.DockGlyph
import com.gp5.launcher.core.design.DockSlot
import com.gp5.launcher.core.design.EmptyDockSlot
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.Gp5Dimens
import com.gp5.launcher.core.design.Gp5Motion
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.IconLoader
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.ModePill
import com.gp5.launcher.core.design.R as DesignR
import com.gp5.launcher.core.design.pressScale
import com.gp5.launcher.core.design.rememberBounds
import com.gp5.launcher.core.design.trackBounds
import com.gp5.launcher.core.model.AppCategory
import com.gp5.launcher.core.model.AppEntry
import com.gp5.launcher.core.model.LauncherSettings
import com.gp5.launcher.core.model.VehicleState
import com.gp5.launcher.media.MediaRepository
import com.gp5.launcher.obd.VehicleStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gp5.launcher.core.model.MediaState
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.gp5.launcher.core.data.Navigator
import com.gp5.launcher.core.data.HeadlightRepository
import androidx.compose.ui.graphics.graphicsLayer

@HiltViewModel
class HomeViewModel @Inject constructor(
    vehicle: VehicleStateRepository,
    media: MediaRepository,
    private val apps: AppsRepository,
    settings: SettingsRepository,
    private val headlights: HeadlightRepository,
) : ViewModel() {
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    /** Headlights from the head unit (night mode / ILL wire); the button flips them by hand. */
    val lights = headlights.state
    fun toggleLights() = headlights.toggle()
    val state = vehicle.state
    private val mediaRepo = media
    val nowPlaying = media.state
    val art = media.art
    fun playPause() = mediaRepo.playPause()
    fun next() = mediaRepo.next()
    fun previous() = mediaRepo.previous()
    fun refreshMedia() = mediaRepo.refresh()
    fun allowMedia() = mediaRepo.requestAccess()
    val appsState = apps.state
    val icons: IconLoader = { pkg, px -> apps.icon(pkg, px) }
    fun launch(pkg: String, bounds: android.graphics.Rect?) = apps.launch(pkg, bounds)
}

/**
 * Home (DESIGN.md section 13): the car stage, a right column (navigation, media) and the dock.
 * Only real data is shown: car values appear once the OBD adapter is connected, and the right column
 * offers the installed navigation and music apps until their live feeds land.
 */
@Composable
fun HomeScreen(onOpenApps: () -> Unit, onOpenSettings: () -> Unit = {}, vm: HomeViewModel = hiltViewModel()) {
    val car by vm.state.collectAsStateWithLifecycle()
    val media by vm.nowPlaying.collectAsStateWithLifecycle()
    val apps by vm.appsState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lights by vm.lights.collectAsStateWithLifecycle()
    // notification access is granted in system settings: re-check whenever Home comes back
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshMedia() }
    val look = LocalGp5Look.current
    val g = Gp5Dimens.Gutter
    val visible = apps.apps.filter { it.packageName !in apps.hidden }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(g)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(g)) {
            CarStage(
                scene = CarScene(speedKmh = car.speedKmh, lightsOn = lights.on, ambient = look.ambient, connected = car.hasSpeed),
                renderer = settings.carRenderer,
                car = car,
                onLights = vm::toggleLights,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Column(Modifier.width(Gp5Dimens.RightCol).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(g)) {
                NavCard(
                    settings = settings,
                    apps = visible.filter { it.category == AppCategory.Navigation }.take(3),
                    icons = vm.icons,
                    onLaunch = vm::launch,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                val musicApps = visible.filter { it.category == AppCategory.Media }.sortedByDescending { apps.isDocked(it.packageName) }.take(3)
                if (media.active) {
                    NowPlayingCard(media, vm, Modifier.fillMaxWidth().weight(1f))
                } else {
                    LauncherCard(
                        title = "Nothing playing",
                        subtitle = if (media.hasAccess) "Open a music app" else "Allow notification access to show music here",
                        apps = musicApps,
                        icons = vm.icons,
                        onLaunch = vm::launch,
                        phase = 240f,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        action = if (media.hasAccess) null else ("Allow" to vm::allowMedia),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(Gp5Dimens.Dock), horizontalArrangement = Arrangement.spacedBy(g)) {
            // The user's dock (shared with the Apps drawer); empty slots open the drawer
            apps.dock.forEachIndexed { i, app ->
                if (app != null) {
                    DockSlot(app.packageName, app.label, vm.icons, onClick = { b -> vm.launch(app.packageName, b) }, phaseDeg = i * 60f, modifier = Modifier.weight(1f))
                } else {
                    EmptyDockSlot(onClick = onOpenApps, modifier = Modifier.weight(1f))
                }
            }
            DockGlyph(DesignR.drawable.ic_rail_apps, "All apps", onClick = onOpenApps, phaseDeg = 30f, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CarStage(scene: CarScene, renderer: com.gp5.launcher.core.model.CarRenderer, car: VehicleState, onLights: () -> Unit, modifier: Modifier) {
    val shown by animateFloatAsState(car.speedKmh, tween(Gp5Motion.Base), label = "speed")
    val look = LocalGp5Look.current
    GpCard(modifier) {
        // The car fills the card (live Filament or the Blender turntable); drag turns it
        Car3d(scene, renderer, Modifier.fillMaxSize())
        // Speed from OBD, or GPS with the adapter unplugged; the drive mode needs OBD
        AnimatedVisibility(car.hasSpeed, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${shown.roundToInt()}", style = Gp5Type.Hero, color = Gp5Palette.Text)
                    Text(if (car.speedSource == com.gp5.launcher.core.model.SpeedSource.Gps) " km/h · GPS" else " km/h", style = Gp5Type.Label, modifier = Modifier.padding(bottom = 10.dp))
                }
                if (car.connected) {
                    Spacer(Modifier.height(8.dp))
                    ModePill(car.mode)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Only what the car reports: hybrid battery (PID 5B) and fuel level (PID 2F)
            val soc = car.socPercent.takeIf { car.connected }
            val fuel = car.fuelPercent.takeIf { car.connected }
            if (soc != null) Meter("Battery", soc, Gp5Palette.Ev, Modifier.weight(1f))
            if (fuel != null) Meter("Fuel", fuel, Gp5Palette.Text2, Modifier.weight(1f))
            if (soc == null && fuel == null) Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (scene.lightsOn) look.accentSoft else Gp5Palette.Surface2)
                    .clickable(onClick = onLights),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_headlight), "Headlights",
                    tint = if (scene.lightsOn) look.accent else Gp5Palette.Text2,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun Meter(label: String, percent: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val f by animateFloatAsState((percent / 100f).coerceIn(0f, 1f), tween(Gp5Motion.Slow, easing = Gp5Motion.EaseOut), label = label)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label ${percent.roundToInt()}%", style = Gp5Type.Label.copy(color = Gp5Palette.Text), fontSize = 13.sp)
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Gp5Palette.Surface2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

/** A right-column card: title, one line, and launch tiles for matching installed apps. */
@Composable
private fun LauncherCard(
    title: String,
    subtitle: String,
    apps: List<AppEntry>,
    icons: IconLoader,
    onLaunch: (String, android.graphics.Rect?) -> Unit,
    phase: Float,
    modifier: Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
    GpCard(modifier, phaseDeg = phase) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = Gp5Type.Title, color = Gp5Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = Gp5Type.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (action != null) {
                    val look = LocalGp5Look.current
                    Text(
                        action.first, style = Gp5Type.Label.copy(color = look.accent),
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(Gp5Dimens.RadiusCtl))
                            .background(look.accentSoft).clickable(onClick = action.second)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                apps.forEach { app -> AppTile(app, icons, onLaunch) }
            }
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, icons: IconLoader, onLaunch: (String, android.graphics.Rect?) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val bounds = rememberBounds()
    val scale = pressScale(source)
    Column(
        Modifier.width(80.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(Gp5Dimens.RadiusCtl)).background(Gp5Palette.Surface2)
            .trackBounds(bounds)
            .combinedClickable(interactionSource = source, indication = null) { onLaunch(app.packageName, bounds.rect) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app.packageName, 36.dp, icons)
        Text(app.label, style = Gp5Type.Caption.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
    }
}

/** Live media session: art, track, progress and transport. Tapping the track opens the player app. */
@Composable
private fun NowPlayingCard(media: MediaState, vm: HomeViewModel, modifier: Modifier) {
    val look = LocalGp5Look.current
    val art by vm.art.collectAsStateWithLifecycle()
    val bounds = rememberBounds()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(media.playing, media.positionAt) {
        now = android.os.SystemClock.elapsedRealtime()
        while (media.playing) { delay(500); now = android.os.SystemClock.elapsedRealtime() }
    }
    GpCard(modifier, phaseDeg = 240f) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().weight(1f).trackBounds(bounds).clickable { vm.launch(media.packageName, bounds.rect) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(Gp5Dimens.RadiusCtl)).background(Gp5Palette.Surface2), contentAlignment = Alignment.Center) {
                    val bmp = art
                    if (bmp != null) {
                        Image(remember(bmp) { bmp.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else if (media.bluetooth) {
                        Icon(painterResource(DesignR.drawable.ic_bluetooth), null, tint = look.accent, modifier = Modifier.size(36.dp))
                    } else {
                        AppIcon(media.packageName, 40.dp, vm.icons)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(media.title, style = Gp5Type.Title, color = Gp5Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (media.artist.isNotBlank()) Text(media.artist, style = Gp5Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (media.bluetooth) Icon(painterResource(DesignR.drawable.ic_bluetooth), "Bluetooth", tint = look.accent, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                        Text(media.source, style = Gp5Type.Caption.copy(color = look.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (media.durationMs > 0) {
                val pos = media.positionNow(now)
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Gp5Palette.Surface2)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((pos.toFloat() / media.durationMs).coerceIn(0f, 1f)).background(look.accent))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(clock(pos), style = Gp5Type.Caption.copy(fontSize = 11.sp))
                    Spacer(Modifier.weight(1f))
                    Text(clock(media.durationMs), style = Gp5Type.Caption.copy(fontSize = 11.sp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Transport(DesignR.drawable.ic_skip_prev, "Previous", enabled = media.canSkipPrevious, onClick = vm::previous)
                Transport(if (media.playing) DesignR.drawable.ic_pause else DesignR.drawable.ic_play, if (media.playing) "Pause" else "Play", primary = true, onClick = vm::playPause)
                Transport(DesignR.drawable.ic_skip_next, "Next", enabled = media.canSkipNext, onClick = vm::next)
            }
        }
    }
}

@Composable
private fun Transport(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val look = LocalGp5Look.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(
        Modifier.size(if (primary) 56.dp else 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(if (primary) look.accentSoft else Gp5Palette.Surface2)
            .combinedClickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), label,
            tint = when { !enabled -> Gp5Palette.Text2.copy(alpha = 0.4f); primary -> look.accent; else -> Gp5Palette.Text },
            modifier = Modifier.size(if (primary) 26.dp else 22.dp),
        )
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Navigation: one-tap Home and Work routes in the preferred maps app, plus the installed maps apps. */
@Composable
private fun NavCard(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    icons: IconLoader,
    onLaunch: (String, android.graphics.Rect?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val places = listOfNotNull(
        settings.homePlace.takeIf { it.isNotBlank() }?.let { Triple("Home", it, DesignR.drawable.ic_home) },
        settings.workPlace.takeIf { it.isNotBlank() }?.let { Triple("Work", it, DesignR.drawable.ic_briefcase) },
    )
    fun go(place: String) {
        if (!Navigator.navigate(context, place, settings.navApp)) Toast.makeText(context, "No maps app can start a route", Toast.LENGTH_SHORT).show()
    }
    GpCard(modifier, phaseDeg = 120f) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Navigation", style = Gp5Type.Title, color = Gp5Palette.Text, maxLines = 1)
            if (places.isEmpty()) {
                Text(
                    "Set Home and Work in Settings for one-tap routes", style = Gp5Type.Label, maxLines = 2,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onOpenSettings),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    places.forEach { (label, place, icon) -> PlaceButton(label, place, icon, Modifier.weight(1f)) { go(place) } }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                apps.forEach { app -> AppTile(app, icons, onLaunch) }
            }
        }
    }
}

@Composable
private fun PlaceButton(label: String, place: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    val look = LocalGp5Look.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Row(
        modifier.height(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(Gp5Dimens.RadiusCtl)).background(Gp5Palette.Surface2)
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = look.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, style = Gp5Type.Body, color = Gp5Palette.Text, maxLines = 1)
            Text(place, style = Gp5Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
