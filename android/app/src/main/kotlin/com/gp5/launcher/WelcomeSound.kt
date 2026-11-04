package com.gp5.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sound for the welcome: the launcher's own chime (res/raw/welcome_chime) and the greeting spoken
 * by the head unit's text-to-speech. Music from the phone ducks while it plays (transient focus,
 * may duck), and the chime dips under the voice. Without a TTS engine the voice is simply skipped.
 */
@Singleton
class WelcomeSound @Inject constructor(@ApplicationContext private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val timers = Handler(Looper.getMainLooper())   // the welcome's own delays, cleared by stop()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var waitingLine: String? = null
    private var player: MediaPlayer? = null
    private var speaking = false
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /** Starts the TTS engine early (it takes a moment), so the greeting is not late. */
    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) setUp() else Log.i(TAG, "no text-to-speech engine ($status)")
                waitingLine?.let { waitingLine = null; if (ttsReady) speakNow(it) }
            }
        }
    }

    private fun setUp() {
        val t = tts ?: return
        val lang = listOf(Locale.getDefault(), Locale.UK, Locale.US)
            .firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        lang?.let { t.language = it }
        t.setAudioAttributes(attrs)
        t.setSpeechRate(0.95f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { speaking = true; player?.setVolume(DIP, DIP) } }
            override fun onDone(id: String?) { main.post { speaking = false; player?.setVolume(MUSIC, MUSIC); maybeRelease() } }
            @Deprecated("old API") override fun onError(id: String?) { onDone(id) }
        })
    }

    /** Plays the welcome: the chime now if [music], and [line] spoken after [voiceDelayMs]. */
    fun play(music: Boolean, line: String?, voiceDelayMs: Long = 600) {
        stop()
        if (!music && line == null) return
        requestFocus()
        if (music) startChime()
        if (line != null) {
            warmUp()
            timers.postDelayed({ if (ttsReady) speakNow(line) else waitingLine = line }, voiceDelayMs)
            // give up on a slow engine: a late greeting is worse than none
            timers.postDelayed({ waitingLine = null; maybeRelease() }, voiceDelayMs + 2_500)
        }
    }

    /** The welcome was skipped: fade the chime and stop speaking. */
    fun stop() {
        timers.removeCallbacksAndMessages(null)
        waitingLine = null
        if (speaking) tts?.stop()
        speaking = false
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        abandonFocus()
    }

    /** Skip with a short fade instead of a cut. */
    fun fadeOut(ms: Long = 400) {
        if (speaking) { tts?.stop(); speaking = false }
        waitingLine = null
        val p = player ?: return stop()
        val steps = 8
        for (i in 1..steps) timers.postDelayed({
            if (player === p) {
                val v = MUSIC * (1f - i / steps.toFloat())
                p.setVolume(v, v)
                if (i == steps) stop()
            }
        }, ms * i / steps)
    }

    private fun startChime() {
        val afd = context.resources.openRawResourceFd(R.raw.welcome_chime) ?: return
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs)
            afd.use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.setVolume(MUSIC, MUSIC)
            p.setOnCompletionListener { if (player === it) { it.release(); player = null; maybeRelease() } }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            Log.w(TAG, "chime failed", e)
            p.release()
        }
    }

    private fun speakNow(line: String) {
        val t = tts ?: return
        t.speak(line, TextToSpeech.QUEUE_FLUSH, Bundle(), "welcome")
    }

    private fun maybeRelease() {
        if (player == null && !speaking && waitingLine == null) abandonFocus()
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
        const val TAG = "Gp5Welcome"
        const val MUSIC = 0.6f
        const val DIP = 0.22f
    }
}
