package com.gp5.launcher

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gp5.launcher.core.data.SettingsRepository
import com.gp5.launcher.core.design.Destination
import com.gp5.launcher.core.design.Gp5Dimens
import com.gp5.launcher.core.design.Gp5Look
import com.gp5.launcher.core.design.Gp5Motion
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Theme
import com.gp5.launcher.core.design.Rail
import com.gp5.launcher.core.design.StatusBar
import com.gp5.launcher.core.model.LauncherSettings
import com.gp5.launcher.core.model.RailSide
import com.gp5.launcher.feature.apps.AppsScreen
import com.gp5.launcher.feature.home.HomeScreen
import com.gp5.launcher.feature.settings.SettingsScreen
import com.gp5.launcher.feature.trips.TripsScreen
import com.gp5.launcher.feature.vehicle.VehicleScreen
import com.gp5.launcher.obd.VehicleStateRepository
import com.gp5.launcher.obd.GpsSpeed
import androidx.activity.result.contract.ActivityResultContracts
import com.gp5.launcher.trips.TripRecorder
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltAndroidApp
class Gp5App : Application() {
    @Inject lateinit var trips: TripRecorder

    override fun onCreate() {
        super.onCreate()
        trips.start()   // records trips for the whole life of the process, whichever screen is open
    }
}

/**
 * The HOME activity. Registered as the launcher (HOME + DEFAULT), single task, landscape.
 * System bars stay hidden: the launcher draws its own 40 dp status bar.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Fires when the Home button is pressed while the launcher is already running. */
    private val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Inject lateinit var gps: GpsSpeed

    /** Location powers the GPS speedometer (no OBD needed) and trip place names. Asked once. */
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { gps.start() }

    override fun onResume() {
        super.onResume()
        gps.start()   // picks up a permission granted in system settings
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.tryEmit(Unit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { LauncherRoot(homePresses) }
        if (!gps.allowed() && savedInstanceState == null) {
            askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

@HiltViewModel
class LauncherViewModel @Inject constructor(
    settings: SettingsRepository,
    vehicle: VehicleStateRepository,
) : ViewModel() {
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    val vehicle = vehicle.state
}

@Composable
fun LauncherRoot(homePresses: SharedFlow<Unit>, vm: LauncherViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val car by vm.vehicle.collectAsStateWithLifecycle()
    var dest by rememberSaveable { mutableStateOf(Destination.Home) }

    // Pressing Home or Back on the launcher always returns to Home
    BackHandler(enabled = dest != Destination.Home) { dest = Destination.Home }
    LaunchedEffect(homePresses) { homePresses.collect { dest = Destination.Home } }

    val look = Gp5Look(
        accent = Color(settings.accentArgb),
        goldTrim = settings.goldTrim,
        ambient = !car.isDriving && !settings.calmMotion,
    )
    Gp5Theme(look) {
        Row(Modifier.fillMaxSize().background(Gp5Palette.Bg)) {
            val rail = @Composable { Rail(selected = dest, onSelect = { dest = it }, side = settings.railSide) }
            if (settings.railSide == RailSide.Left) rail()
            Column(Modifier.weight(1f).padding(start = Gp5Dimens.Gutter, end = Gp5Dimens.Gutter, bottom = Gp5Dimens.Gutter)) {
                StatusBar(
                    obdConnected = car.connected, outsideTempC = car.ambientC?.let { kotlin.math.round(it).toInt() },
                    gpsSpeed = car.speedSource == com.gp5.launcher.core.model.SpeedSource.Gps,
                )
                Screens(dest) { dest = it }
            }
            if (settings.railSide == RailSide.Right) rail()
        }
    }
}

@Composable
private fun Screens(dest: Destination, go: (Destination) -> Unit) {
    // Destination change: 10 dp slide in the rail direction + fade (settings.html page switch)
    AnimatedContent(
        targetState = dest,
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (fadeIn(tween(Gp5Motion.Base, delayMillis = 60)) + slideInVertically(tween(Gp5Motion.Base, easing = Gp5Motion.EaseOut)) { 24 * dir })
                .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -24 * dir })
        },
        label = "destination",
    ) { d ->
        Box(Modifier.fillMaxSize()) {
            when (d) {
                Destination.Home -> HomeScreen(onOpenApps = { go(Destination.Apps) }, onOpenSettings = { go(Destination.Settings) })
                Destination.Vehicle -> VehicleScreen(onOpenObdSettings = { go(Destination.Settings) })
                Destination.Trips -> TripsScreen()
                Destination.Apps -> AppsScreen(onHome = { go(Destination.Home) })
                Destination.Settings -> SettingsScreen()
            }
        }
    }
}
