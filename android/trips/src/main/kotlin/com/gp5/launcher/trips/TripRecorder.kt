package com.gp5.launcher.trips

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.gp5.launcher.core.data.SettingsRepository
import com.gp5.launcher.core.model.DriveMode
import com.gp5.launcher.core.model.LauncherSettings
import com.gp5.launcher.core.model.SpeedSource
import com.gp5.launcher.core.model.VehicleState
import com.gp5.launcher.obd.VehicleStateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The trip being recorded, for the Trips screen's "Recording" chip. */
data class LiveTrip(val id: Long, val startMs: Long, val distanceKm: Double)

/**
 * Records trips from the live OBD state (DESIGN.md section 16, "Trip recording rules").
 * A trip starts on the first movement above 5 km/h, with speed from OBD or, with the adapter unplugged,
 * GPS. It ends after the configured time stopped, or when speed is lost (ignition off: 90 s on OBD,
 * 5 min on GPS for tunnels and car parks); trailing stopped time is cut off. Drive mode and fuel are
 * only recorded while OBD is connected. Trips shorter than 300 m are dropped. Runs in the app process for its whole life.
 */
@Singleton
class TripRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vehicle: VehicleStateRepository,
    private val settings: SettingsRepository,
    private val dao: TripsDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _live = MutableStateFlow<LiveTrip?>(null)
    val live: StateFlow<LiveTrip?> = _live.asStateFlow()
    private val prefs = settings.settings.stateIn(scope, SharingStarted.Eagerly, LauncherSettings())
    private val location = Locator(context)
    private var started = false

    /** Accumulators of the open trip. Stopped time is held back until the car moves again. */
    private class Open(var trip: Trip) {
        var lastSeen = trip.startMs
        var lastTick = trip.startMs
        var stoppedSince: Long? = null
        var pendingStoppedS = 0
        val pendingSamples = ArrayList<TripSample>()
        val samples = ArrayList<TripSample>()
        var fuel = 0.0
        var engineRunS = 0
        var fuelMissingS = 0
        var movedS = 0
        var noObdS = 0
        var source = SpeedSource.None
        var lastSave = 0L
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // close trips left open by a crash or a reboot
            dao.openTrips().forEach { t -> if (t.distanceKm < MIN_KM) dao.delete(t.id) else dao.update(t.copy(open = false)) }
            val keep = settings.settings.first().keepTripsDays
            if (keep > 0) dao.prune(System.currentTimeMillis() - keep * DAY_MS)
            loop()
        }
    }

    private suspend fun loop() {
        var open: Open? = null
        while (true) {
            delay(1_000)
            val s = vehicle.state.value
            val now = System.currentTimeMillis()
            val o = open
            if (o == null) {
                if (s.hasSpeed && s.speedKmh > VehicleState.DRIVING_KMH) open = begin(now, s)
                continue
            }
            if (!s.hasSpeed) {
                if (now - o.lastSeen > (if (o.source == SpeedSource.Gps) LOST_GPS_MS else LOST_MS)) { finish(o, o.stoppedSince ?: o.lastSeen); open = null }
                o.lastTick = now
                continue
            }
            o.lastSeen = now
            o.source = s.speedSource
            tick(o, s, now)
            val since = o.stoppedSince
            if (since != null && now - since > prefs.value.tripEndMinutes * 60_000L) {
                finish(o, since); open = null
            } else if (now - o.lastSave > SAVE_MS) {
                save(o)
            }
        }
    }

    private suspend fun begin(now: Long, s: VehicleState): Open {
        location.start()
        val here = location.last()
        val trip = Trip(startMs = now, endMs = now, open = true, startLat = here?.latitude, startLng = here?.longitude, maxCoolant = s.coolantC)
        val id = dao.insert(trip)
        Log.i(TAG, "trip $id started")
        return Open(trip.copy(id = id)).also { it.lastSave = now; _live.value = LiveTrip(id, now, 0.0) }
    }

    private fun tick(o: Open, s: VehicleState, now: Long) {
        val dt = ((now - o.lastTick) / 1000.0).coerceIn(0.0, 3.0)
        o.lastTick = now
        val t = ((now - o.trip.startMs) / 1000).toInt()
        val obd = s.speedSource == SpeedSource.Obd
        val sample = TripSample(o.trip.id, t, s.speedKmh, if (obd) s.mode.ordinal else UNKNOWN_MODE)
        var trip = o.trip
        if (trip.startLat == null) location.last()?.let { trip = trip.copy(startLat = it.latitude, startLng = it.longitude) }
        if (s.speedKmh < 1f) {
            if (o.stoppedSince == null) o.stoppedSince = now
            o.pendingStoppedS += dt.toInt().coerceAtLeast(1)
            o.pendingSamples += sample
        } else {
            // moving again: the stop was part of the trip
            o.stoppedSince = null
            trip = trip.copy(stoppedS = trip.stoppedS + o.pendingStoppedS)
            o.pendingStoppedS = 0
            o.samples += o.pendingSamples; o.pendingSamples.clear()
            o.samples += sample
            val km = s.speedKmh * dt / 3600.0
            val sec = dt.toInt().coerceAtLeast(1)
            o.movedS += sec
            if (!obd) o.noObdS += sec
            trip = when {
                !obd -> trip.copy(unknownS = trip.unknownS + sec)
                else -> when (s.mode) {
                DriveMode.Ev -> trip.copy(evS = trip.evS + sec, evKm = trip.evKm + km)
                DriveMode.Regen -> trip.copy(regenS = trip.regenS + sec, evKm = trip.evKm + km)
                else -> trip.copy(engineS = trip.engineS + sec)
                }
            }
            trip = trip.copy(distanceKm = trip.distanceKm + km, maxSpeed = maxOf(trip.maxSpeed, s.speedKmh), endMs = now)
        }
        // fuel burns while stopped too (engine charging the pack)
        val lph = s.fuelLph
        if (s.rpm >= 300) o.engineRunS++
        if (lph != null) o.fuel += lph * dt / 3600.0 else if (s.rpm >= 300) o.fuelMissingS++
        s.coolantC?.let { c -> trip = trip.copy(maxCoolant = maxOf(trip.maxCoolant ?: c, c)) }
        o.trip = trip.copy(fuelL = fuelOf(o))
        _live.value = LiveTrip(trip.id, trip.startMs, trip.distanceKm)
    }

    /** Null when more than a fifth of the engine-running time had no airflow data. */
    private fun fuelOf(o: Open): Double? = when {
        o.noObdS > o.movedS / 5 -> null                               // mostly GPS-only: fuel unknown
        o.engineRunS > 0 && o.fuelMissingS > o.engineRunS / 5 -> null
        else -> o.fuel
    }

    private suspend fun save(o: Open) {
        o.lastSave = System.currentTimeMillis()
        dao.update(o.trip)
        if (o.samples.isNotEmpty()) { dao.insertSamples(o.samples.toList()); o.samples.clear() }
    }

    private suspend fun finish(o: Open, endMs: Long) {
        _live.value = null
        val here = location.last()
        location.stop()
        var trip = o.trip.copy(open = false, endMs = maxOf(o.trip.endMs, minOf(endMs, o.lastSeen)), endLat = here?.latitude, endLng = here?.longitude)
        if (trip.distanceKm < MIN_KM) {
            dao.delete(trip.id); Log.i(TAG, "trip ${trip.id} dropped (${"%.2f".format(trip.distanceKm)} km)"); return
        }
        dao.update(trip)
        if (o.samples.isNotEmpty()) dao.insertSamples(o.samples.toList())
        dao.trimSamples(trip.id, ((trip.endMs - trip.startMs) / 1000).toInt())
        settings.addToOdometer(trip.distanceKm)
        Log.i(TAG, "trip ${trip.id} saved: ${"%.1f".format(trip.distanceKm)} km")
        // place names need the network; try once, the list shows times until then
        scope.launch {
            val from = name(trip.startLat, trip.startLng)
            val to = name(trip.endLat, trip.endLng)
            if (from != null || to != null) dao.trip(trip.id)?.let { dao.update(it.copy(fromName = from, toName = to)) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun name(lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null || !Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return@runCatching null
                a.subLocality ?: a.locality ?: a.thoroughfare ?: a.subAdminArea
            }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "TripRecorder"
        private const val MIN_KM = 0.3
        private const val LOST_MS = 90_000L
        private const val LOST_GPS_MS = 300_000L
        /** Sample mode when speed came from GPS (no drive mode known). */
        const val UNKNOWN_MODE = -1
        private const val SAVE_MS = 5_000L
        private const val DAY_MS = 86_400_000L
    }
}

/** GPS (and network, if present) fixes while a trip records. Silent without the location permission. */
private class Locator(private val context: Context) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile private var fix: Location? = null
    private var on = false

    private fun allowed() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (on || lm == null || !allowed()) return
        on = true
        android.os.Handler(Looper.getMainLooper()).post {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                    runCatching { lm.requestLocationUpdates(p, 5_000L, 0f, this, Looper.getMainLooper()) }
                    runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                        ?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { onLocationChanged(it) }
                }
            }
        }
    }

    fun stop() {
        if (!on) return
        on = false
        android.os.Handler(Looper.getMainLooper()).post { runCatching { lm?.removeUpdates(this) } }
    }

    fun last(): Location? = fix

    override fun onLocationChanged(location: Location) {
        val f = fix
        // prefer GPS; take a network fix only when it is all there is
        if (f == null || location.provider == LocationManager.GPS_PROVIDER || f.provider != LocationManager.GPS_PROVIDER) fix = location
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
