package com.gp5.launcher.media

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import java.util.concurrent.Executors
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
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
    /** What the launcher can see, for Settings: one line per app ("Spotify · session"). */
    val sources: StateFlow<List<String>>
    fun playPause()
    fun next()
    fun previous()
    /** Re-checks notification access (call when the launcher comes back to the front). */
    fun refresh()
    /** Opens the system screen where the user allows notification access. */
    fun requestAccess()
}

/**
 * Now playing from any app (Spotify, YouTube Music, radio) and from a phone over Bluetooth.
 *
 * Sources, best first:
 * 1. Media sessions: the active ones from MediaSessionManager, plus sessions attached to media
 *    notifications (head-unit Bluetooth apps often have one that is not listed as active).
 * 2. The common "metachanged" music broadcasts (track, artist, duration, position) some
 *    Bluetooth and music apps send instead of a session.
 * 3. A Bluetooth music app's notification text (title / artist) when it has neither.
 * Album art comes as a bitmap or, for Bluetooth (AVRCP cover art), as a content URI.
 * Without a session the buttons send media keys, which the Bluetooth stack forwards to the phone.
 * Android only shares sessions and notifications with apps that have notification access.
 */
@Singleton
class SessionMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaRepository, MediaListenerService.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val sessions: MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(MediaState())
    override val state: StateFlow<MediaState> = _state.asStateFlow()
    private val _art = MutableStateFlow<Bitmap?>(null)
    override val art: StateFlow<Bitmap?> = _art.asStateFlow()
    private val _sources = MutableStateFlow<List<String>>(emptyList())
    override val sources: StateFlow<List<String>> = _sources.asStateFlow()

    /** Active sessions (MediaSessionManager). */
    private var active: List<MediaController> = emptyList()
    /** Sessions found on media notifications, by notification key. */
    private val fromNotes = HashMap<String, MediaController>()
    /** Bluetooth music notifications without a session, by notification key. */
    private val btNotes = LinkedHashMap<String, NoteTrack>()
    private val watched = HashSet<MediaSession.Token>()
    private var current: MediaController? = null
    private var listening = false
    /** Play state we assume for a notification-only track (toggled by our own button). */
    private var guessPlaying = true

    /** Last "metachanged" broadcast, if any app sends them. */
    private data class Broadcast(
        val pkg: String, val title: String, val artist: String, val playing: Boolean?,
        val durationMs: Long, val positionMs: Long, val at: Long,
    )
    private var broadcast: Broadcast? = null

    private val io = Executors.newSingleThreadExecutor()
    private var artUri: String? = null

    private data class NoteTrack(val pkg: String, val title: String, val text: String, val art: Bitmap?, val postedAt: Long)

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> active = list.orEmpty(); rewatch() }

    init {
        MediaListenerService.listener = this
        main.post { refresh() }
        listenForBroadcasts()
    }

    /** Players and some Bluetooth apps announce tracks with these (Android's old music app convention). */
    private fun listenForBroadcasts() {
        val filter = IntentFilter().apply {
            for (prefix in listOf("com.android.music", "com.android.bluetooth", "com.miui.player", "com.htc.music", "fm.last.android", "com.sec.android.app.music", "com.amazon.mp3", "com.spotify.music")) {
                addAction("$prefix.metachanged"); addAction("$prefix.playstatechanged")
            }
            addAction("com.android.music.playbackcomplete")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val title = (i.getStringExtra("track") ?: i.getStringExtra("title")).orEmpty()
                if (title.isBlank() && broadcast == null) return
                val prev = broadcast
                val playing = if (i.hasExtra("playing")) i.getBooleanExtra("playing", true)
                    else if (i.hasExtra("isPlaying")) i.getBooleanExtra("isPlaying", true) else prev?.playing
                broadcast = Broadcast(
                    pkg = i.getStringExtra("package") ?: i.action?.substringBeforeLast('.') ?: "",
                    title = title.ifBlank { prev?.title.orEmpty() },
                    artist = (i.getStringExtra("artist") ?: prev?.artist).orEmpty(),
                    playing = playing,
                    durationMs = longExtra(i, "duration") ?: prev?.durationMs ?: 0,
                    positionMs = longExtra(i, "position") ?: 0,
                    at = SystemClock.elapsedRealtime(),
                )
                if (i.action?.endsWith("playbackcomplete") == true) broadcast = null
                Log.i(TAG, "${i.action}: $title")
                publish()
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, main, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter, null, main)
        }
    }

    private fun longExtra(i: Intent, key: String): Long? = i.extras?.get(key)?.let { v ->
        when (v) { is Long -> v; is Int -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
    }

    /** Shows [bitmap], or loads [uri] (Bluetooth cover art, content:// or file://) off the main thread. */
    private fun setArt(bitmap: Bitmap?, uri: String?) {
        if (bitmap != null) { artUri = null; _art.value = bitmap; return }
        if (uri.isNullOrBlank()) { artUri = null; _art.value = null; return }
        if (uri == artUri) return
        artUri = uri
        _art.value = null
        io.execute {
            val bmp = runCatching { decode(Uri.parse(uri)) }.onFailure { Log.i(TAG, "art $uri: ${it.message}") }.getOrNull()
            main.post { if (artUri == uri) _art.value = bmp }
        }
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
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
            active = emptyList(); fromNotes.clear(); btNotes.clear()
            rewatch()
            return
        }
        try {
            if (!listening) { manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, main); listening = true }
            active = manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            // access is listed but the listener is not bound yet; onConnected retries
            Log.i(TAG, "sessions not available yet: ${e.message}")
            listening = false
            active = emptyList()
        }
        rewatch()
    }

    // ------------------------------------------------------------------ notifications (listener thread)

    override fun onConnected(current: List<StatusBarNotification>) = main.post {
        fromNotes.clear(); btNotes.clear()
        current.forEach { take(it) }
        refresh()
    }.let { }

    override fun onPosted(sbn: StatusBarNotification) = main.post { if (take(sbn)) rewatch() }.let { }

    override fun onRemoved(sbn: StatusBarNotification) = main.post {
        val a = fromNotes.remove(sbn.key) != null
        val b = btNotes.remove(sbn.key) != null
        if (a || b) rewatch()
    }.let { }

    /** Returns true if the notification changed what we track. */
    private fun take(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == context.packageName) return false
        val n = sbn.notification ?: return false
        val extras = n.extras ?: return false
        val token = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        }.getOrNull()
        if (token != null) {
            fromNotes[sbn.key] = MediaController(context, token)
            btNotes.remove(sbn.key)
            return true
        }
        if (!isBluetoothApp(sbn.packageName)) return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        // a Bluetooth app also posts "Connected to <phone>" style notices: keep only track-like ones
        if (title.isEmpty() || looksLikeStatus(title)) {
            return btNotes.remove(sbn.key) != null
        }
        val art = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON) ?: (extras.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON))?.loadDrawable(context)?.let { d ->
                (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }
        }.getOrNull()
        btNotes[sbn.key] = NoteTrack(sbn.packageName, title, text, art, sbn.postTime)
        return true
    }

    private fun looksLikeStatus(s: String) = STATUS.containsMatchIn(s)

    private fun isBluetoothApp(pkg: String): Boolean = BT_PACKAGE.containsMatchIn(pkg) || BT_LABEL.containsMatchIn(label(pkg))

    // ------------------------------------------------------------------------------------- sessions

    private fun controllers(): List<MediaController> {
        val all = (active + fromNotes.values).filter { it.packageName != context.packageName }
        return all.distinctBy { it.sessionToken }
    }

    private fun rewatch() {
        val list = controllers()
        val tokens = list.map { it.sessionToken }.toSet()
        // register once per session; sessions that went away drop their callbacks with them
        list.filter { it.sessionToken !in watched }.forEach { runCatching { it.registerCallback(callback, main) } }
        watched.retainAll(tokens); watched.addAll(tokens)
        _sources.value = list.map { "${label(it.packageName)} · session" + if (it.metadata == null) " (no track)" else "" } +
            btNotes.values.map { "${label(it.pkg)} · notification" }.distinct()
        publish()
    }

    /** The session to show: the one playing, else the most recent with a track. */
    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: list.firstOrNull { it.metadata != null }

    private fun publish() {
        val list = controllers()
        val c = pick(list)
        current = c
        val meta = c?.metadata
        if (c != null && meta != null) {
            val pb = c.playbackState
            setArt(
                meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            )
            val actions = pb?.actions ?: 0L
            _state.value = MediaState(
                hasAccess = true,
                packageName = c.packageName,
                title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
                artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
                source = source(c.packageName),
                playing = pb?.state == PlaybackState.STATE_PLAYING || pb?.state == PlaybackState.STATE_BUFFERING,
                positionMs = pb?.position ?: 0,
                durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
                positionAt = pb?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                // apps that do not declare actions usually still handle them
                canSkipNext = actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSkipPrevious = actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                bluetooth = isBluetoothApp(c.packageName),
            )
            return
        }
        // a paused broadcast track goes stale after 30 min (the player may have quit without saying)
        val b = broadcast?.takeIf { it.playing == true || SystemClock.elapsedRealtime() - it.at < 30 * 60_000L }
        if (b != null && b.title.isNotBlank()) {
            setArt(null, null)
            val pkg = b.pkg.takeIf { runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess }.orEmpty()
            _state.value = MediaState(
                hasAccess = hasAccess(),
                packageName = pkg.ifEmpty { "broadcast" },
                title = b.title,
                artist = b.artist,
                source = if (pkg.isNotEmpty()) source(pkg) else "Music",
                playing = b.playing ?: guessPlaying,
                positionMs = b.positionMs,
                durationMs = b.durationMs,
                positionAt = b.at,
                canSkipNext = true,
                canSkipPrevious = true,
                bluetooth = pkg.isNotEmpty() && isBluetoothApp(pkg),
                playingKnown = b.playing != null,
            )
            return
        }
        val note = btNotes.values.maxByOrNull { it.postedAt }
        if (note != null) {
            setArt(note.art, null)
            _state.value = MediaState(
                hasAccess = true,
                packageName = note.pkg,
                title = note.title,
                artist = note.text,
                source = source(note.pkg),
                playing = guessPlaying,
                canSkipNext = true,
                canSkipPrevious = true,
                bluetooth = true,
                playingKnown = false,
            )
            return
        }
        setArt(null, null)
        _state.value = MediaState(hasAccess = hasAccess())
    }

    private fun source(pkg: String): String {
        val name = label(pkg)
        return if (isBluetoothApp(pkg) && !name.contains("bluetooth", ignoreCase = true)) "Bluetooth · $name" else name
    }

    private val labels = HashMap<String, String>()
    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    // ------------------------------------------------------------------------------------ transport

    override fun playPause() {
        val c = current
        if (c != null) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        } else {
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            guessPlaying = !guessPlaying
            publish()
        }
    }

    override fun next() { current?.transportControls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT) }
    override fun previous() { current?.transportControls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    /** Media key to whoever holds media-button focus (the Bluetooth app forwards it to the phone over AVRCP). */
    private fun key(code: Int) {
        val a = audio ?: return
        val t = SystemClock.uptimeMillis()
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

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

    companion object {
        private const val TAG = "Media"
        /** Head-unit Bluetooth apps: com.syu.bt, com.microntek.bluetooth, com.fyt.btmusic, com.android.bluetooth ... */
        private val BT_PACKAGE = Regex("""(^|\.)(bt|bluetooth|btmusic|a2dp|bluemusic)(\.|$)|bluetooth|btmusic""", RegexOption.IGNORE_CASE)
        private val BT_LABEL = Regex("""bluetooth|bt music|bt audio""", RegexOption.IGNORE_CASE)
        /** Notices a Bluetooth app shows that are not tracks. */
        private val STATUS = Regex("""connected|disconnected|pairing|paired|searching|incoming call|calling|no device|not connected""", RegexOption.IGNORE_CASE)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun media(impl: SessionMediaRepository): MediaRepository
}
