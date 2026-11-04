package com.gp5.launcher.media

import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant notification access: with it, MediaSessionManager hands the
 * launcher every app's media session. Notifications themselves are ignored.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() { connected?.invoke() }
    override fun onListenerDisconnected() { connected?.invoke() }

    companion object {
        /** Set by the repository: re-checks access and sessions when the binding changes. */
        @Volatile internal var connected: (() -> Unit)? = null
    }
}
