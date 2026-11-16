package com.gp5.launcher.obd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One GPS reading: speed in km/h and when it arrived (SystemClock.elapsedRealtime). */
data class GpsFix(val speedKmh: Float, val atElapsed: Long, val accuracyM: Float?)

/** Where the car is (any fix, moving or not); [bearing] is the direction of travel, set only while moving. */
data class GeoFix(val lat: Double, val lon: Double, val bearing: Float?, val speedKmh: Float, val atElapsed: Long)

/**
 * Speed from the head unit's GPS, 1 Hz. Needs no adapter in the car: the speedometer and trip
 * recording keep working with OBD unplugged. Silent until the location permission is granted;
 * call [start] again after granting it.
 */
@Singleton
class GpsSpeed @Inject constructor(@ApplicationContext private val context: Context) : LocationListener {
    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix.asStateFlow()
    private val _place = MutableStateFlow<GeoFix?>(null)
    /** Last known position (seeded from the last known fix at start), for weather, sunset and the compass. */
    val place: StateFlow<GeoFix?> = _place.asStateFlow()
    private var listening = false
    private var last: Location? = null

    fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        main.post {
            val lm = lm
            if (listening || lm == null) return@post
            if (!allowed()) { Log.i(TAG, "no location permission yet"); return@post }
            if (!runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) { Log.i(TAG, "GPS provider is off"); return@post }
            if (_place.value == null) runCatching {
                (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let {
                    _place.value = GeoFix(it.latitude, it.longitude, null, 0f, SystemClock.elapsedRealtime())
                }
            }
            listening = runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "GPS request failed", it) }.isSuccess
            Log.i(TAG, "GPS speed listening: $listening")
        }
    }

    override fun onLocationChanged(location: Location) {
        val prev = last
        last = location
        val kmh = when {
            location.hasSpeed() -> location.speed * 3.6f
            // no Doppler speed from this receiver: distance over time between fixes
            prev != null && location.time > prev.time -> prev.distanceTo(location) / ((location.time - prev.time) / 1000f) * 3.6f
            else -> 0f
        }
        // GPS wanders a few km/h when parked; treat that as stopped
        val speed = if (kmh < 2.5f) 0f else kmh
        _fix.value = GpsFix(speed, SystemClock.elapsedRealtime(), if (location.hasAccuracy()) location.accuracy else null)
        val bearing = when {
            speed < 4f -> _place.value?.bearing                    // keep the last heading while stopped
            location.hasBearing() && location.bearing != 0f -> location.bearing   // exactly 0 is often "unknown"
            prev != null && prev.distanceTo(location) > 3f -> prev.bearingTo(location).let { (it + 360f) % 360f }
            else -> _place.value?.bearing
        }
        _place.value = GeoFix(location.latitude, location.longitude, bearing, speed, SystemClock.elapsedRealtime())
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) { _fix.value = null }

    private companion object { const val TAG = "GpsSpeed" }
}
