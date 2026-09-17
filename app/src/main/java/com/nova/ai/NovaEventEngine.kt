package com.nova.ai

import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/** Normalizes Android events, evaluates approved rules, and sends matching work to the queue. */
class NovaEventEngine(private val context: Context) {
    data class Event(val type: String, val source: String, val payload: Map<String, String> = emptyMap(), val timestamp: Long = System.currentTimeMillis())

    private val rules = NovaEventRuleEngine(context)
    private val contextEngine = NovaContextEngine(context)
    private val recent = ConcurrentHashMap<String, Long>()

    fun prime() { contextEngine.snapshot() }

    fun handle(event: Event): Int {
        val key = "${event.type}|${event.source}|${event.payload.entries.sortedBy { it.key }}"
        val now = System.currentTimeMillis()
        if (now - (recent[key] ?: 0L) < 2_000L) return 0
        recent[key] = now
        val ctx = contextEngine.snapshot() + event.payload
        var count = 0
        rules.matching(event, ctx).forEach { rule ->
            NovaTaskScheduler(context).submit(rule.task, rule.priority)
            rules.markTriggered(rule.id)
            count++
        }
        if (count > 0) NovaBackgroundTaskService.start(context)
        return count
    }

    fun handleIntent(intent: Intent): Int {
        val action = intent.action.orEmpty()
        val type = when (action) {
            Intent.ACTION_BATTERY_CHANGED -> NovaEvents.BATTERY
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> NovaEvents.CHARGING
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> NovaEvents.SCREEN
            Intent.ACTION_USER_UNLOCKED -> NovaEvents.UNLOCK
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> NovaEvents.BOOT
            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED -> NovaEvents.APP_CHANGE
            "android.net.conn.CONNECTIVITY_CHANGE" -> NovaEvents.CONNECTIVITY
            "android.bluetooth.adapter.action.STATE_CHANGED" -> NovaEvents.BLUETOOTH
            else -> return 0
        }
        val payload = mutableMapOf<String, String>("action" to action)
        intent.data?.schemeSpecificPart?.let { payload["package"] = it }
        if (type == NovaEvents.BATTERY) {
            payload["level"] = intent.getIntExtra("level", -1).toString()
            payload["status"] = intent.getIntExtra("status", -1).toString()
        }
        return handle(Event(type, "android", payload))
    }

    object NovaEvents {
        const val NOTIFICATION = "NOTIFICATION"
        const val REMINDER = "REMINDER"
        const val CALENDAR = "CALENDAR"
        const val ALARM = "ALARM"
        const val CHARGING = "CHARGING"
        const val BATTERY = "BATTERY"
        const val CONNECTIVITY = "CONNECTIVITY"
        const val BLUETOOTH = "BLUETOOTH"
        const val SCREEN = "SCREEN"
        const val UNLOCK = "UNLOCK"
        const val APP_CHANGE = "APP_CHANGE"
        const val SCHEDULED = "SCHEDULED"
        const val BOOT = "BOOT"
    }
}
