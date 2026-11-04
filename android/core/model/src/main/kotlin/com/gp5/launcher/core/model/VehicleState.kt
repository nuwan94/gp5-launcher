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

/**
 * Live car state from the OBD layer, collected by every screen as a StateFlow.
 * Values the car has not reported (or does not support) are null; nothing here is made up.
 */
data class VehicleState(
    val link: ObdLink = ObdLink.Off,
    /** Human-readable link detail, e.g. "ELM327 v1.5 · ISO 15765-4 CAN" or what to do next. */
    val linkDetail: String = "",
    val speedKmh: Float = 0f,
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
) {
    /** Instant economy from MAF (stoichiometric petrol, 745 g/L); null when stopped or unknown. */
    val economyKmL: Float? get() {
        val maf = mafGs ?: return null
        if (speedKmh < 3f || rpm < 300 || maf <= 0.05f) return null
        val litresPerHour = maf * 3600f / (14.7f * 745f)
        return speedKmh / litresPerHour
    }

    val connected: Boolean get() = link == ObdLink.Connected

    /** Ambient motion pauses and some settings lock above this speed (DESIGN.md section 8). */
    val isDriving: Boolean get() = connected && speedKmh > DRIVING_KMH

    companion object {
        const val DRIVING_KMH = 5f
    }
}
