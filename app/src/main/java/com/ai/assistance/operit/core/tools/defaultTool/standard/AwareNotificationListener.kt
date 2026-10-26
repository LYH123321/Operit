package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * MOD v2: notification listener for AG awareness.
 * Keeps a snapshot of active notifications (max 50, newest first).
 */
class AwareNotificationListener : NotificationListenerService() {

    companion object {
        private val cache = ConcurrentHashMap<String, String>()
        @Volatile private var connected = false

        fun isConnected(): Boolean = connected

        fun snapshot(): List<String> = cache.values.sorted().toList()

        private fun key(sbn: StatusBarNotification): String =
            String.format("%010d|%s|%s", sbn.postTime, sbn.packageName, sbn.id)

        private fun render(sbn: StatusBarNotification): String {
            val e = sbn.notification?.extras
            val title = e?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = e?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val time = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(sbn.postTime))
            return "[$time] ${sbn.packageName}: $title $text"
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        refresh(activeNotifications?.toList() ?: emptyList())
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        cache[key(sbn)] = render(sbn)
        trim()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        cache.remove(key(sbn))
    }

    private fun refresh(list: List<StatusBarNotification>) {
        list.filter { it.packageName != packageName }.forEach { cache[key(it)] = render(it) }
        trim()
    }

    private fun trim() {
        while (cache.size > 50) {
            cache.keys.minOrNull()?.let { cache.remove(it) }
        }
    }
}
