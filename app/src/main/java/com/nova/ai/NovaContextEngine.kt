package com.nova.ai

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Lightweight, permission-safe device context used by proactive rules and planning. */
class NovaContextEngine(private val context: Context) {
    fun snapshot(): Map<String, String> {
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", 100) ?: 100
        val charging = when (battery?.getIntExtra("status", -1)) {
            2, 5 -> "true"
            else -> "false"
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(active)
        val network = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps != null -> "other"
            else -> "offline"
        }
        val bt = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (_: Exception) { false }
        return mapOf(
            "battery_percent" to if (level >= 0) ((level * 100) / scale.coerceAtLeast(1)).toString() else "unknown",
            "charging" to charging,
            "network" to network,
            "bluetooth_enabled" to bt.toString(),
            "screen_state" to "unknown"
        )
    }
}
