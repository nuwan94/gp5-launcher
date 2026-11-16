package com.gp5.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.location.Geocoder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The Bluetooth line to the launcher (GP5 Link in the head unit's media module). Connects to the
 * chosen car, reconnects when it comes back in range, and speaks JSON lines both ways:
 *   phone -> car: hello, nav, nav_end, weather
 *   car -> phone: where {lat, lon}  (the car's GPS; the phone fetches the weather for it)
 */
object Link {
    private const val TAG = "Gp5Link"
    /** Same as PhoneLink.ID on the head unit. */
    private val ID: UUID = UUID.fromString("6f1b2c3e-9a54-4d2b-8e57-0b5f9a1c6d21")
    private const val PREFS = "link"

    @Volatile var status: String = "Not started"; private set
    @Volatile private var out: BufferedWriter? = null
    @Volatile private var lastNav: String? = null
    @Volatile private var started = false
    private val lock = Any()

    fun car(context: Context): String? = context.getSharedPreferences(PREFS, 0).getString("car", null)
    fun setCar(context: Context, address: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("car", address).apply()
        runCatching { socket?.close() }   // reconnect to the new one
    }

    @Volatile private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "gp5-link", isDaemon = true) {
            while (true) {
                val address = car(app)
                val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
                if (address == null || adapter == null || !adapter.isEnabled) {
                    status = if (address == null) "Choose your car" else "Bluetooth is off"
                    Thread.sleep(5_000); continue
                }
                try {
                    status = "Looking for the car…"
                    val s = adapter.getRemoteDevice(address).createInsecureRfcommSocketToServiceRecord(ID)
                    socket = s
                    s.connect()
                    out = s.outputStream.bufferedWriter(Charsets.UTF_8)
                    status = "Connected"
                    send(JSONObject().put("t", "hello").put("name", android.os.Build.MODEL))
                    lastNav?.let { line(it) }
                    s.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (true) handle(app, r.readLine() ?: break)
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "link: ${e.message}")
                } finally {
                    out = null
                    runCatching { socket?.close() }
                    socket = null
                }
                status = "Car not in range"
                Thread.sleep(10_000)
            }
        }
    }

    fun nav(dist: String, text: String, eta: String?, app: String, iconB64: String?) {
        val j = JSONObject().put("t", "nav").put("dist", dist).put("text", text).put("app", app)
        eta?.let { j.put("eta", it) }
        iconB64?.let { j.put("icon", it) }
        lastNav = j.toString()
        line(lastNav!!)
    }

    fun navEnd() {
        lastNav = null
        send(JSONObject().put("t", "nav_end"))
    }

    private fun send(j: JSONObject) = line(j.toString())

    private fun line(s: String) {
        val w = out ?: return
        synchronized(lock) {
            runCatching { w.write(s); w.write("\n"); w.flush() }.onFailure { runCatching { socket?.close() } }
        }
    }

    /** The car asks for weather at its position: fetch it over the phone's data and send it back. */
    private fun handle(context: Context, l: String) {
        val j = runCatching { JSONObject(l) }.getOrNull() ?: return
        if (j.optString("t") != "where") return
        val lat = j.optDouble("lat"); val lon = j.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return
        thread(isDaemon = true) {
            runCatching {
                val c = URL(query(lat, lon)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "GP5Link")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val reply = JSONObject().put("t", "weather").put("body", body)
                place(context, lat, lon)?.let { reply.put("place", it) }
                send(reply)
            }.onFailure { Log.i(TAG, "weather: ${it.message}") }
        }
    }

    /** Same query as the launcher's WeatherRepository, so it parses the answer the same way. */
    private fun query(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2"

    @Suppress("DEPRECATION")
    private fun place(context: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(context, Locale.UK).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.locality ?: it.subLocality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()
}
