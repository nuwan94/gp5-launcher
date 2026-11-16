package com.gp5.launcher.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification access lets MediaSessionManager hand the launcher every app's media session. The
 * notifications themselves matter too: head-unit Bluetooth music apps often show the phone's track
 * only in a notification (sometimes with a session token that is not listed as active).
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        val all = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
        listener?.onConnected(all)
        all.forEach { nav?.onPosted(it) }
    }

    override fun onListenerDisconnected() {
        // Android unbinds listeners now and then (memory, updates); ask to be bound again at once
        // instead of losing every session until the launcher next opens Home
        if (android.os.Build.VERSION.SDK_INT >= 24) runCatching {
            requestRebind(android.content.ComponentName(this, MediaListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { listener?.onPosted(sbn); nav?.onPosted(sbn) }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { listener?.onRemoved(sbn); nav?.onRemoved(sbn) }

    interface Listener {
        fun onConnected(current: List<StatusBarNotification>)
        fun onPosted(sbn: StatusBarNotification)
        fun onRemoved(sbn: StatusBarNotification)
    }

    companion object {
        /** Set by the repository. */
        @Volatile internal var listener: Listener? = null
        /** Set by NavigationRepository: navigation apps' turn-by-turn notifications. */
        @Volatile internal var nav: NavigationRepository? = null
    }
}
