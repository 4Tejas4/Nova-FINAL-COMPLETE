package com.nova.ai

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager

/** Stage 64: lightweight device-aware execution policy. */
class NovaPerformanceEngine(private val context: Context) {
    data class Policy(val maxSteps: Int, val delayMs: Long, val reason: String)

    fun policy(): Policy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        return when {
            mem.lowMemory -> Policy(30, 650L, "Low-memory mode")
            level in 0..15 -> Policy(40, 500L, "Low-battery mode")
            else -> Policy(60, 300L, "Normal mode")
        }
    }
}
