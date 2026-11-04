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
        listener?.onConnected(runCatching { activeNotifications?.toList() }.getOrNull().orEmpty())
    }

    override fun onListenerDisconnected() { listener?.onConnected(emptyList()) }

    override fun onNotificationPosted(sbn: StatusBarNotification) { listener?.onPosted(sbn) }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { listener?.onRemoved(sbn) }

    interface Listener {
        fun onConnected(current: List<StatusBarNotification>)
        fun onPosted(sbn: StatusBarNotification)
        fun onRemoved(sbn: StatusBarNotification)
    }

    companion object {
        /** Set by the repository. */
        @Volatile internal var listener: Listener? = null
    }
}
