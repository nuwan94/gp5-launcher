package com.gp5.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sound for the welcome: the launcher's own chime (res/raw/welcome_chime) and a cabin announcement,
 * one of seven recorded lines (res/raw/announce_{morning,afternoon}_N: cabin ding-dong, then a clear
 * voice), picked at random for the time of day and never the same twice in
 * a row. The next clip is prepared ahead, so it starts on the frame the animation starts. Music from
 * the phone ducks while they play (transient focus, may duck) and the chime stays under the voice.
 */
@Singleton
class WelcomeSound @Inject constructor(@ApplicationContext private val context: Context) : com.gp5.launcher.core.data.WelcomeAudio {
    private val timers = Handler(Looper.getMainLooper())   // the welcome's own delays, cleared by stop()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var chime: MediaPlayer? = null
    private var voice: MediaPlayer? = null
    private var voiceMorning: Boolean? = null       // which set the prepared clip is from
    private var lastClip = -1
    private var preparedClip = -1
    private var allowed: Set<Int> = ALL
    private val _previewing = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    override val previewing: kotlinx.coroutines.flow.StateFlow<Int?> = _previewing
    private var chimePlaying = false
    private var voicePlaying = false
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /** Loads both clips (a few ms; done once at start so playing is instant). */
    fun warmUp(allowed: Set<Int> = ALL) {
        if (chime == null) chime = load(R.raw.welcome_chime) { chimePlaying = false; maybeRelease() }
        val morning = isMorning()
        this.allowed = allowed
        if (voice == null || voiceMorning != morning || preparedClip !in allowed) prepareClip(morning)
    }

    private fun isMorning() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 12

    /** Prepares a random allowed announcement for [morning] / afternoon, not the one played last. */
    private fun prepareClip(morning: Boolean, only: Int? = null) {
        voice?.release(); voice = null; preparedClip = -1
        val set = if (morning) MORNING else AFTERNOON
        val pool = only?.let { listOf(it) } ?: set.indices.filter { it in allowed }.ifEmpty { return }
        val i = pool.filter { it != lastClip }.ifEmpty { pool }.random()
        preparedClip = i
        voiceMorning = morning
        Log.i(TAG, "next announcement: ${if (morning) "morning" else "afternoon"} ${i + 1}")
        voice = load(set[i]) {
            voicePlaying = false; _previewing.value = null
            chime?.setVolume(MUSIC, MUSIC); maybeRelease()
            timers.post { prepareClip(isMorning()) }   // the next one, ready
        }
    }

    /**
     * Plays the welcome: the chime now if [music], and if [speak] a random announcement from
     * [allowed] (indexes into LauncherSettings.ANNOUNCEMENTS) after [voiceDelayMs].
     */
    fun play(music: Boolean, speak: Boolean, allowed: Set<Int> = ALL, voiceDelayMs: Long = 150) {
        stop()
        val talk = speak && allowed.isNotEmpty()
        if (!music && !talk) return
        warmUp(allowed)
        requestFocus()
        // with the announcement on, the chime is a quiet bed under it from the start
        if (music) chime?.let { p -> val v = if (talk) DIP else MUSIC; p.setVolume(v, v); p.seekTo(0); p.start(); chimePlaying = true }
        if (talk) timers.postDelayed({ startVoice() }, voiceDelayMs)
    }

    private fun startVoice() {
        voice?.let { p ->
            chime?.setVolume(DIP, DIP)
            p.setVolume(VOICE, VOICE); p.seekTo(0); p.start(); voicePlaying = true
            lastClip = preparedClip
        }
    }

    override fun preview(index: Int) {
        if (_previewing.value == index) { stopPreview(); return }
        stop()
        prepareClip(isMorning(), only = index)
        requestFocus()
        startVoice()
        _previewing.value = index
    }

    override fun stopPreview() {
        stop()
        _previewing.value = null
        prepareClip(isMorning())
    }

    /** The welcome was skipped: stop at once. */
    fun stop() {
        timers.removeCallbacksAndMessages(null)
        for (p in listOfNotNull(chime, voice)) if (p.isPlaying) { p.pause(); p.seekTo(0) }
        chimePlaying = false; voicePlaying = false; _previewing.value = null
        abandonFocus()
    }

    /** Skip with a short fade instead of a cut. */
    fun fadeOut(ms: Long = 400) {
        timers.removeCallbacksAndMessages(null)
        val steps = 8
        for (i in 1..steps) timers.postDelayed({
            val k = 1f - i / steps.toFloat()
            chime?.setVolume(MUSIC * k, MUSIC * k); voice?.setVolume(VOICE * k, VOICE * k)
            if (i == steps) stop()
        }, ms * i / steps)
    }

    private fun load(res: Int, onDone: () -> Unit): MediaPlayer? {
        val afd = context.resources.openRawResourceFd(res) ?: return null
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(attrs)
            afd.use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.setOnCompletionListener { onDone() }
            p.prepare()
            p
        } catch (e: Exception) {
            Log.w(TAG, "welcome sound failed", e)
            p.release()
            null
        }
    }

    private fun maybeRelease() {
        if (!chimePlaying && !voicePlaying) abandonFocus()
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build()
            focusRequest = r
            audio.requestAudioFocus(r)
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else audio.abandonAudioFocus(focusListener)
        focusRequest = null
    }

    private companion object {
        val MORNING = intArrayOf(
            R.raw.announce_morning_1, R.raw.announce_morning_2, R.raw.announce_morning_3, R.raw.announce_morning_4,
            R.raw.announce_morning_5, R.raw.announce_morning_6, R.raw.announce_morning_7,
        )
        val AFTERNOON = intArrayOf(
            R.raw.announce_afternoon_1, R.raw.announce_afternoon_2, R.raw.announce_afternoon_3, R.raw.announce_afternoon_4,
            R.raw.announce_afternoon_5, R.raw.announce_afternoon_6, R.raw.announce_afternoon_7,
        )
        val ALL = (0 until 7).toSet()
        const val TAG = "Gp5Welcome"
        const val MUSIC = 0.6f
        const val DIP = 0.14f
        const val VOICE = 1f
    }
}
