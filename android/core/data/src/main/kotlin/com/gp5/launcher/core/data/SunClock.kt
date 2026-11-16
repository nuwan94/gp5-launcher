package com.gp5.launcher.core.data

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for a place and day (NOAA's simplified solar equations, within a minute or two),
 * for the Auto theme: dark from a little after sunset until a little before sunrise.
 */
object SunClock {
    /** Colombo, when no location is known yet. */
    const val DEFAULT_LAT = 6.93
    const val DEFAULT_LON = 79.85

    /** Sunrise and sunset as epoch ms for the day of [nowMs], or null in polar day / night. */
    fun sunriseSunset(nowMs: Long, lat: Double, lon: Double): Pair<Long, Long>? {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val g = 2 * Math.PI / 365 * (day - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = Math.toDegrees(acos(cosHa))
        val noonMin = 720 - 4 * lon - eqTime                       // solar noon, minutes after 00:00 UTC
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val midnight = cal.timeInMillis
        val rise = midnight + ((noonMin - 4 * ha) * 60_000).toLong()
        val set = midnight + ((noonMin + 4 * ha) * 60_000).toLong()
        return rise to set
    }

    /** Dark theme time: from 15 min after sunset to 15 min before sunrise. */
    fun isNight(nowMs: Long, lat: Double = DEFAULT_LAT, lon: Double = DEFAULT_LON): Boolean {
        val (rise, set) = sunriseSunset(nowMs, lat, lon) ?: return Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 18 || it < 6 }
        val margin = 15 * 60_000L
        return nowMs < rise - margin || nowMs > set + margin
    }

    /** When [isNight] next changes after [nowMs] (to schedule the switch). */
    fun nextChange(nowMs: Long, lat: Double = DEFAULT_LAT, lon: Double = DEFAULT_LON): Long {
        val margin = 15 * 60_000L
        for (d in 0..2) {
            val (rise, set) = sunriseSunset(nowMs + d * 86_400_000L, lat, lon) ?: continue
            for (t in listOf(rise - margin, set + margin).sorted()) if (t > nowMs) return t
        }
        return nowMs + 15 * 60_000L
    }

    @Suppress("unused") private fun unused() = asin(0.0)
}
