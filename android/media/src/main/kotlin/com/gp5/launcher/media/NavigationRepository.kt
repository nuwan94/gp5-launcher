package com.gp5.launcher.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.gp5.launcher.core.model.NavStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current turn-by-turn step, for the Home turn card. Two sources:
 * - the phone, through the GP5 Link companion app over Bluetooth ([PhoneLink]): Google Maps or Waze
 *   running on the phone. Preferred whenever the phone is sending.
 * - a navigation app on the head unit itself, read from its ongoing notification (title = distance,
 *   text = instruction, large icon = maneuver arrow), with the notification access Home already has.
 */
@Singleton
class NavigationRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _step = MutableStateFlow<NavStep?>(null)
    val step: StateFlow<NavStep?> = _step.asStateFlow()

    private var local: NavStep? = null
    private var localKey: String? = null
    private var phone: NavStep? = null

    init {
        MediaListenerService.nav = this
        main.postDelayed(object : Runnable {
            override fun run() { publish(); main.postDelayed(this, 10_000) }   // drops a phone step that went silent
        }, 10_000)
    }

    // ------------------------------------------------------------------ head unit notifications

    internal fun onPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NAV_APPS) return
        val n = sbn.notification ?: return
        val x = n.extras ?: return
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        val sub = x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val navigating = n.category == Notification.CATEGORY_NAVIGATION || (sbn.isOngoing && title.isNotEmpty() && text.isNotEmpty())
        if (!navigating) return
        local = NavStep(
            distance = title, instruction = text, eta = sub,
            iconPng = largeIcon(x)?.let(::png), source = appName(sbn.packageName), fromPhone = false,
            atElapsed = SystemClock.elapsedRealtime(),
        )
        localKey = sbn.key
        main.post { publish() }
    }

    internal fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key != localKey) return
        local = null; localKey = null
        main.post { publish() }
    }

    // ------------------------------------------------------------------ phone

    internal fun onPhone(step: NavStep?) {
        phone = step
        main.post { publish() }
    }

    private fun publish() {
        val p = phone?.takeIf { SystemClock.elapsedRealtime() - it.atElapsed < PHONE_STALE_MS }
        if (p == null && phone != null) phone = null
        _step.value = p ?: local
    }

    @Suppress("DEPRECATION")
    private fun largeIcon(x: android.os.Bundle): Bitmap? = runCatching {
        x.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            ?: x.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON)?.loadDrawable(context)?.let { (it as? BitmapDrawable)?.bitmap }
    }.getOrNull()

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun appName(pkg: String): String = when (pkg) {
        "com.google.android.apps.maps" -> "Google Maps"
        "com.waze" -> "Waze"
        else -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    companion object {
        val NAV_APPS = setOf("com.google.android.apps.maps", "com.waze", "net.osmand", "net.osmand.plus", "com.here.app.maps", "com.sygic.aura", "app.organicmaps")
        private const val PHONE_STALE_MS = 90_000L
        @Suppress("unused") private const val TAG = "Gp5Nav"
        internal fun log(msg: String) = Log.i(TAG, msg)
    }
}
