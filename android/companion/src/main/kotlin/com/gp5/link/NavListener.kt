package com.gp5.link

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Reads Google Maps / Waze turn-by-turn from their ongoing notification (title = distance,
 * text = instruction, sub text = arrival, large icon = the maneuver arrow) and sends it to the car.
 */
class NavListener : NotificationListenerService() {
    private var key: String? = null
    private var last: String? = null

    override fun onListenerConnected() {
        Link.start(this)
        runCatching { activeNotifications }.getOrNull()?.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in APPS) return
        val n = sbn.notification ?: return
        val x = n.extras ?: return
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        val sub = x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        if (n.category != Notification.CATEGORY_NAVIGATION && !(sbn.isOngoing && title.isNotEmpty() && text.isNotEmpty())) return
        key = sbn.key
        val sig = "$title|$text|$sub"
        val icon = if (sig != last) arrow(x)?.let(::b64) else null
        last = sig
        Link.nav(title, text, sub, APPS[sbn.packageName] ?: "Maps", icon)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key != key) return
        key = null; last = null
        Link.navEnd()
    }

    @Suppress("DEPRECATION")
    private fun arrow(x: android.os.Bundle): Bitmap? = runCatching {
        x.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            ?: x.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON)?.loadDrawable(this)?.let { (it as? BitmapDrawable)?.bitmap }
    }.getOrNull()

    /** Small PNG: the arrow is drawn white on the car's turn card. */
    private fun b64(b: Bitmap): String {
        val s = if (b.width > 96) Bitmap.createScaledBitmap(b, 96, 96 * b.height / b.width, true) else b
        val o = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.PNG, 100, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        val APPS = mapOf(
            "com.google.android.apps.maps" to "Google Maps", "com.waze" to "Waze",
            "net.osmand" to "OsmAnd", "net.osmand.plus" to "OsmAnd", "app.organicmaps" to "Organic Maps",
            "com.here.app.maps" to "HERE", "com.sygic.aura" to "Sygic",
        )
    }
}
