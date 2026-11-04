package com.gp5.launcher.core.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gp5.launcher.core.model.LauncherSettings
import javax.inject.Inject
import javax.inject.Singleton

/** Headlights as the launcher sees them. [fromUnit] is false when the state is the manual toggle. */
data class Headlights(val on: Boolean, val fromUnit: Boolean, val source: String)

/** A setting that changed between the lights-off and lights-on snapshots. */
data class LightCandidate(val table: String, val key: String, val off: String?, val on: String?) {
    val signal: String get() = "$table:$key=${on.orEmpty()}"
    val label: String get() = "$table/$key: ${off ?: "none"} → ${on ?: "none"}"
}

/**
 * Headlight state without OBD (DESIGN.md section 9.2). Android head units wire the illumination
 * (ILL) input to the parking lights and usually switch the system to night mode with it, which is
 * what most car launchers read. Some units keep it in a vendor setting instead: [snapshot] and [diff]
 * find that setting (lights off, then on) and the launcher follows it from then on.
 * The Home light button still works; a manual tap holds until the unit's signal changes.
 */
@Singleton
class HeadlightRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = settings.settings.stateIn(scope, SharingStarted.Eagerly, LauncherSettings())
    private val _state = MutableStateFlow(Headlights(on = false, fromUnit = false, source = "Manual"))
    val state: StateFlow<Headlights> = _state.asStateFlow()

    private var manual: Boolean? = null
    private var lastSignal: Boolean? = null

    init {
        scope.launch {
            while (true) {
                publish()
                delay(1_000)
            }
        }
    }

    /** Home light button: flips what is shown until the unit's own signal next changes. */
    fun toggle() {
        manual = !_state.value.on
        publish()
    }

    private fun publish() {
        val p = prefs.value
        val unit = if (p.lightsFromUnit) read(p.lightSignal) else null
        if (unit != lastSignal) { lastSignal = unit; manual = null }   // the unit changed: follow it again
        val m = manual
        _state.value = when {
            m != null -> Headlights(m, fromUnit = false, source = "Manual")
            unit != null -> Headlights(unit, fromUnit = true, source = describe(p.lightSignal))
            else -> Headlights(false, fromUnit = false, source = "Manual")
        }
    }

    /** Reads the configured signal: "" = system night mode, else "table:key=onValue". */
    private fun read(signal: String): Boolean? {
        if (signal.isBlank()) return nightMode()
        val table = signal.substringBefore(':')
        val key = signal.substringAfter(':').substringBefore('=')
        val onValue = signal.substringAfter('=', "")
        val v = runCatching {
            when (table) {
                "system" -> Settings.System.getString(context.contentResolver, key)
                "global" -> Settings.Global.getString(context.contentResolver, key)
                "secure" -> Settings.Secure.getString(context.contentResolver, key)
                else -> null
            }
        }.getOrNull()
        return v == onValue
    }

    private fun nightMode(): Boolean {
        val ui = context.getSystemService(UiModeManager::class.java)
        if (ui?.nightMode == UiModeManager.MODE_NIGHT_YES) return true
        if (ui?.nightMode == UiModeManager.MODE_NIGHT_NO) return false
        // auto / custom: the configuration says what is in effect now
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun describe(signal: String): String =
        if (signal.isBlank()) "Head unit night mode" else "Head unit setting ${signal.substringBefore('=').replace(':', '/')}"

    /** Every readable system, global and secure setting, plus the night-mode state. */
    suspend fun snapshot(): Map<String, String?> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String?>()
        for ((table, uri) in listOf("system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI)) {
            runCatching {
                context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) out["$table:${c.getString(0)}"] = c.getString(1)
                }
            }
        }
        out["night:mode"] = nightMode().toString()
        out
    }

    /**
     * Settings that changed between lights off and lights on, most likely first: night mode, then
     * names that sound like lights, then anything else. Clocks and counters are skipped.
     */
    fun diff(off: Map<String, String?>, on: Map<String, String?>): List<LightCandidate> {
        val noisy = Regex("(time|clock|count|uptime|boot|_ms|elapsed|last_|stamp|wifi|bluetooth|battery|volume)", RegexOption.IGNORE_CASE)
        val likely = Regex("(ill|light|lamp|night|dim|head|bright|backlight|day)", RegexOption.IGNORE_CASE)
        return (off.keys + on.keys).distinct()
            .filter { off[it] != on[it] && !noisy.containsMatchIn(it.substringAfter(':')) }
            .map { LightCandidate(it.substringBefore(':'), it.substringAfter(':'), off[it], on[it]) }
            .sortedBy { c ->
                when {
                    c.table == "night" -> 0
                    likely.containsMatchIn(c.key) && !c.key.contains("bright", true) -> 1
                    likely.containsMatchIn(c.key) -> 2
                    else -> 3
                }
            }
    }

    /** Saves the signal to follow: "" for night mode, else a candidate's [LightCandidate.signal]. */
    suspend fun use(candidate: LightCandidate?) {
        settings.setLightSignal(if (candidate == null || candidate.table == "night") "" else candidate.signal)
        settings.setLightsFromUnit(true)
    }

}
