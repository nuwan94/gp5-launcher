package com.gp5.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.gp5.launcher.core.model.MediaState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Now playing + transport controls (DESIGN.md section 6.1). */
interface MediaRepository {
    val state: StateFlow<MediaState>
    /** Album art of the current session, if the app provides one. */
    val art: StateFlow<Bitmap?>
    fun playPause()
    fun next()
    fun previous()
    /** Re-checks notification access (call when the launcher comes back to the front). */
    fun refresh()
    /** Opens the system screen where the user allows notification access. */
    fun requestAccess()
}

/**
 * Reads the active media session of any app (Spotify, YouTube Music, radio apps) through
 * MediaSessionManager. Android only allows that for apps with notification access, which the user
 * grants once in system settings (see [MediaListenerService]).
 */
@Singleton
class SessionMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaRepository {

    private val main = Handler(Looper.getMainLooper())
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val sessions: MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)
    private val _state = MutableStateFlow(MediaState())
    override val state: StateFlow<MediaState> = _state.asStateFlow()
    private val _art = MutableStateFlow<Bitmap?>(null)
    override val art: StateFlow<Bitmap?> = _art.asStateFlow()

    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var listening = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    init {
        MediaListenerService.connected = { main.post { refresh() } }
        main.post { refresh() }
    }

    private fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
    }

    override fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val manager = sessions
        if (manager == null || !hasAccess()) {
            if (listening) runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
            listening = false
            watch(emptyList())
            return
        }
        try {
            if (!listening) { manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, main); listening = true }
            watch(manager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // access is listed but the listener is not bound yet; MediaListenerService.connected retries
            Log.i(TAG, "sessions not available yet: ${e.message}")
            listening = false
            watch(emptyList())
        }
    }

    private fun watch(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(callback, main) }
        publish()
    }

    /** The session to show: the one playing, else the most recent with a track (the list is newest first). */
    private fun pick(): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: controllers.firstOrNull { it.metadata != null }

    private fun publish() {
        val c = pick()
        current = c
        val meta = c?.metadata
        if (c == null || meta == null) {
            _art.value = null
            _state.value = MediaState(hasAccess = hasAccess())
            return
        }
        val pb = c.playbackState
        _art.value = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val actions = pb?.actions ?: 0L
        _state.value = MediaState(
            hasAccess = true,
            packageName = c.packageName,
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            source = label(c.packageName),
            playing = pb?.state == PlaybackState.STATE_PLAYING || pb?.state == PlaybackState.STATE_BUFFERING,
            positionMs = pb?.position ?: 0,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionAt = pb?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            // apps that do not declare actions usually still handle them
            canSkipNext = actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrevious = actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
        )
    }

    private fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun playPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    override fun next() { current?.transportControls?.skipToNext() }
    override fun previous() { current?.transportControls?.skipToPrevious() }

    override fun requestAccess() {
        val tries = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            // some head units hide that list; the app's own page links to it on most builds
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
        for (i in tries) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    companion object { private const val TAG = "Media" }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun media(impl: SessionMediaRepository): MediaRepository
}
