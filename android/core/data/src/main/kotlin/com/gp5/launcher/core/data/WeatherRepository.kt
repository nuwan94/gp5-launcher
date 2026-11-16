package com.gp5.launcher.core.data

import android.content.Context
import android.location.Geocoder
import android.os.SystemClock
import android.util.Log
import com.gp5.launcher.core.model.Weather
import com.gp5.launcher.core.model.WeatherHour
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Weather now and the next hours from Open-Meteo (free, no key; data CC BY 4.0), for the car's
 * position. Refreshes every 20 minutes, or sooner after moving 10 km. Needs the internet (a phone
 * hotspot is enough); without it the last forecast stays until it is 3 hours old, then hides.
 */
@Singleton
class WeatherRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    @Volatile private var lat = SunClock.DEFAULT_LAT
    @Volatile private var lon = SunClock.DEFAULT_LON
    @Volatile private var fetchedLat = Double.NaN
    @Volatile private var fetchedLon = Double.NaN
    @Volatile private var fetchedAt = 0L
    private var started = false

    /** The car's position; a big move fetches again. */
    fun update(lat: Double, lon: Double) {
        this.lat = lat; this.lon = lon
        if (!fetchedLat.isNaN() && (abs(lat - fetchedLat) > 0.09 || abs(lon - fetchedLon) > 0.09)) scope.launch { fetch() }
    }

    /** Where the forecast should be for (the car's last fix, else Colombo). */
    fun position(): Pair<Double, Double> = lat to lon

    /** True when the forecast is missing or due a refresh: the phone link then asks the phone. */
    fun due(): Boolean = fetchedAt == 0L || SystemClock.elapsedRealtime() - fetchedAt > K.REFRESH_MS

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                val age = SystemClock.elapsedRealtime() - fetchedAt
                if (fetchedAt == 0L || age > K.REFRESH_MS) fetch()
                _weather.value?.let { if (System.currentTimeMillis() - it.updatedAt > K.STALE_MS) _weather.value = null }
                delay(if (_weather.value == null) 60_000 else 5 * 60_000L)
            }
        }
    }

    private fun fetch() {
        val la = lat; val lo = lon
        val url = query(la, lo)
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "GP5Launcher")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            _weather.value = parse(body, placeName(la, lo) ?: _weather.value?.place)
            fetchedLat = la; fetchedLon = lo; fetchedAt = SystemClock.elapsedRealtime()
            Log.i(K.TAG, "weather ${_weather.value?.tempC}° code ${_weather.value?.code} at ${_weather.value?.place}")
        } catch (e: Exception) {
            Log.i(K.TAG, "weather: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * A forecast relayed by the phone (GP5 Link fetches the same Open-Meteo query over the phone's
     * data), used when the head unit itself has no internet (it is on the OBD adapter's Wi-Fi).
     */
    fun fromPhone(body: String, place: String?) {
        runCatching { parse(body, place ?: _weather.value?.place) }
            .onSuccess { _weather.value = it; fetchedAt = SystemClock.elapsedRealtime(); fetchedLat = lat; fetchedLon = lon }
            .onFailure { Log.i(K.TAG, "phone weather: ${it.message}") }
    }

    private fun parse(body: String, place: String?): Weather {
            val j = JSONObject(body)
            val tz = TimeZone.getTimeZone(j.optString("timezone", "UTC"))
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = tz }
            val cur = j.getJSONObject("current")
            val hourly = j.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val now = System.currentTimeMillis()
            val hours = ArrayList<WeatherHour>()
            for (i in 0 until times.length()) {
                val t = fmt.parse(times.getString(i))?.time ?: continue
                if (t < now - 30 * 60_000L) continue
                hours += WeatherHour(
                    t, hourly.getJSONArray("temperature_2m").getDouble(i).toFloat(), hourly.getJSONArray("weather_code").getInt(i),
                    hourly.getJSONArray("precipitation_probability").optInt(i, -1).takeIf { it >= 0 },
                    hourly.getJSONArray("is_day").optInt(i, 1) == 1,
                )
                if (hours.size >= 12) break
            }
            val daily = j.optJSONObject("daily")
            return Weather(
                tempC = cur.getDouble("temperature_2m").toFloat(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                place = place,
                todayMaxC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.toFloat()?.takeIf { !it.isNaN() },
                todayMinC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.toFloat()?.takeIf { !it.isNaN() },
                rainChance = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1)?.takeIf { it >= 0 },
                hours = hours,
                updatedAt = now,
            )
    }

    companion object {
        /** The Open-Meteo query, shared with the phone app so both parse the same answer. */
        fun query(lat: Double, lon: Double): String =
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, lat, lon) +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=2"
    }

    @Suppress("DEPRECATION")
    private fun placeName(la: Double, lo: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(context, Locale.UK).getFromLocation(la, lo, 1)?.firstOrNull()?.let { a ->
            a.locality ?: a.subLocality ?: a.subAdminArea ?: a.adminArea
        }
    }.getOrNull()

    private object K {
        const val TAG = "Gp5Weather"
        const val REFRESH_MS = 20 * 60_000L
        const val STALE_MS = 3 * 60 * 60_000L
    }
}
