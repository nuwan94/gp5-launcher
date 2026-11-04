package com.gp5.launcher.trips

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pump calibration (DESIGN.md section 16): real km/L over the last 5 full fills divided by what the
 * OBD estimate said over the same stretch. [factor] multiplies every trip's OBD economy.
 */
data class Calibration(val factor: Float, val realKmL: Float, val obdKmL: Float, val fills: Int)

/** Trips, samples and the fuel log for the Trips screen. */
@Singleton
class TripsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TripsDao,
    recorder: TripRecorder,
) {
    val trips: Flow<List<Trip>> = dao.trips()
    val fillUps: Flow<List<FillUp>> = dao.fillUps()
    val live: StateFlow<LiveTrip?> = recorder.live

    val calibration: Flow<Calibration?> = combine(trips, fillUps) { t, f -> calibrate(t, f) }

    fun samples(tripId: Long): Flow<List<TripSample>> = dao.samples(tripId)

    suspend fun setKept(trip: Trip, kept: Boolean) = dao.update(trip.copy(kept = kept))

    /** Deletes the trip and its samples; returns what [restore] needs for Undo. */
    suspend fun delete(trip: Trip): Pair<Trip, List<TripSample>> {
        val samples = dao.samplesNow(trip.id)
        dao.delete(trip.id)
        return trip to samples
    }

    suspend fun restore(saved: Pair<Trip, List<TripSample>>) {
        dao.put(saved.first)
        if (saved.second.isNotEmpty()) dao.insertSamples(saved.second)
    }

    suspend fun addFillUp(f: FillUp) = dao.insertFillUp(f)
    suspend fun deleteFillUp(id: Long) = dao.deleteFillUp(id)

    /**
     * Writes trips-YYYY-MM-DD.csv and fuel.csv into a folder the user picked (USB drive or storage).
     * Returns the file names written.
     */
    suspend fun exportCsv(folder: Uri): List<String> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, folder) ?: error("Folder not available")
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val t = trips.first().filter { !it.open }.sortedBy { it.startMs }
        val f = fillUps.first().sortedBy { it.timeMs }
        val tripsCsv = buildString {
            appendLine("start,end,from,to,distance_km,duration_min,avg_kmh,max_kmh,ev_percent,ev_km,fuel_l,obd_km_per_l,max_coolant_c,kept")
            for (x in t) {
                appendLine(listOf(
                    stamp.format(Date(x.startMs)), stamp.format(Date(x.endMs)), csv(x.fromName), csv(x.toName),
                    "%.2f".format(Locale.US, x.distanceKm), "%.1f".format(Locale.US, x.durationS / 60f),
                    "%.1f".format(Locale.US, x.avgSpeed), "%.0f".format(Locale.US, x.maxSpeed),
                    "%.0f".format(Locale.US, x.evShare * 100), "%.2f".format(Locale.US, x.evKm),
                    x.fuelL?.let { "%.3f".format(Locale.US, it) } ?: "", x.economy?.let { "%.1f".format(Locale.US, it) } ?: "",
                    x.maxCoolant?.let { "%.0f".format(Locale.US, it) } ?: "", if (x.kept) "1" else "0",
                ).joinToString(","))
            }
        }
        val fuelCsv = buildString {
            appendLine("date,odometer_km,litres,price_per_l,total,full")
            for (x in f) {
                appendLine(listOf(
                    stamp.format(Date(x.timeMs)), "%.0f".format(Locale.US, x.odometerKm), "%.2f".format(Locale.US, x.litres),
                    "%.2f".format(Locale.US, x.pricePerL), "%.2f".format(Locale.US, x.total), if (x.full) "1" else "0",
                ).joinToString(","))
            }
        }
        listOf("trips-$day.csv" to tripsCsv, "fuel.csv" to fuelCsv).map { (name, body) ->
            dir.findFile(name)?.delete()
            val file = dir.createFile("text/csv", name) ?: error("Could not create $name")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(body.toByteArray()) } ?: error("Could not write $name")
            name
        }
    }

    private fun csv(s: String?): String = s?.let { if (it.contains(',') || it.contains('"')) "\"" + it.replace("\"", "\"\"") + "\"" else it } ?: ""

    companion object {
        /**
         * Over the last 5 full-to-full stretches: real km/L = km between the fills ÷ litres pumped
         * (partial fills count toward the next full). OBD km/L = trip km ÷ trip fuel over the same time.
         */
        fun calibrate(trips: List<Trip>, fills: List<FillUp>): Calibration? {
            val sorted = fills.sortedBy { it.timeMs }
            val fullIdx = sorted.indices.filter { sorted[it].full }.takeLast(6)
            if (fullIdx.size < 2) return null
            var km = 0.0; var litres = 0.0; var obdKm = 0.0; var obdL = 0.0
            for (k in 1 until fullIdx.size) {
                val a = sorted[fullIdx[k - 1]]; val b = sorted[fullIdx[k]]
                km += b.odometerKm - a.odometerKm
                litres += (fullIdx[k - 1] + 1..fullIdx[k]).sumOf { sorted[it].litres }
                trips.filter { !it.open && it.startMs >= a.timeMs && it.startMs < b.timeMs && it.fuelL != null }.forEach {
                    obdKm += it.distanceKm; obdL += it.fuelL ?: 0.0
                }
            }
            if (km <= 0 || litres <= 0 || obdL <= 0.05 || obdKm < 10) return null
            val real = (km / litres).toFloat()
            val obd = (obdKm / obdL).toFloat()
            return Calibration((real / obd).coerceIn(0.5f, 2f), real, obd, fullIdx.size - 1)
        }
    }
}
