package com.gp5.launcher.trips

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * One drive (DESIGN.md section 16). Time split: [evS] / [engineS] / [regenS] while moving, [stoppedS]
 * at standstill. [fuelL] is the OBD estimate (MAF or speed-density); null when the car gave no airflow data.
 */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    /** True while the recorder is still writing it. */
    val open: Boolean = false,
    val distanceKm: Double = 0.0,
    val evKm: Double = 0.0,
    val evS: Int = 0,
    val engineS: Int = 0,
    val regenS: Int = 0,
    /** Moving time with GPS speed only (OBD unplugged): drive mode unknown. */
    val unknownS: Int = 0,
    val stoppedS: Int = 0,
    val maxSpeed: Float = 0f,
    val fuelL: Double? = null,
    val maxCoolant: Float? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val fromName: String? = null,
    val toName: String? = null,
    val kept: Boolean = false,
) {
    val durationS: Int get() = ((endMs - startMs) / 1000).toInt().coerceAtLeast(0)
    val movingS: Int get() = evS + engineS + regenS + unknownS
    /** Moving time with a known drive mode (OBD connected). */
    val modeS: Int get() = evS + engineS + regenS
    val hasModes: Boolean get() = modeS > 0
    val evShare: Float get() = if (modeS > 0) evS.toFloat() / modeS else 0f
    val avgSpeed: Float get() = if (durationS > 0) (distanceKm * 3600 / durationS).toFloat() else 0f
    /** OBD economy (km/L), before pump calibration. */
    val economy: Float? get() = fuelL?.takeIf { it > 0.005 && distanceKm >= 0.5 }?.let { (distanceKm / it).toFloat() }
}

/** 1 Hz trace for the speed / drive-mode chart. [t] is seconds from the trip start. */
@Entity(
    tableName = "samples",
    primaryKeys = ["tripId", "t"],
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class TripSample(val tripId: Long, val t: Int, val speed: Float, val mode: Int)

/** A pump fill-up. A partial fill counts toward the next full tank. */
@Entity(tableName = "fillups")
data class FillUp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val odometerKm: Double,
    val litres: Double,
    val pricePerL: Double,
    val full: Boolean,
) {
    val total: Double get() = litres * pricePerL
}

@Dao
interface TripsDao {
    @Query("SELECT * FROM trips ORDER BY startMs DESC")
    fun trips(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE open = 1")
    suspend fun openTrips(): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun trip(id: Long): Trip?

    @Insert
    suspend fun insert(trip: Trip): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(trip: Trip)

    @Update
    suspend fun update(trip: Trip)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trips WHERE kept = 0 AND open = 0 AND startMs < :before")
    suspend fun prune(before: Long): Int

    @Query("SELECT * FROM samples WHERE tripId = :tripId ORDER BY t")
    fun samples(tripId: Long): Flow<List<TripSample>>

    @Query("SELECT * FROM samples WHERE tripId = :tripId ORDER BY t")
    suspend fun samplesNow(tripId: Long): List<TripSample>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<TripSample>)

    @Query("DELETE FROM samples WHERE tripId = :tripId AND t > :after")
    suspend fun trimSamples(tripId: Long, after: Int)

    @Query("SELECT * FROM fillups ORDER BY timeMs DESC")
    fun fillUps(): Flow<List<FillUp>>

    @Insert
    suspend fun insertFillUp(f: FillUp): Long

    @Query("DELETE FROM fillups WHERE id = :id")
    suspend fun deleteFillUp(id: Long)
}

@Database(entities = [Trip::class, TripSample::class, FillUp::class], version = 2, exportSchema = false)
abstract class TripsDatabase : RoomDatabase() {
    abstract fun dao(): TripsDao
}

/** v2: GPS-only moving time. */
private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN unknownS INTEGER NOT NULL DEFAULT 0")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object TripsDbModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TripsDatabase =
        Room.databaseBuilder(context, TripsDatabase::class.java, "gp5-trips.db").addMigrations(MIGRATION_1_2).build()

    @Provides
    fun dao(db: TripsDatabase): TripsDao = db.dao()
}
