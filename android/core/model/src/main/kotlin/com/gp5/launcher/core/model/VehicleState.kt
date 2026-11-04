package com.gp5.launcher.core.model

/** i-DCD drive mode, inferred from OBD (DESIGN.md section 6.4). */
enum class DriveMode { Ready, Ev, Engine, Assist, Regen, Charging }

/** OBD adapter link, as shown in Settings > OBD. */
enum class ObdLink {
    /** OBD switched off in Settings. */
    Off,
    /** No adapter answering at the configured address (Wi-Fi not joined, adapter unplugged). */
    Searching,
    /** Adapter answers; starting the ELM327. */
    Connecting,
    /** Adapter answers but the car does not (ignition off). */
    NoCar,
    /** Live data flowing. */
    Connected,
}

/** Where [VehicleState.speedKmh] comes from: OBD when the adapter is connected, else the head unit's GPS. */
enum class SpeedSource { None, Obd, Gps }

/**
 * Live car state from the OBD layer, collected by every screen as a StateFlow.
 * Values the car has not reported (or does not support) are null; nothing here is made up.
 */
data class VehicleState(
    val link: ObdLink = ObdLink.Off,
    /** Human-readable link detail, e.g. "ELM327 v1.5 · ISO 15765-4 CAN" or what to do next. */
    val linkDetail: String = "",
    val speedKmh: Float = 0f,
    val speedSource: SpeedSource = SpeedSource.None,
    val rpm: Int = 0,
    val mode: DriveMode = DriveMode.Ready,
    /** PID 5B hybrid battery pack remaining life (%), if the car answers it. */
    val socPercent: Float? = null,
    val coolantC: Float? = null,
    val batteryVolts: Float? = null,
    val fuelPercent: Float? = null,
    val ambientC: Float? = null,
    val intakeC: Float? = null,
    val loadPercent: Float? = null,
    val throttlePercent: Float? = null,
    /** Mass air flow (g/s), for the fuel-rate estimate. */
    val mafGs: Float? = null,
    /** Intake manifold pressure (kPa, PID 0B), for the speed-density airflow estimate. */
    val mapKpa: Float? = null,
) {
    /**
     * Engine airflow (g/s): the MAF sensor if the car reports one, otherwise speed-density from
     * manifold pressure, intake temperature and rpm (1.5 L L15, volumetric efficiency ~0.85).
     */
    val airflowGs: Float? get() {
        mafGs?.let { return it }
        val map = mapKpa ?: return null
        if (rpm < 300) return 0f
        val intakeK = (intakeC ?: 30f) + 273.15f
        val density = map * 1000f / (287.05f * intakeK)                  // kg/m3
        return density * (DISPLACEMENT_M3 * rpm / 120f) * VOLUMETRIC_EFF * 1000f
    }

    /** Fuel flow (litres per hour): stoichiometric petrol, 745 g/L. Null when unknown; 0 with the engine off. */
    val fuelLph: Float? get() {
        if (connected && rpm < 300) return 0f
        val air = airflowGs ?: return null
        return air * 3600f / (14.7f * 745f)
    }

    /** Instant economy; null when stopped, in EV or unknown. */
    val economyKmL: Float? get() {
        if (speedKmh < 3f || rpm < 300) return null
        val lph = fuelLph ?: return null
        if (lph <= 0.02f) return null
        return speedKmh / lph
    }

    val connected: Boolean get() = link == ObdLink.Connected

    /** Ambient motion pauses and some settings lock above this speed (DESIGN.md section 8). */
    val isDriving: Boolean get() = hasSpeed && speedKmh > DRIVING_KMH

    /** Speed is known (from OBD or GPS). */
    val hasSpeed: Boolean get() = speedSource != SpeedSource.None

    companion object {
        const val DRIVING_KMH = 5f
        private const val DISPLACEMENT_M3 = 0.001496f
        private const val VOLUMETRIC_EFF = 0.85f
    }
}
