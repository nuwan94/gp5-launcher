package com.gp5.launcher.obd

import com.gp5.launcher.core.model.DriveMode
import com.gp5.launcher.core.model.VehicleState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/** Single source of live car data (DESIGN.md section 7.3). UI only collects [state]. */
interface VehicleStateRepository {
    val state: StateFlow<VehicleState>

    /** Demo / bench mode: drive the simulated feed. No-op once a real ELM327 session exists. */
    fun setSimulatedDriving(on: Boolean)
}

/**
 * Simulated feed (milestone M1) until the ELM327 transport lands in M2: the same 60 s drive cycle
 * as the HTML prototypes, sampled at 10 Hz, with the i-DCD mode inferred from speed and acceleration.
 */
@Singleton
class SimulatedVehicleRepository @Inject constructor() : VehicleStateRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(VehicleState(connected = true, coolantC = 86f, batteryVolts = 14.1f, avgKmPerL = 18.4f))
    override val state: StateFlow<VehicleState> = _state.asStateFlow()
    private val driving = MutableStateFlow(false)

    init {
        scope.launch {
            var t = 0f
            var v = 0f
            while (true) {
                delay(100)
                val dt = 0.1f
                t = if (driving.value) (t + dt) % 60f else 0f
                val target = if (!driving.value) 0f else when {
                    t < 6 -> 0f
                    t < 22 -> 48f
                    t < 34 -> 30f
                    t < 46 -> 62f
                    else -> 0f
                }
                val prev = v
                v = if (target > v) min(target, v + 3f * dt * 3) else max(target, v - 4f * dt * 3)
                val acc = (v - prev) / dt
                val mode = when {
                    v < 0.5f -> DriveMode.Ready
                    acc < -0.6f -> DriveMode.Regen
                    acc > 1.2f || v > 58f -> DriveMode.Engine
                    else -> DriveMode.Ev
                }
                val soc = (_state.value.socPercent + when (mode) {
                    DriveMode.Ev -> -0.004f
                    DriveMode.Regen -> 0.008f
                    else -> 0f
                }).coerceIn(20f, 80f)
                _state.value = _state.value.copy(
                    speedKmh = v,
                    rpm = if (mode == DriveMode.Engine) (1000 + 14 * v).toInt() else 0,
                    mode = mode,
                    socPercent = soc,
                )
            }
        }
    }

    override fun setSimulatedDriving(on: Boolean) {
        driving.value = on
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ObdModule {
    @Binds
    abstract fun vehicleState(impl: SimulatedVehicleRepository): VehicleStateRepository
}
