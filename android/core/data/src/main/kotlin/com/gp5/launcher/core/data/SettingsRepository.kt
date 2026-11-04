package com.gp5.launcher.core.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gp5.launcher.core.model.CarRenderer
import com.gp5.launcher.core.model.LauncherSettings
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
