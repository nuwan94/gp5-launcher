package com.gp5.launcher.obd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.gp5.launcher.core.data.SettingsRepository
import com.gp5.launcher.core.model.DriveMode
import com.gp5.launcher.core.model.ObdLink
import com.gp5.launcher.core.model.VehicleState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Single source of live car data (DESIGN.md section 7.3). UI only collects [state]. */
interface VehicleStateRepository {
    val state: StateFlow<VehicleState>

    /** Stored and pending trouble codes. Throws if the adapter is not connected. */
    suspend fun readFaults(): FaultScan

    /** Clears stored codes (mode 04), then re-reads them. Callers must only offer this when stopped. */
    suspend fun clearFaults(): FaultScan
}

data class FaultScan(val stored: List<String>, val pending: List<String>) {
    val all: List<String> get() = (stored + pending).distinct()
}

class NotConnectedException : IllegalStateException("OBD adapter not connected")

/**
 * ELM327 Wi-Fi adapter (the usual clone: its own access point, 192.168.0.10:35000).
 *
 * The head unit joins the adapter's Wi-Fi in Android settings. That network has no internet, so the
 * socket is bound to it explicitly (Android would otherwise route by the "validated" network).
 * Reconnects forever with a short backoff; ignition off shows as [ObdLink.NoCar].
 */
