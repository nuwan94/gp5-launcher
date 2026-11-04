package com.gp5.launcher.core.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When to greet the driver: once when the launcher process starts (the head unit boots with the
 * ignition), and when the screen comes back on after being off for [SLEEP_MS] (units that sleep on
 * ACC off and wake on ACC on). [pending] holds the trigger time until the welcome has played.
 */
object WelcomeTrigger {
    private const val SLEEP_MS = 60_000L
    private val _pending = MutableStateFlow<Long?>(null)
    val pending: StateFlow<Long?> = _pending.asStateFlow()
    private var screenOffAt = 0L
    private var registered = false

    fun install(context: Context) {
        fire()
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                    Intent.ACTION_SCREEN_ON -> if (screenOffAt > 0 && SystemClock.elapsedRealtime() - screenOffAt > SLEEP_MS) fire()
                }
            }
        }
        context.applicationContext.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    fun fire() { _pending.value = SystemClock.elapsedRealtime() }

    /** The welcome has played (or was skipped). */
    fun done() { _pending.value = null }
}
