package com.nova.ai

import android.content.Context
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/** Device-aware reliability layer: bounded retries, cooldowns and truthful health state. */
class NovaReliabilityEngine(private val context: Context) {
    data class Health(val batteryPercent: Int, val charging: Boolean, val lowMemory: Boolean, val accessibility: Boolean, val notificationAccess: Boolean)
    private val failures = ConcurrentHashMap<String, Int>()
    private val lastFailure = ConcurrentHashMap<String, Long>()

    fun health(): Health {
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val status = battery?.getIntExtra("status", -1) ?: -1
        val charging = status == 2 || status == 5
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Health(pct, charging, mi.lowMemory, NovaAccessibilityService.get() != null, hasNotificationAccess())
    }

    fun shouldRetry(action: String, message: String): Boolean {
        val key = action.uppercase()
        val now = System.currentTimeMillis()
        val count = failures[key] ?: 0
        val transient = message.contains("not available", true) || message.contains("timeout", true) || message.contains("busy", true) || message.contains("try again", true)
        val allowed = transient && count < 2 && now - (lastFailure[key] ?: 0L) > 700L
        if (allowed) { failures[key] = count + 1; lastFailure[key] = now }
        else if (!transient) { failures.remove(key); lastFailure.remove(key) }
        return allowed
    }

    fun reset(action: String) { failures.remove(action.uppercase()); lastFailure.remove(action.uppercase()) }

    private fun hasNotificationAccess(): Boolean = try {
        android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
    } catch (_: Exception) { false }

    fun deviceSummary(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ${health()}"
}