@Singleton
class Elm327WifiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
) : VehicleStateRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    /** One-off commands (fault codes) run by the polling loop between cycles, so they share its connection. */
    private class Job<T>(val run: (Elm327) -> T) { val result = CompletableDeferred<T>() }
    private val jobs = Channel<Job<*>>(Channel.UNLIMITED)

    private suspend fun <T> onAdapter(run: (Elm327) -> T): T {
        if (!_state.value.connected) throw NotConnectedException()
        val job = Job(run)
        jobs.send(job)
        return job.result.await()
    }

    override suspend fun readFaults(): FaultScan = onAdapter { FaultScan(it.troubleCodes("03"), it.troubleCodes("07")) }
    override suspend fun clearFaults(): FaultScan = onAdapter { it.clearTroubleCodes(); FaultScan(it.troubleCodes("03"), it.troubleCodes("07")) }

    init {
        scope.launch {
            settings.settings.map { it.obdEnabled to it.obdAddress }.distinctUntilChanged().collectLatest { (enabled, address) ->
                if (!enabled) { _state.value = VehicleState(link = ObdLink.Off, linkDetail = "OBD is off"); return@collectLatest }
                val (host, port) = parseAddress(address)
                var backoff = 2_000L
                while (coroutineContext.isActive) {
                    val ok = runCatching { session(host, port) }
                        .onFailure { Log.i(TAG, "session ended: ${it.message}") }
                        .getOrDefault(false)
                    if (ok) backoff = 2_000L
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(10_000L)
                }
            }
        }
    }

    /** One connection: connect, init, poll until the link drops. Returns true if data flowed. */
    private suspend fun session(host: String, port: Int): Boolean {
        val net = adapterNetwork()
        _state.value = VehicleState(link = ObdLink.Searching, linkDetail =
            if (net == null) "Join the adapter's Wi-Fi (often WiFi_OBDII) in Android settings"
            else "Looking for the adapter at $host:$port")
        val socket = (net?.socketFactory?.createSocket() ?: Socket())
        try {
            runInterruptible { socket.connect(InetSocketAddress(host, port), 3_000) }
            socket.soTimeout = 300
            socket.tcpNoDelay = true
            val elm = Elm327(socket.getInputStream(), socket.getOutputStream())
            _state.value = VehicleState(link = ObdLink.Connecting, linkDetail = "Starting the adapter")
            val version = runInterruptible { elm.init() }
            val protocol = try {
                runInterruptible { elm.connectCar() }
            } catch (e: Elm327.NoCarException) {
                _state.value = VehicleState(link = ObdLink.NoCar, linkDetail = "$version found. Switch the car on (READY) to read data")
                delay(3_000)
                return false
            }
            val supported = runInterruptible { elm.supportedPids() }
            Log.i(TAG, "$version · $protocol · ${supported.size} PIDs")
            poll(elm, "$version · $protocol", supported)
            return true
        } finally {
            runCatching { socket.close() }
            while (true) {
                val job = jobs.tryReceive().getOrNull() ?: break
                job.result.completeExceptionally(NotConnectedException())
            }
        }
    }

    private suspend fun poll(elm: Elm327, detail: String, supported: Set<Int>) {
        fun has(p: Int) = supported.isEmpty() || p in supported
        val slow = listOf(PID_COOLANT, PID_VOLTS, PID_FUEL, PID_AMBIENT, PID_HYBRID_SOC, PID_INTAKE, PID_THROTTLE, PID_MAF).filter(::has)
        // engine load joins speed and rpm every cycle: it separates Engine from Assist
        var s = VehicleState(link = ObdLink.Connected, linkDetail = detail)
        var slowIndex = 0
        var lastSpeed = 0f
        var lastT = System.nanoTime()
        var misses = 0
        while (coroutineContext.isActive) {
            val speed = runInterruptible { elm.pid(PID_SPEED) }?.let { it[0].toFloat() }
            val rpm = if (has(PID_RPM)) runInterruptible { elm.pid(PID_RPM) }?.let { if (it.size >= 2) (it[0] * 256 + it[1]) / 4 else null } else null
            if (speed == null && rpm == null) {
                if (++misses >= 5) { _state.value = s.copy(link = ObdLink.NoCar, linkDetail = "The car stopped answering (ignition off?)"); return }
                continue
            }
            misses = 0
            val load = if (has(PID_LOAD)) runInterruptible { elm.pid(PID_LOAD) }?.let { it[0] * 100f / 255f } else null
            if (load != null) s = s.copy(loadPercent = load)
            // one slow PID per cycle: ~5 Hz speed/rpm, each slow value every ~1-2 s
            if (slow.isNotEmpty()) {
                val p = slow[slowIndex++ % slow.size]
                val b = runInterruptible { elm.pid(p) }
                s = when (p) {
                    PID_COOLANT -> s.copy(coolantC = b?.let { it[0] - 40f })
                    PID_VOLTS -> s.copy(batteryVolts = b?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 1000f })
                    PID_FUEL -> s.copy(fuelPercent = b?.let { it[0] * 100f / 255f })
                    PID_AMBIENT -> s.copy(ambientC = b?.let { it[0] - 40f })
                    PID_HYBRID_SOC -> s.copy(socPercent = b?.let { it[0] * 100f / 255f })
                    PID_INTAKE -> s.copy(intakeC = b?.let { it[0] - 40f })
                    PID_THROTTLE -> s.copy(throttlePercent = b?.let { it[0] * 100f / 255f })
                    PID_MAF -> s.copy(mafGs = b?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 100f })
                    else -> s
                }
            }
            val now = System.nanoTime()
            val v = speed ?: s.speedKmh
            val r = rpm ?: s.rpm
            val accel = (v - lastSpeed) / ((now - lastT) / 1e9f).coerceAtLeast(0.05f)   // km/h per s
            lastSpeed = v; lastT = now
            s = s.copy(speedKmh = v, rpm = r, mode = inferMode(v, r, accel, s.loadPercent))
            _state.value = s
            // queued one-off commands (fault codes)
            while (true) {
                @Suppress("UNCHECKED_CAST")
                val job = jobs.tryReceive().getOrNull() as Job<Any?>? ?: break
                runCatching { runInterruptible { job.run(elm) } }
                    .onSuccess { job.result.complete(it) }.onFailure { job.result.completeExceptionally(it) }
            }
        }
    }

    /** i-DCD mode from speed, rpm, acceleration and engine load (DESIGN.md section 6.4). */
    private fun inferMode(speed: Float, rpm: Int, accel: Float, load: Float?): DriveMode = when {
        speed < 1f && rpm < 300 -> DriveMode.Ready
        speed < 1f -> DriveMode.Charging                       // engine running while stopped charges the pack
        rpm < 300 && accel < -1.5f -> DriveMode.Regen
        rpm < 300 -> DriveMode.Ev
        (load ?: 0f) >= 70f || accel > 4f && rpm > 2500 -> DriveMode.Assist   // engine + motor
        else -> DriveMode.Engine                               // engine driving, surplus charges the pack
    }

    /** The Wi-Fi network the adapter lives on (any Wi-Fi network; the adapter's has no internet). */
    @Suppress("DEPRECATION")
    private fun adapterNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private fun parseAddress(a: String): Pair<String, Int> {
        val t = a.trim().ifEmpty { DEFAULT_ADDRESS }
        val host = t.substringBefore(':').ifEmpty { "192.168.0.10" }
        val port = t.substringAfter(':', "35000").toIntOrNull() ?: 35000
        return host to port
    }

    companion object {
        private const val TAG = "Elm327"
        const val DEFAULT_ADDRESS = "192.168.0.10:35000"
        private const val PID_LOAD = 0x04
        private const val PID_COOLANT = 0x05
        private const val PID_MAF = 0x10
        private const val PID_THROTTLE = 0x11
        private const val PID_RPM = 0x0C
        private const val PID_SPEED = 0x0D
        private const val PID_INTAKE = 0x0F
        private const val PID_FUEL = 0x2F
        private const val PID_VOLTS = 0x42
        private const val PID_AMBIENT = 0x46
        private const val PID_HYBRID_SOC = 0x5B
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ObdModule {
    @Binds
    abstract fun vehicleState(impl: Elm327WifiRepository): VehicleStateRepository
}
