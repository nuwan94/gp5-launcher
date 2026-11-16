package com.gp5.launcher.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * The sound playing on the head unit, as frequency bands, for the equalizer animation. Reads the
 * output mix (audio session 0) with [Visualizer], which needs the microphone permission (Android
 * counts it as audio capture; nothing is recorded or kept). Some apps and some Bluetooth audio paths
 * hide their sound from it: [read] then returns false and the equalizer animates on its own.
 */
@Singleton
class SoundBands @Inject constructor(@ApplicationContext private val context: Context) {
    private var viz: Visualizer? = null
    private var fft = ByteArray(0)
    private var users = 0
    private var triedAt = 0L

    fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun acquire() { users++; open() }

    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { runCatching { viz?.enabled = false; viz?.release() }; viz = null }
    }

    private fun open() {
        if (viz != null || users == 0 || !allowed()) return
        val now = SystemClock.elapsedRealtime()
        if (now - triedAt < RETRY_MS) return
        triedAt = now
        viz = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                enabled = true
            }
        }.onFailure { Log.w(TAG, "no visualizer on the output mix", it) }.getOrNull()
        fft = ByteArray(viz?.captureSize ?: 0)
    }

    /** Fills [out] with 0..1 per band, bass first, log spaced. False when there is no sound to show. */
    @Synchronized fun read(out: FloatArray): Boolean {
        open()
        val v = viz ?: return false
        if (runCatching { v.getFft(fft) }.getOrDefault(Visualizer.ERROR) != Visualizer.SUCCESS) return false
        val bins = fft.size / 2
        if (bins < 4) return false
        var heard = false
        for (b in out.indices) {
            val lo = bin(b, out.size, bins)
            val hi = max(lo + 1, bin(b + 1, out.size, bins))
            var peak = 0f
            for (k in lo until hi.coerceAtMost(bins)) peak = max(peak, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            if (peak > 2f) heard = true
            out[b] = ((20f * log10(peak + 1f) - 6f) / 36f).coerceIn(0f, 1f)
        }
        return heard
    }

    /** First FFT bin of band [b] of [n], log spaced from about 40 Hz up. */
    private fun bin(b: Int, n: Int, bins: Int): Int = (1.0 * (bins / 1.0).pow(b.toDouble() / n)).toInt().coerceIn(1, bins)

    private companion object {
        const val TAG = "SoundBands"
        const val RETRY_MS = 5_000L
    }
}
