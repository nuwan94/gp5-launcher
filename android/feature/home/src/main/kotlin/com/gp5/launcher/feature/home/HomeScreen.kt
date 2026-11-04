package com.gp5.launcher.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gp5.launcher.core.data.AppsRepository
import com.gp5.launcher.core.design.DockGlyph
import com.gp5.launcher.core.design.DockSlot
import com.gp5.launcher.core.design.EmptyDockSlot
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.IconLoader
import com.gp5.launcher.core.design.R as DesignR
import com.gp5.launcher.core.design.Gp5Dimens
import com.gp5.launcher.core.design.Gp5Motion
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.ModePill
import com.gp5.launcher.core.model.DriveMode
import com.gp5.launcher.media.MediaRepository
import com.gp5.launcher.obd.VehicleStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val vehicle: VehicleStateRepository,
    private val media: MediaRepository,
    private val apps: AppsRepository,
) : ViewModel() {
    val state = vehicle.state
    val nowPlaying = media.state
    val appsState = apps.state
    val icons: IconLoader = { pkg, px -> apps.icon(pkg, px) }
    fun launch(pkg: String, bounds: android.graphics.Rect?) = apps.launch(pkg, bounds)
    fun toggleDemoDrive(on: Boolean) = vehicle.setSimulatedDriving(on)
    fun playPause() = media.playPause()
}

/**
 * Home, Layout B (DESIGN.md section 13, design/home.html): car stage + right column (next turn, media),
 * smart cards, dock. M1 frame: live speed / mode / battery from the vehicle feed; the Filament car
 * stage, nav card content and animated widgets land in M2-M4.
 */
@Composable
fun HomeScreen(onOpenApps: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val car by vm.state.collectAsStateWithLifecycle()
    val media by vm.nowPlaying.collectAsStateWithLifecycle()
    val apps by vm.appsState.collectAsStateWithLifecycle()
    val g = Gp5Dimens.Gutter
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(g)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(g)) {
            CarStage(car.speedKmh, car.mode, car.socPercent, onDemoToggle = { vm.toggleDemoDrive(car.speedKmh < 1f) }, modifier = Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(Gp5Dimens.RightCol).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(g)) {
                GpCard(Modifier.fillMaxWidth().weight(1f), phaseDeg = 120f) {
                    Column(Modifier.padding(16.dp)) {
                        Text("400 m", style = Gp5Type.Display, color = Gp5Palette.Text)
                        Text("Turn right onto Galle Rd", style = Gp5Type.Body, color = Gp5Palette.Text)
                        Text("Next turn from the nav app notification (M3)", style = Gp5Type.Caption, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                GpCard(Modifier.fillMaxWidth().weight(1f).clickable { vm.playPause() }, phaseDeg = 240f) {
                    Column(Modifier.padding(16.dp)) {
                        Text(media.title, style = Gp5Type.Title, color = Gp5Palette.Text)
                        Text(media.artist, style = Gp5Type.Label)
                        Spacer(Modifier.height(8.dp))
                        Text(if (media.playing) "Playing · tap to pause" else "Paused · tap to play", style = Gp5Type.Caption)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(Gp5Dimens.Smart), horizontalArrangement = Arrangement.spacedBy(g)) {
            SmartCard("Last trip · 58% EV", "12.4 km · 21.0 km/L", 60f, Modifier.weight(1f))
            SmartCard("Service · oil 12% left", "Oil change in 620 km", 180f, Modifier.weight(1f))
            SmartCard("Weather", "Rain from 3 pm", 300f, Modifier.weight(1f))
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
private fun CarStage(speed: Float, mode: DriveMode, soc: Float, onDemoToggle: () -> Unit, modifier: Modifier) {
    val shown by animateFloatAsState(speed, tween(Gp5Motion.Base), label = "speed")
    val socAnim by animateFloatAsState(soc / 100f, tween(Gp5Motion.Slow, easing = Gp5Motion.EaseOut), label = "soc")
    GpCard(modifier.clickable(onClick = onDemoToggle)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${shown.roundToInt()}", style = Gp5Type.Hero, color = Gp5Palette.Text)
                Text(" km/h", style = Gp5Type.Label, modifier = Modifier.padding(bottom = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
            ModePill(mode)
        }
        Text(
            "3D car stage (Filament, M4) · tap to start or stop the demo drive",
            style = Gp5Type.Caption.copy(color = Gp5Palette.Text3),
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${soc.roundToInt()}%", style = Gp5Type.Label.copy(color = Gp5Palette.Text), fontSize = 14.sp)
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Gp5Palette.Surface2)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(socAnim)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (mode == DriveMode.Regen) Gp5Palette.Regen else Gp5Palette.Ev),
                )
            }
            Text("410 km", style = Gp5Type.Label.copy(color = Gp5Palette.Text), fontSize = 14.sp)
        }
    }
}

@Composable
private fun SmartCard(label: String, value: String, phase: Float, modifier: Modifier) {
    GpCard(modifier.fillMaxHeight(), phaseDeg = phase) {
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)) {
            Text(label, style = Gp5Type.Caption)
            Text(value, style = Gp5Type.Body, color = Gp5Palette.Text)
        }
    }
}
