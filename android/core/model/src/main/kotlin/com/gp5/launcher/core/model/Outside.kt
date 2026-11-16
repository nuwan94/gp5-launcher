package com.gp5.launcher.core.model

/**
 * Weather at the car (Open-Meteo). [code] is the WMO weather code: 0 clear, 1-3 cloud, 45/48 fog,
 * 51-57 drizzle, 61-67 rain, 71-77 snow, 80-82 showers, 95-99 thunderstorm.
 */
data class Weather(
    val tempC: Float,
    val code: Int,
    val isDay: Boolean,
    val place: String?,
    val todayMaxC: Float?,
    val todayMinC: Float?,
    val rainChance: Int?,
    val hours: List<WeatherHour>,
    val updatedAt: Long,
) {
    val summary: String get() = weatherLabel(code)
}

data class WeatherHour(val timeMs: Long, val tempC: Float, val code: Int, val rainChance: Int?, val isDay: Boolean)

fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Showers"
    in 95..99 -> "Thunderstorm"
    else -> "—"
}

/**
 * A turn-by-turn step from a navigation app: Google Maps or Waze on the head unit, or on the phone
 * through the GP5 Link companion app over Bluetooth.
 */
data class NavStep(
    /** "400 m" */
    val distance: String,
    /** "Turn right onto Galle Rd" */
    val instruction: String,
    /** "Arrive 9:14 · 6.2 km" or similar, as the app gives it */
    val eta: String?,
    /** The maneuver arrow as PNG bytes, when the app provides one. */
    val iconPng: ByteArray?,
    /** "Phone · Google Maps", "Waze" */
    val source: String,
    val fromPhone: Boolean,
    val atElapsed: Long,
) {
    override fun equals(other: Any?): Boolean = other is NavStep && other.distance == distance && other.instruction == instruction &&
        other.eta == eta && other.source == source && java.util.Arrays.equals(other.iconPng, iconPng)
    override fun hashCode(): Int = distance.hashCode() * 31 + instruction.hashCode()
}

