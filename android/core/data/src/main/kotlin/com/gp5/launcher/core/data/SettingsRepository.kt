package com.gp5.launcher.core.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gp5.launcher.core.model.CarRenderer
import com.gp5.launcher.core.model.LauncherSettings
import com.gp5.launcher.core.model.NavApp
import com.gp5.launcher.core.model.RailSide
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "gp5.settings",
    // the number plate feature was removed: delete any plate text saved by earlier versions
    produceMigrations = { listOf(DropKeys("plate")) },
)

private class DropKeys(vararg names: String) : DataMigration<Preferences> {
    private val keys = names.map { stringPreferencesKey(it) }
    override suspend fun shouldMigrate(currentData: Preferences) = keys.any { it in currentData }
    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { keys.forEach { remove(it) } }.toPreferences()
    override suspend fun cleanUp() = Unit
}

/** Launcher settings (the prototype's localStorage "gp5.settings"). */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private object Keys {
        val Accent = longPreferencesKey("accent")
        val Rail = stringPreferencesKey("rail")
        val Gold = booleanPreferencesKey("gold")
        val Calm = booleanPreferencesKey("calm")
        val CarRenderer = stringPreferencesKey("carRenderer")
        val ObdEnabled = booleanPreferencesKey("obdEnabled")
        val ObdAddress = stringPreferencesKey("obdAddress")
        val NavApp = stringPreferencesKey("navApp")
        val HomePlace = stringPreferencesKey("homePlace")
        val WorkPlace = stringPreferencesKey("workPlace")
        val Odometer = doublePreferencesKey("odometerKm")
        val TripEnd = intPreferencesKey("tripEndMinutes")
        val KeepTrips = intPreferencesKey("keepTripsDays")
        val OilAt = doublePreferencesKey("oilChangedAtKm")
        val OilInterval = intPreferencesKey("oilIntervalKm")
        val LightsFromUnit = booleanPreferencesKey("lightsFromUnit")
        val LightSignal = stringPreferencesKey("lightSignal")
    }

    val settings: Flow<LauncherSettings> = store.data.map { p ->
        LauncherSettings(
            accentArgb = p[Keys.Accent] ?: LauncherSettings.ACCENT_RS_RED,
            railSide = if (p[Keys.Rail] == "right") RailSide.Right else RailSide.Left,
            goldTrim = p[Keys.Gold] ?: true,
            calmMotion = p[Keys.Calm] ?: false,
            carRenderer = CarRenderer.entries.firstOrNull { it.name == p[Keys.CarRenderer] } ?: CarRenderer.Auto,
            obdEnabled = p[Keys.ObdEnabled] ?: true,
            obdAddress = p[Keys.ObdAddress] ?: "192.168.0.10:35000",
            navApp = NavApp.entries.firstOrNull { it.name == p[Keys.NavApp] } ?: NavApp.Auto,
            homePlace = p[Keys.HomePlace] ?: "",
            workPlace = p[Keys.WorkPlace] ?: "",
            odometerKm = p[Keys.Odometer] ?: 0.0,
            tripEndMinutes = p[Keys.TripEnd] ?: 10,
            keepTripsDays = p[Keys.KeepTrips] ?: 90,
            oilChangedAtKm = p[Keys.OilAt] ?: 0.0,
            oilIntervalKm = p[Keys.OilInterval] ?: 5000,
            lightsFromUnit = p[Keys.LightsFromUnit] ?: true,
            lightSignal = p[Keys.LightSignal] ?: "",
        )
    }

    suspend fun setAccent(argb: Long) {
        store.edit { it[Keys.Accent] = argb }
    }
    suspend fun setRailSide(side: RailSide) {
        store.edit { it[Keys.Rail] = if (side == RailSide.Right) "right" else "left" }
    }
    suspend fun setGoldTrim(on: Boolean) {
        store.edit { it[Keys.Gold] = on }
    }
    suspend fun setCalmMotion(on: Boolean) {
        store.edit { it[Keys.Calm] = on }
    }
    suspend fun setCarRenderer(r: CarRenderer) {
        store.edit { it[Keys.CarRenderer] = r.name }
    }
    suspend fun setObdEnabled(on: Boolean) {
        store.edit { it[Keys.ObdEnabled] = on }
    }
    suspend fun setObdAddress(address: String) {
        store.edit { it[Keys.ObdAddress] = address.trim() }
    }
    suspend fun setNavApp(app: NavApp) {
        store.edit { it[Keys.NavApp] = app.name }
    }
    suspend fun setHomePlace(place: String) {
        store.edit { it[Keys.HomePlace] = place.trim() }
    }
    suspend fun setWorkPlace(place: String) {
        store.edit { it[Keys.WorkPlace] = place.trim() }
    }
    suspend fun setOdometer(km: Double) {
        store.edit { it[Keys.Odometer] = km.coerceAtLeast(0.0) }
    }
    /** Adds a finished trip's distance (only once the odometer has been set). */
    suspend fun addToOdometer(km: Double) {
        store.edit { p -> val now = p[Keys.Odometer] ?: 0.0; if (now > 0.0) p[Keys.Odometer] = now + km }
    }
    suspend fun setTripEndMinutes(min: Int) {
        store.edit { it[Keys.TripEnd] = min }
    }
    suspend fun setKeepTripsDays(days: Int) {
        store.edit { it[Keys.KeepTrips] = days }
    }
    suspend fun setOilChangedAt(km: Double) {
        store.edit { it[Keys.OilAt] = km }
    }
    suspend fun setOilInterval(km: Int) {
        store.edit { it[Keys.OilInterval] = km }
    }
    suspend fun setLightsFromUnit(on: Boolean) {
        store.edit { it[Keys.LightsFromUnit] = on }
    }
    suspend fun setLightSignal(signal: String) {
        store.edit { it[Keys.LightSignal] = signal }
    }
    suspend fun reset() {
        store.edit { it.clear() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore
}
