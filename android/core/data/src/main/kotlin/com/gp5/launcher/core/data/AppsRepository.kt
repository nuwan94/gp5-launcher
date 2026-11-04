package com.gp5.launcher.core.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.DisplayMetrics
import android.util.LruCache
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gp5.launcher.core.model.AppCategory
import com.gp5.launcher.core.model.AppEntry
import com.gp5.launcher.core.model.AppsState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appsStore: DataStore<Preferences> by preferencesDataStore(name = "gp5.apps")

/**
 * Installed apps (LauncherApps, live on install / remove / update) plus the user's dock and hidden
 * set (DataStore "gp5.apps", the prototype's localStorage key). DESIGN.md section 15.
 */
@Singleton
class AppsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = context.appsStore
    private val icons = LruCache<String, Bitmap>(160)

    private object Keys {
        val Dock = stringPreferencesKey("dock")      // 6 package names joined by '|', "" = empty slot
        val Hidden = stringSetPreferencesKey("hidden")
    }

    /** Fires once at start and again whenever any package changes. */
    private val packageChanges = callbackFlow {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackageRemoved(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackageChanged(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
        }
        trySend(Unit)
        launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(cb) }
    }.conflate()

    private val installed: StateFlow<List<AppEntry>?> = packageChanges
        .map { icons.evictAll(); queryApps() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<AppsState> = combine(installed, store.data) { apps, p ->
        if (apps == null) return@combine AppsState()
        val byPkg = apps.associateBy { it.packageName }
        val dock = dockOf(p, apps).map { byPkg[it] }
        AppsState(apps = apps, dock = dock, hidden = p[Keys.Hidden] ?: emptySet(), loaded = true)
    }.stateIn(scope, SharingStarted.Eagerly, AppsState())

    // ---- Actions -------------------------------------------------------------------------------

    /** Launch an app's main activity. [sourceBounds] (screen px) lets the system animate from the icon. */
    fun launch(packageName: String, sourceBounds: Rect? = null): Boolean {
        val info = mainActivity(packageName) ?: return false
        return runCatching { launcherApps.startMainActivity(info.componentName, user, sourceBounds, null) }.isSuccess
    }

    fun openAppInfo(packageName: String, sourceBounds: Rect? = null) {
        val info = mainActivity(packageName) ?: return
        runCatching { launcherApps.startAppDetailsActivity(info.componentName, user, sourceBounds, null) }
    }

    /** Put [packageName] in the first empty dock slot. Returns false when the dock is full. */
    suspend fun addToDock(packageName: String): Boolean {
        val s = state.value
        if (s.isDocked(packageName)) return true
        val slot = s.dock.indexOfFirst { it == null }
        if (slot < 0) return false
        store.edit { p ->
            val dock = dockOf(p, s.apps).toMutableList()
            dock[slot] = packageName
            p[Keys.Dock] = dock.joinToString("|")
        }
        return true
    }

    suspend fun removeFromDock(packageName: String) {
        val apps = state.value.apps
        store.edit { p ->
            p[Keys.Dock] = dockOf(p, apps).map { if (it == packageName) "" else it }.joinToString("|")
        }
    }

    suspend fun hide(packageName: String) {
        store.edit { it[Keys.Hidden] = (it[Keys.Hidden] ?: emptySet()) + packageName }
    }

    suspend fun show(packageName: String) {
        store.edit { it[Keys.Hidden] = (it[Keys.Hidden] ?: emptySet()) - packageName }
    }

    suspend fun resetDockAndHidden() {
        store.edit { it.remove(Keys.Dock); it.remove(Keys.Hidden) }
    }

    /** App icon rendered to a square bitmap, cached until the next package change. */
    suspend fun icon(packageName: String, sizePx: Int): Bitmap? {
        val key = "$packageName@$sizePx"
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val info = mainActivity(packageName) ?: return@withContext null
            val density = maxOf(context.resources.displayMetrics.densityDpi, DisplayMetrics.DENSITY_XXHIGH)
            val drawable = runCatching { info.getIcon(density) }.getOrNull() ?: return@withContext null
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bmp))
            icons.put(key, bmp)
            bmp
        }
    }

    // ---- Internals -----------------------------------------------------------------------------

    private fun mainActivity(packageName: String): LauncherActivityInfo? =
        runCatching { launcherApps.getActivityList(packageName, user).firstOrNull() }.getOrNull()

    private fun queryApps(): List<AppEntry> =
        launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .distinctBy { it.componentName.packageName }
            .map { AppEntry(it.componentName.packageName, it.label.toString().trim(), categorize(it)) }
            .sortedBy { it.label.lowercase() }

    /** Stored dock, or the default one (first installed match per slot) when the user never changed it. */
    private fun dockOf(p: Preferences, apps: List<AppEntry>): List<String> {
        val stored = p[Keys.Dock]?.split('|')
        val dock = stored ?: run {
            val have = apps.mapTo(HashSet()) { it.packageName }
            DEFAULT_DOCK.mapNotNull { candidates -> candidates.firstOrNull { it in have } }
        }
        return (dock + List(AppsState.DOCK_SLOTS) { "" }).take(AppsState.DOCK_SLOTS)
    }

    private fun categorize(info: LauncherActivityInfo): AppCategory {
        val ai = info.applicationInfo
        // ApplicationInfo.category exists from Android 8 (API 26); older units use the name match only
        val cat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ai.category else -1
        val text = "${info.label} ${ai.packageName}".lowercase()
        return when {
            cat == ApplicationInfo.CATEGORY_MAPS || NAV_WORDS.any { it in text } -> AppCategory.Navigation
            cat in MEDIA_CATEGORIES || MEDIA_WORDS.any { it in text } -> AppCategory.Media
            CAR_WORDS.any { it in text } -> AppCategory.Car
            else -> AppCategory.Tools
        }
    }

    private companion object {
        /** Default dock (apps.html): Maps, Waze, Spotify, YT Music, Radio, Phone; alternatives per slot. */
        val DEFAULT_DOCK = listOf(
            listOf("com.google.android.apps.maps"),
            listOf("com.waze"),
            listOf("com.spotify.music"),
            listOf("com.google.android.apps.youtube.music"),
            listOf("com.syu.radio", "com.microntek.radio", "com.android.fmradio", "com.caf.fmradio"),
            listOf("com.syu.bt", "com.microntek.bluetooth", "com.google.android.dialer", "com.android.dialer"),
        )
        val MEDIA_CATEGORIES = setOf(ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO)   // not IMAGE: Photos is not a player
        val NAV_WORDS = listOf("maps", "waze", "navi", "gps", "sygic", "osmand", "here wego")
        val MEDIA_WORDS = listOf("music", "radio", "player", "video", "spotify", "youtube", "podcast", "audio", "deezer", "tidal", "netflix")
        val CAR_WORDS = listOf("obd", "torque", "dvr", "dashcam", "tpms", "canbus", "bluetooth", "dialer", "phone", "honda", "vehicle", "car ", ".car", "carplay", "android auto")
    }
}
