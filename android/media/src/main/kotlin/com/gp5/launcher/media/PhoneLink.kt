package com.gp5.launcher.media

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.gp5.launcher.core.data.WeatherRepository
import com.gp5.launcher.core.model.NavStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * GP5 Link: the phone's navigation (and weather, over the phone's data) on the head unit, over the
 * Bluetooth connection the phone already has with the car. The companion app (module :companion)
 * reads Google Maps / Waze turn-by-turn notifications on the phone and sends them here as JSON lines
 * on an RFCOMM channel:
 *   {"t":"hello","name":"Pixel 8"}
 *   {"t":"nav","dist":"400 m","text":"Turn right onto Galle Rd","eta":"Arrive 9:14","app":"Google Maps","icon":"<base64 PNG>"}
 *   {"t":"nav_end"}
 *   {"t":"weather","body":"<Open-Meteo JSON>","place":"Maharagama"}
 * and the launcher asks the phone for weather at the car's GPS position when its own forecast is due:
 *   {"t":"where","lat":6.93,"lon":79.85}
 * The launcher listens; the phone connects (and reconnects) whenever it is in range.
 */
@Singleton
class PhoneLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nav: NavigationRepository,
    private val weather: WeatherRepository,
) {
    private val _phone = MutableStateFlow<String?>(null)
    /** Name of the connected phone, or null. */
    val phone: StateFlow<String?> = _phone.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    @Volatile private var started = false
    @Volatile private var server: BluetoothServerSocket? = null

    fun allowed(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !allowed()) return
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        started = true
        thread(name = "gp5-link", isDaemon = true) {
            while (true) {
                try {
                    if (!adapter.isEnabled) { _listening.value = false; Thread.sleep(5_000); continue }
                    val s = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE, ID)
                    server = s; _listening.value = true
                    val sock = s.accept()                      // one phone at a time
                    runCatching { s.close() }
                    _phone.value = runCatching { sock.remoteDevice.name }.getOrNull() ?: "Phone"
                    Log.i(TAG, "phone connected: ${_phone.value}")
                    val out = sock.outputStream.bufferedWriter(Charsets.UTF_8)
                    thread(name = "gp5-link-ask", isDaemon = true) {
                        // weather over the phone's data whenever the head unit's own is due
                        runCatching {
                            while (sock.isConnected) {
                                if (weather.due()) {
                                    val (la, lo) = weather.position()
                                    out.write(JSONObject().put("t", "where").put("lat", la).put("lon", lo).toString()); out.newLine(); out.flush()
                                }
                                Thread.sleep(ASK_MS)
                            }
                        }
                    }
                    sock.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (true) handle(r.readLine() ?: break)
                    }
                    runCatching { sock.close() }
                } catch (e: Exception) {
                    Log.i(TAG, "link: ${e.javaClass.simpleName} ${e.message}")
                    Thread.sleep(3_000)
                } finally {
                    if (_phone.value != null) { _phone.value = null; nav.onPhone(null) }
                }
            }
        }
    }

    private fun handle(line: String) {
        val j = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (j.optString("t")) {
            "hello" -> _phone.value = j.optString("name").ifBlank { _phone.value }
            "nav" -> nav.onPhone(
                NavStep(
                    distance = j.optString("dist"), instruction = j.optString("text"),
                    eta = j.optString("eta").ifBlank { null },
                    iconPng = j.optString("icon").takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
                    source = "Phone · " + j.optString("app", "Maps"), fromPhone = true,
                    atElapsed = SystemClock.elapsedRealtime(),
                ),
            )
            "nav_end" -> nav.onPhone(null)
            "weather" -> weather.fromPhone(j.optString("body"), j.optString("place").ifBlank { null })
        }
    }

    companion object {
        const val SERVICE = "GP5 Link"
        /** Shared with the companion app. */
        val ID: UUID = UUID.fromString("6f1b2c3e-9a54-4d2b-8e57-0b5f9a1c6d21")
        private const val TAG = "Gp5Link"
        private const val ASK_MS = 5 * 60_000L
    }
}
