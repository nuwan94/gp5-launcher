package com.gp5.launcher.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "gp5.settings")

/** Launcher settings (the prototype's localStorage "gp5.settings"). */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private object Keys {
        val Accent = longPreferencesKey("accent")
        val Rail = stringPreferencesKey("rail")
        val Gold = booleanPreferencesKey("gold")
        val Calm = booleanPreferencesKey("calm")
    }

    val settings: Flow<LauncherSettings> = store.data.map { p ->
        LauncherSettings(
            accentArgb = p[Keys.Accent] ?: LauncherSettings.ACCENT_RS_RED,
            railSide = if (p[Keys.Rail] == "right") RailSide.Right else RailSide.Left,
            goldTrim = p[Keys.Gold] ?: true,
            calmMotion = p[Keys.Calm] ?: false,
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
