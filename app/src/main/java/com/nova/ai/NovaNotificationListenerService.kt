package com.nova.ai

import android.service.notification.NotificationListenerService

class NovaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null; super.onListenerDisconnected() }
    companion object { @Volatile var instance: NovaNotificationListenerService? = null }
}
